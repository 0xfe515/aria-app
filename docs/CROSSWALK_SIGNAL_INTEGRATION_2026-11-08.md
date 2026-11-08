# 횡단보도 신호등 존재 알림 통합 검증 보고서 (Crosswalk Signal Integration Verification) — 2026-11-08

**Task ID:** ARIA-CROSSWALK-SIGNAL-INTEGRATION-20261108
**Run ID:** run_2736967cff19
**Dispatch ID:** ctx_5bea835b8102
**Worker Terminal:** term_a82f54e8-0aa9-4998-ab22-40fd1b969cbf
**Coordinator Terminal:** term_f6f9149b-d0bb-4558-922c-0ee25c69bfe3

---

## 1. 개요 및 안전 원칙

사용자의 요청("횡단보도 신호등도 가능하면 연결")에 따라, 실제 카메라 프레임 분석 파이프라인에서 번들 EfficientDet-Lite0 객체 탐지(OD) 모델의 `traffic light`(카테고리 9) 바운딩 박스와 세그멘테이션 모델의 횡단보도(클래스 3) 증거를 결합하여 사실에 기반한 주의 안내(`"횡단보도 앞 신호등을 확인하세요"`)를 기존의 위험 경보 조정기(`HazardAlertCoordinator`), 음성(`HazardSpeechBridge`), 진동(Haptics) 경로로 연결하였습니다.

### 핵심 안전 원칙
1. **사실 기반 존재 확인 전용 (Factual Presence Only)**:
   - 검증된 보행자 신호등 전용 온디바이스 모델이 부재하므로, 빨간불/초록불 상태 판독이나 보행 허가/정지 권고("건너세요" 등)는 일절 수행하지 않음.
   - 신호등 위치 탐지 및 횡단보도 접근 시 `"횡단보도 앞 신호등을 확인하세요"` 사실 알림만 방출.
2. **횡단보도 접근당 최대 1회 안내 (Once-Per-Approach Enforcement)**:
   - 사용자가 횡단보도에 머무르는 동안 동일한 알림이 반복 재생되지 않도록, 하류 `HazardAlertCoordinator`에서 알림 전달 결정(`AlertDecision.Fire`)이 발생했을 때만 전달 승인(`acknowledgeDelivered`)을 호출하여 해당 접근 티켓(`approachId`)을 소비.
   - 원본 근접 영역의 횡단보도 분류가 연속 유효 프레임에서 사라진 뒤 다시 관측될 때 신규 `approachId`가 부여되어 1회 안내를 재제공.
3. **상위 위험 보존 (Stronger Hazard Invariant)**:
   - 차량 접근(`W1_VEHICLE_APPROACH`), 장애물 근접(`W2_OBJECT_IMMINENT`), 차도 이탈(`W3`), 정적 장애물(`W4`), 전방 차단(`W5`) 등 경고(`WARNING`) 및 자세 불안정(`DEGRADED`, `UNKNOWN_DEGRADED`), 전방 장애물 주의(`C1_OBJECT_IN_PATH`, `C2_VEHICLE_NEARBY`)가 존재할 경우 신호등 안내는 조건 없이 억제되며 원본 위험 평가를 100% 보존.
   - 기존의 신호 부재 안내(`C5_CROSSWALK_ENTRY`: `"횡단보도 앞입니다. 신호는 확인할 수 없습니다"`)는 신호등 존재가 시계열로 확인되면 안전하게 `C6_CROSSWALK_SIGNAL_PRESENCE`(`"횡단보도 앞 신호등을 확인하세요"`)로 승격/대체됨.
4. **프레임당 단일 평가 (Single Assessment Invariant)**:
   - 카메라 1프레임당 정확히 1회의 `alertCoordinator.processAssessment(finalAssessment, timestampNanos)` 호출만을 수행.
5. **페일클로즈 및 시간/기하구조 일관성**:
   - 1.5초 초과 지연 관측, 미래 타임스탬프, 세션/기하구조 불일치, 회전/크롭 변경, 모션 센서 신뢰도 부족 시 즉시 페일클로즈 억제 및 시계열 후보 초기화.

---

## 2. 이원 클럭(Two-Clock) 및 시계열 추적 아키텍처

