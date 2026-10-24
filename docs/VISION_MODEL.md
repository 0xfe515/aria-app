# ARiA 비전 모델 규격 및 최적화 문서

최종 갱신일: 2026-10-22

## 1. 모델 개요 및 출처

- **업스트림 저장소**: https://github.com/tulg580/vision_model (origin/main)
- **통합 커밋**: `bfb11e6` (2026-10-21 upstream push 반영)
- **아키텍처**: MobileNetV2 백본 + U-Net 디코더 기반 7-클래스 시맨틱 세그멘테이션 (FP16 가중치)
- **라이선스 현황**: 업스트림에 명시적 오픈소스 라이선스 파일이 제공되지 않음 (`NOT PROVIDED` 명시 카탈로그 반영)

### 배포 파일 규격
| 파일명 | 용도 | 크기 | SHA-256 해시 | GPU 위임 상태 (Pixel 3) |
|---|---|---|---|---|
| `walkable_seg_aria5.tflite` | 원본 기본 에셋 | 10,837,912 B | `3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7` | 3 파티션 분할 (64/204 GPU, 140 CPU fallback), 840 ms |
| `walkable_seg_aria5_unfused.tflite` | GPU 최적화 그래프 | 10,839,936 B | `84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847` | **단일 파티션 (209/209 GPU 위임, CPU fallback 없음), 90.07 ms (FP32 Sustained 기본값) / 64.76 ms (FP16 진단 프로브)** |

## 2. 텐서 입출력 계약 및 정규화

| 구분 | 텐서 Shape | 데이터 타입 | 값 범위 / 의미 |
|---|---|---|---|
| **입력 (Input 0)** | `[1, 288, 512, 3]` | `FLOAT32` (RGB) | `0.0f .. 1.0f` (픽셀값 / 255.0f). 채널 순서: R, G, B |
| **출력 (Output 0)** | `[1, 288, 512, 7]` | `FLOAT32` | 각 픽셀별 7개 클래스 Softmax 확률 분포 (합계 = 1.0) |

### 클래스 정의 및 보행 적합성
| ID | 클래스명 | 보행 여부 | 비고 |
|---|---|---|---|
| 0 | 기타 / 배경 (`other`) | 불가 | 인도·도로 외 영역 |
| 1 | 차도 / 도로 (`road`) | **위험** | 차량 통행 구역 (보행 불가) |
| 2 | 인도 / 보도 (`sidewalk`) | **보행 가능** | 주 보행 통로 |
| 3 | 횡단보도 (`crosswalk`) | **보행 가능** | AI Hub 데이터셋 파인튜닝 반영 (보행 허용) |
| 4 | 보행자 (`person`) | 장애물 | 보행자 충돌 주의 |
| 5 | 차량 (`vehicle`) | **고위험 장애물** | 차량 진입 위험 |
| 6 | 기둥 / 표지판 (`pole_sign`) | 장애물 | 고정 시설물 |

## 3. LiteRT GPU 위임 병목 원인 및 해결 (FlatBuffers 그래프 변환)

### 병목 현상 및 원인 규명
원본 `walkable_seg_aria5.tflite`를 Google Pixel 3 (Snapdragon 845, Adreno 630 GPU)에서 LiteRT GPU Delegate(`TfLiteGpuDelegateV2`)로 실행했을 때, 기대와 달리 **840 ms**가 소요되었습니다.
Logcat 및 TFLite C++ Delegate 소스 분석 결과:
```
Replacing 64 out of 204 node(s) with delegate (TfLiteGpuDelegateV2) node, yielding 3 partitions for subgraph 0.
Replacing 140 out of 141 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 3 partitions for subgraph 0.
```
1. **연산자 버전 한계**:
   - 디코더의 5개 `TRANSPOSE_CONV` 노드(Op 189, 192, 195, 198, 201)에 `fused_activation_function = RELU`가 융합되어 있어 TFLite 연산자 버전 4를 요구함.
   - LiteRT GPU delegate의 연산자 등록부는 `TRANSPOSE_CONV`를 버전 3까지만 지원(`CheckMaxSupportedOpVersion(registration, 3)`).
   - GPU delegate가 이 5개 노드를 거부하여 디코더 전체가 CPU(XNNPack)로 폴백되고, CPU와 GPU 간 텍스처 복사 왕복 오버헤드가 발생함.
