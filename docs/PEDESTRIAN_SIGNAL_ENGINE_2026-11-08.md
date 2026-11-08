# 보행자 신호등 상태 관측 엔진 타당성 조사 및 계약 — 2026-11-08

## 1. 개요 및 안전 원칙

본 문서는 Aria 앱에서 횡단보도 접근 시 보행자 신호등(Pedestrian Traffic Signal)의 상태(빨강/초록)를 오프라인으로 관측할 수 있는지에 대한 사전 타당성 조사 결과 및 최종 아키텍처 결정을 기록합니다.

### 핵심 안전 원칙 (Non-Negotiable Constraints)
1. **정체성 날조 금지 (No Fabricated Pedestrian Identity)**: COCO 범주 9(`traffic light`) 박스나 단순 색상/HSV 분포만으로 보행자 신호등 여부를 단정하지 않습니다.
2. **"초록 = 건너기" 안내 금지 (No Green Means Go)**: 신호등 상태 관측은 순수 사실 전달이어야 하며, 사용자에게 보행 허가나 횡단 명령을 내려서는 안 됩니다.
3. **완전 오프라인 및 프라이버시 (Zero Cloud Upload)**: 카메라는 100% 로컬 기기 내에서만 처리되며 외부 업로드는 일절 없습니다.
4. **미지원 시 안전 실패 (Fail-Closed to UNKNOWN / PRESENCE_ONLY)**: 신호등 유형이나 상태를 확신할 수 없을 때는 즉시 상태 판독을 배제하고 안전 우선으로 동작합니다.
5. **허위 구현 배제 (No Fake Production Scaffold)**: 신뢰할 수 있는 모델이 준비되지 않은 상태에서 동작하지 않는 가짜 엔진이나 미사용 프로덕션 코드를 추가하지 않습니다.

---

## 2. 후보 모델 소스 및 실측 호스트 감사 (Host Audit)

재현 스크립트: `tools/vision/pedestrian_signal/audit_pedestrian_signal_models.py` (Exit Code: 0)

