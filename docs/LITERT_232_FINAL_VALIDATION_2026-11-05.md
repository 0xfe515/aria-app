# LiteRT 2.3.2 final validation — 2026-11-05

Release target: `v1.0.0-preview.20261104.3`, versionCode 5. This is a debug-signed prerelease, following the existing preview distribution pattern. The release tag identifies its source commit.

## Fix and review

The first Pixel 3 run failed 2 of 9 vision tests because model preparation instantiated legacy `org.tensorflow.lite.Interpreter`, whose `TensorFlowLite.nativeDoNothing()` JNI entry point is absent from the bundled 2.3.2 runtime. The authorized Gemini worker replaced production inspection with official `litert-metadata:2.3.2` / `MetadataExtractor`. Production inference uses `CompiledModel` and reusable `TensorBuffer`s; there is no production Interpreter construction or redundant CPU compilation for metadata.

The FLOAT32 portrait contract remains input `[1,512,288,3]`, output `[1,512,288,7]`. Thirteen new JVM tests cover official FlatBuffer fixtures without custom metadata, tensor counts/types/shapes, malformed buffers, actual packaged models, and caller buffer state. FlatBuffers Java 25.2.10 is included in the license inventory. Existing camera recovery, original-model ownership, session cleanup, production GPU FP32, and conservative NPU guards remain.

Final manual launch exposed a second blocker that the older device tests missed by forcing `StartupViewModel.admit`: startup waited for model Ready before starting Home's camera, while model preparation unconditionally waited for camera stability. `StartupVisionPreparationPolicy` now prepares and warms up the model first when no camera graph is active/pending/recovering. An active camera still uses the stability gate. Live binding/retry state, rather than historical failure counters, determines recovery; closed sessions cancel. A persisted explicit Stop with no camera graph permits model preparation while preserving the Stop latch. No Home layout changes or premature Ready publication were introduced.

The coordinator reviewed the actual changes and official metadata usage, independently executed validation, and accepted Task `task_7f60a9f5a6ec` / Dispatch `ctx_05580985c650`. Its exact worker completion was received; the worker was released with transcript capture before delivery acknowledgment. Scoped reclaimable workers: zero. Production code/tests/build changes were worker-authored; coordinator changes are documentation and Git/artifact integration.

Startup follow-up Task `task_026c41317aea` / Dispatch `ctx_d0df8dce7966` was implemented by an exclusively assigned Orca Gemini worker on the same Run. The coordinator required corrections preserving Stop, using live recovery state, and stopping the camera before testing settled proxy closure. `VisionNaturalStartupDeviceTest` observes natural admission and actual inference without forcing admission or model state.

After independent host and final device validation, this worker was also accepted and released with transcript capture before acknowledgment. No reclaimable worker remains. A final normal launcher-equivalent `am start` reached the established Home UI showing `RUNNING` and `VOICE RECOGNITION ON`; no recovery or degraded-mode button was used. Installed versionCode/versionName are 5 / `1.0.0-preview.20261104.3`, and the installed base APK SHA-256 matches the distributed artifact below.

## Host validation