2. **비대칭 패딩 계산 불일치 (홀수 높이 9)**:
   - 첫 번째 TransposeConv(Op 189)의 입력 크기는 $H=9, W=16$, 목표 출력은 $18 \times 32$.
   - TF Lite GPU delegate의 `operations.cc` `CalculateSamePadding`은 포워드 컨볼루션 패딩 공식을 사용하므로 홀수 입력 $H=9$에 대해 총 패딩 2 (top 1, bottom 1)를 산출함.
   - 반면 CPU TransposeConv는 원하는 $18$ 크기에 대해 총 패딩 1 (top 0, bottom 1)을 적용하므로, GPU에서 상단 1픽셀 수직 시프트가 발생하여 FP32에서도 클래스 불일치가 유발됨.

### 최적화 변환 (`tools/vision/unfuse_transpose_conv.py`)
가중치, 양자화 계수, 정규화 방식은 단 1비트도 건드리지 않고 FlatBuffers 구조만 독립 분리:
1. **첫 번째 TransposeConv(Op 189)**:
   - 패딩을 `VALID`로 변경하여 $19 \times 33$ 크기로 온전히 확장 (연산자 버전 3, 활성화 함수 NONE).
   - 직후에 명시적 `SLICE` 연산자(시작 `[0,0,0,0]`, 크기 `[1,18,32,256]`)를 삽입하여 하단 1행과 우측 1열을 슬라이스, CPU SAME 패딩과 수학적으로 100% 동일한 $18 \times 32$ 텐서 생성.
   - 직후에 명시적 `RELU`(버전 1) 연산자를 연결하여 원래 출력 복원.
2. **나머지 4개 TransposeConv(Op 192, 195, 198, 201)**:
   - 공간 차원이 모두 짝수($18 \times 32, 36 \times 64, 72 \times 128, 144 \times 256$)이므로 GPU SAME 패딩과 CPU TransposeConv 패딩이 정확히 일치함.
   - `fused_activation_function = NONE`(버전 3)으로 강등하고 직후에 명시적 `RELU`(버전 1)를 연결.
3. **수치 검증 결과 (Google Pixel 3 실측)**:
   - **CPU Parity (원본 CPU vs 변환 CPU)**: 3가지 결정론적 픽스처(상수 0.5, 그래디언트 램프, 시드 난수) 모두 **maxDiff = 0.0, MAE = 0.0, argmax = 100.0%, walkable = 100.0%** 완전 일치.
   - **GPU FP32 Sustained (프로덕션 기본값 vs CPU reference)**:
     - 4가지 결정론적 픽스처:
       - `constant_0.5`: maxDiff = 0.000095, MAE = 0.000001, **argmax = 100.0% (147,456/147,456), walkable = 100.0%**
       - `gradient_ramp`: maxDiff = 0.000054, MAE = 0.000001, **argmax = 100.0% (147,456/147,456), walkable = 100.0%**
       - `seeded_random`: maxDiff = 0.000009, MAE = 0.000000, **argmax = 100.0% (147,456/147,456), walkable = 100.0%**
       - `blank_zeros`: maxDiff = 0.000125, MAE = 0.000004, **argmax = 99.99864% (147,454/147,456 일치, 미세한 부동소수점 수치 차이에 따른 2픽셀 편차 관측), walkable = 99.99864% (147,454/147,456)**
     - 단일 파티션 209/209 GPU 위임 (CPU 폴백 0개), 마이크로프로브 지연시간 **90.07 ms** (기본 FP32 103.5 ms 대비 추가 단축).
   - **GPU FP16 vs CPU reference (진단 프로브 전용)**:
     - `constant_0.5`: maxDiff = 0.120, MAE = 0.0027, argmax = 98.59%, walkable = 98.65%
     - `gradient_ramp`: maxDiff = 0.298, MAE = 0.0044, argmax = 99.00%, walkable = 99.02%
     - `seeded_random`: maxDiff = 0.030, MAE = 0.0008, argmax = 99.81%, walkable = 99.82%
     - 단일 파티션 209/209 GPU 위임 (CPU 폴백 0개), 지연시간 64.76 ms. (주의: 진단 프로브 전용이며 프로덕션 기본값은 FP32 Sustained임).

