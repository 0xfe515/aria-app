# Repository Guidelines

## Project Structure & Module Organization
Aria is a single-module Android app built with Kotlin, Jetpack Compose, and Material 3. Source code lives in `app/src/main/java/com/ariateam/aria/`: `MainActivity.kt` hosts the UI, `speech/` manages speech recognition and wake-word listening, `ui/screen/` contains screens, and `ui/theme/` defines styling. Resources live in `app/src/main/res/`, including audio cues in `raw/`. The manifest declares microphone access.

Local tests belong in `app/src/test/`; device tests belong in `app/src/androidTest/`. Dependencies and plugin versions are centralized in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands
Run commands from the repository root using the Gradle wrapper:

- `./gradlew :app:assembleDebug` — build a debug APK.
- `./gradlew :app:installDebug` — install it on a connected device or emulator; launch Aria from the launcher.
- `./gradlew :app:testDebugUnitTest` — run local JVM tests.
- `./gradlew :app:connectedDebugAndroidTest` — run instrumented tests on a connected device or emulator.
- `./gradlew :app:lintDebug` — run Android Lint.

Configure the Android SDK through Android Studio or `local.properties`. The app compiles against SDK 37 and supports Android API 31 and later.

## Coding Style & Naming Conventions
Follow official Kotlin style (`kotlin.code.style=official`) with four-space indentation. Use PascalCase for classes, files, and composable functions; camelCase for ordinary functions and properties; and UPPER_SNAKE_CASE for constants. Use lowercase underscore names for Android resources. Keep speech lifecycle handling in `speech/` and screen composables in `ui/screen/`. Use Android Studio formatting; no standalone formatter or lint plugin is configured beyond Android Lint.

## Testing Guidelines
Tests use JUnit 4, AndroidJUnit4, Espresso, and Compose UI testing dependencies. Name test classes `*Test` and methods for the behavior they verify. Existing tests are starter examples; no coverage threshold is configured. Add focused regression tests for behavior changes. For speech changes, manually verify microphone permission handling, wake-word recognition, command listening, and cleanup on a device with speech recognition support.

## Commit & Pull Request Guidelines
This checkout is connected to https://github.com/0xfe515/aria-app.git on the main branch. Use concise, imperative commit subjects, such as `Fix speech recognizer cleanup`. PRs should describe the change, link relevant issues, report validation commands and device details, and include screenshots for UI changes.

## Configuration Hygiene
Keep SDK paths in ignored `local.properties`. Do not commit credentials, signing keys, or generated build output.

## Agent Workflow
This is an isolated development environment. Builds, tests, and lint are part of the agent's work and do not require a separate request. Use the installed Android Studio JDK and Android SDK with the repository's Gradle wrapper. For code or build configuration changes, run `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug` as appropriate. Run instrumented tests when a compatible device or emulator is available. Fix failures within the task's scope and report the commands, results, and any remaining environment or device limitations. Static review alone does not establish that the app builds or its tests pass. Keep machine-specific SDK paths in ignored `local.properties`.

