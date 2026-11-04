# Repository inspection and cleanup — 2026-11-04

Reviewed directly by Codex; no delegated review in this pass. Baseline main: 84c841b.

## Coverage and limits

- Enumerated all 101 production Kotlin files across cloud, command, distance, hazard, licenses, navigation, runtime, speech, startup, UI and vision. Compiler/lint/test analysis covered 176 Kotlin production/test files; this is not a claim of line-by-line manual review or exhaustive runtime correctness.
- Independently ran assembleDebug, testDebugUnitTest, lintDebug and assembleDebugAndroidTest: exit 0. Existing JVM suite has 389 tests. Reused unchanged Gradle inputs where up-to-date; no new device tests or installations.
- Parsed all 50 Python source files under tools with Python AST, and all 22 source XML documents with XML parser: zero parse failures. Python training/integration behavior and dependency availability were not validated by parsing.
- Manually traced camera failure/retry, foreground notification coordination, stop receiver, cross-process state, voice/command startup, cloud cancellation and transport, navigation query validation, settings and backup rules; sampled distance guards and license HTML escaping. Training/model assets and historical evidence retained.

## Findings requiring code changes

1. **High — hazard speech is not wired into actual hazard events.** HazardAlertCoordinator dispatches vibration/Toast. speakHazardWarning/preemptSpeech exist in command/AIDL wrappers but have no hazard/UI caller. Priority plumbing alone does not establish cloud-speech preemption.
2. **High — obsolete local LLM startup remains.** CommandCoordinator.started calls prepareModel; installed Gemma triggers LanguageClient.prepare. GemmaModelStore, LanguageService, language-process manifest entry, AIDL and litert-lm dependency remain. Removing their Settings UI did not remove startup work or memory cost. Remove this as a coordinated dependency change, not isolated file deletion; preserve ASR and vision.
3. **High — camera availability is not filtered to the selected camera.** VisionPipelineController.onCameraAvailable ignores cameraId and can immediately retry on other camera availability, bypassing intended backoff. Verify registration's initial availability events and fence queued retry callbacks per attempt.
4. **Medium — cross-process notification status has no death reconciliation.** AriaRuntimeStateBridge stores booleans in provider memory, without process/session identity or binder death handling. Service death/provider process recreation can make aggregate status inaccurate.
5. **Medium — Stop reports success without service-stop confirmation.** AriaUnifiedStopReceiver catches failure to send VoiceService.ACTION_STOP then still writes voice/vision inactive. Actual service state can disagree with displayed state. Use confirmed lifecycle cleanup or a reliable direct stop path and coordinated state update.
6. **Low — README is stale.** It describes local Gemma as a primary feature and omits current Gemini account/menu architecture. License notices must remain while bundled dependencies remain.

These are static code findings, not device reproductions. No production source fixes were performed in this inspection/cleanup pass.

## Cleanup completed

Moved 11 unrelated/unreferenced root diagnostics or sample files outside the checkout to:

`C:\Users\felice\AppData\Local\Temp\aria-cleanup-20261104-113555`

The directory contains manifest.json with original relative path, SHA-256 and size, permitting restoration. No recursive deletion was used.

- analyze_test.sh, check_env.py, test_check.py: one-off importer test inspection.
- run_tests.py, run_tests.bat: hardcoded duplicate wrappers; Python wrapper also had an unbound stderr failure branch.
- inspect_tflite.py, inspect_tflite_wsl.py, inspect_output.txt: duplicate temporary model inspections and output.
- test_precheck_live.py: temporary live automation reproduction with dry_run=False; inappropriate as a normal test entrypoint.
- Pipfile: empty dependency placeholder with no discovered project reference.
- docs/class Unit.cs: unrelated console number-guessing sample, no Android/build/documentation references.

Reference search covered README, AGENTS, docs, tools and app (excluding generated outputs); found no consumers of these filenames. The C# sample was tracked and is now a pending deletion; the other ten files were untracked. No cleanup commit/push was made.

## Preserved work

Preserved pre-existing staged training importer/test modifications, modified delegation documentation, .orca checkpoints, continuation/account-rotation tools and historical evidence. Deferred automation and local LLM source cannot safely be labeled unused while live references remain. Generated build caches were retained for reproducibility and efficient validation.
