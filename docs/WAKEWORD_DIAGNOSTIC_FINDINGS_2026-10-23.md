# 웨이크워드 간헐 감지 진단 — 2026-10-23

## 요약

코드에서 재현 가능한 대기 중단 경로를 확인했다. 정상 KWS 감지는 마이크를 해제하고 콜백을 한 번 전달하는 one-shot 동작인데, 최근 웨이크 감지 후 1,500ms 안에 다시 감지된 콜백은 `VoiceListeningSession`이 아무 복구 없이 버린다. 이때 KWS는 이미 마이크를 닫았지만 세션 상태는 `Waiting`, `wakeFallback=false`로 남을 수 있어, 그 이후 호출어를 듣지 못하는 상태가 된다. 이 경로는 빠른 연속 호출에서만 발생하므로 사용자가 말한 모든 불규칙한 미감지를 설명한다고 단정할 수는 없다.

## 우선순위가 높은 코드 근거

### P1 — 쿨다운에 걸린 두 번째 감지가 one-shot KWS를 영구 대기 상태로 만들 수 있음

1. `VoiceListeningSession.startWakeListening()`은 먼저 `wakeDetectorActive=true`와 `Waiting`을 게시한 뒤 탐지기를 시작한다 (`app/src/main/java/com/ariateam/aria/speech/VoiceListeningSession.kt:211-218`).
2. 콜백은 마지막 수락 시각부터 기본 1,500ms 미만이면 `return`한다 (`VoiceListeningSession.kt:219-227`). 이 분기에서는 `stopWakeDetector()`, 재시작 예약, Android 폴백 전환을 하지 않으므로 앞서 설정한 `wakeDetectorActive`와 현재 티켓도 그대로 남는다.
3. 그러나 실제 `SherpaWakeWordDetector`는 키워드를 얻으면 `AudioRecord`를 중지·해제하고 `activeAttempt`를 0으로 바꾼 후 콜백을 전달하고, 해당 실행은 종료된다 (`SherpaWakeWordDetector.kt:289-304`). 따라서 콜백이 쿨다운에 걸리면 앱은 활성 KWS 캡처가 없는데도 `Waiting`을 표시할 수 있다. 예외나 `onError`도 발생하지 않아 오류 폴백은 실행되지 않는다.
4. 도달 경로는 구체적이다. 첫 호출어는 명령 인식으로 전환하고, 명령 결과 후 300ms 뒤 KWS를 재시작한다 (`VoiceListeningSession.kt:388-390`, `RecognitionDriver.kt:47-57`). 첫 감지 후 1,500ms 이내에 다시 호출어를 말하면 재시작된 one-shot KWS가 두 번째 콜백을 보내고, 쿨다운 분기가 위 상태를 만들 수 있다.

기존 `wakeWordCooldownRejectsDoubleTriggers` 테스트는 이 수명주기를 재현하지 못한다. 테스트용 `FakeWakeDetector.triggerWakeHit()`는 리스너를 소비하지 않고 같은 리스너를 반복 호출할 수 있으며(`VoiceListeningSessionWakeWordTest.kt:10-40`), 테스트는 쿨다운 뒤 동일 리스너가 다시 감지하는 것을 기대한다(`:174-199`). 실제 탐지기는 감지 한 번 뒤 캡처를 종료하므로, 이 테스트는 두 번째 one-shot 콜백 뒤의 복구 누락을 숨긴다. 이를 분리해 확인하는 one-shot 진단 프로브는 쿨다운 시점의 감지 후 `Waiting` 단계와 무리스너 상태가 유지되는 것을 재현했다(아래 검증 참조).

**사용자 증상과의 관계:** 이 결함은 “앞선 웨이크가 이미 수락된 뒤 1.5초 안에 한 번 더 말함”이라는 조건부 사례를 설명할 수 있다. 매 시도마다 첫 호출어가 수락되지 않는 경우나 그보다 긴 간격의 미감지 원인이라는 증거는 아니다. 재시작을 포함한 연속 발화 시점과 성공/실패 기록이 없어 사용자 증상과의 일치는 아직 확인되지 않았다.

