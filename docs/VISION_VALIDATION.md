# Aria Vision Pipeline Validation & Benchmark Report

최종 갱신일: 2026-10-22

## 1. 테스트 환경 및 빌드 규격

| 항목 | 규격 / 실측값 |
|---|---|
| 물리 기기 | Google Pixel 3 (`adb` serial `8B7X15A0F`), Android 12 (API 31, `SP1A.210812.016.C2`), `arm64-v8a` |
| SoC / GPU | Qualcomm Snapdragon 845 / Adreno 630 GPU |
| 개발 환경 & JDK | Windows 11, Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`) |
| CameraX 버전 | `androidx.camera:camera-{core,camera2,lifecycle,view}:1.6.2` |
| LiteRT 런타임 | `com.google.ai.edge.litert:litert:1.4.2` |
| GPU Delegate | `com.google.ai.edge.litert:litert-gpu:1.4.2` / `litert-gpu-api:1.4.2` |
| 원본 모델 에셋 | `walkable_seg_aria5.tflite` (10,837,912 B, SHA-256 `3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7`, commit `bfb11e6`) |
| 최적화 모델 에셋 | `walkable_seg_aria5_unfused.tflite` (10,839,936 B, SHA-256 `84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847`) |

---

## 2. 검증 명령 및 결과

### 2.1 JVM 로컬 단위 테스트
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
./gradlew :app:testDebugUnitTest
```
- **결과**: **BUILD SUCCESSFUL** (총 **134개** 테스트 전체 통과, 실패 0개).
- 포함 테스트: `CameraGeometryTest`, `VisionPipelineUnitTest`, `MotionTrackerTest`, `HazardAlertCoordinatorLifecycleTest`, `SpeechLifecycleUnitTest` 등.

### 2.2 디바이스 계측 테스트 및 벤치마크 (Google Pixel 3)
```powershell
# 1. CPU Parity 검증 (원본 CPU vs 변환 모델 CPU)
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionBackendComparisonTest#probeOriginalVsTransformedCpuParity"

# 2. 다중 픽스처 정밀도 트레이드오프 검증 (CPU FP32 vs GPU FP32 vs GPU FP16)
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionBackendComparisonTest#probePrecisionTradeoffOnTransformedModel"

# 3. 원본 모델 백엔드 비교 (CPU 4T/2T/1T vs 원본 GPU)
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionBackendComparisonTest#probeGpuDelegateAndCpuThreadScaling"

# 4. 변환 모델 백엔드 비교 (CPU 4T vs 단일 파티션 GPU)
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionBackendComparisonTest#probeTransformedUnfusedModelOnGpuAndCpu"

# 5. CameraX 지속 파이프라인 CPU 기준선 벤치마크 (60초 steady window)
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionRealModelBenchmarkTest#realModelCpu4BaselineBenchmark_measuresWarmupAnd20SecondSteadyWindow"

# 6. CameraX 지속 파이프라인 GPU 가속 벤치마크 (21초 steady window)
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionRealModelBenchmarkTest#realModelOptimizedGpuBenchmark_measuresWarmupAnd20SecondSteadyWindow"
```

---

## 3. 정밀도 및 수치 검증 결과 (Google Pixel 3)

### 3.1 원본 CPU vs 변환 모델 CPU 등가성 (CPU Parity)
3가지 결정론적 픽스처(Constant 0.5, Gradient Ramp, Seeded Random) 전반에서 원본 모델과 변환 모델을 동일한 4스레드 XNNPACK 환경에서 1:1 비교:
- `constant_0.5`: maxDiff = **0.0**, MAE = **0.0**, argmax 일치율 = **100.0%**, walkable 일치율 = **100.0%**, 비유한수(NaN/Inf) 없음.
- `gradient_ramp`: maxDiff = **0.0**, MAE = **0.0**, argmax 일치율 = **100.0%**, walkable 일치율 = **100.0%**, 비유한수 없음.
- `seeded_random`: maxDiff = **0.0**, MAE = **0.0**, argmax 일치율 = **100.0%**, walkable 일치율 = **100.0%**, 비유한수 없음.

