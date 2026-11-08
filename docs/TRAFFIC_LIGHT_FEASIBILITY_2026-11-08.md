# 신호등 인식 가능 범위 — 2026-11-08

## 변경 전 앱에서 확인한 근거

- 번들 EfficientDet-Lite0의 `efficientdet_lite0_labels.txt`에는 COCO-90 기준 0부터 센 인덱스 9에 `traffic light`가 있습니다.
- `vision/od/LiteRtObjectDetector.kt`의 현재 허용 범주는 `0,1,2,3,5,7`이며 신호등 9는 제외됩니다. 따라서 현재 앱에서 신호등 박스가 표시된다고 주장할 수 없습니다.
- 모델 출력은 상자, 범주 인덱스, 점수, 개수입니다. 빨강/초록 상태나 보행자 신호등 구분을 출력하는 별도 클래스는 현재 라벨 목록에 없습니다.

## 가능한 확장

신호등 물체의 존재·위치 표시는 기존 OD 모델의 범주 9를 허용하고 한국어 라벨/표시 정책을 연결하는 방식으로 확장할 수 있습니다. 실제 원거리·소형 신호등 탐지 성능은 별도로 측정해야 합니다. 신호등은 장애물 경고 융합과 분리한 표시용 결과로 취급하는 편이 현재 구조에 맞습니다.

보행자 신호등과 차량용 신호등을 구분하고 빨강·초록·꺼짐 등을 판독하려면 해당 상태를 학습한 모델 또는 검증된 추가 분류 단계와 시간적 안정화가 필요합니다. 일반 `traffic light` 상자나 횡단보도의 초록색 분할 표시만으로 이 상태를 얻을 수는 없습니다.

사용자는 후속 응답에서 신호등 위치 박스 추가를 승인했습니다. 별도 OD 작업에서 범주 9의 위치·한국어 라벨 표시를 연결하며, 기존 장애물 경고 허용 범주와 신호 상태 판독 기능은 변경하지 않습니다. 구현·검증 결과는 `TRAFFIC_LIGHT_BOX_2026-11-08.md`에 기록합니다.

## 공식 자료

- [Google Object Detector: 물체 범주와 위치 출력](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector)
- [번들 모델과 일치하는 Google 라벨 목록](https://storage.googleapis.com/mediapipe-tasks/object_detector/labelmap.txt)
- 앱에 고정된 실제 모델·출력 계약: `app/src/main/assets/models/efficientdet_lite0_provenance.md`