### P2 — 예상치 못한 캡처 종료는 무음으로 끝날 수 있으나 Android 경로에서는 제한적

탐지기 루프는 `audioSession.read()`가 음수를 반환하면 그냥 빠져나온다 (`SherpaWakeWordDetector.kt:281-306`). 이 경로는 `onError`를 보내지 않고 `activeAttempt`도 초기화하지 않는다. 따라서 `AudioCaptureSource`가 예기치 않은 EOS를 반환하면 위와 비슷하게 세션은 듣고 있다고 믿을 수 있다. 다만 현재 Android 어댑터는 사용자가 중지한 경우의 음수만 `-1`로 반환하고, 중지되지 않은 `AudioRecord.read()` 음수는 `AudioCaptureException`으로 변환한다 (`SherpaWakeWordDetector.kt:134-150`); 그 예외는 탐지기에서 오류 콜백으로 전달된다 (`:307-315`). 그러므로 이 종료 경로는 인터페이스 수준의 복구 결함이지만, 현재 Android 구현에서 사용자 증상을 일으켰다는 근거는 없다. 중지 중 반환되는 `-1`은 의도된 종료이며 오류 폴백으로 바꾸면 안 된다.

## 캡처·디코드·핸드오프 분석

- KWS는 wake 대기 중 `AudioRecord`를 16kHz, 모노 PCM으로 열고, 640개 샘플(40ms)을 blocking read한 뒤 동기적으로 스트림에 넣고 준비된 추론을 반복한다 (`SherpaWakeWordDetector.kt:82-97`, `:134-150`, `:278-305`). 별도 큐나 추론 백프레셔/overrun 계측은 없다. 최소 링 버퍼 계산은 64,000바이트(16kHz × 2바이트 × 2초)이고 실제 크기는 플랫폼 최소 버퍼에 따라 더 커질 수 있다. 추론이 캡처보다 오래 걸리면 이 버퍼가 지연을 흡수할 수 있지만, 버퍼가 소진될 때 오디오 손실 또는 지연이 생기는지는 실측되지 않았다.
- 키워드가 감지되면 캡처를 중지·해제한 뒤 메인 스레드 콜백을 게시한다. 세션은 수락한 콜백에서 탐지기를 무효화하고 명령용 `RecognitionDriver`를 시작한다 (`SherpaWakeWordDetector.kt:292-303`, `VoiceListeningSession.kt:228-236`). 마이크를 해제한 뒤 핸드오프하는 순서는 코드상 올바르다. 다만 웨이크가 난 40ms 청크 뒤의 오디오를 보존하거나 다음 엔진에 전달하는 코드는 없다. 호출어 직후 명령을 매우 빨리 시작할 때 명령 첫부분이 핸드오프 중 잘릴 가능성은 있지만, 이는 웨이크 단어 자체의 미감지 원인과는 다르며 기기 측정도 없다.
- KWS는 상시 대기 전체에 걸쳐 한 번의 세션으로 연속 읽기/추론을 반복한다. 호출어 감지 때만 의도적으로 중단해 Android 음성 인식기로 넘긴다. 오디오 소스/스트림 시작 실패나 실행 중 오류가 오면 세션은 `wakeFallback=true`로 바꾸고 300ms 후 Android 경로에 들어간다 (`VoiceListeningSession.kt:238-252`, `:261-267`). KWS가 없는 경우도 Android 경로를 쓴다. 프로덕션 드라이버는 기본 Android 인식기에 `EXTRA_PREFER_OFFLINE` 힌트를 준다 (`AndroidRecognitionDriver.kt:178-179`, `:241-250`); 이 힌트는 오프라인 동작을 보장하지 않는다. Android wake 대기는 `NoMatch`/`NoSpeech` 후 재시작되는 구간형 경로이며, KWS 쿨다운 조기 반환은 이 폴백으로 전환되지 않는다 (`VoiceListeningSession.kt:393-399`, `:416-425`). `wakeFallback`은 세션 안에서 오류 후 KWS로 자동 복귀하지 않는다.
- 재생(TTS)이 시작되면 세션은 기존 KWS/인식 시도를 무효화하고 `Speaking`으로 바꾸며, 재생 종료 뒤 300ms 지연 후 다시 시작한다 (`VoiceListeningSession.kt:124-133`). 재생 중이나 재시작 창의 말은 의도적으로 받지 않는다. 이 시점과 사용자 발화가 겹치면 간헐적 미감지처럼 보일 수 있지만, 이벤트 타임스탬프가 없어 이번 증상과의 연관성은 미확인이다.
- 세대/티켓 검사는 중지·모드 변경 뒤 도착한 오래된 콜백을 거부한다 (`VoiceListeningSession.kt:219-222`, `:255-260`). 이는 의도된 stale-callback 방어다. 반면 쿨다운 조기 반환은 stale callback이 아니라 현재 티켓의 유효한 one-shot 감지이므로 단순히 그 가드에 포함되지 않는다.

