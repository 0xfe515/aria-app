# Black-screen MVP validation

The black screen is a Compose overlay over Home; it does not navigate, change screen brightness, pause speech, or stop CameraX. Auto-hide is limited to a foreground, admitted Home with the wake-word session waiting, a running camera, a ready vision model, and no active dialog, diagnostic/settings screen, permission loss, or actionable voice/vision failure.

## Automated coverage

- `:app:testDebugUnitTest` covers 30-second timing, screen/session eligibility, visibility-event freshness, exact hide/show phrases, and negation.
- `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.ui.screen.BlackScreenOverlayTest,com.ariateam.aria.command.CommandSnapshotBundleTest` targets overlay touch/accessibility, retention of underlying composed session content, and legacy Bundle defaults/event round-trip. Android Gradle compiles all `androidTest` sources before applying this class filter, so unrelated instrumentation compile errors can block execution.

## Device walkthrough

1. Start Aria and wait until Home, the camera preview, and wake-word listening are active. Confirm the screen remains visible through 29 seconds without touch.
2. At 30 seconds idle, verify the in-app surface turns black while voice commands and spoken hazard alerts continue. Tap the black surface; it must reveal Home, consume the tap, and restart the 30-second timer.
3. Say “화면 숨겨” or “화면 꺼”, then “화면 보여줘” or “화면 켜”. Confirm voice hiding does not trigger navigation, background the app, alter brightness, stop camera/hazard updates, or stop wake-word listening. Negated requests such as “화면 끄지 마” must not hide it.
4. Open Settings, diagnostics, the profile dialog, or startup/recovery UI; suspend/resume the app; introduce a camera/vision/voice error. Confirm auto-hide is disabled or the overlay is revealed, with the existing actionable guidance visible.
5. After hiding, verify an important voice/vision failure reveals its existing guidance and hazard TTS remains audible. Confirm a fresh Home resume starts a new 30-second interval.
