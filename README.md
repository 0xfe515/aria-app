# ARiA (Adaptive Realtime Intelligence Assistant)

ARiA는 한국어 오프라인 음성 명령 인식, 온디바이스 언어 모델 해석, 그리고 실시간 카메라 비전 진단을 결합한 차세대 AI 글래스 어시스턴트 안드로이드 애플리케이션입니다.

## 핵심 기능 (Key Features)
- **오프라인 한국어 음성 인식 (Sherpa-ONNX)**: 기기 내에서 실행되는 스트리밍 Zipformer 모델 기반 음성 인식 및 호출어 감지.
- **온디바이스 언어 모델 (LiteRT-LM / Gemma)**: 로컬 기기에서 안전하고 신속하게 실행되는 온디바이스 지능형 명령 해석.
- **반응형 홈 & 플로팅 캡슐 내비게이션**: 화면 스크롤 없이 컴팩트/가로 화면까지 완벽하게 대응하는 원형 전원 버튼 UI와 투명 외곽의 모던 플로팅 탭 바.
- **사용자 중심 토스트 피드백**: 명령 수행 결과와 음성 엔진 상태를 화면을 가리지 않는 일관된 안드로이드 토스트로 즉각 전달.
- **실시간 카메라 및 비전 진단**: CameraX 기반 카메라 프리뷰 및 사용자 정의 비전 모델 인터페이스.
- **정직하고 완벽한 오픈소스 라이선스 고지**: 사용된 모든 런타임/네이티브/모델 자산의 명확한 출처 표기 및 앱 내 오프라인 라이선스 전문 뷰어.

## 빌드 및 실행 요구 사항 (Requirements)
- **Android Studio Ladybug / Meerkat** 권장
- **JDK 17 / 21** (Android Studio 내장 JBR 권장)
- **Android SDK API 31+** (Android 12 이상 지원, 타깃 API 37)
- **Gradle 9.4.1** (래퍼 번들)

### 주요 Gradle 명령어
```bash
# 디버그 APK 빌드
./gradlew :app:assembleDebug

# JVM 로컬 단위 테스트 실행
./gradlew :app:testDebugUnitTest

# Android Lint 정적 분석 실행
./gradlew :app:lintDebug

# 연결된 실기기/에뮬레이터 계측 테스트 실행
./gradlew :app:connectedDebugAndroidTest
```

---

## 오픈소스 소프트웨어 및 라이선스 고지 (Open-Source Licenses)

ARiA에 사용된 외부 오픈소스 소프트웨어, 런타임 의존성, 네이티브 바이너리 및 모델 목록입니다. 상세한 라이선스 전문 및 고지 사항은 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 및 앱 내 설정의 **오픈소스 라이선스** 메뉴에서 확인하실 수 있습니다.

