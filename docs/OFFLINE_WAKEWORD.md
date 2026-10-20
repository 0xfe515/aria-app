# 내장 한국어 오프라인 스트리밍 음성 인식(sherpa-onnx)으로 호출어와 명령 처리

> 2026-10-20 업데이트: Pixel 3 (Android 12, API 31) 실기기 검증 결과 플랫폼 `createOnDeviceSpeechRecognizer()`(`com.google.android.as`)에 한국어 온디바이스 모델이 없어 `ERROR_LANGUAGE_NOT_SUPPORTED(12)`가 발생함을 확인하고, 프로덕션 기본 음성 인식 드라이버를 번들형 로컬 한국어 스트리밍 ASR(`SherpaRecognitionDriver`, `sherpa-onnx-1.13.8` + `sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile` int8)로 전환했습니다.

## 현재 상태

프로덕션 호출어 대기(`Mode.Wake`)와 한국어 명령 받아쓰기(`Mode.Command`, `Mode.Manual`, `Mode.FollowUp`)는 앱에 내장된 로컬 스트리밍 한국어 음성 인식 엔진(`SherpaRecognitionDriver`)으로 100% 오프라인 동작한다:
- **로컬 스트리밍 런타임 및 모델**: `sherpa-onnx-1.13.8.aar`와 한국어 모바일 스트리밍 Zipformer 모델(`sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile`: `encoder-epoch-99-avg-1.int8.onnx` 111.5 MB, `decoder-epoch-99-avg-1.onnx` 11.3 MB, `joiner-epoch-99-avg-1.int8.onnx` 2.6 MB, `tokens.txt`, `bpe.model` — 합계 약 126 MB, 276 MB 비양자화 FP32 인코더 제외)을 사용한다.
- **재현 가능한 빌드 프로비저닝(Git 바이너리 미포함)**: 대용량 `.aar`/`.onnx` 바이너리는 Git에 커밋하지 않고, `:app` Gradle 빌드(`provisionSherpaOfflineAar`, `provision<Variant>SherpaOfflineAssets`)에서 고정 URL 및 SHA-256 해시 검증을 거쳐 빌드 생성 디렉터리로만 주입한다. 캐시 경로는 `-PariaOfflineAssetCacheDir=<경로>` 또는 `ARIA_OFFLINE_ASSET_CACHE_DIR` 환경 변수로 지정할 수 있으며, 미지정 시 검증된 임시 캐시(`$TEMP/aria-sherpa-validation`) 또는 Gradle 사용자 홈 캐시(`~/.gradle/caches/aria-offline-speech`)를 재사용한다. APK 패키징 시 `.onnx`, `.wav`, `.model` 확장자는 `noCompress`로 설정해 런타임 압축 해제 비용 없이 `AssetManager`에서 직접 로드한다.
- **백그라운드 캡처·디코딩 및 모델 재사용**: `AudioRecord`(16 kHz, mono, `ENCODING_PCM_16BIT`) 캡처와 `OnlineRecognizer`/`OnlineStream` JNI 디코딩은 전용 단일 백그라운드 스레드(`aria-sherpa-asr`)에서 수행하며, 모든 `RecognitionDriver.Listener` 이벤트는 세대 번호(attempt ID) 가드와 함께 메인 스레드로 직렬 전달한다. 무음 구간에서 네이티브 침묵 엔드포인트(`rule1`)가 발생하면 세션을 닫지 않고 스트림만 즉시 `reset()`하여 300ms 재시작 공백 없이 연속 청취하며, 실제 한국어 발화가 포함된 엔드포인트(`rule2`: 후행 침묵 1.4초·최소 발화 길이 1.5초 이상 및 최소 발화 구간 충족) 또는 명시적 `finish()` 호출 시에만 최종 결과를 확정한다.
- **즉각적인 마이크 해제와 수명주기 정리**: 중지(`stop()`)나 TTS 재생(`playbackChanged(true)`)으로 `close()`가 호출되면 현재 시도의 세대 번호를 즉시 무효화하고 `AudioRecord`를 곧바로 정지·해제하되, 로드된 `OnlineRecognizer` 모델 인스턴스는 메모리에 유지해 다음 청취 시작 시 재로드 지연을 없앤다. 서비스 종료(`VoiceService.onDestroy()`) 시에는 `session.shutdown()`을 호출해 네이티브 `OnlineRecognizer`와 워커 스레드를 결정론적으로 해제한다.
- **플랫폼 인식기 진단 보존**: 기존 `AndroidRecognitionDriver`는 암묵적 클라우드 폴백 없이 플랫폼 온디바이스/기본 음성 인식 서비스의 기기별 지원 여부를 점검하는 명시적 진단·비교용 드라이버로만 유지한다.

