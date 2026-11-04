# LiteRT 2.3.2 Migration & NPU Acceleration Report

**Task ID:** `ARIA-LITERT-232-MIGRATION`
**Date:** 2026-11-05
**Component:** Vision Inference Pipeline (`com.ariateam.aria.vision`)

---

## 1. Executive Summary

Aria's offline vision inference pipeline has been migrated from legacy LiteRT 1.4.2 (`org.tensorflow.lite.Interpreter` with `GpuDelegate`) to the modern, stable LiteRT 2.3.2 bundled runtime architecture utilizing `com.google.ai.edge.litert.CompiledModel` and `TensorBuffer`.

The migration adds optional hardware NPU acceleration integration for supported chipsets via bundled LiteRT vendor plugins and dispatch runtimes (`google-tensor`, `qualcomm`, `mediatek`). To preserve proven production stability (such as on Pixel 3 Snapdragon 845 Adreno 630 GPU), GPU remains the verified production default. A robust, bounded multi-tier fallback cascade (`NPU -> GPU -> CPU`) ensures graceful degradation and truthful reporting without masking backend state.

---

## 2. Dependency & Build Configuration

All LiteRT artifacts are centrally pinned in `gradle/libs.versions.toml`:

```toml
[versions]
litert = "2.3.2"
litertLegacy = "1.4.2"
kotlin = "2.4.20"

[libraries]
litert = { module = "com.google.ai.edge.litert:litert", version.ref = "litert" }
litert-api = { module = "com.google.ai.edge.litert:litert-api", version.ref = "litert" }
litert-metadata = { module = "com.google.ai.edge.litert:litert-metadata", version.ref = "litert" }
litert-gpu = { module = "com.google.ai.edge.litert:litert-gpu", version.ref = "litert" }
litert-gpu-api = { module = "com.google.ai.edge.litert:litert-gpu-api", version.ref = "litertLegacy" }
litert-npu-google-tensor = { module = "com.google.ai.edge.litert:litert-npu-runtime-google-tensor", version.ref = "litert" }
litert-npu-qualcomm = { module = "com.google.ai.edge.litert:litert-npu-runtime-qualcomm", version.ref = "litert" }
litert-npu-mediatek = { module = "com.google.ai.edge.litert:litert-npu-runtime-mediatek", version.ref = "litert" }
# Compatibility for legacy instrumented test references
tensorflow-lite-gpu = { module = "org.tensorflow:tensorflow-lite-gpu", version = "2.16.1" }
```

### NPU Vendor Scope & Packaging Realities
- **Bundled Artifacts:**
  - Google Tensor: `litert-npu-runtime-google-tensor:2.3.2` (`libLiteRtCompilerPlugin_google_tensor.so`, `libLiteRtDispatch_GoogleTensor.so`).
  - Qualcomm Snapdragon: `litert-npu-runtime-qualcomm:2.3.2` (`libLiteRtCompilerPlugin_Qualcomm.so`, `libLiteRtDispatch_Qualcomm.so`).
  - MediaTek Dimensity: `litert-npu-runtime-mediatek:2.3.2` (`libLiteRtCompilerPlugin_MediaTek.so`, `libLiteRtDispatch_MediaTek.so`).
- **Vendor Runtime Boundaries:** The public Maven AARs package compiler plugins and dispatch wrappers, but not full proprietary vendor runtimes (e.g., Qualcomm QNN SDK / `libQnnHtp.so`). Bytecode inspection confirms `BuiltinNpuAcceleratorProvider.isLibraryReady()` unconditionally returns `true` and `getLibraryDir()` points to `applicationInfo.nativeLibraryDir`.
- **Packaging:** Configured `packaging { jniLibs { useLegacyPackaging = true } }` in `app/build.gradle.kts` so native plugins are extracted into `nativeLibraryDir` for discovery.
- **Samsung Exynos Status:** Google Maven metadata query confirms `com.google.ai.edge.litert:litert-npu-runtime-samsung:2.3.2` returns HTTP 404 (not published). No Samsung NPU runtime is included. Exynos follows the GPU-to-CPU fallback policy, but this migration has not been tested on an Exynos device. No unverified proprietary binaries or invented coordinates are introduced.
- **Production Default Policy:** Because LiteRT `Accelerator.NPU` options include CPU fallback and unverified NPU initialization might silently execute on CPU, production keeps GPU as the default preference. NPU is engaged only when explicitly configured or verified.