## 기기 관측과 원인 범위

코디네이터가 제공한 Pixel 3의 약 05:13 KST 스냅샷에서는 Android `RecordActivityMonitor`의 `VOICE_RECOGNITION` 입력이 활성(`active=true`, `silenced=false`), 16kHz 모노 PCM이었고, KWS 프로세스의 PSS는 190,451KB(스왑 PSS 48,488KB), 순간 CPU는 약 78.5%(약 한 코어)였다. 오디오 캡처가 약 04:33부터 유지되어 이 스냅샷 시점의 전역 마이크 권한 거부나 지속적인 캡처 중단 가능성은 낮아 보인다. 이는 한 시점의 기기 상태일 뿐 과거 미감지 시점의 상태나 감지 성공률을 입증하지 않는다.

현재 상태/로그는 세션 단계, 오류, 재시도, `wakeFallback` 정도만 제공한다 (`VoiceService.kt:163-165`, `VoiceState.kt:24-40`). `VoiceDiagnosticsScreen`은 단계와 오류 등을 보여주지만 KWS 캡처 활성 여부, 폴백 사용 여부, 캡처 오류/종료 이유, 디코드 시간, 쿨다운에 버린 이벤트, 오디오 레벨/오버런을 보여주지 않는다 (`VoiceDiagnosticsScreen.kt:85-130`). 특히 P1 경로는 상태가 `Waiting`이고 오류도 없어 현재 관측만으로 실제로 듣는 중인지 구분하기 어렵다. 음성 파형·인식 confidence·성공/실패 타임라인 데이터셋도 제공되지 않았다.

`SherpaWakeWordDetector`는 번들 sherpa-onnx 키워드 스포터를 사용한다. 현재 설정은 키워드 점수 1.5, threshold 0.25, 2개 스레드이며(`SherpaWakeWordDetector.kt:343-345`), 음향 환경, 거리, 발화 세기/속도/억양, 주변 소음, 마이크 AGC/장치 특성에 따른 false negative/false positive 실측은 없다. 코디네이터는 번들 키워드 파일의 네 줄에서 token ID가 존재함을 확인했지만, 이것만으로 최적 BPE 분할이나 음향 인식 성공률을 입증하지 않는다. 이 모델/임계값의 정확도나 지연을 추정하거나 단정하지 않는다. Gemma 상태나 외부 Gemma 다운로드는 호출어 경로와 별개이며 KWS 결과를 설명하지 않는다.

## 권고 순서