### 3.2 디코더 패딩 오류 규명 및 VALID + SLICE 해소 검증
- **문제 원인**: TFLite GPU delegate의 `operations.cc` `CalculateSamePadding` 함수가 포워드 컨볼루션 패딩 공식을 사용하여 홀수 입력 높이 9에 대해 total pad 2 (top 1, bottom 1)를 산출함. 반면 TransposeConv는 원하는 출력 18에 대해 total pad 1 (top 0, bottom 1)을 요구하므로, 상단 1행이 시프트되어 FP32에서도 클래스 불일치가 유발되었음.
- **해소 방안**: 첫 번째 TransposeConv(Op 189)를 `VALID` 패딩(출력 $19 \times 33$)으로 온전히 확장한 후, 명시적 `SLICE`(begin `[0,0,0,0]`, size `[1,18,32,256]`)로 하단 1행과 우측 1열을 슬라이스하고 `RELU`(v1)를 적용.
- **실측 정밀도 (`precision-tradeoff.json`)**:
  - **GPU FP32 Sustained (프로덕션 기본값) vs CPU reference**:
    - `constant_0.5`: maxDiff = 0.000095, MAE = 0.000001, **argmax = 100.0% (147,456/147,456), walkable = 100.0% (147,456/147,456)**
    - `gradient_ramp`: maxDiff = 0.000054, MAE = 0.000001, **argmax = 100.0% (147,456/147,456), walkable = 100.0% (147,456/147,456)**
    - `seeded_random`: maxDiff = 0.000009, MAE = 0.000000, **argmax = 100.0% (147,456/147,456), walkable = 100.0% (147,456/147,456)**
    - `blank_zeros`: maxDiff = 0.000125, MAE = 0.000004, **argmax = 99.99864% (147,454/147,456 일치, 미세한 부동소수점 수치 차이에 따른 2픽셀 편차 관측), walkable = 99.99864% (147,454/147,456)**
  - **GPU FP16 vs CPU reference (진단 프로브 전용)**:
    - `constant_0.5`: maxDiff = 0.120, MAE = 0.0027, argmax = 98.59%, walkable = 98.65%
    - `gradient_ramp`: maxDiff = 0.298, MAE = 0.0044, argmax = 99.00%, walkable = 99.02%
    - `seeded_random`: maxDiff = 0.030, MAE = 0.0008, argmax = 99.81%, walkable = 99.82%

---

## 4. 실측 성능 비교: 마이크로프로브 vs 지속 CameraX 파이프라인

### 4.1 마이크로프로브 지연시간 (단일 텐서 순수 모델 실행)
| 구성 | CPU 4T (XNNPACK) | CPU 2T | CPU 1T | GPU FP32 Sustained (기본) | GPU FP16 (진단용) | GPU 위임 상태 |
|---|---|---|---|---|---|---|
| 원본 모델 (`walkable_seg_aria5.tflite`) | 891.42 ms | 887.57 ms | 891.32 ms | - | 840.13 ms | 3 파티션 분할 (64 GPU, 140 CPU fallback) |
| 변환 모델 (`walkable_seg_aria5_unfused.tflite`) | 894.87 ms | - | - | **90.07 ms** (3개 합성 입력에서 반올림 일치율 100%) | **64.76 ms** (98.6~99.8%) | **단일 파티션 (209/209 GPU 위임, CPU fallback 0개)** |

- 변환 모델 GPU는 원본 GPU 대비 **9.3배(FP32) ~ 13.0배(FP16) 가속**, 원본 CPU 대비 **9.9배(FP32) ~ 13.8배(FP16) 가속** 달성.