```
Camera Frame (test example ~8 Hz)   Auxiliary OD (test example 5 Hz)
       │                                     │
       ▼                                     ▼
 classMask (Class 3)                   OdEvidence (Class 9)
       │                                     │
       └─────────────────┬───────────────────┘
                         ▼
        CrosswalkSignalAdvisory (Adapter)
                         │
                         ├── 1. 엄격한 메타데이터 & 지연 검증 (Stale > 1.5s, Future > 0.5s 거부)
                         ├── 2. 단일 트랙 IoU/Center 연속성 추적 (Jitter 및 리스트 순서 무관 TrackId 유지)
                         ├── 3. 진행 회랑(Active Corridor) 정렬 검증 (무관한 측면 신호등 거부)
                         ├── 4. 분리된 경쟁 신호등(Disjoint Competing Lights) 페일클로즈 차단
                         ├── 5. 중복 탐지(Overlapping Duplicates) 결합 (IoU >= 0.40)
                         ├── 6. 횡단보도 접근 유효성 및 접근 티켓(ApproachId) 관리
                         │
                         ▼
         CrosswalkSignalPresencePolicy (Pure Policy)
                         │
                         ├── 2프레임 연속 신선 관측 확인 (presenceConfirmationFramesRequired = 2)
                         ├── 카메라 프레임 간 후보 지속 유지 (Replay until Fire)
                         └── Decision: CautionPresence or Suppressed
                         │
                         ▼
             candidate.applyTo(baseAssessment)
                         │  (Level: PATH_CAUTION, Cause: C6_CROSSWALK_SIGNAL_PRESENCE)
                         ▼
      HazardAlertCoordinator.processAssessment(...)
                         │
                         ├── HazardAlertPolicy (3-frame hysteresis for CAUTION)
                         ├── AlertDecision.Fire 발생 시:
                         │     ├── acknowledgeDelivered 호출 (티켓 소비)
                         │     ├── 약한 진동 (70ms haptic tap)
                         │     └── HazardSpeechBridge -> VoiceService 음성 발화
                         │
                         └── AlertDecision.Suppressed 발생 시:
                               └── 티켓 소비하지 않고 대기 (Downstream Starvation 원천 방지)
```

---

## 3. 세부 설계 및 개선 내역 (Review Enhancements)

1. **단일 트랙 IoU & 중심점 연속성 추적 (`updateTargetTrack`)**:
   - 기존의 4비트 양자화 해시 방식의 버킷 경계 지터 및 충돌 문제를 해결하기 위해, 이전 관측 박스와의 IoU(`>= 0.30`) 및 중심점 거리(`<= 0.08`) 기반의 기하학적 연속성 트래커를 구현.
   - 탐지 리스트의 인덱스 순서 변경이나 미세한 바운딩 박스 흔들림에도 동일한 `trackId`를 일관되게 유지.
2. **분리된 다중 경쟁 신호등 페일클로즈 및 측면 신호등 거부**:
   - 보행 회랑(`CorridorDirection.CENTER/LEFT/RIGHT`)의 전방 수평 시야각 내에 정렬되지 않은 측면 신호등은 제외.
   - 한 장면에 둘 이상의 물리적으로 분리된(`IoU < 0.40`) 고득점 신호등이 동시에 탐지될 경우, 신호 적용 대상을 단정할 수 없으므로 안전하게 페일클로즈(`emptyList()`) 처리.
   - 동일 신호등의 중복 탐지 박스는 최고 점수 박스로 단일화.
3. **기하구조 토큰 객체 동일성 및 단조 증가 카운터**:
   - `hashCode()` 충돌 가능성을 배제하고, `OdGeometryToken`의 실제 객체 동등성 비교 및 단조 증가 세대 번호(`monotonicGeometryToken`)를 policy에 주입.
4. **엄격한 시간/메타데이터 정밀 검증**:
   - `captureTimestampNanos`, `arrivalElapsedNanos`, `completedElapsedNanos`, `resultAgeNanos`의 시계열 순서 및 시스템 모노토닉 클럭 상호 일관성 검증.
   - 카메라 촬영 시점보다 미래 시점의 관측 타임스탬프 거부.
5. **접근 재무장(Re-arm) 카메라 타임스탬프 검증**:
   - 횡단보도 이탈 후 접근 재무장은 중복되거나 지연된 프레임이 아닌, 신선하고 단조 증가하는 카메라 프레임만을 카운트하여 재무장.

