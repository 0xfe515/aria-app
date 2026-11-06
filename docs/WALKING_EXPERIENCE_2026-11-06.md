# Walking assist production flow

## Session state

`VisionPipelineController` starts one walking-assist generation for each camera attempt. A monotonic elapsed-realtime generation fences status, diagnostics, frame results, and TTS-start callbacks across controller recreation. A 100 ms controller tick evaluates camera permission and status, model readiness, motion sensor availability/calibration/reliability, capture freshness, and any persistent pipeline fault. It publishes `PREPARING`, `CALIBRATION_REQUIRED`, `ACTIVE`, `LIMITED`, or `STOPPED` with the matching cause/action. Permission or camera loss is immediately limited; a result age of 1.5 seconds is limited; persistent transient faults take 1 second; recovery requires 1 second of stable valid sensing. CPU fallback remains governed by the existing model policy.

A successful real session with a fresh result, ready camera/model, calibrated motion, and reliable sensor timestamps is the only path that announces activation. State announcements are transition-driven, and the stopped state remains queryable after its update ages. The state machine rejects older session generations and stale, future, duplicate, or out-of-order result captures. Stopping cancels its tick, invalidates the camera generation, clears pending frame capture, unbinds analysis, and stops the alert policy.

## Voice and hazard path

The offline command interpreter recognizes `정면 보정`, `보행 상태`, and existing `현재 상태` / `전체 중지`. Calibration requests cross the VoiceService process boundary with an explicit package broadcast; the camera controller calls the real motion tracker only while its session is running and returns the actual result. The service announces success only when calibration succeeds and reports failure when it fails or times out. Existing Settings recovery exposes the app settings route for restoring camera permission.

The controller sends status to `VoiceService` through the existing hazard speech bridge. Walking status uses ordinary command speech priority; confirmed near-path and blocked-path hazards use the existing hazard priority and short `walking_strings.xml` copy. The established 2-frame confirmation, 2,500 ms cooldown, 1,500 ms expiry, and 500 ms capture-gap reset remain in `HazardAlertPolicy`; non-candidate caution levels remain suppressed. A hazard clears the active cloud question as well as preempting speech, so a late Gemini response fails the existing request-ID check. The existing whole-session stop receiver is used for the voice `전체 중지` command.

## Diagnostics and tests

The bounded in-memory diagnostics record session and frame sequence, actual backend/model identity, elapsed-realtime capture and decision, dispatch, actual TTS engine start, and alert/state suppression or limitation reason. Records contain no image, transcript, prompt, or API-key payload. Focused JVM coverage includes state timing/session fencing and diagnostics, existing hazard hysteresis and capture-gap cases, command interpretation/status, speech priority, and cancellation of late speech-start callbacks.

The coordinator ran the combined host validation successfully: `:app:assembleDebug`, `:app:testDebugUnitTest` (549 tests, 0 failures/errors/skips), `:app:lintDebug`, and `:app:compileDebugAndroidTestKotlin` all passed in 1m11s; log: `C:/Users/felice/AppData/Local/Temp/aria-walking-independent-5.log`. `git diff --check` also passed. No install or device test was run. Runtime sensor quality, speech recognition quality, hazard accuracy, accessibility behavior on device, and walking performance remain unverified without compatible hardware.

Subsequent user-authorized Pixel 3 validation completed with 550 JVM tests and 47 selected device tests passing. It found and fixed late diagnostic events after camera stop. See [device validation and limits](WALKING_DEVICE_VALIDATION_2026-11-06.md); the preceding paragraph records the earlier host-only checkpoint, not the final validation state.
