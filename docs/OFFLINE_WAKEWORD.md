# 온디바이스 호출어(KWS) 및 Android 음성 인식 구조

Aria는 오프라인 호출어 감지(“헤이 아리아”, “헤이 아리야”)와 음성 명령/받아쓰기를 분리하여 처리합니다. 상시 대기 시에는 전용 온디바이스 KWS(Keyword Spotting) 엔진을 사용해 호출어만 독립적으로 감지하고, 호출어가 확인되면 마이크를 해제한 뒤 기존의 Android 시스템 기본 `SpeechRecognizer`로 핸드오프하여 음성 명령을 받아씁니다.

## 아키텍처 개요

1. **호출어 대기 (On-Device KWS):**
   - 엔진: `sherpa-onnx` `KeywordSpotter` (JNI 기반 온디바이스 추론)
   - 모델: 한국어 스트리밍 Zipformer 모델 (`sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile`, `encoder.int8.onnx`, `decoder.onnx`, `joiner.int8.onnx`, `tokens.txt`)
   - 키워드 정의: `keywords.txt` (SentencePiece BPE 토큰 분할 기반 개방형 어휘 매칭: `헤이아리아`, `헤이아리야`)
   - 오디오 수집: 전용 백그라운드 스레드에서 `AudioRecord` (16kHz 16-bit Mono PCM) 실시간 스트림 처리
   - 핸드오프 및 마이크 해제: 호출어 감지 즉시 `AudioRecord`를 중지(`stop`) 및 해제(`release`)하여 Android 인식 서비스가 마이크 하드웨어를 점유할 수 있도록 준비

2. **명령 수동 받아쓰기 및 후속 답변 (Android SpeechRecognizer):**
   - 호출어 감지 후의 음성 명령, 수동 받아쓰기(`beginManual`), 후속 질문 답변(`beginFollowUp`)은 100% 기존 Android 내장 `SpeechRecognizer`(`ko-KR`, `EXTRA_PREFER_OFFLINE=true`)를 유지합니다.
   - 받아쓰기 경로 및 백엔드는 임의로 변경되지 않으며 시스템 기본 인식 서비스의 동작을 따릅니다.

3. **안전한 폴백 경로 (Graceful Fallback):**
   - 기기에 네이티브 라이브러리(`sherpa-onnx-jni`)가 없거나, 모델 파일이 누락되었거나, KWS 초기화/오디오 녹음 시작에 실패한 경우 앱은 크래시 없이 기존 Android `SpeechRecognizer` 기반 호출어 대기 경로로 안전하게 전환됩니다(`wakeFallback = true`).
   - 폴백 경로에서는 [KoreanWakePhraseMatcher](../app/src/main/java/com/ariateam/aria/speech/KoreanWakePhraseMatcher.kt)를 사용하여 문장 선두의 호출어를 엄격히 판별합니다.

## 세션 제어 및 안전 정책

- **이중 트리거 방지 쿨다운:** 호출어 감지 후 설정된 쿨다운 시간(기본 1,500ms) 동안 추가 호출어 트리거를 무시하여 연쇄 오작동을 방지합니다.
- **TTS 자기 음성 감지 억제:** 음성 안내(TTS) 재생 중(`playbackChanged(true)`)에는 호출어 감지 및 세션 시작을 중단하여 스피커에서 나오는 자기 음성으로 인한 오트리거를 차단합니다.
- **단일 실행 보장 (Exactly-once) 및 Watchdog:** `VoiceListeningSession`이 세션 세대(`generation`)와 시도 티켓(`attempt`)을 관리하여 오래된 콜백(stale callback)을 거부하고, 명령 입력 제한 시간(`NO_SPEECH_MILLIS`, `CAPTURE_MILLIS`) 초과 시 안전하게 대기 상태로 복귀합니다.

## 솔직한 한계점 및 하드웨어 제약

- **메모리 및 모델 크기:** int8 양자화된 한국어 스트리밍 모델 파일들이 APK 및 메모리에 적재(인코더 ~111MB, 디코더 ~11MB, 조이너 ~2.5MB 등 총 약 125MB)되므로 저사양 기기에서 메모리 부담이 존재합니다.
- **CPU 및 배터리 소모:** 전용 하드웨어 DSP 핫워드 칩셋과 달리 애플리케이션 프로세서(CPU)에서 신경망 인퍼런스를 연속 실행하므로, 장시간 상시 대기 시 배터리 소모와 발열이 발생할 수 있습니다.
- **음향 정확도 및 실측 한계:** 실제 소음 환경, 다양한 억양, 마이크 이격 거리 및 기기 하드웨어 특성에 따른 실제 인식률과 오탐률은 제어된 환경의 실기기 측정 전까지 단정할 수 없습니다.
- **OS 백그라운드 정책:** Android의 Doze 모드, 배터리 최적화 정책 및 제조사별 백그라운드 프로세스 제한에 따라 화면 꺼짐 상태에서의 상시 청취 지속 시간이 제한될 수 있습니다.