> **Pixel 3 (Android 12, API 31) 실측 백엔드 특성 및 전환 근거**:
> 시스템 오버레이 `config_defaultOnDeviceSpeechRecognitionService`는 `com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService`로 설정되어 있어 `isOnDeviceRecognitionAvailable()`는 참을 반환하지만, Pixel 3의 Android 12 `com.google.android.as` 내 `speech-recognition` 슈퍼팩은 0 kB이며 한국어(`ko-KR`) 온디바이스 모델이 설치되어 있지 않아 실기기 호출 시 즉시 `error=12(Language)`(`ERROR_LANGUAGE_NOT_SUPPORTED`)를 반환한다.
> 반면 기기 기본 인식 서비스 `voice_recognition_service`(`GoogleTTSRecognitionService`)의 `EXTRA_PREFER_OFFLINE`은 제공자 힌트일 뿐 오프라인 실행을 보장하지 않는다.
> 이에 따라 프로덕션 경로를 번들형 `SherpaRecognitionDriver`로 전환하여 Pixel 3에서도 네트워크 없이 한국어 호출어와 명령 인식이 완결되도록 구현했다.

## 호출과 명령

- 문장 시작의 공식 호출어 “헤이 아리아”, “헤이 아리야”를 인정하며, 실기기 한국어 ASR에서 전체 호출어 발화가 고정적으로 전사되는 좁은 음운 변형(“헤뤼이아”, “헤뤼이야”, “해이 아리아”, “해이 아리야”)은 뒤에 공백·문장부호·문장 끝 경계가 있고 조사(“는/가/를/라고” 등)나 다른 단어(“헤뤼이아파트” 등)와 결합하지 않은 경우에만 제한적으로 허용한다. 유니코드 NFKC 정규화를 적용하고 띄어쓰기·문장부호·비가시 서식 문자는 무시한다.
- 호출어 대기(`Mode.Wake`)에서는 최상위 후보(rank 0)가 이미 전체 호출어와 일치하면 그 후보의 명령을 그대로 사용하며, 최상위 후보가 호출어 접두 조각(“헤”, “헤이”, “헤이 아리” 등)일 때만 하위 N-best 후보(최대 5개)에서 전체 호출어를 확인하되 명령어 접미사가 최상위 후보와 정확히 일치해야만 채택한다. 일반 명령·수동 입력·재질문(`Mode.Command`, `Mode.Manual`, `Mode.FollowUp`)에서는 최상위 후보(top-1) 의미를 그대로 유지한다.
- 단음절·접두 조각(“헤”, “헤이”, “헤이 아리”, “헤뤼”, “헤뤼이”), “히어리야”, 이름 단독(“아리아”, “아리야”), 문장 중간의 호출어는 절대 인정하지 않는다. 임의 편집 거리 기반 퍼지 매칭이나 세션 간 조각 이어붙이기는 하지 않는다.
- “헤이 아리아 설정 열어”를 같은 세션에서 인식한다. 부분 결과에서 호출어를 찾으면 한 번 진동하고,
  인식기를 유지한 채 뒤의 명령을 표시한다. 최종 결과에서도 전체 호출어가 확인돼야 실행한다.
- 최종 결과에서 호출어가 사라지거나 “헤”, “헤이 아리” 같은 조각만 남으면 임시 감지를 폐기한다. 부분 결과와 오류 뒤 남은 텍스트는 실행하지 않는다.
- 호출어만 최종 확인되면 다음 세션을 명령 입력으로 연다. 준비 완료부터 8초 동안 발화 시작을 기다린다.
  그 세션에는 호출어가 없어도 된다. 마이크를 다른 엔진으로 넘기지는 않지만 세션 사이 공백은 남는다.
- 부분 결과를 제공하지 않는 서비스도 최종 결과로 동작한다. 이때 감지 진동은 발화 종료 뒤에 올 수 있다.
- 일반 대화는 화면·명령 해석기·Gemma에 보내지 않는다. 앱 자체는 원음·인식 문장을 저장하거나 로그에 남기지 않는다.
- 수동 시작과 재질문 답변은 호출어 없이 받는다. 수동 완료는 최종 결과를 기다리며 한 번만 요청한다.

## 구조와 복구