### 4.2 실제 지속 CameraX 파이프라인 성능 (측정 윈도우 명시)
| 파이프라인 단계 | 원본 모델 CPU 4T 기준선 (`baseline-cpu4-model.json`, 60초) | 최적화 모델 GPU FP32 Sustained (음성 미동작, 21초, `optimized-gpu-model.json`) | 최적화 모델 GPU FP32 Sustained (음성 ACTIVE 검증, 21초, `optimized-gpu-model-voice-active.json`) | 변환 모델 GPU FP16 (진단 프로브 전용, 21초) |
|---|---|---|---|---|
| **카메라 프레임 전달 주기 (Cadence p50)** | 1126.94 ms | **123.74 ms** | **110.00 ms** | 96.50 ms |
| **단일 프레임 전처리 지연 (p50)** | 12.03 ms | **18.93 ms** | **14.57 ms** | 20.67 ms |
| **순수 모델 추론 지연 (Invoke p50)** | 1108.84 ms | **93.09 ms** | **84.81 ms** | 64.76 ms |
| **후처리 및 마스크 생성 (p50)** | 4.68 ms | **7.96 ms** | **6.59 ms** | 8.65 ms |
| **분석기 총 실행 시간 (Total Analyzer p50)** | 1125.72 ms | **121.66 ms** | **108.46 ms** | 94.43 ms |
| **카메라 캡처 -> 결과 공개 신선도 (Age p50)** | 1259.57 ms | **230.00 ms** | **214.66 ms** | 240.95 ms |
| **지속 실측 분석 처리율 (Analyzed FPS)** | **0.88 FPS** (53 frames / 60.0s) | **8.01 FPS** (169 frames / 21.1s) | **8.96 FPS** (189 frames / 21.1s) | 10.27 FPS (216 frames / 21.0s) |
| **카메라 센서 프레임 처리율 (Sensor FPS)** | 15.44 FPS | 22.95 FPS | 23.90 FPS | 미기록 |
| **음성 서비스 동시 구동 상태** | 비활성 | 비활성 | **ACTIVE 검증** (엔드포인트: Waiting, error=0, fallback=false) | 비활성 |
| **메모리 점유 (PSS)** | 277.51 MB | **395.62 MB** | **379.99 MB** | - |
| **방향 인식 유효 Upright HFOV** | - | **50.98°** (세로 Portrait 실측) | **50.98°** (세로 Portrait 실측) | - |

> **분석 및 해석**:
> 1. **"CPU Ground Truth" 미사용 및 투명성 고지**: 본 검증은 레이블된 실측 정답 데이터셋이 아닌 기기 CPU LiteRT 레퍼런스(`CPU reference`)와의 수치적 일치율(Parity)을 검증한 것입니다.
> 2. **합성 픽스처 일치율**: `constant_0.5`, `gradient_ramp`, `seeded_random` 3개 픽스처에서는 147,456/147,456 (100.0%) 일치하며, `blank_zeros`에서는 미세한 부동소수점 수치 차이로 인해 147,454/147,456 (99.99864%) 일치(2픽셀 편차 관측)를 명확히 분리 보고합니다.
> 3. **프로덕션 vs 진단 분리**: 프로덕션 기본값은 4개 합성 입력에서 설정된 수치 허용오차를 통과한 **FP32 Sustained** 모드(순수 invoke ~85~93 ms, 파이프라인 ~8.0~9.0 FPS)이며, FP16 모드(~10.3 FPS, 64.8 ms)는 클래스 편차(argmax 98.6~99.8%)를 수반하는 진단 프로브 전용으로 명확히 구분됩니다.
> 4. **음성 동시 구동 검증**: 음성 인식 백그라운드 서비스(`IVoiceService`)가 활성화된 상태에서 steady 윈도우 시작 직전과 종료 직후 엔드포인트에서 상태(`phase = Waiting`, `errorCode = 0`, `wakeFallback = false`)가 완벽히 유지됨을 확인하였습니다.
> 5. **비실측 수치 배제**: 마이크로프로브 순수 실행 시간(~85~93 ms)과 카메라 파이프라인 지속 처리율(~8.0~9.0 FPS)의 관계를 명확히 설명하며, 12.5 FPS 등 비실측 이론치는 일체 주장하지 않습니다.

---

## 5. 실제 기기 화면

코디네이터가 최종 APK 설치 후 Pixel 3에서 직접 캡처한 PNG는 로컬 작업 폴더 `C:/Users/felice/AppData/Local/Temp/aria-vision-motion-20261022/screenshots/`에 저장합니다. `home.png`는 ARiA 홈과 호출어 대기, `model.png`는 모델 규격과 FP32 버튼, `motion.png`는 GPU 및 센서 상태입니다. 보정 대기 화면은 실제 거치 상태이며 보행 현장 검증을 의미하지 않습니다. 이전 워커 PNG는 인코딩 손상으로 증거에서 제외했습니다.
## 6. 카메라 방향 인식 기하 및 IMU 모션 융합 정밀도

