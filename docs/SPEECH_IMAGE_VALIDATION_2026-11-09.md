# Speech, photo question and onboarding validation — 2026-11-09

## Result

Continued the previous speech/photo field fix with an Orca-managed Gemini review. App version is 1.0.0-preview.20261109.2 (versionCode 12). Question intake keeps processing state without progress toast/spoken acknowledgment. Setup speech pronounces ARIA as 아리아. Wake aliases and 22 native KWS entries prioritize recall. Loose visual cues route to photo requests; explicit negative capture/read/send requests retain their veto. Cloud delivery collects backing request flows independently of Activity STARTED state while preserving synchronization/admission gates and request IDs.

## Independent host validation

Installed Android Studio JDK and the existing ignored local.properties SDK were used. This combined invocation exited 0:

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:compileDebugAndroidTestKotlin
```

BUILD SUCCESSFUL in 1m49s. JVM results: 93 suites, 1065 tests, 0 failures, 0 errors, 0 skipped. Lint: 0 errors, 197 warnings, 3 hints. Lint success does not mean a warning-free app. Full host log: C:\Users\felice\AppData\Local\Temp\aria-resume-20261109-host.log. Machine-local log is not committed.

`git diff --check` exited 0. Independent check of all generated debug keywords against generated tokens.txt found 22 keyword lines and 0 unknown tokens.

## Limits

The current field-fix policy explicitly excludes real-device execution and installation. Android instrumentation code and APK compile; hardware tests, native acoustic recall, actual camera capture, background behavior and live provider image interpretation were not verified. The optional synthetic live image probe was not run. No user app data was cleared or app uninstalled.

Gemini review outcome and delivery receipts will be recorded after independent acceptance. Unrelated baseline changes and research/automation artifacts remain outside this release.

Gemini 3.8 Flash medium review completed under task_09ea5113c051 / ctx_edb5b39e8c51. Coordinator accepted core requirements and deferred non-blocking idle-cancel optimization, rejecting a proposed replay high-water reset. Root-cause speculation and unexecuted device checks are qualified in the accepted review. Worker released and Run reclaimable terminal list is empty. No production/test files changed after host validation.