---

## 4. 단위 테스트 검증 결과

Studio JBR Gradle 래퍼를 통해 전용 테스트 스위트를 실행하였으며, 전원 통과를 확인하였습니다.

```
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& gradlew.bat :app:testDebugUnitTest --tests com.ariateam.aria.hazard.traffic.* --tests com.ariateam.aria.vision.CrosswalkSignalAdvisoryTest --console=plain"

BUILD SUCCESSFUL in 12s
27 actionable tasks: 2 executed, 25 up-to-date
```

### 테스트 케이스 상세 (25/25 Passed)

#### `CrosswalkSignalPresencePolicyTest` (13 Passed)
1. `twoClock_odReusedAcrossCameraFrames_sustainsEligibleCandidateWithoutDoubleCounting` — 카메라 8Hz vs OD 5Hz 이원 주기에서 중복 관측 재사용 시 중복 카운트 방지 및 후보 유지 검증
2. `endToEndRegression_deliversAlertThroughHazardAlertPolicy_andEnforcesOncePerApproach` — HazardAlertPolicy 3연속 프레임 확인, 1회 Fire 발생, 전달 승인 후 접근당 1회 억제 검증
3. `crosswalkAbsentOrScoreTooLow_suppressesCaution` — 횡단보도 부재 또는 점수 미달 시 주의 억제 검증
4. `crosswalkScoreValidation_mustBeInZeroToOneRange` — 횡단보도 점수 음수/1초과/NaN 가드 검증
5. `strictGeometryValidation_requiresRightExceedsLeftAndBottomExceedsTop` — 제로 박스 및 역전 박스 거부 검증
6. `unreliableMotion_suppressesTrafficCaution` — 모션 센서 신뢰도 부족 시 즉시 페일클로즈 검증
7. `strongerHazardPresent_suppressesTrafficCaution` — 상위 위험 시 주의 억제 검증
8. `observationGap_exceedingMaxGap_resetsConsecutiveCount` — 750ms 초과 공백 시 연속 카운트 초기화 검증
9. `sessionAndGeometryMigration_resetStateFailClosed` — 세션 및 기하구조 변경 시 페일클로즈 초기화 검증
10. `timestampValidation_zeroNegativeFutureStale_rejectedFailClosed` — 음수, 0, 미래, 1.5s 지연 타임스탬프 차단 검증
11. `frameDuplicateOrOutOfOrder_rejected` — 중복/역전 카메라 프레임 거부 검증
12. `candidate_preservesStrongerBaseAssessment_andAppliesWhenClear` — base의 WARNING 및 DEGRADED 100% 보존, PATH_CAUTION 레벨 설정 검증
13. `cameraStopAndPipelineError_resetState` — 카메라 중지 및 파이프라인 에러 리셋 검증

#### `CrosswalkSignalAdvisoryTest` (12 Passed)
1. `twoRate_drivingHazardAlertPolicy_firesExactlyOnce_withoutStarvationOrReplay` — 실제 어댑터와 HazardAlertPolicy 간 10프레임 엔드투엔드 시뮬레이션, 정확히 1회 Fire 및 전달 승인, 스타베이션/재발화 없음 검증
2. `singleTrackContinuity_boundaryJitterAndReorderedBoxes_preservesTargetId` — 경계 지터 및 탐지 리스트 순서 변경 시에도 동일 trackId 유지 검증
3. `disjointCompetingLights_and_unrelatedSideLights_failClosed` — 분리된 경쟁 신호등 및 보행 회랑 외 측면 신호등 페일클로즈 검증
4. `overlappingDuplicateDetections_collapseCleanly` — 동일 신호등 중복 박스 결합 검증
5. `strictMetadataChecks_rejectInconsistentOrFutureEvidence` — 도착/완료 시각 모순 및 미래 관측 차단 검증
6. `staleHeldOdEvidence_suppressesAdvisory` — 디스플레이 홀드 잔존 스냅샷(>1.5s) 차단 검증
7. `sessionMismatch_and_geometryMismatch_suppressesAdvisory` — 세션 및 기하구조 불일치 차단 검증
8. `irrelevantCrosswalk_suppressesAdvisory` — 횡단보도 미검출 마스크 차단 검증
9. `lowConfidence_or_invalidBox_suppressesAdvisory` — 신뢰도 미달(<0.5) 및 비정상 박스 차단 검증
10. `strongerHazard_warningAndObstacleCautionPreserved_c5EntryUpgraded` — 차량/장애물 경고 보존, C5(신호확인불가)에서 C6(신호등확인) 정상 승격 검증
11. `approachSettlement_requiresDistinctValidCameraFrames_rearmsAfterMeaningfulLoss` — 단일 프레임 깜빡임은 접근 유지, 5프레임 이상 연속 이탈 시 신규 접근 재무장 검증
12. `lifecycleResets_cleanlyResetTemporalState` — 카메라 중지, 에러, 세션 변경 시 시계열 상태 정상 초기화 검증

