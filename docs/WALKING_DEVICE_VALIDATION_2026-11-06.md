# Walking assistance — Pixel 3 validation

Validated on Google Pixel 3 (blueline), Android 12 / API 31, on 2026-11-06. This is a debug-signed preview build, versionCode 5 / `1.0.0-preview.20261104.3`. The source commit containing this report identifies the implementation; no new release version or production signing identity is claimed.

## Build and host results

Using the repository wrapper, installed Android Studio JBR and Android SDK:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Final independent run: exit 0, 1m38s, without source exclusions or temporary init scripts. Parsed JUnit XML: **550 tests, 0 failures, 0 errors, 0 skipped**. Android Lint and application/instrumentation APK assembly passed; compiler deprecation warnings remain. `git diff --check` passed. [Host log](evidence/walking-20261106/host-validation.log).

## Device results

Installed with `adb install -r` and `adb install -r -t`. No app-data clear, uninstall, API-key inspection or production model replacement was performed. Tests ran serially using class-selected `adb shell am instrument -w -r`; success required both the final `OK` count and per-test status, not shell exit alone. Each suite began from a force-stopped process, without clearing persisted data.

| Suite | Passed | Evidence |
| --- | ---: | --- |
| Natural startup and live inference | 1 | [log](evidence/walking-20261106/VisionNaturalStartupDeviceTest-final.log) |
| Camera background, recovery and resource cleanup | 9 | [log](evidence/walking-20261106/VisionBackgroundSessionTest-final.log) |
| Actual VoiceViewModel binding, stop and late callbacks | 4 | [log](evidence/walking-20261106/VoiceViewModelLifecycleTest-final.log) |
| Unified stop receiver and idle-service IPC | 2 | [log](evidence/walking-20261106/UnifiedStopDeviceTest-final.log) |
| Hazard bridge and no-revival policy | 2 | [log](evidence/walking-20261106/HazardSpeechIntegrationDeviceTest-final.log) |
| Settings information semantics | 1 | [log](evidence/walking-20261106/SettingsInfoRowUiTest-final.log) |
| Settings navigation and controls | 14 | [log](evidence/walking-20261106/SettingsScreenTest-final.log) |
| Cloud request cancellation and isolated key storage | 10 | [log](evidence/walking-20261106/CloudQuestionDeviceTest-final.log) |
| Live walking diagnostics, actual calibration outcome and stopped-generation fencing | 1 | [log](evidence/walking-20261106/WalkingAssistDeviceTest-fixed.log) |
| Walking status policy, active/idle cross-process AIDL and hazard delivery | 3 | [log](evidence/walking-20261106/WalkingSpeechDeviceTest-fixed.log) |

**Final selections: 47 distinct tests passed, 0 failed, 0 skipped.** The status-policy portion uses a recording speech engine on Android and is not an acoustic TTS verification. Active/idle AIDL tests use the real separate VoiceService process. Cloud tests use isolated test credentials/transport and do not submit user images or questions to Gemini.

## Findings resolved before acceptance

- Hazard integration expected the retired free-form message; the assertion now verifies the resource-backed warning while retaining actual bridge-delivery checks.
- The first live walking test detected diagnostic events arriving after camera stop. Diagnostics now have a synchronized per-generation stop fence before STOPPED publication; late capture, decision and speech-start events are rejected while prior history remains readable. JVM regression and the repeated live device test both passed.
- New speech fixtures initially mixed real and synthetic time/session identity. Tests now inject clocks for policy assertions, derive actual walking session generations for AIDL, and clean up any voice activity they started. Existing user audio settings are restored.
- An older optional active-voice test skipped when its pre-existing-session condition was absent. It is not included in the 47 final passes; the new explicitly started active-AIDL test passed without that skip.

## Artifact and limits

APK SHA-256: `28466e1def8092637e021350f3914ff4d6f8d392f2102a5d38636d1f12d9a178`. The installed base APK checksum matches the built file. Production portrait model remains `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`.

Pruning candidates remain unapplied: prior Pixel 3 evaluation found GPU compilation incompatibility, no consistent CPU speed gain, and degraded segmentation metrics. Display filtering affects only overlay colors; hazard/distance masks remain original.

This validates the selected Android/device execution paths. It does not establish real walking-course hazard accuracy, Korean spoken-command recognition accuracy, acoustic warning audibility, TalkBack usability with representative users, long-duration battery/thermal behavior, or a measured visual-quality improvement from filtering. No safety certification or universal device compatibility is claimed.
