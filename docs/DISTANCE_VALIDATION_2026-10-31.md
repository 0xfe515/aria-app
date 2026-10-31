# 거리 감지 통합 검증 — 2026-10-31

## 구현 범위

- 홈의 `보행 거리 감지 (AR)` 및 설정의 진단 메뉴에서 전용 ARCore Raw Depth 모드로 진입한다.
- 기존 CameraX 분할 모드와 카메라 소유권을 전환한다. 기존 분할 결과와 깊이의 의미론적 융합은 이번 범위에 포함하지 않는다.
- 바닥 후보와 깊이 신뢰도를 확인하고, 카메라 수평 전방 복도 내 장애물 군집의 추정 거리를 표시한다.
- 실험용 임계값은 주의 3m 이하, 강한 경고 1.5m 이하이다. 매 세션 준비 상태를 다시 평가하고 재보정을 제공한다.

## 독립 검증

Android Studio JDK와 저장소 Gradle wrapper를 사용했다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

최종 실행은 종료 코드 0, `BUILD SUCCESSFUL`이다. 최종 JVM XML 합계는 **246개, 실패 0, 오류 0**이다. Lint는 **오류 0, 경고 97개**이며 경고를 숨기는 설정은 추가하지 않았다. 구현을 복사한 테스트 2개는 제거하고 실제 추정기·경고 정책·바닥 후보 처리 테스트를 유지했다.

Pixel 3 (`8B7X15A0F`, Android 12/API 31), Google Play Services for AR `1.57.262640093`, SDK 의존성 `com.google.ar:core:1.57.0`에서 검증했다.

```powershell
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.distance.DistanceActivitySmokeTest,com.ariateam.aria.ui.screen.VoiceDemoScreenTest,com.ariateam.aria.ui.screen.SettingsScreenTest'
```

위 실행은 성공했으나 결과 XML에서 첫 클래스인 거리 모드 테스트 **4개만 실행**된 것을 확인했다. 실제 Activity 생성·일시정지·재개, 매니페스트, 거리·오류 UI 검증이 통과했다. 누락된 기존 UI 클래스는 테스트 APK를 다시 설치한 후 아래 명령으로 별도 실행했고 **23개 모두 통과**했다.

```powershell
adb -s 8B7X15A0F install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 8B7X15A0F install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 8B7X15A0F shell am instrument -w -r -e class 'com.ariateam.aria.ui.screen.VoiceDemoScreenTest,com.ariateam.aria.ui.screen.SettingsScreenTest' com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner
```

최초 실기기 실행에서 AndroidViewModelFactory의 Application 단일 인자 생성자 누락으로 종료되는 문제를 발견하여 수정했다. 이후 Activity 실행·일시정지·재개 테스트와 실제 홈 버튼 진입이 통과했다.

## 실제 실행 확인 및 한계

- 최신 debug APK 설치 성공, MainActivity 실행 성공.
- 실제 홈 버튼 → 거리 모드 → 최초 안내 → 확인 및 시작 진입 성공.
- 실시간 카메라 프리뷰, 재보정 버튼, 경고 기준, `ARCore Raw Depth 동작 중` 표시 확인.
- 정지한 책상 장면에서 `바닥 인식 대기 중`, `-- m` 표시 확인. 이 표시만으로 유효한 장애물 깊이 측정이 검증된 것은 아니다.
- 실제 1m/2m/3m 오차, 보행 중 감지율·지연·발열, 움직이는 장애물, 낙차·계단 검증은 수행하지 않았다. 사용자가 기기를 들고 바닥과 전방을 천천히 비추며 확인해야 한다.

## 작업 provenance

Orca Run `run_0c9ff72aac7d`에서 추정기와 Android 연결부를 별도 파일 소유권으로 병렬 구현했다. 추정기 Dispatch `ctx_79fa6319d7cf`, Android 연결부 재시도 Dispatch `ctx_9236cc41db07`의 요청·적용 모델은 모두 Antigravity `gemini-3.8-flash-high`로 런치 영수증에서 확인했다. Codex는 명세, 소스 검토, 독립 검증, 문서 기록 및 Git 통합을 담당했다.

Android 연결부의 최초 Copilot 시도는 월간 무료 요청 소진 메시지가 확인되어 중지했다. Orca 중지 영수증의 `closed_agent_terminal`, `ptyKilled=true`를 확인한 후 같은 Task를 Gemini로 재시도했으며 해당 Copilot 시도에 소스 변경은 없었다.