### 1. 런타임 및 네이티브 라이브러리 (Runtime & Native Libraries)
| 컴포넌트 | 버전 | 프로젝트 링크 | 라이선스 | 비고 |
| :--- | :--- | :--- | :--- | :--- |
| **Sherpa-ONNX** | `1.13.8` | [github.com/k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | 오프라인 음성 인식 및 호출어 JNI 바이너리 |
| **kaldifst** | `bundled` | [github.com/k2-fsa/kaldifst](https://github.com/k2-fsa/kaldifst) | Apache-2.0 | Sherpa-ONNX 내 포함된 OpenFST 구현체 |
| **Google SentencePiece** | `bundled` | [github.com/google/sentencepiece](https://github.com/google/sentencepiece) | Apache-2.0 | Sherpa-ONNX 내 포함된 텍스트 토크나이저 |
| **Microsoft ONNX Runtime** | `1.20.1` | [github.com/microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT | Sherpa AAR 내 포함된 `libonnxruntime.so` |
| **Google LiteRT-LM** | `0.16.1` | [github.com/google-ai-edge/litert-lm](https://github.com/google-ai-edge/litert-lm) | Apache-2.0 | 온디바이스 언어 모델 런타임 (AAR 내 공식 THIRD_PARTY_NOTICE 번들) |
| **Google LiteRT** | `1.4.2` | [ai.google.dev/edge/litert](https://ai.google.dev/edge/litert) | Apache-2.0 | 온디바이스 ML 런타임 (구 TensorFlow Lite, litert-gpu 및 litert-gpu-api 포함) |
| **AndroidX Jetpack Compose** | `BOM 2026.02.01 / UI 1.10.4` | [developer.android.com/jetpack/compose](https://developer.android.com/jetpack/compose) | Apache-2.0 | UI 컴포저블, 그래픽, 테마, 런타임 |
| **AndroidX CameraX** | `1.6.2` | [developer.android.com/.../camera](https://developer.android.com/jetpack/androidx/releases/camera) | Apache-2.0 | 카메라 프리뷰 및 이미지 분석 파이프라인 |
| **AndroidX Lifecycle & Core** | `Lifecycle 2.10.0 / Core 1.19.1` | [developer.android.com/jetpack/androidx](https://developer.android.com/jetpack/androidx) | Apache-2.0 | 생명주기 관리, ViewModel Compose, Core KTX, Activity, NavigationEvent |
| **Kotlin Standard Library & Coroutines** | `2.2.21 / 1.9.0` | [kotlinlang.org](https://kotlinlang.org) | Apache-2.0 | Kotlin 표준 라이브러리, 코루틴 비동기 런타임 및 리플렉션 |
| **Kotlinx Serialization Core** | `1.7.3` | [github.com/Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 | 데이터 직렬화 라이브러리 |
| **Kotlinx AtomicFU** | `0.28.0` | [github.com/Kotlin/kotlinx-atomicfu](https://github.com/Kotlin/kotlinx-atomicfu) | Apache-2.0 | 원자적 상태 연산 라이브러리 |
| **Google Dagger** | `2.59` | [github.com/google/dagger](https://github.com/google/dagger) | Apache-2.0 | 의존성 주입 프레임워크 |
| **Jakarta Dependency Injection API** | `2.0.1` | [github.com/eclipse-ee4j/injection-api](https://github.com/eclipse-ee4j/injection-api) | Apache-2.0 | 이클립스 표준 의존성 주입 인터페이스 (공식 NOTICE 포함) |
| **JSR-330 javax.inject** | `1` | [github.com/javax-inject/javax-inject](https://github.com/javax-inject/javax-inject) | Apache-2.0 | 표준 의존성 주입 어노테이션 |
| **Google Guava** | `33.3.1-android` | [github.com/google/guava](https://github.com/google/guava) | Apache-2.0 | 핵심 자바 유틸리티 라이브러리 |
| **Google Guava FailureAccess** | `1.0.2` | [github.com/google/guava](https://github.com/google/guava) | Apache-2.0 | ListenableFuture FutureCallback 도구 |
| **Google Gson** | `2.13.2` | [github.com/google/gson](https://github.com/google/gson) | Apache-2.0 | JSON 직렬화/역직렬화 (LiteRT-LM 전이 의존성) |
| **FindBugs JSR-305 Annotations** | `3.0.2` | [code.google.com/archive/p/jsr-305](https://code.google.com/archive/p/jsr-305/) | Apache-2.0 | 정적 분석용 코드 어노테이션 (공식 Maven POM 기준) |
| **Checker Framework Qualifiers** | `3.43.0` | [checkerframework.org](https://checkerframework.org/) | MIT | 타입 시스템 정적 검증 한정자 어노테이션 (JAR 라이선스 전문 적용) |
| **JSpecify Standard Annotations** | `1.0.0` | [jspecify.dev](https://jspecify.dev/) | Apache-2.0 | 자바 정적 널 가능성 표준 어노테이션 |
| **JetBrains Java Annotations** | `23.0.0` | [github.com/JetBrains/java-annotations](https://github.com/JetBrains/java-annotations) | Apache-2.0 | JetBrains 정적 분석 어노테이션 |
| **Google ErrorProne Annotations** | `2.41.0` | [github.com/google/error-prone](https://github.com/google/error-prone) | Apache-2.0 | 버그 패턴 컴파일러 검사 어노테이션 |
| **Google AutoValue Annotations** | `1.6.3` | [github.com/google/auto](https://github.com/google/auto) | Apache-2.0 | 소스 코드 생성기 어노테이션 |
| **Google J2ObjC Annotations** | `3.0.0` | [github.com/google/j2objc](https://github.com/google/j2objc) | Apache-2.0 | 자바-Objective-C 변환 메타데이터 어노테이션 |

### 2. 머신러닝 모델 및 에셋 (Models & Assets)
| 컴포넌트 | 식별자 / 파일 | 출처 및 모델 카드 | 라이선스 | 비고 |
| :--- | :--- | :--- | :--- | :--- |
| **Sherpa Zipformer Korean** | `2024-06-16-mobile` | [johnBamma/icefall-asr-ksponspeech](https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12) | Apache-2.0 | KsponSpeech 학습 한국어 스트리밍 ASR 모델 |
| **Gemma 4 E2B-IT** | `gemma-4-E2B-it-litert-lm` | [huggingface.co/litert-community/...](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | Apache-2.0 | 선택적 다운로드 온디바이스 언어 모델 (공식 모델 카드 기준) |
| **ARiA Team Vision Model** | `team-vision-v1` | [github.com/tulg580/vision_model](https://github.com/tulg580/vision_model) | **NOT PROVIDED** | ARiA 팀 소스 비전 모델 (참조: docs/VISION_MODEL.md, 외부 라이선스 임의 주장 없음) |
| **Custom Vision Model** | `User-imported .tflite` | 로컬 파일 탐색기를 통한 사용자 임포트 | **NOT PROVIDED** | 사용자 정의 모델 (앱에서 별도 오픈소스 라이선스 주장하지 않음) |
| **Audio Feedback Cues** | `voice_start_clear.wav` 등 | `app/src/main/res/raw/` 번들 자산 | **NOT PROVIDED** | 프로젝트 내부 효과음 (외부 저작권 주장 없음) |

### 3. 빌드 및 테스트 도구 (Build & Test Dependencies)
| 컴포넌트 | 버전 | 프로젝트 링크 | 라이선스 |
| :--- | :--- | :--- | :--- |
| **Android Gradle Plugin** | `9.4.1` | [developer.android.com/build](https://developer.android.com/build) | Apache-2.0 |
| **JUnit 4** | `4.13.2` | [junit.org/junit4](https://junit.org/junit4/) | EPL-1.0 |
| **AndroidX Espresso Core** | `3.7.0` | [developer.android.com/.../espresso](https://developer.android.com/training/testing/espresso) | Apache-2.0 |
| **AndroidX Test JUnit** | `1.3.0` | [developer.android.com/.../testing](https://developer.android.com/training/testing) | Apache-2.0 |