## 4. 실측 벤치마크 비교 (Google Pixel 3)

| 지표 | 원본 CPU 4T 기준선 (`baseline-cpu4-model.json`, 60초) | 변환 모델 GPU FP32 Sustained (음성 미동작, 21초) | 변환 모델 GPU FP32 Sustained (음성 ACTIVE 검증, 21초) | 변환 모델 GPU FP16 (진단 프로브 전용, 21초) |
|---|---|---|---|---|
| **위임 노드 수** | 0 / 204 | **209 / 209 (1 파티션)** | **209 / 209 (1 파티션)** | 209 / 209 (1 파티션) |
| **마이크로프로브 순수 모델 지연 (p50)** | 891.42 ms | **90.07 ms** | **90.07 ms** | 64.76 ms (진단용) |
| **CameraX 분석 파이프라인 지연 (p50)** | 1125.72 ms | **121.66 ms** | **108.46 ms** | 94.43 ms (진단용) |
| **파이프라인 순수 추론 지연 (Invoke p50)** | 1108.84 ms | **93.09 ms** | **84.81 ms** | 64.76 ms (진단용) |
| **카메라-결과 신선도 (Age p50)** | 1259.57 ms | **230.00 ms** | **214.66 ms** | 240.95 ms (진단용) |
| **지속 프레임 처리율 (Analyzed FPS)** | **0.88 FPS** (53 frames / 60.0s) | **8.01 FPS** (169 frames / 21.1s) | **8.96 FPS** (189 frames / 21.1s) | 10.27 FPS (진단용) |
| **분석 프레임 전달 주기 (Cadence p50)** | 1126.94 ms | **123.74 ms** | **110.00 ms** | 96.50 ms |
| **음성 서비스 동시 동작 상태** | 비활성 | 비활성 | **ACTIVE 검증** (엔드포인트: Waiting, error=0, fallback=false) | 비활성 |
| **메모리 점유 (PSS)** | 277.51 MB | **395.62 MB** | **379.99 MB** | - |
| **방향 인식 유효 Upright HFOV** | - | **50.98°** (세로 Portrait 실측) | **50.98°** (세로 Portrait 실측) | - |

> **주의 및 투명성 고지**:
> 1. "CPU Ground Truth"라는 표현은 사용하지 않으며 레이블된 정답 데이터셋이 아닌 **"CPU reference"**와의 수치적 일치율(Parity)입니다.
> 2. `blank_zeros` 합성 픽스처는 147,454/147,456 (99.99864%) 일치율로 미세한 부동소수점 수치 차이에 따른 2픽셀 편차가 관측되었으며, `constant_0.5`, `gradient_ramp`, `seeded_random` 3개 픽스처는 100.0% 완전 일치합니다.
> 3. 음성 동시 구동 검증은 윈도우 시작 직전과 종료 직후 엔드포인트에서 `IVoiceService` 상태(`phase = Waiting`, `errorCode = 0`, `wakeFallback = false`)를 바운디드 검증하였습니다.
> 4. 마이크로프로브 순수 모델 실행 시간(~90 ms FP32, ~65 ms FP16)과 실제 카메라 센서-전처리-추론-후처리 지속 파이프라인 처리율(~8.0~9.0 FPS FP32, ~10.3 FPS FP16)은 명확히 구분되며, 12.5 FPS 등 비실측 이론치는 주장하지 않습니다.