Environment: Android Studio JBR via process-local `JAVA_HOME`; repository Gradle wrapper and installed Android SDK.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
git diff --check
```

- Metadata worker combined run: exit 0, `build/validation_litert_metadata_fix.log`.
- Final startup worker run: exit 0, `build/validation_startup_gate_fix.log`; the worker also reran the full JVM suite.
- Final independent coordinator run: exit 0, `%TEMP%/aria-litert232-startup-independent.log` (7 seconds; most tasks up-to-date with unchanged inputs). Earlier metadata-only independent run also passed.
- Final parsed JVM XML: **484 tests, 0 failures, 0 errors, 0 skipped**, including 10 CompiledModel acceptance tests, 13 metadata inspection tests, and 9 startup preparation policy tests.
- Whitespace check: exit 0.
- APK contains nine arm64 LiteRT runtime/GPU/vendor plugin libraries. No proprietary QNN HTP SDK runtime is packaged.

## Pixel 3 / Android 12 validation

Each Gradle invocation selected one class or method; result XML and per-test logs were copied before the next run overwrote outputs. All successful suites below had no ignored/skipped tests.

| Test selection | Result | Evidence under `%TEMP%` |
| --- | --- | --- |
| Packaged model warmup / first inference method | 1/1 pass, Gradle exit 0 | `aria-litert232-metadata-smoke.log`, `aria-litert232-final-evidence/smoke/` |
| `VisionBackgroundSessionTest` | 9/9 pass, Gradle exit 0 | `aria-litert232-final-vision.log`, `aria-litert232-final-evidence/vision/` |
| `SettingsScreenTest` | 14/14 pass, Gradle exit 0 | `aria-litert232-final-settings.log`, `aria-litert232-final-evidence/settings/` |
| `CloudQuestionDeviceTest` | 10/10 pass, Gradle exit 0 | `aria-litert232-final-cloud.log`, `aria-litert232-final-evidence/cloud/` |
| `UnifiedStopDeviceTest` via direct instrumentation | 2/2 pass, shell exit 0, `OK (2 tests)` | `aria-litert232-final-stop-direct.log` |
| `HazardSpeechIntegrationDeviceTest` via direct instrumentation | 2/2 pass, shell exit 0, `OK (2 tests)` | `aria-litert232-final-hazard-direct.log` |
| Full vision suite again via direct instrumentation | 9/9 pass, shell exit 0, `OK (9 tests)` | `aria-litert232-final-vision-direct.log` |
| Packaged warmup after final `adb install -r -t` update | 1/1 pass, shell exit 0, `OK (1 test)` | `aria-litert232-final-update-smoke-direct.log` |
| Natural unforced startup on final startup fix | 1/1 pass, shell exit 0, `OK (1 test)`, 6.626 seconds | `aria-litert232-natural-startup-device.log`, `aria-litert232-natural-startup-logcat.txt` |
| Full vision suite after final startup fix | 9/9 pass, shell exit 0, `OK (9 tests)`, 29.383 seconds | `aria-litert232-startup-final-vision.log` |

This covers **38 distinct device tests** plus repeated vision/warmup runs. Settings/cloud/stop/hazard results precede the final startup-only fix; their components and tests were unchanged by that fix. Final natural startup and the full vision suite exercise the changed model/camera preparation paths. Shell exit alone was not treated as success: the final `OK` count and per-test instrumentation status were checked. Direct commands used the already compiled test APK and `com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner`.

Actual Home evidence from the full vision logs:

- Model restore reached `Ready`, backend `LiteRT GPU FP32 (CompiledModel)`, `isInvocationReady=true`.
- Initial controller identity was retained through restore and inference.
- Settled snapshot: **3 inference entries / 3 returns; 203 analyzer entries / 203 proxy closes**, no camera error.
- The native smoke log delegated **210/210 graph nodes** through `LITERT_CL`. This is device-specific execution evidence, not a cross-device speed benchmark.
- Background/return, blocked executor, bounded startup/stall/fatal-camera recovery tests passed.
- Final natural-startup evidence: actual `LiteRT GPU FP32 (CompiledModel)` Ready; settled **3 inference entries / 3 returns, 4 analyzer entries / 4 proxy closes**, no frame left in flight. The full startup path completed without manual admission or degraded-mode acceptance.

### Runner and data-preservation incident

No explicit `pm clear` or production data deletion command was issued. However, ActivityManager logs revealed that Gradle's connected-test teardown automatically uninstalled the application and test APK after runs. The initial update-and-smoke began against the existing installation, but subsequent Gradle runs cannot establish retention of the original user data. The coordinator disclosed this finding and corrected the earlier data-preservation statement. **Original persisted user data cannot be claimed preserved or restored.** Credentials were not inspected, copied, or logged.

The optional Gradle stop-test invocation then stalled before instrumentation because Play Protect asked whether to send the unknown test APK to Google. Its UTP process was ended; that invocation has exit 1 (`aria-litert232-final-stop.log`) and is not counted as a passing test. The visible dialog's “do not send” option was selected without changing global security settings. The installer finished; its APK hash was verified, and the stop/voice and repeated vision tests passed via direct instrumentation without automatic uninstall. Settled pending install clients/sessions were cleaned up.

For the final update, the persisted vision slot file's SHA-256 was recorded before and after `adb install -r -t`; it remained identical. The final warmup then passed with that retained model. This demonstrates preservation across the final update, not recovery of the data removed earlier by Gradle teardown. The final APK remains installed on Pixel 3 and MainActivity was launched.

## Artifact and limits

- APK: `aria-1.0.0-preview.20261104.3-debug.apk`
- Size: **274558470 bytes**
- SHA-256: `0ab7923bc43205aa462b8af217304a6dc158b44cf3e83cb0b6286cf75c7b03cb`
- `SHA256SUMS.txt` accompanies the APK in the GitHub prerelease.
- NPU/Exynos hardware execution and comparative performance remain unverified. NPU is optional and conservatively gated; CPU fallback is reported explicitly. `readFloat()` still allocates an output array each invocation. Legacy delegate benchmarks that assume-skip are not acceptance evidence for this runtime.
- Unrelated staged training files, continuation tooling/state, and unrelated documentation are excluded from the release commit and preserved in the workspace.
