# 빌드 및 테스트 검증

검증일: 2026-10-20

## 개발 지침

빌드·테스트·Lint 실행 금지 지침을 철회했습니다. 이 저장소는 격리된 개발환경이며, 에이전트는 변경 범위에 맞는 빌드와 테스트를 작업에 포함합니다. 현재 기준은 루트 `AGENTS.md`의 Agent Workflow입니다.

## 환경

- Windows, Android Studio 내장 JetBrains Runtime 25.0.3
- 프로젝트 Gradle Wrapper 9.6.0, Android Gradle Plugin 9.4.1
- Android SDK Platform 37.0 revision 2, Build-Tools 36.0.0
- 기기: Pixel 3, Android 12(API 31), USB 디버깅 연결
- SDK 경로는 Git에서 제외된 `local.properties`에 설정

## 결과

| 명령 | 결과 |
| --- | --- |
| `gradlew.bat :app:assembleDebug` | 통과, 디버그 APK 생성 |
| `gradlew.bat :app:testDebugUnitTest` | 35개 통과, 실패·오류·건너뜀 0개 |
| `gradlew.bat :app:lintDebug --rerun-tasks` | 통과, 오류 0개·경고 34개 |
| `gradlew.bat :app:connectedDebugAndroidTest` | Pixel 3에서 10개 통과, 실패·오류·건너뜀 0개 |

단위 테스트는 명령 해석, 호출어 매칭, 음성 세션 및 생명주기 정책을 검사합니다. 기기 테스트는 앱 패키지, 상태 Bundle 왕복, 설정 화면 및 음성 화면의 UI 동작을 검사합니다.

## 해결한 문제

- Windows 환경에 Android SDK를 설치하고 로컬 SDK 경로를 설정했습니다. Lint가 발견한 드라이브 구분자 이스케이프 오류를 수정한 뒤, 이전 진단 캐시를 피하도록 Lint를 재실행했습니다.
- `SettingsScreenTest`의 잘못된 `assertDoesNotExist` import를 제거했습니다. 이 함수는 `SemanticsNodeInteraction`의 멤버이므로 별도 import가 필요하지 않습니다.
- 기존 기기 앱과 새 디버그 APK의 서명 충돌은 사용자의 삭제·재설치 승인 후 해소했습니다.
- 설치 실패 때에도 Gradle이 성공으로 종료하는 상황이 있어, 최종 통과 여부는 실제 XML 테스트 보고서의 실행 수와 실패 수로 확인했습니다.

## 산출물 및 남은 검증

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 단위 테스트: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint: `app/build/reports/lint-results-debug.html`
- 기기 테스트 XML: `app/build/outputs/androidTest-results/connected/debug/`

Lint 경고 34개는 남아 있습니다. 의존성 업데이트 안내, SharedPreferences의 동기 쓰기, WakeLock 제한 시간, Compose API 권장사항 등이 포함됩니다. 경고를 숨기는 baseline이나 검사 비활성화는 추가하지 않았습니다.

실제 마이크 입력, 장시간 백그라운드 호출어 감지, TTS 및 Gemma 모델 다운로드·추론 성능은 이 자동 테스트의 검증 범위에 포함되지 않습니다. 기존 음성 검증 문서의 실기기 기능 점검은 계속 필요합니다.
