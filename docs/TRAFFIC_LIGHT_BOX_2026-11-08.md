# 신호등 위치 박스 표시 구현 — 2026-11-08

## 개요

사용자가 "신호등 위치 박스도 추가"를 명시적으로 선택함에 따라, 기존 번들 EfficientDet-Lite0 보조 객체 탐지(OD) 모델의 `traffic light`(카테고리 9) 바운딩 박스를 화면에 표시하도록 확장하였습니다.

본 구현은 **신호등 물체의 위치 및 신뢰도 표시 전용(Display-only)** 이며, 신호등 색상(빨강/초록/노랑), 보행자 신호등/차량용 신호등 구분, 보행 허가/정지 권고 등 **신호 상태 판독은 일절 수행하지 않습니다.**

---

## 변경 내역

### 1. `LiteRtObjectDetector.kt`
- `ALLOWED_CLASS_IDS`에 인덱스 9(`traffic light`) 추가:
  `intArrayOf(0, 1, 2, 3, 5, 7, TRAFFIC_LIGHT_CLASS_ID)` (단, `TRAFFIC_LIGHT_CLASS_ID = 9`).
- 모델 초기화 시 라벨 맵 검증(`validateLabelMap`)에 인덱스 9가 `"traffic light"`임을 확인하는 핀 검증 추가.
- 탐지 디코더(`decodeDetectionOutput`)에서 동일한 NMS, 신뢰도 임계값(0.50), 좌표 정규화 및 클리핑 규칙을 일관되게 적용.
- **장애물 우선 캡 선택(Obstacle-Preserving Cap Selection)**:
  - 후보 수가 `maxDetections` (12)를 초과할 경우, 장애물 카테고리(0, 1, 2, 3, 5, 7)를 점수 내림차순으로 우선 선택하여 최대 12개 슬롯을 보장.
  - 신호등(9)은 장애물이 차지하고 남은 여유 슬롯에만 점수 내림차순으로 채워짐.
  - 선택된 최종 서브셋은 엔진의 공개 정렬 계약에 따라 점수 내림차순으로 정렬하여 반환.
  - 이를 통해 고득점의 신호등이 다수 탐지되더라도 전방 장애물(사람, 차량 등)이 `maxDetections`에서 밀려나 누락되는 현상을 원천 방지.

### 2. `OdBoundingBoxOverlay.kt`
- `KOREAN_OBSTACLE_LABELS`에 `9 to "신호등"` 매핑 추가.
- `formatKoreanClassLabel(9)` 호출 시 `"신호등"` 반환, `formatOdLabel` 호출 시 `"신호등 {신뢰도}%"` 포맷 적용.
- 기존의 중립적인 OD 박스 스타일링(Amber Yellow 2dp 스트로크, 어두운 그림자 스트로크, 슬레이트 네이비 라벨 배지)을 그대로 유지하며, 신호 상태를 암시하는 빨강/초록 색상 변경을 배제.
- TalkBack 접근성 설명(`buildAccessibilityDescription`)에서도 `"보조 탐지 객체 N개: 신호등 XX%"` 형식으로 제공하여 보행 허가나 신호 상태 오인을 방지.

### 3. `ObjectDetectionHazardFusion.kt`
- 보행 안전 위험도 융합의 허용 카테고리(`ALLOWED_CLASS_IDS`)는 **기존 6대 장애물(`0, 1, 2, 3, 5, 7`)을 엄격히 유지**.
- 신호등(9)은 장애물 후보 선택(`selectEligibleObject`) 단계에서 제외되므로:
  - 전방 장애물 경고(advisory)를 발생시키지 않음.
  - 세그멘테이션 기본 위험도(HazardLevel)를 올리거나 내리지 않음.
  - 연속 프레임으로 관측되더라도 경고 후보가 되지 않음.
- 장애물(차량 등)과 신호등이 한 프레임에 동시 탐지될 경우, 장애물 단독 관측과 완전히 동일한 위험도 평가 결과를 보장.

### 4. `efficientdet_lite0_provenance.md`
- 표시 카테고리(0, 1, 2, 3, 5, 7, 9)와 위험도 융합 카테고리(0, 1, 2, 3, 5, 7)를 분리하여 문서화.
- 실제 디바이스 상의 원거리/소형 신호등 탐지 정확도 및 신호 상태 판독은 검증되지 않았음을 명시.

---

## 단위 테스트 검증 내역

1. **`OdModelArtifactTest`**:
   - `efficientdet_lite0_labels.txt` 인덱스 9가 `"traffic light"`임을 검증.
   - `LiteRtObjectDetector.ALLOWED_CLASS_IDS`가 0, 1, 2, 3, 5, 7, 9만 포함하고 타 COCO 카테고리를 제외함을 검증.
2. **`OdDetectionDecodeTest`**:
   - 카테고리 9 디코딩 및 좌표 매핑 검증.
   - 점수 미달, NaN, 소수점 클래스 ID, 비정상 바운딩 박스(좌표 역전 등) 가드 검증.
   - 혼합 >12 탐지 시 장애물이 고득점 신호등에 밀려나지 않고 모두 보존됨을 검증.
   - 전체 신호등 >12, 전체 장애물 >12, 정확히 12개, 12개 미만 엣지 케이스 검증.
3. **`ObjectDetectionHazardFusionTest`**:
   - 신호등 단독 5개 연속 프레임 관측 시 위험도 불변, 경고 미적용 검증.
   - 신호등 + 장애물(차량) 동시 관측 시 장애물 단독 관측과 100% 동일한 위험도 평가(advisory 적용) 생성 검증.
   - 기존 6대 장애물 카테고리 전부의 정상적인 경고 발생 검증.
4. **`OdBoxGeometryCalculatorTest`**:
   - `mapAndClip`의 "신호등", "신호등 92%" 한국어 라벨 및 픽셀 좌표 매핑 검증.
   - `evaluateBoxes`의 신호등 렌더링, 카메라 정지(Stopped/Error) 시 즉각 소멸, TalkBack 텍스트 검증.

---

## 안전 및 제약 조건 준수

1. **상태 분류 배제**: 보행 신호등 초록/빨강 판독 없음.
2. **TTS / 안내 배제**: 신호등 감지로 인한 음성 건너기 안내 또는 방향 지시 없음.
3. **위험도 융합 격리**: 신호등은 시각적 보조 오버레이로만 동작하며 장애물 판정/경보 정책에 영향 없음.
4. **UI 레이아웃 불변**: Home 화면의 오버레이 서브트리에 버튼, 토글, 상호작용 액션 일절 없음. 단일 방향선 및 기존 레이아웃 보존.
5. **모델 불변**: 번들 모델 파일, 해시, 바이트 수 변경 없음.