#### `com.ariateam.aria.distance.*` 회귀 검증
- 기존 지면 접촉 거리 추정(`GroundContactDistanceEstimatorTest`, `OdMaskMatcherTest`) 전체 테스트 재실행 성공 (Zero Regressions).

---

## 5. 변경 파일 요약

1. `app/src/main/java/com/ariateam/aria/hazard/HazardTypes.kt`:
   - `HazardCause.C6_CROSSWALK_SIGNAL_PRESENCE(AlertSeverity.CAUTION)` 열거형 추가.
2. `app/src/main/java/com/ariateam/aria/hazard/HazardRuleEngine.kt`:
   - `createMessage`에 `C6_CROSSWALK_SIGNAL_PRESENCE` 분기 추가 (`"횡단보도 앞 신호등을 확인하세요"`).
3. `app/src/main/java/com/ariateam/aria/walking/WalkingAssistCopy.kt`:
   - `hazardWarning`에 `C6_CROSSWALK_SIGNAL_PRESENCE` 매핑 추가.
4. `app/src/main/res/values/walking_strings.xml`:
   - `walking_hazard_c6_crosswalk_signal` 리소스 정의 추가.
5. `app/src/main/java/com/ariateam/aria/hazard/traffic/TrafficSignalTypes.kt`:
   - `candidate.applyTo`에서 `level = HazardLevel.PATH_CAUTION` 보장 및 `UNKNOWN_DEGRADED` 덮어쓰기 방지.
6. `app/src/main/java/com/ariateam/aria/hazard/traffic/CrosswalkSignalPresencePolicy.kt`:
   - 카메라 프레임 대비 미래 관측 차단 가드 및 `clearCandidate()` 가시성 확대.
7. `app/src/main/java/com/ariateam/aria/vision/CrosswalkSignalAdvisory.kt` *(New)*:
   - 세그멘테이션 마스크 클래스 3 추출, OD 카테고리 9 필터링, 단일 트랙 IoU 추적, 분리 경쟁 신호등 페일클로즈, 전달 승인 인터페이스 구현.
8. `app/src/main/java/com/ariateam/aria/vision/VisionPipelineController.kt`:
   - `CrosswalkSignalAdvisory` 인스턴스 생성, 라이프사이클(중지/에러/회전/세션/전처리 변경 등) 리셋 연결, 프레임당 단일 평가 및 발화 시 `acknowledgeDelivered` 연동.
9. `app/src/test/java/com/ariateam/aria/hazard/traffic/CrosswalkSignalPresencePolicyTest.kt`:
   - 타임스탬프 유효 범위 및 엔드투엔드 접근 억제 테스트 보강.
10. `app/src/test/java/com/ariateam/aria/vision/CrosswalkSignalAdvisoryTest.kt` *(New)*:
    - 12개 핵심 시나리오 회귀 테스트 구현.

## 해석 범위

- 접근 구분은 원본 횡단보도 분류의 부재에 기반한 휴리스틱입니다. 기본값은 서로 다른 유효 카메라 프레임 10개이며 테스트 일부는 5개로 설정합니다. 실제 교차로의 정체성이나 사용자가 물리적으로 떠났는지를 측정하지 않습니다.
- Fire는 기존 알림 경로의 전달 결정이며 실제 TTS 소리가 출력됐다는 기기 측정 결과가 아닙니다. 음성 설정, 서비스 활성 상태와 TTS 준비 상태에 따릅니다.
- 도식의 카메라/OD 주기는 테스트 예시이며 이번 기능의 실제 처리율을 측정하지 않았습니다. 현재 앱의 OD 설정은 세그멘테이션 주기를 따릅니다.