## Planning and Implementation Split (Codex + Gemini 4 Argon)
- **Default Supervised Orchestration (Orca)**: For supervised implementation, use the Orca `orchestration` skill (`orca skills get orchestration --json`) and real Orca runtime `Run` / `Task` / `Dispatch` state (`orca orchestration ...`), not a raw `agy.exe` process or a non-Orca subagent as a substitute. Codex (`Codex/gpt-sol`) remains the coordinator; select the implementation worker via `orca orchestration worker-start --spec $taskSpec --worktree current --agent antigravity --model gemini-4-argon-high --json`. Plain unsupervised handoff or terminal-only use belongs to `orca-cli` (`orca skills get orca-cli --json`) as the official skill prescribes. Direct headless Antigravity CLI (`%LOCALAPPDATA%\agy\bin\agy.exe`) invocation is retained only as an explicit lower-level diagnostic fallback.
- **Role Split**: Codex (`Codex/gpt-sol`) is responsible for planning, writing precise task specifications, reviewing diffs, performing independent verification, and Git integration. All production source code, tests, build/configuration scripts, and automation code must be authored or modified exclusively by the Gemini 4 Argon (`gemini-4-argon-high`) worker. Documentation and task specifications may be authored by Codex or delegated to Gemini 4 Argon under an explicit task specification.
- **Model Pinning & Blocker Policy**: Always pin `--model gemini-4-argon-high` for implementation tasks; never silently substitute another model. If Orca runtime readiness, authentication, `gemini-4-argon-high` availability, or CLI execution fails, report the specific blocker and stop—do not have Codex implement code as a fallback.
- **Pre-Dispatch & Task Specification Contract**: Read applicable repository instructions (`AGENTS.md`, [docs/ANTIGRAVITY_DELEGATION.md](docs/ANTIGRAVITY_DELEGATION.md)) before dispatching a task. Every task specification sent to the worker must align with Orca's `Target / Change / Constraints / Ownership / Observable acceptance` structure and explicitly define:
  1. Task ID (`TASK_ID`)
  2. Goal and change (`GOAL` / `CHANGE`)
  3. Current evidence, context, and required reading (`CONTEXT` / `VERIFIED_EVIDENCE` / `READ_FIRST`)
  4. Target workspace and allowed file paths (`ALLOWED_CHANGES` / `TARGET` / `OWNERSHIP`)
  5. Forbidden changes and constraints (`FORBIDDEN` / `CONSTRAINTS`)
  6. Behavior requirements including edge cases (`REQUIREMENTS`)
  7. Exact validation commands and verification owner (`VALIDATION_COMMANDS`)
  8. Observable acceptance criteria (`ACCEPTANCE_CRITERIA`)
  9. Required result format (`OUTPUT_FORMAT`)
- **Orca Lifecycle Discipline (Worker & Coordinator)**:
  - **Worker**: Derive the exact executable, flags, terminal handle (`--from` / `--terminal`), `--task-id`, `--dispatch-id`, and heartbeat cadence exclusively from the live Orca-injected preamble (never hardcode a universal heartbeat interval or fabricate/reuse IDs). Send heartbeats at the preamble-specified cadence while actively working (`--type heartbeat --phase <investigating|implementing|reviewing|waiting>`), use `ask` or `escalation` if blocked, run `orca orchestration check --terminal <worker_terminal_id> --json` at natural checkpoints and immediately before `worker_done`, send `worker_done` exactly once with `--outcome succeeded` or `--outcome failed` plus a 3-sentence `--body` summary and true `--files-modified` / `--report-path` values, and remain idle afterward.
  - **Coordinator**: Process every delivered message (`worker_done`, `escalation`, `question`), answer worker questions, verify matching active `taskId` and `dispatchId`, verify outputs, and choose worker reuse, retention, or `worker-release --dispatch <dispatch_id> --json` before acknowledging delivery with `check --ack <delivery_id> --json`. Do not run a manual `task-update` to mark a task completed after `worker_done`. Empty waits (`check --wait`) or unknown liveness do not authorize cancellation, relaunch, or cleanup; follow the orchestration skill recovery receipts.
- **Git & Scope Discipline**: The worker must not run `git commit` or `git push` unless separately and explicitly instructed. Do not alter global `PATH`, CLI global settings, or credentials.
- **Independent Verification & Acceptance**:
  - **Orca Supervised Runs (Default)**: Requested model alone is not proof; Codex verifies `launch.requested` versus `launch.effective` from the Orca launch receipt alongside actual worker evidence, matching `worker_done` (`taskId`, `dispatchId`, `outcome`), `git diff` / `git diff --check`, and independent validation results, and confirms `worker-list --run <run_id> --terminal-state reclaimable --json` after release.
  - **Direct `agy.exe` CLI Runs (Diagnostic Fallback)**: When running the direct headless CLI fallback, a CLI `SUCCESS` status alone is insufficient; Codex must verify that the CLI exit code is `0`, `stream-json` `init.model` is exactly `gemini-4-argon-high`, `init.cwd` matches the target workspace, and the final `result` status is `SUCCESS`.
  - In all modes, Codex independently inspects diffs (`git diff`, `git diff --check`) and validation evidence, runs validation commands when needed, and dispatches explicit correction tasks to `gemini-4-argon-high` if any issue is found.
- **Prompting Claims**: Do not claim any universal model-specific prompt optimization beyond the locally tested task contract documented in [docs/ANTIGRAVITY_DELEGATION.md](docs/ANTIGRAVITY_DELEGATION.md).
