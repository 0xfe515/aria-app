# 보행자 횡단보도 신호 존재 알림 정책 (Crosswalk Traffic Signal Presence Policy) — 2026-11-08

## 1. 개요 및 안전 원칙

본 문서는 보행 안전 안내 시스템(Aria)에서 횡단보도 접근 시 보행자에게 사실에 기반한 신호등 확인 권고(`"횡단보도 앞 신호등을 확인하세요"`)를 제공하기 위한 순수 Kotlin 기반의 시계열 의사결정 정책(`PedestrianCrosswalkSignalPolicy`)의 설계 및 규격을 기술합니다.

### 실제 모델 감사 및 제품 스코프 동결 (Scope Freeze & Model Prerequisites)
- **실제 모델 감사 결과**:
  - 오프라인/호스트 상의 Autoware ROI 분류기는 보행자용 신호와 차량용 신호 구분이 불가능함.
  - LYTNet 모델은 안전한 가중치 로드가 차단되어 디바이스 상에 배포 불가함.
  - 따라서 현재 디바이스/런타임에는 검증되고 이식 가능한 보행 신호등 전용 상태(Red/Green) 판독 파이프라인이 부재함.
- **제품 구현 동결**:
  - 검증되지 않은 가상의 빨간불/초록불 판독 로직을 배제하고, 현존하는 EfficientDet-Lite0 카테고리 9(`traffic light`) 위치 바운딩 박스와 세그멘테이션 횡단보도(`CrosswalkRelevanceContext`)가 관련성을 가질 때 사실 기반의 리마인더(`"횡단보도 앞 신호등을 확인하세요"`)만 접근당 1회(`once per approach`) 방출하도록 순수 구현을 동결함.
  - **초록불 안전 허가 절대 배제**: 신호등을 감지하더라도 "건너세요" 또는 안전 횡단 허가는 일절 생성하지 않음.
  - **빨간불 상태 날조 배제**: 일반 신호등 존재만으로 빨간불로 지칭하거나 정지 명령을 단정하지 않음.
  - **미래 모델 전제 조건**: 향후 실제 보행자 신호등 전용 온디바이스 TFLite/LiteRT 모델이 검증·배포된 이후에만 빨간불 상태 추론 브랜치를 확장함.

---

## 2. 이원 클럭(Two-Clock) 및 전달 승인(Delivery Contract)

### 2.1. 이원 클럭(Two-Clock) 설계
Aria 아키텍처는 기본 카메라 세그멘테이션 파이프라인(~8-15 Hz)과 보조 OD 사이드카 파이프라인(5 Hz)이 서로 다른 주기로 구동됩니다.
- `frameCaptureTimestampNanos`: 주 카메라 프레임 수신 시각. 매 카메라 프레임마다 단조 증가해야 함.
- `signalObservationCaptureTimestampNanos`: 사이드카 OD 추론 스냅샷 시각. 약 200ms(5 Hz)마다 갱신되며, 여러 카메라 프레임에 걸쳐 동일한 관측이 재사용(캐싱)될 수 있음.
- **중복 증거 방지 및 후보 유지**:
  - 동일한 `signalObservationCaptureTimestampNanos`가 여러 카메라 프레임에서 재사용될 때, 시계열 증거 카운트(`consecutivePresenceCount`)는 중복 합산되지 않음.
  - 반면, 신호등 존재 조건이 한 번 유효(eligible) 판정을 받으면 하류 `HazardAlertPolicy`가 3연속 프레임을 확인할 수 있도록 카메라 프레임에 걸쳐 안정적으로 후보(`CautionPresence`)를 지속 재생(replay)함.

### 2.2. 전달 승인 (`acknowledgeDelivered`)
- `HazardAlertPolicy`는 `CAUTION` 심각도에 대해 3연속 프레임 일관성을 요구합니다.
- 정책이 1프레임만 후보를 내고 즉시 소비해버리면 사용자는 알림을 수신하지 못합니다.
- 따라서 `HazardAlertPolicy.evaluate`가 실제로 `AlertDecision.Fire`를 반환했을 때 `policy.acknowledgeDelivered(cause, approachId)`를 호출하여 횡단보도 접근 1회 티켓(`lastWarnedApproachId`)을 소비합니다.
- 이후 동일 접근에서는 `APPROACH_ALREADY_WARNED`로 억제되며, 사용자가 횡단보도를 벗어났다가 새로운 접근(`approachId` 변경)으로 진입할 때 다시 1회 안내를 제공합니다.

---

