# Codex direct preview review

Status: final integration reviewed. Gemini outputs were independently evaluated, with the additional IO-dispatch Bitmap cleanup fix incorporated before release validation.

## Findings to verify

- `CloudQuestionCoordinator.askPhotoQuestion`: a Bitmap passes the cancellation/disposal check before `scope.launch`; disposal can cancel the scope before the coroutine body starts, leaving the received Bitmap outside the compression `finally`. Verify ownership and recycling on a cancelled-before-start job using a queued test dispatcher.
- `DashboardHeadingArrow`: origin is only 34dp above the lower edge while arrow radius can exceed that distance. Rear-facing angles can put the tip outside the canvas. Verify settled core changes provide a visible truthful rear-facing representation and bounded geometry.
- `VisionPipelineController.analyzeFrame`: snapshot conversion/callback shares the outer try/catch, so a throwing callback can be invoked a second time with null. Verify callback single-delivery, Bitmap ownership, and that YUV buffers remain valid for preprocessing after `ImageProxy.toBitmap()`.
- Cloud question dispatch is intentionally retained until completion (`consumeCloudQuestion` no-op); verify initial-sync baseline, Activity recreation, background/foreground and timeout do not drop a new request or replay photo upload. Current unit success alone is insufficient for this UI/IPC contract.

## Verified so far

- Secure key storage uses AndroidKeyStore AES/GCM with app-private ciphertext preferences. Cancellation now registers request-scoped connection disconnect and coordinator disposal exists.
- User requested Codex direct review; implementation-worker reports will be reviewed separately. Publishing/install remain pending integrated validation and review.

## Final review evidence

- Bitmap ownership now remains request-scoped before the coroutine starts, and an outer `finally` recycles the local Bitmap even when cancelled before the IO block starts. Added real-device regression coverage for both cancel and dispose at each dispatch boundary.
- Cancellation registrations use synchronized ownership and a per-registration atomic execution guard; callbacks run at most once.
- Arrow geometry now bounds shaft/tip/wings across rear-facing angles, backed by full-angle regression checks.
- Coordinator independently passed assembleDebug, testDebugUnitTest (374 tests, zero failures/errors), lintDebug, assembleDebugAndroidTest and git diff --check. Signed preview APK verified with apksigner.
- Worker reported 25 focused connected tests passing on Pixel 3. Coordinator additionally reruns CloudQuestionDeviceTest before publication; result recorded in release notes.
- Live Gemini requests with a user API key and physical outdoor walking remain unverified. Snapshot callback exceptional behavior remains a low-priority hardening consideration; no callback exception observed in the tested flow.
