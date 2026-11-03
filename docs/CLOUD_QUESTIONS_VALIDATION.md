# Gemini Cloud Questions Integration & Validation

## Overview

Aria implements user-invoked **Google Gemini text questions** and **single-photo questions** with spoken Korean responses, designed for blind and visually impaired users. Cloud AI interactions are strictly separated from local voice commands, navigation intents, and real-time hazard detection.

### Core Architecture & Privacy Principles

1. **Explicit User Initiation Only**:
   - Cloud requests are triggered **only** by explicit, unambiguous user speech commands (e.g., "제미나이 질문 [질문]", "사진 찍어서 앞에 뭐 있어").
   - Local speech without explicit cloud intent (e.g., "치킨 먹고 싶다", "홈으로 가", "소리 꺼") remains completely offline and is **never** uploaded to cloud services.
2. **Single-Frame Capture with Disclosure**:
   - Photo questions capture exactly **one** image frame on demand. Continuous video/photo streaming is prohibited.
   - Before photo data is transmitted, Aria speaks an explicit Korean disclosure: *"사진을 촬영하여 Gemini에 질문을 보냅니다."*
3. **Safe CameraX Proxy Ownership & Frame Lifecycle**:
   - `VisionPipelineController.captureSingleFrame` and `VisionSession.captureSingleFrame` acquire a single frame atomically using CameraX `ImageProxy.toBitmap()`.
   - The analyzer exclusively retains and closes the underlying `ImageProxy` in its `finally` block; no camera proxy is retained across threads or closed prematurely.
   - If a photo question is cancelled or disposed before the frame callback delivers the `Bitmap`, the incoming `Bitmap` is immediately recycled on arrival without dispatching background work or leaking memory.
4. **Android Keystore Backed API Key Storage**:
   - User-supplied Gemini API keys are encrypted with hardware-backed AES-256-GCM via `AndroidKeyStoreAriaKeyStorage`.
   - Keys are stored in app-private storage (`aria_cloud_secure_prefs.xml`) and excluded from auto-backup and device migration in `backup_rules.xml` and `data_extraction_rules.xml`.
   - The UI displays only a masked key (e.g., `AIza...xyz`), provides a one-tap Clear Key action, and never logs secret bytes.
5. **Request-Scoped Active Cancellation & Resource Disposal**:
   - `GeminiCancellationToken` provides active, thread-safe cancellation registration.
   - `DefaultGeminiTransport` attaches an active abort callback (`conn.disconnect()`) to the request's token; cancelling an in-flight request actively disconnects the live HTTP socket, immediately unblocking any waiting thread.
   - Request-scoped cancellation ensures that cancelling Request 1 only disconnects Request 1's socket, completely isolated from Request 2.
   - `CloudQuestionCoordinator` implements `Closeable` / `AutoCloseable` with `dispose()` / `close()`. Calling `dispose()` actively aborts active network connections, cancels the coroutine `Job` and underlying `SupervisorJob` scope, and rejects subsequent requests.
   - `MainScreen` binds `cloudCoordinator` to the composition lifecycle via `DisposableEffect`, ensuring full cleanup on composition disposal or screen exit.
6. **TTS Priority & Hazard Warning Preemption**:
   - `CommandSpeech` enforces a three-tier priority hierarchy:
     `CloudResponse (rank 0) < NormalCommand (rank 1) < HazardWarning (rank 2)`
   - Any local hazard warning or navigation alert immediately preempts (stops) active cloud speech.
   - Obsolete, stale, or cancelled cloud responses are rejected and never spoken.

---

## Official Provider Specification & API Contract

- **Default Model ID**: `gemini-2.5-flash` (multimodal low-latency).
- **Supported Active Models**: `gemini-2.5-flash`, `gemini-3.5-flash`, `gemini-3.8-flash`.
  *Note: Older models such as `gemini-1.5-flash` and `gemini-2.0-flash` have reached end-of-life and are fully retired. Obsolete fallbacks have been removed.*
