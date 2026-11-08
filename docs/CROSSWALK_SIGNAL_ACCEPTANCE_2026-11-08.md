# 횡단보도 신호등 확인 안내 — 독립 검증 결과 (2026-11-08)

현재 작업은 신호 색상 판독이 아닌, 원본 횡단보도 분류와 일반 신호등 탐지를 결합한 사실적 확인 안내를 구현했다. 실제 카메라 경로는 CrosswalkSignalAdvisory를 거쳐 기존 HazardAlertCoordinator/HazardSpeechBridge로 프레임당 한 평가를 전달한다. 안내는 “횡단보도 앞 신호등을 확인하세요”이며, 기존 음성 설정과 서비스 상태에 따른다.

## 동작과 한계

- raw class 3 근접 영역 비율 기본 0.20 이상, 유효 class 9 박스의 점수 기본 0.50 이상을 사용한다. 표시용 초록색/횡단보도 간격 채움은 위험 증거로 쓰지 않는다.
- 서로 다른 OD 관측 2개를 확인한 뒤 후보를 유지하며, 기존 CAUTION 3프레임 정책을 통과한 Fire에 대해서만 접근 안내를 소비한다. 동일 관측은 중복 집계하지 않는다.
- 오래되거나 잘못된 시각/세션/화면 형상, 불확실한 자세, 분리된 경쟁 신호등과 무관한 측면 신호는 억제한다. 박스는 IoU/중심 연속성으로 연결한다.
- WARNING, DEGRADED/UNKNOWN 및 C1–C4를 보존한다. C5 횡단보도 진입 안내는 관련 신호등이 확인되면 C6 확인 안내로 바꿀 수 있다.
- 기본 접근 재무장은 서로 다른 유효 프레임 10개에서 원본 횡단보도 분류가 사라지는 휴리스틱이다. 실제 교차로의 정체성이나 보행자의 물리적 이동을 입증하지 않는다.
- 일반 traffic light 범주이므로 차량용/보행자 신호 구분이나 빨간불/초록불 판독은 하지 않는다. 정지/횡단 허가를 신호 색상에 따라 안내하지 않는다. 임계값은 실제 환경에서 교정된 안전 기준이 아니다.

## 모델 조사

Autoware ROI 분류기와 ImVisible LYTNet 가중치를 실제 호스트에서 감사했다. ONNX 출력 Softmax와 입출력 규격, 원본 해시, CPU map_location을 사용한 안전한 PyTorch 가중치 적재/순전파를 확인했다. 하지만 현재 앱에서 보행 신호 유형과 해당 횡단보도의 관련성을 검증한 Android 상태 판독 경로는 마련되지 않아, 새 모델 자산이나 가짜 분류 엔진은 추가하지 않았다. 모델 크기/입력 해상도만으로 모바일 실행 불가능이나 지연을 주장하지 않는다.

- 조사/변환 범위: [모델 감사](PEDESTRIAN_SIGNAL_ENGINE_2026-11-08.md)
- 독립 감사 명령: `build/vision-pedestrian-signal/venv/Scripts/python.exe tools/vision/pedestrian_signal/audit_pedestrian_signal_models.py`
- 종료 코드 0; 원본 로그 `%TEMP%/aria-pedestrian-signal-independent-audit-20261108.log`

## 독립 앱 검증

Android Studio JBR와 기존 SDK 설정, 저장소 Gradle wrapper를 사용했다.

`./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain`

- 실제 종료 코드 **0**, BUILD SUCCESSFUL (2m 56s).
- 전체 JVM **1,044개**, 실패/오류/건너뛰기 **0**. 전체 testDebugUnitTest가 실행됐으며 캐시 재사용으로만 통과한 결과가 아니다.
- 신호 정책/연결 관련 25개 테스트(13+12) 통과. 실제 HazardAlertPolicy에 대한 두 주기 시뮬레이션으로 Fire 발생·한 접근 중 억제·대상 연속성·경쟁 신호·메타데이터·기존 위험 보존·초기화를 확인했다.
- 첫 집중 실행의 실패 3개는 별도로 기록했고, 기본 UNKNOWN 상태/음수 stale 시각/확인 전 null 후보 등 테스트 입력과 기대를 바로잡아 재검증했다. 실패를 통과로 집계하지 않았다.
- Lint 오류 0, 경고 196, note 3. androidTest는 컴파일만 했으며 기기 실행은 하지 않았다.
- 원본 로그 `%TEMP%/aria-crosswalk-signal-independent-validation-20261108.log`; XML `app/build/test-results/testDebugUnitTest/`; Lint `app/build/reports/lint-results-debug.html`.
- `git diff --check` 종료 코드 0. 신규 Kotlin 6개 파일의 후행 공백도 별도 점검했다.

## 소유권과 보존

Orca 모델 조사/정책/앱 연결 및 감사 교정 Tasks의 최종 산출물을 직접 검토했다. 완료 작업자의 터미널은 해제했고 재사용 전 조사 Dispatch는 소유 자원 없는 과거 기록만 남는다. 현재 Run에 실행 중이거나 회수해야 할 작업자 터미널은 없다.

작업 시작 전부터 있던 거리 추정 코드/테스트 4개 파일의 SHA-256이 보존됐다. VisionPipelineController도 별도 시작 시점 사본과 비교해 이번 신호 안내 추가만 확인했다. Home 레이아웃/방향선/비상호작용 규칙은 바꾸지 않았다. 실제 카메라 신호 인식률·처리율·실외 보행·음성 출력은 기기에서 검증하지 않았다. 설치·커밋·푸시·릴리즈 업로드는 수행하지 않았다.
