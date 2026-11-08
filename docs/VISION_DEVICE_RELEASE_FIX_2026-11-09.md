# Vision background device test fixture fix (2026-11-09)

The selected `homeBackgroundAndReturn_keepsOneCameraSessionUntilExplicitStop` instrumentation test granted runtime permissions and admitted startup, but did not provide the explicit body stature and mount profile required for valid Home distance output. This matched the observed `groundContactDistance` setup hint even though the model state was ready.

The test now installs a valid 170 cm chest mount profile through `BodyMountProfileStore` before launching Home. Its `@After` restores the exact prior profile (or clears the fixture when the original was unset), closes the store listener, and retains controller/service/scenario cleanup plus restoration of the original explicit-stop latch. No production gate, camera/service entry point, lifecycle operation, or assertion was altered.

Validation performed by this worker: scoped `git diff --check`. Android build, instrumentation compilation, and Pixel 3 execution remain with the coordinator; no device pass is claimed.