- **REST Endpoint**: `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
- **Header Authentication**: `x-goog-api-key: {apiKey}` (avoids logging API keys in server URL access logs).
- **System Instruction**: `"시각 장애인을 위한 음성 비서 Aria입니다. 질문에 대해 간결하고 명확한 한국어 1~3문장 구어로 답변해 주세요."`
- **Truthful Error Handling**:
  - Missing Key: *"Gemini API 키가 설정되지 않았어요. 설정에서 API 키를 등록해 주세요."*
  - Auth Failure (HTTP 401/403): *"Gemini API 키가 올바르지 않거나 권한이 없어요. 설정에서 확인해 주세요."*
  - Model Unavailable (HTTP 404): *"요청한 Gemini 모델을 사용할 수 없거나 지원이 종료되었어요."*
  - Rate Limit / Quota (HTTP 429): *"Gemini 사용 한도를 초과했어요. 잠시 후 다시 시도해 주세요."*
  - Timeout (HTTP 408/504): *"Gemini 응답 시간이 초과되었어요. 잠시 후 다시 질문해 주세요."*
  - Network Failure (HTTP 503): *"네트워크에 연결할 수 없어요. 인터넷 연결을 확인해 주세요."*
  - Server Error: *"Gemini 서비스에 일시적인 문제가 발생했어요. 잠시 후 다시 시도해 주세요."*
  - Camera Unavailable: *"카메라가 실행 중이지 않아 사진을 찍을 수 없어요. 카메라를 켠 후 다시 시도해 주세요."*
  - Cancellation: *"질문이 취소되었어요."*

---

## Voice Commands & Intent Routing

| Category | Spoken Korean Utterance | Parsed Intent | Action / Spoken Feedback |
| :--- | :--- | :--- | :--- |
| **Text Question** | "제미나이 질문 오늘 서울 날씨 어때" | `CloudTextQuestion` | Speaks "질문을 확인하고 있어요.", calls Gemini, speaks response |
| **Text Question** | "제미나이에게 프랑스 수도 어디야 물어봐" | `CloudTextQuestion` | Sends prompt to Gemini API |
| **Text Question** | "질문 있어 피자의 유래가 뭐야" | `CloudTextQuestion` | Sends prompt to Gemini API |
| **Photo Question** | "사진 찍어서 앞에 뭐 있어" | `CloudPhotoQuestion` | Speaks disclosure, copies single frame, sends image to Gemini |
| **Photo Question** | "이거 뭐야" / "이게 뭐야" | `CloudPhotoQuestion` | Copies single frame, asks Gemini to describe object |
| **Photo Question** | "글자 읽어줘" / "앞에 있는 글자 읽어줘" | `CloudPhotoQuestion` | Copies single frame, asks Gemini to read visible text |
| **Photo Question** | "카메라로 봐줘" | `CloudPhotoQuestion` | Copies single frame, asks Gemini to describe scene |
| **Ambiguity** | "질문", "물어봐", "제미나이" | `Clarify` | *"어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요."* |
| **Negation** | "질문 하지마", "사진 찍지마", "물어보지마" | `Clarify` | *"원하는 명령을 하나만 다시 말해 주세요."* (Never uploads) |
| **Compound** | "사진 찍고 그리고 홈으로 가" | `Clarify` | *"명령을 하나씩 말해 주세요."* (Never uploads) |
| **Unknown Speech** | "치킨 먹고 싶다", "산책 가자", "배고파" | `Unknown` | Local offline response; **NEVER** uploads data to cloud |

---

## Validation & Verification Summary

### 1. JVM Unit Tests (`:app:testDebugUnitTest`)
- **Status**: PASSED (349 tests passed, 0 failures).
- **Coverage**:
  - `CloudQuestionCoordinatorTest`:
    * Verified graceful error reporting when API key is missing.
    * Verified normal response delivery with configured API key.
    * Verified active cancellation immediately (<1s) aborting blocked network connection via `GeminiCancellationToken`.
    * Verified coordinator `dispose()` actively aborts transport, cancels `Job` and `SupervisorJob` scope, and rejects subsequent requests.
    * Verified request-scoped isolation: launching a new request actively cancels the prior request without disconnecting the new request or emitting stale responses.
    * Verified cancelled/disposed photo queries emit no stale callbacks and reject stale responses.
  - `GeminiClientTest`:
    * Verified JSON request/response contracts, header auth, multimodal inline_data formatting, bounded prompt/response lengths.
    * Verified HTTP 401/403 (Auth), 404 (Model Unavailable), 429 (Rate Limit), 408 (Timeout), 503 (Network) handling.
    * Verified `GeminiCancellationToken` active cancellation.
  - `CloudCommandParserTest`: Verified parsing of all Korean text and photo query variants, standalone clarification, negation rejection, compound interception, and guaranteed non-upload for unknown local speech.
  - `RuleCommandInterpreterTest`: Verified integration of cloud parsing alongside navigation and local commands.
  - `AriaKeyStorageTest`: Verified key storage, retrieval, clearing, and masking rules.
  - `CommandSpeechPriorityTest`: Verified speech priority rank ordering and default snapshot state.

### 2. Android Lint (`:app:lintDebug`)
- **Status**: PASSED (0 errors, 0 warnings).

### 3. Application & Test Assembly
- `:app:assembleDebug`: PASSED (exit code 0).
- `:app:assembleDebugAndroidTest`: PASSED (exit code 0).

### 4. Connected Device Tests on Pixel 3 (`:app:connectedDebugAndroidTest`)
- **Device**: Google Pixel 3 (Android 12, Build SP1A.210812.016.C2).
- **Results**:
  - `CloudQuestionDeviceTest`: 6 tests PASSED:
    * `androidKeyStoreEncryptsAndDecryptsApiKey`: Real `AndroidKeyStore` AES-256-GCM encryption/decryption on hardware, key masking, key clearing.
    * `cloudImageProcessorCompressesBitmapWithinBounds`: Real Android `Bitmap` compression and bounds checking.
    * `geminiClientHandlesDeterministicFakeTransportOnDevice`: Deterministic device client execution.
    * `truthfulReportingWhenNoKeyConfigured`: Truthful missing key reporting.
    * `cancelledPhotoQuestionImmediatelyRecyclesRealBitmap`: Real Android `Bitmap` is immediately recycled when question is cancelled prior to callback arrival.
    * `disposedCoordinatorImmediatelyRecyclesRealBitmap`: Real Android `Bitmap` is immediately recycled when coordinator is disposed prior to callback arrival.
  - `CommandSnapshotBundleTest`: 4 tests PASSED (Including `cloudQuestionEventRoundTrips` IPC Bundle serialization).
  - `CommandCoordinatorNavigationTest`: 1 test PASSED (Preserved navigation pipeline).

### 5. Git Hygiene
- `git diff --check`: PASSED (exit code 0).

---

## Limitations & Truthful Reporting

1. **No Production Test Key Embedded**:
   As required by project guidelines, no hardcoded API key is embedded in the repository, build scripts, or APK. Live end-to-end cloud requests with an active Google Gemini account remain unverified without a user-supplied key and were tested with deterministic fake transport.
2. **Device Hardware Scope**:
   Connected tests were verified on Google Pixel 3 running Android 12. Android 13+ notification permissions, Galaxy/Exynos chipsets, and network carrier metering remain unverified on hardware.
