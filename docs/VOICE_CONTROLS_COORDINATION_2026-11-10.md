# Voice controls expansion

User request: make voice control useful for navigation and in-app features, specifically volume, warning frequency and disabling the caution tier. Acceptance includes real runtime setting changes, not only new recognition phrases. No extra model/API. Current field iteration excludes real-device tests and installation.

Run `run_2b3cd263f99a`. Independent areas dispatched before waiting:

- Runtime/settings Task `task_2dd53fd1856c`, Dispatch `ctx_f6bed30fdb26`: new settings package/provider/client, Android manifest, TTS/cues, hazard/distance alert policies, General settings controls and their tests.
- Commands Task `task_7552c718bc8f`, Dispatch `ctx_d78cdc94d423`: typed local setting commands, interpreter/coordinator execution, readback/help and regression tests.
- Navigation Task `task_a4b658ee32bc` / `ctx_55c8e7ba5b8d`; follow-up `task_aa57fbb76771` / `ctx_c3c8e1d18dac` settled; final mode-ending follow-up is active in the same exact terminal. Exclusive navigation parser/tests and version metadata to 16 / `1.0.0-preview.20261110.2`.

Runtime and command workers share an explicit `FeedbackSettings` / `AlertFrequency` / `FeedbackSettingsClient` interface. VoiceService runs in `:wakeword`; live cross-process updates must use main-owned persistence plus IPC/observation rather than multiprocess SharedPreferences assumptions. App volume controls speech/cues, preserving system media volume and existing mute behavior. Frequency changes repeat intervals, not perception thresholds; caution-off suppresses caution feedback while keeping WARNING/URGENT and detector/classification truthful.

All workers use verified Orca Codex `gpt-6-luna` / `medium` receipts under the already observed provider-quota fallback (Copilot monthly requests exhausted; Gemini CLI sign-in unavailable). No global settings/credentials or automation changes. Preserve unrelated dirty startup/vision/MainScreen/MainActivity changes. Coordinator owns builds, review and Git/release; workers do not run Gradle. Required validation: assembleDebug, testDebugUnitTest, lintDebug and compileDebugAndroidTestKotlin using Android Studio JDK, with scoped release source separated from unrelated dirty files.

Next action: collect launch/interface receipts, review actual runtime paths and worker output, validate final integration independently, then follow standing commit/push/prerelease delivery authorization.

Runtime client API is now published: `read(): FeedbackSettings` is nonnull and throws on failed IPC/read; mutations return nullable snapshots, and `settings` is cached StateFlow for hot paths. Review corrections sent for initialization ordering, corrupt preference types, missing/invalid mutation payloads, integer overflow, duplicate fields, observer cleanup, and slider commit-on-finish. Parser review covers caution/frequency precedence, actual dangerous-only mutation, signed/overflow/absolute-vs-relative volume, photo topic collisions, negation and compound commands.

Ownership extension: runtime worker also owns `IVoiceService.aidl`, `HazardSpeechBridge.kt`, `VoiceService.kt`, their focused speech tests/stubs. Append `postHazardAlertWithSeverity(..., int severityOrdinal)` preserving the old AIDL API as WARNING. Command worker owns coordinator `postHazardAlert(..., severity: AlertSeverity = WARNING)` and checks cached caution setting before mutating any speech/dedup/cloud state. This preserves severity across Binder so queued CAUTION can be filtered in `:wakeword`.

Scoped release baseline is `%TEMP%/aria-voice-controls-release-20261110`, an archive of committed f4c9255 plus generated dependency assets copied from the previously validated release. Do not copy unrelated dirty sources there. No validation has run for this task yet. Navigation launch initially had an unsent draft; exact screen inspection confirmed it, and only bare Enter submitted the existing draft, with no duplicate editor. On midnight rollover, work continues under the existing version/spec date.

## Independent acceptance

All implementation and correction Tasks settled successfully; the Run's reclaimable-worker list is empty. Diagnostic integration appended `USER_CAUTION_DISABLED` and updated the exhaustive suppression mapping. Actual build failures (nullable Context, missing diagnostic mapping, and two test Binder implementations) were fixed through exclusive workers. The first runnable JVM suite had four regressions: two existing photo-compound cases, an incomplete numeric request and an intentionally updated help-copy expectation. Focused corrections preserved photo tests and reject malformed/out-of-range spoken numerals without trimming meaningful leading digits.

Final command, using Android Studio JDK, on the isolated scoped source snapshot:

```text
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain
```

Exit 0, `BUILD SUCCESSFUL in 1m 49s`. JVM XML totals: 1,096 tests, zero failures/errors/skips. All 38 scoped code/build/test files match the final working sources by SHA-256; unrelated dirty startup/vision/MainScreen/MainActivity work is excluded from the release APK and commit. Raw logs remain outside tracked files: `%TEMP%/aria-voice-controls-preliminary.log`, `aria-voice-controls-integration.log`, `aria-voice-controls-final-validation.log`, and `aria-voice-controls-acceptance.log`.

No device execution, installation, acoustic recognition, real volume/haptic output or cross-process device performance is claimed verified; the current field iteration explicitly excludes hardware checks. Provider persistence/close instrumentation was compiled. Native Korean number words such as `쉰` remain unsupported; digits and Sino-Korean numbers such as `오십`, `이십`, `칠십` are covered. Release target: versionCode 16, `1.0.0-preview.20261110.2`, debug APK prerelease under standing delivery authorization.