### Kotlin Toolchain Hygiene
- Upgraded `kotlin` from `2.2.10` to `2.4.20` in `gradle/libs.versions.toml`, cleanly aligning the compiler with `litert-api-2.3.2`'s Kotlin metadata version (`2.4.0`).
- No blanket compiler bypasses (such as `-Xskip-metadata-version-check`) are used.

---

## 3. Architecture & Implementation

### 3.1. Production Inference (`LiteRtVisionSegmenter.kt`) & Pure-Java Metadata Fix
- **CompiledModel & TensorBuffer Execution:** Production inference executes via `com.google.ai.edge.litert.CompiledModel` using direct float array copy into `inputBuffer.writeFloat(...)`. Note: `outputBuffer.readFloat()` returns a newly allocated `FloatArray` per invoke; intermediate input `ByteBuffer` allocations are eliminated.
- **Pure-Java Model Contract Inspection (`inspectMetadataSpec`):**
  - Production `prepareModelSession` previously instantiated `org.tensorflow.lite.Interpreter` for shape/type checks. On real Pixel 3 devices with LiteRT 2.3.2, this triggered `UnsatisfiedLinkError: No implementation found for void org.tensorflow.lite.TensorFlowLite.nativeDoNothing()` because `libtensorflowlite_jni.so` is absent from the modern LiteRT runtime APK.
  - Resolved by integrating official pure-Java `com.google.ai.edge.litert:litert-metadata:2.3.2` (`MetadataExtractor`), backed transitively by `com.google.flatbuffers:flatbuffers-java:25.2.10`.
  - Model tensors and shapes are extracted purely in Java from the model's FlatBuffer header with no native JNI dependencies.
  - Tensor counts are inspected before indexing to guard against out-of-bounds exceptions.
  - Caller buffer position, limit, and endianness are preserved via `.duplicate().order(ByteOrder.LITTLE_ENDIAN)`.
  - Strictly validates portrait tensor dimensions against `VisionContract`:
    - Input: batch 1, height 512, width 288, channels 3 (`[1, 512, 288, 3]`), `FLOAT32`.
    - Output: batch 1, height 512, width 288, classes 7 (`[1, 512, 288, 7]`), `FLOAT32`.
  - Invalid models immediately return `PrepareOutcome.Invalid` and terminate import without triggering hardware fallback.
  - Fatal JVM `Error`s (such as `OutOfMemoryError`) propagate immediately.
- **Resource Cleanup & Leak Prevention:**
  - In `buildRuntimeSession`, `candidateInputBuffers` is assigned immediately after creation so that if output buffer creation fails, input buffers are closed in `catch`.
  - In `InferenceSession.close()`, all native handles (`inputBuffers`, `outputBuffers`, `compiledModel`, `environment`, and legacy delegates) are closed.

### 3.2. Real NPU Provider Wiring & Vendor Readiness Validation
- **Device Vendor Detection:** `NpuEnvironment` detects the active hardware vendor using LiteRT's `NpuCompatibilityChecker` (`Qualcomm`, `Mediatek`, `GoogleTensor`), or test-injected vendor provider (`SupportedNpuVendor`).
- **Vendor-Specific Pair & Runtime Verification (`isVendorPairAndRuntimeReady`):**
  - **Matching Plugin + Dispatch Pair:** Enforces that compiler plugin and dispatch library belong to the exact same vendor (`libLiteRtCompilerPlugin_Qualcomm.so` + `libLiteRtDispatch_Qualcomm.so`). Mismatched pairs (e.g., Qualcomm plugin + MediaTek dispatch) are strictly rejected.
  - **Qualcomm HTP Runtime Requirement:** `libQnnSystem.so` alone cannot establish HTP readiness. Concrete presence of `libQnnHtp.so` (in `nativeDir`, `/vendor/lib64`, or `/vendor/dsp/cdsp`) is required before NPU is engaged.
  - **MediaTek & Google Tensor Status:** Because vendor runtime engines (e.g., Neuron runtime, Edge TPU runtime) are unestablished in standard packaging, they are conservatively disabled and documented rather than falsely reported as supported.
  - **Pure File-Check Helper:** Enables fast, deterministic JVM unit tests without Android mocks.
