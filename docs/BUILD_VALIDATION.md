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
| `gradlew.bat -PariaOfflineAssetCacheDir=<검증 캐시> :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` | 통과, APK 생성, 단위 테스트 61개 통과, Lint 오류 0개·경고 34개 |
| `gradlew.bat -PariaOfflineAssetCacheDir=<검증 캐시> -Pandroid.testInstrumentationRunnerArguments.runRealGemmaProbe=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :app:connectedDebugAndroidTest` | Pixel 3에서 15개 통과, 실패·오류·건너뜀 0개; 실제 Gemma 검사 포함 |

단위 테스트 61개(9개 suite)는 명령 해석, Gemma 제한 시간과 프로세스 복구, 호출어 매칭, Sherpa 오디오/세션 및 생명주기 정책을 검사합니다. Pixel 3의 15개 계측 테스트에는 번들된 한국어 WAV 실제 디코딩과 Gemma 실제 추론을 포함합니다. Gemma opt-in 인자를 빼면 Android 테스트 러너가 해당 검사를 실패로 기록하므로, 전체 계측 검증에는 위 인자를 사용합니다.

Pixel 3에서 Sherpa-ONNX가 번들 WAV를 오프라인 디코딩해 `걔는괜찮은척하려구애쓰는거같았다`를 반환했습니다(콜드 4,287ms, 웜 1,023ms). 이 코퍼스 WAV 검사는 사람의 실제 발화나 마이크 환경에서 호출어 정확도를 입증하지 않습니다.

Pixel 3의 검증된 Gemma 모델 준비는 18,727ms, 콜드 추론은 18,226ms(`Execute(SoundOff)`), 웜 추론은 14,396ms(`Execute(Settings)`)였습니다. 이번 설정에서는 제한 시간 내 완료했지만 기기 부하와 발열에 따라 지연될 수 있습니다.

최종 `app-debug.apk`는 335,284,638 bytes(SHA-256 `e1ed0b3e47118575686632d718d08a19cd30bdde7b0b79981f14e621c2259c77`)입니다. 테스트용 Sherpa 모델은 APK 안의 자산으로 포함되며 약 126 MB입니다. Pixel 3에는 Gemma 모델(2,588,147,712 bytes)과 모델 SHA-256이 일치하는 `gemma.verified` 표식이 남아 있습니다.

## 해결한 문제

- Windows 환경에 Android SDK를 설치하고 로컬 SDK 경로를 설정했습니다. Lint가 발견한 드라이브 구분자 이스케이프 오류를 수정한 뒤, 이전 진단 캐시를 피하도록 Lint를 재실행했습니다.
- `SettingsScreenTest`의 잘못된 `assertDoesNotExist` import를 제거했습니다. 이 함수는 `SemanticsNodeInteraction`의 멤버이므로 별도 import가 필요하지 않습니다.
- 기존 기기 앱과 새 디버그 APK의 서명 충돌은 사용자의 삭제·재설치 승인 후 해소했습니다.
- 설치 실패 때에도 Gradle이 성공으로 종료하는 상황이 있어, 최종 통과 여부는 실제 XML 테스트 보고서의 실행 수와 실패 수로 확인했습니다.
- Pixel 3에서 시스템 한국어 온디바이스 인식 서비스가 `ERROR_LANGUAGE_NOT_SUPPORTED(12)`를 반환해, 프로덕션 호출 경로를 번들된 Sherpa-ONNX 한국어 스트리밍 ASR로 전환했습니다. 모델과 AAR은 고정 URL 및 SHA-256을 검증해 빌드 시 자산으로 준비하며 Git에는 넣지 않습니다.
- Gemma 실제 검증에서 컨텍스트 512 설정은 추론에 실패했고 2048 설정은 통과했습니다. 콜드 추론이 이전 15초 제한을 넘는 실측이 있어 준비·콜드 추론·웜 추론 제한 시간을 각각 60초·30초·20초로 분리했습니다.

## 산출물 및 남은 검증

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 단위 테스트: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint: `app/build/reports/lint-results-debug.html`
- 기기 테스트 XML: `app/build/outputs/androidTest-results/connected/debug/`

Lint 경고 34개는 남아 있습니다. 의존성 업데이트 안내, SharedPreferences의 동기 쓰기, WakeLock 제한 시간, Compose API 권장사항 등이 포함됩니다. 경고를 숨기는 baseline이나 검사 비활성화는 추가하지 않았습니다.

실제 사용자 발화의 호출어 정확도, 장시간 백그라운드 감지, TTS 및 네트워크에서 Gemma를 내려받는 흐름은 이 자동 테스트의 검증 범위에 포함되지 않습니다. WAV 모델 검증과 실제 마이크 호출어 검증은 별도 결과로 취급해야 합니다. 기존 음성 검증 문서의 실기기 기능 점검은 계속 필요합니다.