1. **P1 복구:** 쿨다운에 걸린 유효한 one-shot 콜백을 무시할 때 탐지기가 이미 멈췄다는 사실을 반영한다. 남은 쿨다운만큼 기다렸다 KWS를 재시작하거나 명시적인 대체 경로로 전환하고, 세션 상태가 실제 듣기 상태를 나타내도록 한다. 즉시 무조건 재시작하면 반복 감지/쿨다운 루프가 생길 수 있으므로 남은 시간과 티켓을 함께 처리한다.
2. **P2 캡처 종료 처리:** 캡처 종료 원인을 정상 stop, EOS, 오류로 구분한다. 예기치 않은 EOS는 리소스 정리 후 `onError` 또는 세션 복구로 보고하고, 현재 Android 어댑터의 의도된 중지 반환은 무음 종료로 유지한다. 제로 샘플의 연속 반환에 대한 과도한 루프도 계측/완화한다.
3. **관측성:** 개인정보를 보존하며 `kwsCaptureActive`, 선택된 wake 경로(KWS/Android), 탐지기 시작·정지·오류·EOS 수, 쿨다운 거부 수/시각, read 및 decode 지연/오디오 오류를 로그나 진단 화면에 추가한다. 이 값이 있어야 `Waiting`이 실제 마이크 수집 중인지 확인할 수 있다.
4. **음향 검증:** 쿨다운/상태 복구를 먼저 고친 후 동일 기기에서 발화 시점, 거리, 소음, TTS 겹침을 통제한 반복 테스트를 기록한다. 승인된 테스트 음성과 조건별 성공/실패 수가 모이기 전에는 인식률·임계값 조정 효과를 주장하지 않는다.

## 검증 범위

프로덕션 코드와 기존 테스트는 수정하지 않았다. one-shot 수명주기 진단 프로브는 `.\\gradlew.bat :app:testDebugUnitTest --tests com.ariateam.aria.speech.WakeWordDiagnosticProbeTest`로 코디네이터가 실행해 `BUILD SUCCESSFUL`(22초)을 보고했다. 테스트는 한 번의 감지 후 리스너를 소비하는 fake로 첫 호출어와 명령 완료를 진행하고, 기본 쿨다운 안에 재시작된 KWS의 감지를 주입한 뒤 `Waiting`, `wakeFallback=false`, 감지 리스너 없음, 쿨다운 종료 후에도 재시작 없음(시작 횟수 2회)을 확인했다. 테스트가 검증한 것은 세션/탐지기 수명주기 결함이며, AudioRecord·실제 음향 인식률·지연은 측정하지 않았다. 임시 프로브 파일은 결과 확인 후 제거했다.

## 후속 수정 — one-shot 쿨다운 복구

후속 구현에서 위의 쿨다운 중단 경로를 수정했다. **이전:** 유효한 두 번째 one-shot 감지가 쿨다운에 걸리면 콜백만 버리고 `Waiting`을 유지해 감지기가 없는 상태가 됐다. **현재 변경:** 그 one-shot 감지를 무효화하고 쿨다운 잔여 시간만큼 `Reconnecting` 상태에서 기다린 뒤 탐지기를 다시 시작한다. 따라서 마이크를 즉시 재획득하거나 Android 명령 인식과 겹치지 않으며, TTS/정지 등 세션 무효화는 예약 재시작을 취소한다.

영구 회귀 테스트는 fake 탐지기의 콜백을 실제 one-shot처럼 소비하고, 잔여 쿨다운 뒤 재시작 및 경계 시각에서 다음 웨이크 수락, 이미 수락된 콜백의 중복 무시, 정지 중 재시작 취소, TTS 중 재시작 억제를 다룬다 (`VoiceListeningSessionWakeWordTest.kt`). 이 변경의 빌드·단위 테스트·Lint는 코디네이터가 실행한다. 이 수정은 수명주기 복구만 다루며 음향 조건에 따른 누락 여부를 입증하거나 보정하지 않는다.
