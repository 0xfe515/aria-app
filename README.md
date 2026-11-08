# ARiA (Adaptive Realtime Intelligence Assistant)

ARiA는 한국어 오프라인 음성 명령 인식, Google Gemini 기반 지능형 클라우드 질의응답, 그리고 실시간 카메라 비전 진단을 결합한 차세대 AI 글래스 어시스턴트 안드로이드 애플리케이션입니다.

## 핵심 기능 (Key Features)

비전 모델 학습 이력: **Cityscapes 최초 학습 → Surface_1 한국 보도 환경 파인튜닝**. [학습 데이터와 모델 변환 이력](docs/VISION_TRAINING_DATA.md)을 참조하세요.

- **오프라인 한국어 음성 인식 (Sherpa-ONNX)**: 기기 내에서 실행되는 스트리밍 Zipformer 모델 기반 음성 인식 및 호출어 감지.
- **오프라인 로컬 명령 & Gemini 클라우드 자동 전환**: 호출어 뒤 기기 제어(홈/설정 이동, 소리/화면 제어, 지도 길안내)는 오프라인 로컬에서 우선 실행되며, 미해석 일반 대화 및 복합 질의는 Gemini 클라우드로 안전하게 자동 전환.
- **실시간 보행 안전 비전 및 우선순위 위험 음성 경고**: LiteRT 온디바이스 세그멘테이션과 IMU 융합 위험 판단을 기반으로 기존 TTS를 선점하는 즉각적인 음성 경고 지원.
- **반응형 홈 & 플로팅 캡슐 내비게이션**: 화면 스크롤 없이 컴팩트/가로 화면까지 완벽하게 대응하는 원형 전원 버튼 UI와 투명 외곽의 모던 플로팅 탭 바.
- **사용자 중심 토스트 피드백**: 명령 수행 결과와 음성 엔진 상태를 화면을 가리지 않는 일관된 안드로이드 토스트로 즉각 전달.
- **실시간 카메라 및 비전 진단**: CameraX 기반 카메라 프리뷰 및 사용자 정의 비전 모델 인터페이스.
- **정직하고 완벽한 오픈소스 라이선스 고지**: 사용된 모든 런타임/네이티브/모델 자산의 명확한 출처 표기 및 앱 내 오프라인 라이선스 전문 뷰어.

## 현재 비전 경고 규칙

아래는 현재 코드 기본값 기준입니다. 영상의 영역 비율·객체 위치·거리·충돌 예상 시간(TTC)을 이용한 추정이며, **경고 미감지는 안전 보장이 아닙니다.** 거리와 TTC를 구할 수 없는 경우에는 해당 수치 조건을 사용하지 않습니다.

### 경로·객체 위험 판단

기준 구현: [HazardRuleEngine.kt](app/src/main/java/com/ariateam/aria/hazard/HazardRuleEngine.kt). 객체는 확인된 추적 결과만 사용합니다. 객체 폭의 진행 복도 겹침 비율이 **50% 이상**이면 경로 안으로 판단합니다. 보행 상태가 아직 `UNKNOWN`인 경우에도 보행 조건을 적용합니다.

| 구분 | 조건 (기본값) |
| --- | --- |
| W1 · 차량·이륜차 접근 경고 | 경로 안 또는 횡방향 접근 속도가 양수인 차량·이륜차에 대해, 거리 **4m 미만 또는 TTC 3초 미만**. 횡단보도 위에서는 **6m 미만 또는 4초 미만**으로 확대 |
| W2 · 동적 객체 임박 경고 | 경로 안의 움직이는 객체 TTC가 **2초 미만** |
| W3 · 발밑 차도 경고 | 보행 중, 횡단보도 밖에서 가까운 영역의 차도 비율이 **50% 초과** |
| W4 · 정적 장애물 임박 경고 | 보행 중 경로 안의 정적 객체가 **1.2m 미만**. 거리 미확인 시 객체 상자 아래쪽 좌표가 화면 높이의 **92% 이상** |
| W5 · 보행 경로 상실 경고 | 보행 중 가까운 영역의 보행 가능 비율이 **20% 미만** |
| C1 · 경로 내 객체 주의 | 경로 안 객체가 **1.2m 이상, 3.8m 미만**. 거리 미확인 시 상자 아래쪽 좌표 **80% 이상**. 정적 객체는 보행 중에만 적용 |
| C2 · 주변 차량 주의 | 경로 밖 차량·이륜차가 **6m 미만** |
| C3 · 차도 가장자리 주의 | 좌우 측면 영역 중 차도 비율이 **30% 초과**인 쪽을 안내 |
| C4 · 경로 협소 주의 | 보행 중 가까운 영역의 보행 가능 비율 **50% 미만**, 또는 관심 영역의 보행 가능 비율 **65% 미만** |
| C5 · 횡단보도 진입 주의 | 가까운 영역의 횡단보도 비율이 **30% 이상**으로 진입하는 순간. 횡단 가능 여부나 신호등 상태를 뜻하지 않음 |

