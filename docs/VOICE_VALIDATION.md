# 온디바이스 호출어 및 음성 명령 검증

Aria는 전용 온디바이스 KWS(`sherpa-onnx` KeywordSpotter)를 통해 호출어(“헤이 아리아”, “헤이 아리야”)를 상시 감지하고, 명령 인식 및 수동 받아쓰기는 Android 시스템 기본 `SpeechRecognizer`(`ko-KR`, `EXTRA_PREFER_OFFLINE`)로 분리하여 실행합니다. 상세 아키텍처는 [OFFLINE_WAKEWORD.md](OFFLINE_WAKEWORD.md)를 참고하세요.

## 빌드 및 자동 테스트

저장소 루트의 PowerShell에서 실행합니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

### 단위 테스트 항목

- `SherpaWakeWordDetectorTest`: 전용 오디오 스레드에서의 PCM 데이터 처리, 호출어 감지 시 마이크(`AudioRecord`) 즉시 해제, 에러 전파, 수명주기 정리 검증.
- `VoiceListeningSessionWakeWordTest`: 호출어 감지 시 Android 명령 인식기로의 핸드오프, 호출어 쿨다운(이중 트리거 방지), TTS 재생 중 호출어 감지 억제, KWS 누락/오류 시 Android 기본 인식기로의 안전한 폴백(`wakeFallback = true`), 수동/후속 받아쓰기의 Android 인식기 100% 유지 검증.
- `VoiceListeningSessionTest` & `KoreanWakePhraseMatcherTest`: 폴백 경로에서의 엄격한 호출어 매칭, 호출어 뒤 명령 보존, exactly-once 실행, watchdog 타이머 검증.

## 기기 수동 확인 절차

기기 모델, Android 버전, 기본 인식 서비스, 설치된 오프라인 언어 데이터 및 네트워크 상태를 기록합니다.

1. **마이크 권한 및 KWS 상시 대기:**
   - 마이크 권한을 허용하고 음성 대기를 시작합니다.
   - 포그라운드 서비스 알림이 활성화되고 `AudioRecord` 기반 16kHz 모노 스트리밍이 시작되는지 확인합니다.
2. **호출어 인식 및 마이크 핸드오프:**
   - “헤이 아리아” 또는 “헤이 아리야”를 발화합니다.
   - 즉시 진동 피드백이 발생하고, KWS 엔진이 마이크를 해제한 뒤 Android `SpeechRecognizer` 세션으로 전환되는지 확인합니다.
   - 이어서 “설정 열어” 또는 “홈으로 가”를 말하여 명령이 1회 정확히 실행되는지 확인합니다.
3. **이중 호출 방지 쿨다운:**
   - 짧은 간격으로 “헤이 아리아”를 연속 발화하여 첫 번째 호출 이후 쿨다운 시간(1.5초) 내의 중복 트리거가 방지되는지 확인합니다.
4. **TTS 재생 중 억제:**
   - 안내 음성이 스피커로 출력되는 동안 호출어를 발화하여 자기 음성으로 인한 오트리거가 발생하지 않는지 확인합니다.
5. **수동 받아쓰기 및 후속 입력:**
   - 앱 내 마이크 버튼을 눌러 수동 받아쓰기를 시작합니다. KWS 엔진 없이 Android 인식기로 직접 녹음 및 확정되는지 확인합니다.
6. **Graceful Fallback 확인:**
   - KWS 모델 파일이 없거나 네이티브 라이브러리를 로드할 수 없는 환경에서도 앱이 중단되지 않고 Android `SpeechRecognizer` wake path로 자동 폴백되는지 확인합니다.

실제 기기에서의 정확도, 응답 지연 시간, 배터리 소비 및 오탐률은 통제된 음향 환경과 기기 계측을 통해서만 객관적으로 보고해야 합니다.
