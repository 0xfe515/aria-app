# Gemini 코드 독립 검토 및 수정 검증 — 2026-11-09

## 수정

기준 8f95c39의 호출어·사진 질문·온보딩 변경과 이전 사진/내비게이션 경로를 직접 검토했다. 높은 사진 질문 재현율은 유지한다. 누락된 금지 표현(앞에 글씨 읽으면 안 돼, 앞에 사진 안 찍어줘)이 사진 요청으로 전달되고, 사진/질문 취소·중지 표현이 정상 취소로 처리되지 않는 결함을 수정했다. 취소 판정은 CloudCommandParser에 모으고 interpreter가 해당 실행 결과를 전달한다.

수정 작업자는 Orca Gemini gemini-3.8-flash-high였으며 requested/effective 일치 및 실제 터미널 모델 표시를 확인했다. Task task_d69951bcfc63 / Dispatch ctx_829f9b6f394d의 완료를 대조하고 독립 검증했다. 초기 수정안의 못 읽겠어 같은 불능 표현 거부는 제거했고 정상 사진 질문 회귀 사례로 보호했다. 이전 Gemini 검토의 lastQuestionId 초기화 제안은 중복 방지 기준을 약화시켜 수용하지 않았다. idle Cancel 반복은 무해한 중복 호출로 남겼다.

## 검증

Android Studio JDK와 ignored local.properties의 SDK를 사용했다.

- 수정 전 회귀 재현: 33개 중 5개 실패, 종료 1. 수정 후 동일 대상 33개 통과, 종료 0.
- 독립 실행: gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin — 종료 0, BUILD SUCCESSFUL in 1m 49s.
- JVM 1,071개, 실패/오류/건너뜀 0. Lint 오류 0, 경고 197, 힌트 3. git diff --check 종료 0.
- Pixel 3 / blueline / Android API 31: adb install -r 및 test APK install -r -t 성공. versionCode 13, 1.0.0-preview.20261109.3 확인. firstInstallTime은 기존 2026-11-09 04:35:30으로 유지.
- 직접 instrumentation: WakeRecallNativeSpotterTest 통과. 첫 카메라 테스트는 화면이 꺼진 상태에서 Running 대기 30초 초과로 실패. 화면 깨우기 후 CloudImageCaptureDeviceTest 단독 재실행은 5.701초에 OK (1 test). 실제 카메라 JPEG가 가짜 API 전송 본문에 첨부되고 디코딩됨을 확인했다.
- adb 명령 종료 0만으로 테스트 성공을 판단하지 않았다. 최초 instrumentation은 명령 종료 0이었지만 내부 테스트 1개 실패로 기록했다.

## 한계 및 배포

금지 표현은 명시된 형태와 회귀 예시에 대한 보완이며 임의 한국어 문장의 완전한 의도 판별을 보장하지 않는다. 호출어 엔진 초기화/무음 입력 통과는 실제 발화 인식률 검증이 아니다. 실제 서버 이미지 해석과 잠금/화면 꺼짐 상태 카메라 실행은 확인하지 못했다. 카메라 테스트는 가짜 인증/전송을 사용하며 기존 프로필을 복원한다. 앱 제거, 데이터 삭제, 유료 사용, 실서버 합성 이미지 진단은 실행하지 않았다.

최신 상시 승인에 따라 연결된 호환 기기에 데이터를 보존하는 설치를 수행했다. 이전 no-device 가정은 현재 연결 상태와 달라 적용하지 않았다. 관련 코드/테스트/버전/검증 문서만 커밋하며 기존 AGENTS.md 수정 및 연구·자동화 미추적 파일은 보존한다. 기존 debug APK prerelease 배포 방식을 유지한다.

원본 로그(머신 로컬 TEMP): aria-gemini-review-20261109-host.log, aria-gemini-review-20261109-device.log, aria-gemini-review-20261109-camera-retry.log; worker red/green: test_regression_red.log, test_regression_final.log.