C1의 상한은 기본 반응거리 `1.2m/s × 1.5초 + 0.5m = 2.3m`에 1.5m를 더한 값입니다. 여러 후보가 있으면 **경고 > 주의**, 위험 점수, 규칙 순서, 추적 ID 순으로 하나를 선택합니다.

[HazardDetector.kt](app/src/main/java/com/ariateam/aria/hazard/HazardDetector.kt)는 별도로 근접 영역 비보행 비율 **35% 초과**, 경로 보행 가능 비율 **40% 미만 / 65% 미만**을 이용한 경로 상태도 계산합니다. 이 화면 상태와 W/C 규칙의 최종 알림 심각도는 별개이며, 상태 표시만으로 항상 음성 경고가 발생하지는 않습니다.

보조 객체 탐지 융합([ObjectDetectionHazardFusion.kt](app/src/main/java/com/ariateam/aria/vision/od/ObjectDetectionHazardFusion.kt))은 점수 **0.65 이상**, 상자 아래쪽 **80% 이상**, 복도 겹침 **60% 이상** 등의 필터와 서로 다른 관측의 연속 확인을 거쳐 주의 정보를 보탭니다. 기존 위험 경고를 낮추거나, 상자 크기만으로 실제 거리·안전한 통행을 단정하지 않습니다.

### 반복 알림 억제와 음성 우선순위

기준 구현: [HazardAlertPolicy.kt](app/src/main/java/com/ariateam/aria/hazard/HazardAlertPolicy.kt), [HazardAlertCoordinator.kt](app/src/main/java/com/ariateam/aria/hazard/HazardAlertCoordinator.kt).

- 같은 심각도·원인·대상 후보가 **경고 2프레임 / 주의 3프레임** 연속 확인돼야 합니다. 프레임 간격이 500ms를 넘으면 연속 확인을 초기화합니다.
- 같은 대상의 반복 알림 간격은 **경고 2.5초 / 주의 6초**입니다. 심각도 상승이나 대상·원인 변경은 연속 확인 후 대기시간보다 먼저 알릴 수 있습니다.
- 촬영 후 **1.5초 초과** 결과, 중복·역순 시각, 기준 시각보다 **500ms 초과 미래**인 결과는 제외합니다. 세션 중지 또는 알림 비활성 시에도 발화·진동을 차단합니다.
- 위험 후보가 없는 프레임이 경고 **4개 / 주의 6개** 이어지면 이전 알림 상태를 해제합니다. 이는 안전 판정이 아닙니다.
- 위험 음성은 일반 질문 답변 TTS보다 우선하며, 진행 중인 질문과 발화를 선점·취소합니다. 실제 소리 출력은 앱 음성 피드백 설정과 TTS 준비 상태에 따릅니다.

### 방향·자세 불확실성과 흔들림

센서·보정·시야각 정보가 없거나 방향 신뢰도가 부족하면 방향 기반 위험 판단을 제한합니다. 비정상 센서값, 센서 지연(200ms 초과), 과도한 기울기·시야 이탈은 정상 보행으로 간주하지 않습니다. 분할 결과의 기타 영역 비율이 **60% 초과**여도 인식 제한 상태가 됩니다.

[MotionRapidTurnPolicy.kt](app/src/main/java/com/ariateam/aria/hazard/MotionRapidTurnPolicy.kt)는 제한 판정에만 100ms 시정수의 평활화를 적용합니다.

| 구분 | 제한 진입 | 안정 복귀 |
| --- | --- | --- |
| 진행 방향 (수직축 회전) | 평활 회전속도 **35°/s 초과가 150ms 지속** | **25°/s 미만이 300ms 지속** |
| 지면 거리 추정 (전체 회전) | 평활 회전속도 **45°/s 초과가 150ms 지속** | **35°/s 미만이 300ms 지속** |

두 경로 모두 원시 회전속도 **120°/s 이상**이면 즉시 제한합니다. 지면 자세가 불확실하면 거리값은 무효화하며 이전 값을 현재 측정값처럼 재사용하지 않습니다.

### 거리 모드의 별도 진동 규칙

거리 모드([DepthDistanceEstimator.kt](app/src/main/java/com/ariateam/aria/distance/DepthDistanceEstimator.kt), [GroundContactDistanceViewModel.kt](app/src/main/java/com/ariateam/aria/distance/GroundContactDistanceViewModel.kt))에서는 유효 거리 **1.5m 이하: 긴급**, **1.5m 초과~3m 이하: 주의**로 분류합니다. [DistanceAlertVibrator.kt](app/src/main/java/com/ariateam/aria/distance/DistanceAlertVibrator.kt)의 반복 진동 간격은 긴급 **1.2초**, 주의 **2.5초**이며 상태 상승 시 조기 알림이 가능합니다. 이 규칙은 위 보행 위험 W/C 규칙과 별도입니다.

이 임계값은 현재 구현의 기본 설정이며 실환경 보행 성능이나 위험 탐지의 완전성을 보장하지 않습니다.

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
