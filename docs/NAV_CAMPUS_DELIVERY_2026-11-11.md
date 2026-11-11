# Korean voice destination and Naver walking completion

The user reported that saying 한국교통대학교 충주캠퍼스 까지 안내해줘 left the request suffix in Naver search and never started guidance. Acceptance requires the exact command and spacing variants to yield only the campus name, then the production handoff to select the requested Naver place and start walking guidance. Opening a search or route screen alone does not establish completion.

Gemini workers implement in the isolated accessibility-firstlaunch release worktree, excluding the main checkout's unrelated dirty startup/vision changes. The coordinator owns review, Gradle, device operations and delivery. The literal closed-spacing command already strips correctly in the prior source; spaced forms 안내해 줘 / 안내 해 줘 fail. These are reproduced code-input variants; no recording of the user's actual recognizer transcript was captured, so they are not claimed as an observed acoustic transcript.

The prior production fallback after Geocoder failure opened Naver search without arming assistance. Live Naver 6.12.1.1 search returns the campus plus distinct campus buildings, so selection must use a unique exact name within its own result card. Actual WebView node screen coordinates omit the translated bottom-sheet offset; semantic ACTION_CLICK is required. Plus and percent20 URLs both decode correctly on this device; percent20 is retained for portable URI encoding, without claiming plus was the observed root cause.

Pixel 3 / Android 12 is authorized and the user enabled Aria's accessibility service before this task. A real location settings prompt appeared during manual diagnosis; after the user approved enabling it only if present, the prompt was already gone and location was on, so the coordinator made no setting change. The user subsequently explicitly approved Naver's physical activity permission; the coordinator verified the matching live prompt and allowed it. A Naver compass calibration overlay was dismissed with Back. Manual diagnosis then reached actual named-campus walking guidance, and Back returned to the route so the later automation test cannot pass against a preexisting active guidance session. This manual diagnosis is not evidence that Aria automation succeeded.

## Resumed validation

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain` exited 0 with Android Studio JBR. The JVM run passed all 1,143 tests across 100 suites. Lint reported 0 errors, 204 warnings and 3 hints.
- The raw Gradle log is `%TEMP%/aria-nav-campus-20261111/gradle-resumed.log`. Both earlier failing test expectations were corrected and independently rechecked in this run.
- ADB detected no connected devices on resumption. Replacement installation, installed-data hash checks, and the two instrumented guidance scenarios (default resolver and forced NotFound) remain pending. The command-input tests supply recognized text directly, so acoustic speech recognition also remains unverified.
- Debug APK version code 19 / `1.0.0-preview.20261111.3`. SHA-256 `3c0baf13c2811ab78d97bcbdae629a23d25654df34f7258ee6193b1cc4f779ef`.

Source commit `717661e` was pushed to `main` and tagged `v1.0.0-preview.20261111.3`. GitHub reported the APK uploaded and its SHA-256 matched the local package. The existing root startup and vision edits were preserved during the fast-forward.
