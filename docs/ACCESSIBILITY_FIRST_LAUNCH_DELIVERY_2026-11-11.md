# First-launch accessibility setup and Pixel 3 check

The user requested another device check and accessibility setup at first app launch. Pixel 3 (blueline), Android API 31, is connected and authorized. Naver Maps 6.12.1.1 is installed; the existing Aria installation is version code 13. Accessibility was disabled at inspection. The current request supersedes the previous no-device field constraint; replacement installation and direct instrumentation must preserve the installed app and user data.

A Korean setup gate now precedes body-profile onboarding and main startup, so microphone/camera permission requests cannot overlap it. Users may open public Android Accessibility settings or choose 나중에. The app records a handled marker only after a successful settings handoff or skip, refreshes the real enabled-service state on return, and continues even if the service remains disabled. Rendering alone does not mark handled. Existing enabled services bypass the explanation. The screen scrolls and uses safe-drawing insets. The marker follows existing SharedPreferences backup behavior.

The coordinator inspected Naver's actual accessibility tree using a fixed public walking test route (테스트출발 to 서울역), without starting guidance. The selected walking node reports 도보 2분. The 안내시작 text and description are two nonclickable children of the same immediate clickable parent. Target selection now resolves only a direct node or its immediate parent, deduplicates the shared action, and rejects ambiguous targets, disabled labels/targets, unrelated broad containers, distant ancestors and mismatched windows/packages. Existing destination, mode, freshness, token, expiry and cancel/stop guards remain.

## Validation and delivery

Validated source: 996d053, following 840e761. The isolated Orca release worktree excludes all unrelated dirty startup/vision/MainActivity changes. The initial implementation predates the latest Gemini-only instruction; the subsequent Gemini 3.8 Flash medium Orca review fixed the pending-settings-return lifecycle edge and AndroidTest import. No additional Luna workers were launched after that instruction. The coordinator independently inspected all scoped changes and ran validation.

- Gradle `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain`: exit 0, BUILD SUCCESSFUL in 2m 9s. JVM: 98 suites, 1,119 tests, zero failures/errors/skips. Lint: no errors, 203 warnings and 3 hints remain.
- Clean build log: `%TEMP%/aria-accessibility-firstlaunch-20261111/gradle-release-3.log`. Earlier compilation attempts exposed the invalid Compose test import; the Gemini fix was included in this successful run.
- Pixel 3 replacement installs of both app and test APK: Success. Version code 18 / `1.0.0-preview.20261111.2` verified on device.
- Direct AndroidJUnitRunner invocation of `FirstLaunchAccessibilityGateTest`: OK (2 tests), 2.633s. This tests failed settings handoff/skip and successful handoff/return with fake state, without touching real preferences. The first device attempt failed to find Compose hierarchies while the screen was dozing; waking the screen and dismissing the nonrestricted keyguard resolved it without code changes. Final log: `%TEMP%/aria-accessibility-firstlaunch-20261111/instrumentation-2.log`.
- Real MainActivity cold launch and UI hierarchy confirm the Korean setup explanation and both buttons appear, with no overlapping camera/microphone permission dialog. The real prompt is left unhandled for the user.
- Body-profile and account preferences SHA-256 hashes match the pre-install baseline. No app uninstall, data clear, or accessibility settings override was performed. Direct instrumentation preserves the installed app.
- APK SHA-256: `84c68ca3c9c645ea4a9b52b791f7eadf2b5f1db4aa26a015ed0bae69c56d0130`.

Real automatic guidance still requires the user to manually enable Aria's accessibility service. The service remains disabled on this device, so an end-to-end automatic guidance click is not claimed. The actual Naver hierarchy and bounded selector are verified separately as described above. No map API key is needed for this accessibility-assisted path.

Distribution: prerelease `v1.0.0-preview.20261111.2`, debug APK `aria-1.0.0-preview.20261111.2-debug.apk`. Delivery is subject to the final push/upload receipts; source validation is tied to the commit above.