## 3. 기하구조, 시간 검증 및 상위 위험 보존

1. **엄격한 기하구조 검증**:
   - `boxRight > boxLeft && boxBottom > boxTop` (기본 영(zero) 박스 및 역전 박스 거부).
   - `crosswalkScore in 0.0..1.0`.
2. **시간/세션 페일클로즈 차단**:
   - 0 이하, 역전, 중복 카메라 프레임 타임스탬프 거부 (`INVALID_CAPTURE_TIME`, `DUPLICATE_OR_OUT_OF_ORDER`).
   - 500ms 초과 미래 타임스탬프 거부 (`FUTURE_CAPTURE`).
   - 1.5초 초과 지연 관측 거부 (`STALE_OBSERVATION`).
   - 카메라 세션/기하구조 변경 시 즉시 상태 초기화 (`SESSION_MISMATCH`, `GEOMETRY_MISMATCH`).
   - 750ms 초과 관측 공백 시 연속 카운트 초기화.
3. **상위 위험 보존 (`applyTo`)**:
   - `candidate.applyTo(baseAssessment, explicitHazardCause)`:
     - 기본 평가가 이미 `AlertSeverity.WARNING` 또는 `AlertSeverity.DEGRADED`인 경우 원본을 100% 보존하고 신호등 주의 안내로 덮어쓰지 않음.
     - 통합 작업자(`coordinator integrationTask`)가 소유한 명시적 `HazardCause` 인자를 필수로 요구함.

---

## 4. 잠정 임계값 (Provisional Thresholds)

| 설정 항목 | 기본값 | 단위 | 설계 근거 |
| :--- | :--- | :--- | :--- |
| `presenceConfirmationFramesRequired` | 2 | 프레임 | 5Hz 신선 관측 기준 약 400ms 동안 유지되어야 안내 후보 방출 |
| `staleObservationExpiryNanos` | 1.5 | 초 | 카메라 촬영 시점으로부터 1.5초 이상 지연된 추론 결과는 폐기 |
| `maxFutureToleranceNanos` | 0.5 | 초 | 시스템 클럭 오차 허용 한계 (500ms) |
| `maxConsecutiveCaptureGapNanos` | 0.75 | 초 | 프레임 간격이 750ms를 초과하여 끊기면 시계열 연속 카운트 초기화 |
| `minPresenceConfidence` | 0.50 | 비율 | 일반 신호등 탐지 박스의 최소 신뢰도 임계값 |
| `minCrosswalkScore` | 0.20 | 비율 | 횡단보도 세그멘테이션 관련성 최소 점수 (0.0 ~ 1.0 범위) |
| `presenceFallbackMessage` | `"횡단보도 앞 신호등을 확인하세요"` | 문자열 | 요구사항에 부합하는 사실적 중립 안내문 |

---

## 5. 단위 테스트 검증 내역 (`PedestrianCrosswalkSignalPolicyTest`)

1. **이원 클럭(카메라 ~8Hz, OD 5Hz) 및 증거 중복 방지**:
   - 동일 OD 스냅샷이 연속 카메라 프레임에 재사용될 때 중복 카운트 방지 검증.
   - 2회 신선 OD 관측 후 후보가 카메라 프레임 간에 안정적으로 지속 방출됨을 검증.
2. **HazardAlertPolicy 실제 클래스와의 엔드투엔드 시뮬레이션**:
   - 카메라 6프레임 시뮬레이션 중 3연속 CAUTION 주입 -> `HazardAlertPolicy.evaluate`에서 `AlertDecision.Fire` 발생 확인 -> `acknowledgeDelivered` -> `APPROACH_ALREADY_WARNED` 전환 확인.
   - 신규 횡단보도 접근(`approachId = 2`) 시 다시 1회 정상 방출 허용 확인.
3. **기하구조 및 점수 범위 엄격 검증**:
   - 영(zero) 박스, 좌표 역전, crosswalkScore 음수/1초과/NaN 거부 검증.
4. **시간 예외 차단**:
   - 0/음수 타임스탬프, 중복/역전, 미래 타임스탬프, 1.5초 지연 거부 검증.
5. **라이프사이클 및 마이그레이션**:
   - 세션/기하구조 마이그레이션, `onCameraStop()`, `onPipelineError()`, `reset()` 시 페일클로즈 초기화 검증.
6. **상위 위험 우선순위 및 applyTo 보존**:
   - 차량/충돌 상위 위험 시 주의 억제 검증.
   - `applyTo`에서 base의 `WARNING` 및 `DEGRADED` 100% 보존 검증.