- **Provider Wiring:** When NPU is confirmed ready, `BuiltinNpuAcceleratorProvider(context, checker)` registers the vendor accelerator with `Environment.create(context, provider)`.
- **Failure Boundaries:** Fatal JVM `Error`s (such as `OutOfMemoryError`) and `ModelValidationException`s propagate immediately and are never caught or converted into backend fallbacks or retry loops.

### 3.3. Bounded Fallback Cascade (`NPU -> GPU -> CPU`)
- **Execution Cascade:** When NPU is requested:
  1. Try `NPU`: If warmup fails or NPU is unsupported/unready, capture `NpuInitializationException`.
  2. Try `GPU`: If GPU warmup fails or GPU is unsupported, capture `GpuInitializationException`.
  3. Try `CPU`: Initialize low-performance fallback with `XNNPACK` and configured CPU threads. If CPU fails, throw `CpuFallbackException` with both GPU and NPU causes attached.
- **Truthful Backend Reporting:**
  - `markNpuFallback` and `markGpuFallback` accurately inspect `backendType`.
  - When degraded to CPU, the label is reported as `LiteRT CPU FP32 [저성능 폴백: NPU 초기화 실패 (...), GPU 초기화 실패 (...), 4스레드]`, never misrepresenting the active backend as GPU.

### 3.4. Session Lifecycle, Idempotency & Temp File Safety
- **Idempotent Close:** `InferenceSession.close()` utilizes an `AtomicBoolean` guard (`compareAndSet(false, true)`). Multiple invocations or concurrent shutdown paths execute cleanup exactly once without double-free errors.
- **Temp File Lifecycle:**
  - Distinguishes between caller-owned `modelFile` and temporary buffer clones (`ownedTempFile`). Original model files are never deleted on session close.
  - Temporary files created during model preparation are deleted immediately if session construction fails in `catch`.
  - `configureBackend` reuses `activeSession?.modelFile` instead of creating redundant temporary file copies.

### 3.5. GPU Precision Policy (`GpuPrecisionPolicy`)
- **Strict Production Precision:** Production inference strictly enforces `CompiledModel.GpuOptions(precision = Precision.FP32)` regardless of caller `precisionLossAllowed=true` flags.
- **Diagnostic Precision Control:** Diagnostic sessions (`DiagnosticInferencePolicy`) permit `Precision.FP16` only when explicitly requested; otherwise `Precision.FP32`.
- **Unified Label Derivation:** Display labels are derived directly from the effective precision mapping:
  - FP32: `LiteRT GPU FP32 (CompiledModel)`
  - FP16: `LiteRT GPU FP16 [진단 전용] (CompiledModel)`
  - Hardcoded internal accelerator library names (`libLiteRtClGlAccelerator`) have been removed from the description, accurately reflecting CompiledModel's GPU backend.

### 3.6. Instrumented Test Compatibility (`LegacyDelegateCompat.kt`)
- LiteRT 2.x removed `Delegate` registration from standard `Interpreter.Options`.
- `LegacyDelegateCompat.kt` provides an extension function that checks for reflection `addDelegate`. If absent, it invokes `Assume.assumeTrue(false)` to skip legacy diagnostic tests explicitly, preventing them from silently running on CPU while labeled GPU.

### 3.7. Proguard / R8 Rules (`app/proguard-rules.pro`)
Keep rules preserve native LiteRT JNI entry points:
```proguard
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
```

---

## 4. Verification & Validation Commands

All required JVM builds, unit tests, lint, and androidTest compilations pass cleanly:

| Command | Scope | Result | Details |
|:---|:---|:---|:---|
| `.\gradlew.bat :app:compileDebugKotlin` | Production Kotlin compilation | **SUCCESS (0 errors)** | Kotlin 2.4.20 without metadata bypass |
| `.\gradlew.bat :app:testDebugUnitTest` | JVM Unit Tests | **SUCCESS (484/484 passed)** | Includes 10 LiteRT acceptance, 13 metadata inspection, and 9 startup policy tests |
| `.\gradlew.bat :app:assembleDebug` | Debug APK build & DEX packaging | **SUCCESS** | Bundles pure-Java litert-metadata 2.3.2 and FlatBuffers; no legacy TFLite JNI in production APK |
| `.\gradlew.bat :app:assembleDebugAndroidTest` | Instrumented Test Compilation & APK | **SUCCESS (0 errors)** | Full androidTest packaging and DEX compilation verified |
| `.\gradlew.bat :app:lintDebug` | Android Lint static analysis | **SUCCESS (0 errors)** | Fragment aligned to 1.8.6; no blanket compiler or lint bypass |
| `git diff --check` | Whitespace & format check | **SUCCESS (0 errors)** | Clean patch, no trailing whitespace or conflicts |