### 후보 1: Autoware MobileNetV2 Pedestrian Traffic Light Classifier
- **저장소**: [Hugging Face AutowareFoundation/traffic_light_classifier](https://huggingface.co/AutowareFoundation/traffic_light_classifier)
- **라이선스**: Apache-2.0
- **파일**: `ped_traffic_light_classifier_mobilenetv2_batch_1.onnx`
- **크기**: 8,885,654 bytes (~8.88 MB)
- **SHA-256**: `b52632fee96d1bc99922e743335ebfd49d6a0645c8a04e615f156e38661add24`
- **텐서 계약 및 그래프 검증**:
  - 입력: `[1, 3, 224, 224]` float32 (NCHW)
  - 정규화: ImageNet RGB 평균 `[123.675, 116.28, 103.53]`, 표준편차 `[58.395, 57.12, 57.375]`
  - 출력: `[1, 3]` float32. 그래프 검증 결과 최종 노드는 `Softmax(axis=1)`이며, 출력 합이 1.000000(허용 오차 1e-5 이내)임을 실측 확인.
  - 라벨 순서: `lamp_labels_ped.txt` 기준 `0: red`, `1: green`, `2: unknown`
- **호스트 추론 검증 (ONNXRuntime)**:
  - 더미 입력에 대해 정상 추론 수행 (Output: `(1, 3)`, 유한한 확률 분포 확인).
- **구조적 한계 및 도메인 외(Out-of-Domain) 위험**:
  - 본 모델은 사전에 완벽히 잘라낸 **보행자 신호등 ROI 전용 분류기**입니다.
  - 객체 탐지(OD) 모델이 제공하는 일반 `traffic light` 상자가 차량용인지 보행자용인지를 판별하는 "신호등 유형 분류(Type Detector)" 능력이 없습니다.
  - 차량용 신호등 ROI에 대한 실제 오분류 실험을 직접 수행한 것은 아니나, 모델 학습이 보행자 신호등 ROI만으로 이루어졌기 때문에 유형 구분 없는 일반 신호등 박스 투입 시 도메인 외(out-of-domain) 거짓 긍정(false positive) 상태 할당 위험이 구조적으로 존재합니다.

### 후보 2: ImVisible LYTNet (PyTorch Weights)
- **저장소**: [GitHub samuelyu2002/ImVisible](https://github.com/samuelyu2002/ImVisible) (관련 논문: arXiv:1907.09706, arXiv:1909.09598)
- **라이선스**: MIT
- **파일**: `Model/LytNetV1_weights`, `Model/LYTNet.py`, `LICENSE_ImVisible.txt`
- **크기**: 7,699,886 bytes (~7.70 MB)
- **SHA-256**: `4103aea2cb1f804fdcf5eb0a38862ae9fffd495fa6b3d0f64b8d6152e9569cfb`
- **데이터셋 출처**:
  - 원 논문(arXiv:1907.09706)에 명시된 원본 데이터셋은 **중국 상하이(Shanghai)** 교차로에서 수집된 5,000장 이상의 PTL(Pedestrian-Traffic-Lights) 데이터셋입니다 (Shanghai American School Puxi West).
- **텐서 계약 및 호스트 추론 검증**:
  - 입력: 전체 프레임 이미지 `[1, 3, 576, 768]` float32 (NCHW)
  - 출력 1: 신호 상태 `[1, 5]` float32 Softmax
  - 출력 2: 횡단보도 중심선 회귀 `[1, 4]` float32
- **역직렬화(Deserialization) 검증**:
  - 가중치가 CUDA 장치 텐서 메타데이터를 포함하고 있어, 기본 `torch.load(..., weights_only=True)` 호출 시 `[RuntimeError] Attempting to deserialize object on a CUDA device...` 예외가 발생함을 확인.
  - `map_location='cpu'`를 명시한 `torch.load(..., map_location='cpu', weights_only=True)`를 통해 임의 코드 실행 없이 222개 텐서 키를 안전하게 적재 성공.
  - 순전파 추론 확인 (`out_light: [1, 5]`, `out_dir: [1, 4]`).
- **자원 우려 및 미수행 사항**:
  - 576x768 (442,368 픽셀) 전신 이미지 입력은 기존 모바일 사이드카(320x320 OD, 512x288 세그멘테이션) 대비 상당한 연산 자원 우려가 있습니다.
  - 단, 실제 모바일 기기에서의 프레임 지연 시간 측정(기기 미연결)이나 Android/TFLite 포팅 및 현지화 검증은 수행되지 않았으므로, 이를 기기 불가능성으로 단정하지 않고 미검증 자원 우려로 규정합니다.

### 후보 3: Autoware YOLOX-s Traffic Light Fine Detector (카탈로그 감사)
- **저장소**: [Hugging Face AutowareFoundation/traffic_light_fine_detector](https://huggingface.co/AutowareFoundation/traffic_light_fine_detector)
- **라이선스**: Apache-2.0
- **파일**: `tlr_car_ped_yolox_s_batch_1.onnx`
- **원격 카탈로그 크기**: 35,794,092 bytes (~35.8 MB)
- **목표 클래스**: `BACKGROUND`, `traffic_light`, `pedestrian_traffic_light`
- **자원 우려**:
  - 35.8 MB 모델 크기는 번들 EfficientDet-Lite0(4.4 MB) 대비 현저히 큰 다운로드/메모리 자원 우려가 있습니다 (기기 실측 지연 시간은 미측정). 광범위한 다운로드 제한 규칙에 따라 로컬 다운로드는 생략하고 원격 카탈로그 규격만 감사하였습니다.

---

## 3. 최종 결정: 사실적 신호등 존재 알림(Presence-Only Fallback) 동결

코디네이터와의 상호 질의(`orca orchestration ask`)를 통해 다음 방향을 확정하였습니다.

1. **프로덕션 신호 상태 분류기 배제**:
   - 현재 신호등 유형을 신뢰성 있게 구분하는 검증된 경량 온디바이스 모델 파이프라인이 부재하므로, 가짜 엔진이나 신호 상태(빨강/초록) 판독 코드를 작성하지 않습니다.
   - 번들 앱에 불필요한 모델 에셋(`assets/models/pedestrian_signal*`)이나 미사용 프로덕션 스캐폴드를 추가하지 않습니다.

2. **사실적 신호등 존재 알림 (Presence-Only Fallback)**:
   - 전방에 횡단보도가 감지되고 COCO 범주 9(`traffic light`)가 함께 관측될 경우, "신호등 상태(빨강/초록)"를 판독했다고 주장하지 않고 **"전방에 신호등이 감지되었습니다. 신호를 직접 확인하세요"** 수준의 사실적 존재(Presence-Only) 안내 정책을 후속 작업에서 안전하게 연결합니다.

3. **증거 및 아티팩트 보존**:
   - 호스트 감사 스크립트([`tools/vision/pedestrian_signal/audit_pedestrian_signal_models.py`](file:///C:/Users/felice/orca/aria-app/tools/vision/pedestrian_signal/audit_pedestrian_signal_models.py))와 다운로드된 소스 파일들은 `build/vision-pedestrian-signal/`에 보존되어 언제든 재현 가능합니다.