`VoiceService(:wakeword) → VoiceListeningSession → SherpaRecognitionDriver`로 구성한다(플랫폼 비교 진단용 `AndroidRecognitionDriver`는 별도 유지).
서비스 프로세스의 메인 스레드가 모든 세션 상태 전이와 타이머를 직렬 처리하며, `SherpaRecognitionDriver`의 단일 워커 스레드가 오디오 캡처와 스트리밍 디코딩을 전담한다. 동시에 인식기를 하나만 연다.
제어기는 드라이버와 시계를 주입받는 순수 Kotlin 코드이며 실제 마이크 없이 가짜 이벤트와 스크립트 스트림으로 테스트한다.

| 상황 | 동작 |
| --- | --- |
| 정상 결과에 호출어 없음 / 무응답 / NO_MATCH | 안내 없이 해제 후 300ms 뒤 호출어 대기 재시작 |
| 명령 최종 확인 | 한 번 실행하고 다시 호출어 대기, 결과 TTS가 있으면 그동안 인식 중단 |
| 네트워크·서버·사용 중·오디오·클라이언트 오류 | 1·2·4·8·15초 간격으로 최대 5회 재시도 |
| 재시도 소진 / 권한 / 언어 / 서비스 없음 / 요청 제한 | 오류 유지, 인식기·WakeLock·Gemma 해제, 사용자 재시작 필요 |
| TTS 시작 | 인식과 재시작 예약을 취소하고 Speaking 표시 |
| TTS 완료 | 기존 500ms 잔향 대기 후 300ms 재시작 정책으로 복귀 |
| 전체 중지 | 모든 콜백과 예약 무효화, 인식기·WakeLock·Gemma 해제 |

정상 최종 결과 또는 정상 무응답 종료가 발생해야 연속 오류 횟수를 초기화한다. 준비 콜백만으로
오류 횟수를 초기화하지 않는다. TTS가 재시도 중간에 재생돼도 남아 있는 재시도 대기 시간을 건너뛰지 않는다. 이미 확인한 호출어의 명령 입력 기한은 오류 재시도로 연장하지 않는다.
인식 시작·수동 전환·중지·TTS마다 세션 번호를 확인하므로 늦은 콜백이나 예약이 새 상태를 덮지 않는다.

준비 응답은 8초, 세션 입력은 최대 60초, 발화 종료/수동 완료 후 최종 응답은 5초로 제한한다.
호출어 대기에는 앱의 8초 무발화 제한을 적용하지 않는다. 실제 세션은 기본 서비스가 더 일찍 끝낼 수 있다.

`Waiting`은 실제 호출어 인식 준비 완료, `Reconnecting(7)`은 재시작/재시도,
`Speaking(8)`은 TTS로 인식이 멈춘 상태다. 기존 진단 번호 6은 재사용하지 않는다.
AIDL 콜백과 상태 조회는 같은 Bundle 형식을 사용하고 오류 횟수·재시작 예정 시각도 전달한다.
화면은 남은 재시도 시간을 표시하며 재시작 자체는 서비스가 소유한다.

## 기기 확인

[VOICE_VALIDATION.md](VOICE_VALIDATION.md)의 절차를 Android Studio에서 수행한다.
화면 꺼짐 30분, 일반 대화 오탐, 호출/명령 정확도, 재시작 공백과 서비스 자체 안내음을 함께 확인한다.
시스템 볼륨을 강제로 바꾸거나 다른 엔진으로 자동 대체하지 않는다.

## 오픈소스 및 모델 라이선스 고지 (Third-Party Attribution)

- **sherpa-onnx Runtime (`sherpa-onnx-1.13.8.aar`)**
  - 출처: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8>
  - 라이선스: Apache License 2.0 (내장 `libonnxruntime.so`는 MIT License)
- **Korean Streaming Zipformer ASR Model (`sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile`)**
  - 아카이브 출처: <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile.tar.bz2>
  - 원본 모델: <https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12> (AIHub 한국어 자유대화 음성 코퍼스 KsponSpeech 기반 학습, `cardData.license = apache-2.0` / `license:apache-2.0`)
  - 라이선스: Apache License 2.0
  - 패키징 구성: 모바일 배포 크기를 위해 양자화 모델(`encoder-epoch-99-avg-1.int8.onnx`, `decoder-epoch-99-avg-1.onnx`, `joiner-epoch-99-avg-1.int8.onnx`, `tokens.txt`, `bpe.model`, `notes.md`, `test_wavs/0.wav`, `test_wavs/trans.txt`, 합계 약 126 MB)만 포함하며 276 MB 비양자화 FP32 인코더(`encoder-epoch-99-avg-1.onnx`)는 제외한다.
