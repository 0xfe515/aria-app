# Binary vision / traffic-light display independent acceptance — 2026-11-08

Resumed the previous Codex Run `run_06ec8405188a` at the user's request. All three original Tasks have succeeded and their worker terminals are released; final reclaimable enumeration is empty. No new implementation workers were launched.

## Accepted behavior

- Home, presentation and diagnostics use the shared binary overlay: sidewalk/crosswalk green, other classes red, equal alpha, no transparent noise holes. Bounded crosswalk stripe gaps are supplemented while obstacle holes remain red.
- Raw semantic masks and summary/hazard inputs remain separate from display postprocessing. Regression tests cover semantic invariance, live/static rendering parity and concurrent static calls.
- Traffic-light category 9 displays a neutral box with Korean label 신호등. Existing obstacle classes have priority at the result cap; hazard fusion still allows only 0,1,2,3,5,7. Signal state and crossing permission are outside this implementation.
- Home layout and the existing single direction line are preserved; Home source was not changed by this task.

## Correction during resumed validation

The first full JVM suite failed 3 tests because new OD cap fixtures used zero-area boxes. The exclusive OD owner corrected their coordinates; production box validation was preserved. Independently reviewed the correction and reran the full suite.

## Independent validation

Used installed Android Studio JBR (`C:/Program Files/Android/Android Studio/jbr`) and repository Gradle wrapper, with SDK path kept in ignored local.properties.

| Command | Exit | Result |
| --- | --- | --- |
| `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain` | 0 | BUILD SUCCESSFUL; JVM task reused prior result |
| `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain` | 0 | All tasks executed; 1,003 tests, zero failures/errors/skips |
| `git diff --check` | 0 | No whitespace errors |

Raw logs:
- `C:/Users/felice/AppData/Local/Temp/aria-binary-vision-independent-20261108.log`
- `C:/Users/felice/AppData/Local/Temp/aria-binary-vision-independent-unit-20261108.log`
- `C:/Users/felice/AppData/Local/Temp/aria-binary-vision-diffcheck-20261108.log`

JUnit XML: `app/build/test-results/testDebugUnitTest/`. Lint: `app/build/reports/lint-results-debug.html` and `.sarif`: no errors, 196 warnings and 3 notes. No claim that these findings are new or resolved.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (280,816,092 bytes), SHA-256 `195c5cc165ff23544f3f0c76cf867b76a51a89408b158787fa0d325323f36d03`.

## Limits

Synthetic helper fixture was visually reviewed at `app/build/reports/crosswalk/crosswalk_display_fixture.png`. Perspective geometry can fill only some eligible stripe gaps; production still maps raw crosswalk pixels green. Updated helper documentation to describe this and removed an unmeasured mobile latency claim. No hardware accuracy/performance, device test, installation, commit, push or release was performed. Existing unrelated changes remain preserved.