### Real-Device (Pixel 3) Verification Status
- **Initial Device Run (2026-11-05 06:02):** FAILED (2/9 tests failed). `packagedModelWarmup` and `homeSharedSession` failed due to `UnsatisfiedLinkError: nativeDoNothing()` caused by legacy `org.tensorflow.lite.Interpreter` instantiation during model preparation.
- **Coordinator verification (2026-11-05):** All four mandatory Gradle tasks independently passed (exit 0); final JVM XML confirms 484 tests, zero failures/errors/skips. The packaged-model smoke test passed (1/1), then the full `VisionBackgroundSessionTest` passed (9/9, zero failures/errors/skips) on Pixel 3 / Android 12.
- **Data-preservation limitation:** No explicit app-data clear was issued. However, later ActivityManager evidence showed Gradle's connected-test teardown automatically uninstalling the app and test APK after runs. Therefore only the initial update-and-smoke started against the existing installation; the later Gradle runs cannot establish recovery with the original persisted data. This unintended teardown was disclosed, and subsequent validation uses direct `adb install -r -t` / `am instrument` without uninstall or data clearing. Original persisted data cannot be claimed preserved or recovered.
- **Actual Home evidence:** Restore reached `Ready` with `LiteRT GPU FP32 (CompiledModel)` and `isInvocationReady=true`. After inference and camera stop, the settled snapshot recorded 3 inference entries / 3 returns, 203 analyzer entries / 203 proxy closes, unchanged controller identity, and no camera error. Native smoke logs show all 210 graph nodes delegated through `LITERT_CL`; no NPU or cross-device performance claim is made.
- **Natural-startup follow-up:** Manual launch exposed circular startup gating that forced-admission tests had missed. `StartupVisionPreparationPolicy` prepares the model before Home camera activation when no graph is active/pending/recovering, retaining the real camera stability gate for live sessions and preserving explicit Stop. The new unforced startup test passed in 6.626 seconds with actual GPU FP32 Ready, 3 inference returns, and 4 analyzer entries / 4 proxy closes after camera stop. See the final validation report for exact source/artifact evidence and retained earlier test results.
- **Release gate:** Settings, cloud, and stop/voice checks plus final source/artifact publication are recorded in the final validation report. No release decision relies on legacy GPU benchmark assumption skips.

---

## 5. Artifacts and Modified Files

- `gradle/libs.versions.toml`: Centrally pinned `litert-metadata` 2.3.2.
- `app/build.gradle.kts`: Added `litert.metadata` dependency.
- `app/src/main/java/com/ariateam/aria/vision/LiteRtVisionSegmenter.kt`: Replaced legacy `Interpreter` metadata inspection with pure-Java `inspectMetadataSpec` using `MetadataExtractor`, preserved duplicate/little-endian buffer order, safe tensor count validation before indexing, schema type resolution, and error propagation.
- `app/src/main/java/com/ariateam/aria/vision/VisionModelStore.kt`: Hardened rollback paths to preserve un-cleared state when rolling back failed imports without prior metadata.
- `app/src/main/java/com/ariateam/aria/licenses/LicenseRepository.kt`: Added `google-flatbuffers` (v25.2.10, Apache-2.0) and updated `litert` component entry.
- `app/src/test/java/com/ariateam/aria/licenses/LicenseRepositoryTest.kt`: Added inventory test assertion for `google-flatbuffers`.
- `app/src/test/java/com/ariateam/aria/vision/LiteRtMetadataInspectionTest.kt`: 13 focused regression tests for pure-Java metadata parsing (valid 7-class portrait, no custom metadata, wrong shapes/counts/dtypes, malformed buffers, and position/limit preservation).
- `docs/LITERT_232_MIGRATION.md`: Documented root cause, pure-Java fix, FlatBuffers license, test evidence, and pending real-device validation status.
