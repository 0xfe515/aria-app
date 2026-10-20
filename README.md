# Aria

Kotlin, Jetpack Compose, Material 3 기반 Android 음성 비서 앱입니다. 음성 호출과 명령 인식, 음성 안내, Gemma 기반 명령 해석 기능을 포함합니다.

## 프로젝트 열기

1. Android Studio에서 저장소 루트(`aria-app`)를 엽니다.
2. Android SDK 37과 프로젝트에 지정된 Gradle JDK 25를 준비합니다. 최소 지원 버전은 Android 12(API 31)입니다.
3. SDK 위치는 Android Studio 또는 로컬 `local.properties`의 `sdk.dir`에 설정합니다. 이 파일은 Git에서 제외됩니다.
4. Gradle Sync 후 `app` 실행 구성을 선택하고 기기에서 마이크 권한을 허용합니다.

음성 인식과 TTS를 지원하는 기기에서 확인하세요. Gemma 모델 준비와 기기 검증은 아래 문서를 참고하세요.

## 구성 및 문서

- `app/src/main/`: 앱 코드, AIDL, 리소스 및 매니페스트
- `app/src/test/`, `app/src/androidTest/`: 단위 테스트 및 기기 테스트
- `gradle/`: 버전 카탈로그, Gradle Wrapper 및 JVM 설정
- [음성 명령 및 Gemma 설정](docs/VOICE_COMMANDS.md)
- [음성 기능 검증](docs/VOICE_VALIDATION.md)
- [호출어 처리](docs/OFFLINE_WAKEWORD.md)
- [UI 통합](docs/UI_INTEGRATION.md)
- [개발 지침](AGENTS.md)

## 저장소 통합

2026-10-20에 기존 `../aria`의 소스, 테스트, 리소스, 문서 및 Gradle 설정을 통합했습니다. 기존 Git 이력과 `origin` 연결은 유지했습니다. 원본 폴더는 보존했으며, 이후 개발은 이 저장소에서 진행합니다.

빌드 출력, Gradle/Kotlin 캐시, 개인 IDE 설정, SDK 경로 및 서명 키는 버전 관리에서 제외합니다.

## 빌드 및 테스트

이 저장소는 격리된 개발환경이며, 빌드·테스트·Lint는 에이전트의 기본 작업에 포함됩니다. Android Studio에 포함된 JDK와 로컬 Android SDK를 사용합니다. Windows PowerShell에서는 다음과 같이 실행합니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

기기 테스트에는 USB 디버깅을 허용한 Android 기기 또는 에뮬레이터가 필요합니다. 실제 음성 인식·TTS·Gemma 성능은 별도 기능 검증이 필요합니다.

2026-10-20 검증: 디버그 APK 빌드, 단위 테스트 35개, Pixel 3 기기 테스트 10개, Lint 통과(오류 0개·경고 34개). [검증 환경과 상세 결과](docs/BUILD_VALIDATION.md)를 참고하세요.