- **방향 인식 Upright HFOV 유도 (`CameraGeometry.kt`)**:
  - Camera2의 실제 렌즈/센서 메타데이터와 ImageProxy 크기·회전·모델 크롭에서 유도합니다. Pixel 3의 640×480, 회전 90°, 480×270 하단 크롭에서 유효 upright HFOV는 **50.98°**였습니다.
  - 0°/180°와 90°/270°의 축 교환 및 크롭 처리를 실제 Pixel 3 메타데이터 기기 테스트로 확인했습니다.
  - 회전 변경 및 비정상 센서 메타데이터 발생 시: 기존 투영 및 캘리브레이션 이력 즉각 초기화(Fence) 및 `CAMERA_FOV_UNKNOWN` 감등(Degraded) 플래그 부여.
- **캘리브레이션 가정 및 한계**:
  - **전방 상대 정렬 (Forward-Relative)** 및 **휴대폰 장착 정렬 (Phone-Aligned)**을 가정합니다.
  - 절대 방위(Absolute Heading) 또는 미터 단위의 절대 거리(Metric Distance)는 측정/보장하지 않습니다.
  - 센서 적분 드리프트 방지를 위해 60초 이상 경과 시 점진적 감쇠 및 재보정이 필요합니다.
  - 실제 보행자가 야외에서 걷는 **실외 보행 필드 테스트(outdoor walking field test)는 수행되지 않았으며**, 기기 거치 및 통제된 벤치마크 환경에서 정밀도 및 동작 검증이 완료되었습니다.

## 7. 코디네이터 독립 검증 (2026-10-22)

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`: 종료 코드 0, BUILD SUCCESSFUL. 단위 테스트 134개, 실패 0. Lint 오류 0, 경고 52개.
- 전체 `:app:connectedDebugAndroidTest`: 종료 코드 0, BUILD SUCCESSFUL (11분 32초). 러너는 38 tests / 0 failed를 출력했습니다. 상세 XML에는 선택 실행 Gemma 실모델 검사 1개의 `AssumptionViolatedException`이 failure로 직렬화되었습니다. 따라서 실제 결과는 **37개 통과, 선택 검사 1개 미실행**입니다. Gemma 2.59GB 파일은 설치하지 않았습니다.
- 생산용 GPU 경로에서 검은색 입력 147,454/147,456 일치, 나머지 3개 입력 147,456/147,456 일치를 독립 재현했습니다. 검은색 입력 maxDiff=0.00012478232, MAE=0.00000388914, 클래스·보행 일치율 99.99864%입니다.
- 원시 로그/JSON/XML: 로컬 `C:/Users/felice/AppData/Local/Temp/aria-vision-motion-20261022/coordinator/`, 빌드 로그는 상위 폴더 `coordinator-build.log`, `coordinator-connected.log`.

### 연속 테스트 후 재측정 (위 표의 개별 측정과 분리)

| 구성 | 분석 FPS | 프레임 / 측정 시간 | invoke p50 | 결과 age p50 | thermal status 시작→종료 | 배터리 온도 시작→종료 |
|---|---:|---:|---:|---:|---|---|
| GPU FP32, 음성 대기 활성 | 7.75 | 163 / 21.03초 | 87.80ms | 227.65ms | 1→3 | 38.7→41.1°C |
| GPU FP32, 음성 대기 중지 | 8.15 | 172 / 21.10초 | 92.99ms | 224.46ms | 3→2 | 41.1→40.4°C |
| 원본 CPU 4T | 0.53 | 50 / 93.51초 | 1611.66ms | 1752.10ms | 2→3 | 40.4→41.8°C |

온도·실행 순서·카메라 공급 주기가 같지 않습니다. 음성 대기 전후 수치로 음성 서비스의 인과적 성능 비용을 추정하지 않습니다. GPU의 관측 범위는 7.75~8.96 FPS이며 장시간 고정 FPS를 보장하지 않습니다. `totalAnalyzer`는 전처리·추론·분할 후처리 범위이며, 위험 평가와 공개까지의 비용은 실제 FPS와 결과 age에서 함께 관측합니다. PSS는 측정 중 앱 메인 프로세스 값이며 별도 음성 프로세스와 합산한 메모리가 아닙니다.

최종 텍스트 수정(`65ms` → `FP32`) 뒤 워커가 assemble/unit/lint를 다시 통과했습니다. 해당 문자열 수정으로 전체 계측 테스트를 반복하지 않았습니다.