## 5. 관성 센서(IMU) 모션 트래킹 및 보행 통로 조향

- **센서 융합**: 가속도계 + 자이로스코프 융합 (`MotionTracker.kt`, `MotionFusionEngine.kt`).
- **중력 보정**: 세로 모드 기준 $+Y$ 방향 중력 정렬, 1차 저역 통과 필터 `gravityTauSeconds = 0.15f`.
- **안정성 게이팅**: 기기 기울기 $\le 35^\circ$ 및 격렬한 회전 각속도 $\le 45^\circ/\text{s}$ 이하에서만 조향 각도 반영.
- **카메라 핀홀 기하 투영 및 방향 인식 화각 유도 (`CameraGeometry.kt`)**:
  - Camera2의 초점거리·센서 크기·활성 배열과 실제 ImageProxy 크기/회전 및 전처리 크롭으로 유효 화각을 계산합니다.
  - Pixel 3의 640×480, 회전 90°, 480×270 하단 크롭에서 유효 upright 수평 화각은 **50.98°**였습니다. 회전 90°/270°에서는 센서 축을 교환하며, 기하 정보가 없으면 UNKNOWN으로 처리합니다.
  - 조향 중심 $x_{proj} = 256 + f_x \cdot \tan(\Delta \psi)$.
  - 보행 통로 분할: 좌측(`0..170`), 중앙(`171..341`), 우측(`342..511`).
- **캘리브레이션 가정 및 한계**:
  - **전방 상대 정렬(Forward-Relative)** 및 **휴대폰 장착 정렬(Phone-Aligned)**을 가정합니다.
  - 절대 방위(absolute heading)나 미터 단위의 절대 거리(metric distance)는 측정하거나 보장하지 않습니다.
  - 센서 적분 드리프트 방지를 위해 60초 이상 경과 시 점진적 감쇠/재보정(recalibration)이 필요합니다.
  - 실제 보행자가 야외에서 걷는 **실외 보행 필드 테스트(outdoor walking field test)는 수행되지 않았으며**, 기기 거치 및 벤치마크 환경에서 정밀도/동작 검증이 수행되었습니다.
- **위험 감지 기준** (영상 비율 기반 휴리스틱, 거리 측정 아님):
  - 선택 통로의 하단 근접 영역(행 230..287)에서 비보행 픽셀 비율 > 0.35이면 근접 경로 장애물 추정.
  - 전체 하단 ROI(행 158..287)의 보행 가능 비율 < 0.40이면 통로 차단, < 0.65이면 주의.
  - 신뢰 가능한 방향 상태에서 연속 2프레임 확인 후 경고, 경고 간격 2.5초, 1.5초가 지난 결과는 폐기합니다.
- **경고 전달**: 진동과 Toast. 세션 토큰과 타임스탬프로 카메라 중지·재시작 이후 이전 결과의 알림을 억제합니다.
## 6. 모델 저장소 트랜잭션 관리 (`VisionModelStore`)

- **슬롯 A/B 듀얼 슬롯 구조**: `activeSlotFile`, `inactiveSlotFile` 원자적 슬롯 교체 (`atomicMoveSameDirectory`).
- **초기 프로비저닝**: 번들된 최적화 모델(`walkable_seg_aria5_unfused.tflite`) 우선 적용, 실패 시 원본 모델 자동 폴백.
- **백엔드 영속성**: `preferred_backend` 및 `preferred_cpu_threads`를 영속화하여 앱 재시작 시 선호 백엔드로 즉각 재초기화.
- **사용자 모델 해제 보호**: `user_cleared_model` 플래그 영속화를 통해 사용자가 모델을 해제한 경우 앱 재시작 시 번들 모델 강제 복원 방지.
