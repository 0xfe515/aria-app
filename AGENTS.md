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
- **Role Split**: Codex (`Codex/gpt-sol`) is responsible for planning, writing precise task specifications, reviewing diffs, and performing independent verification. All production source code, tests, build/configuration scripts, and automation code must be authored or modified exclusively by the Gemini 4 Argon worker via the official Antigravity CLI (`%LOCALAPPDATA%\agy\bin\agy.exe`). Documentation and task specifications may be authored by Codex.
- **Model Pinning & Blocker Policy**: Always pin `--model gemini-4-argon-high` for implementation tasks; never silently substitute another model. If authentication, `gemini-4-argon-high` availability, or CLI execution fails, report the specific blocker and stop—do not have Codex implement code as a fallback.
- **Pre-Dispatch & Task Specification Contract**: Read applicable repository instructions before dispatching a task. Every task specification sent to the worker must explicitly define:
  1. Task ID (`TASK_ID`)
  2. Goal (`GOAL`)
  3. Current evidence and context (`CONTEXT` / `VERIFIED_EVIDENCE`)
  4. Allowed file paths (`ALLOWED_CHANGES`)
  5. Forbidden changes (`FORBIDDEN`)
  6. Behavior requirements including edge cases (`REQUIREMENTS`)
  7. Exact validation commands (`VALIDATION_COMMANDS`)
  8. Acceptance criteria (`ACCEPTANCE_CRITERIA`)
  9. Required result format (`OUTPUT_FORMAT`)
- **Git & Scope Discipline**: The worker must not run `git commit` or `git push` unless separately and explicitly instructed. Do not alter global `PATH`, CLI global settings, or credentials.
- **Independent Verification & Acceptance**: A CLI `SUCCESS` status alone is insufficient for acceptance. Codex must verify that the CLI exit code is `0`, `init.model` is exactly `gemini-4-argon-high`, `init.cwd` matches the target workspace, and the final result status is `SUCCESS`. Because headless CLI runs may deny shell command execution even when reporting `SUCCESS`, Codex independently inspects diffs (`git diff`, `git diff --check`) and validation evidence, runs validation commands when needed, and dispatches explicit correction tasks to `gemini-4-argon-high` if any issue is found.
- **Prompting Claims**: Do not claim any universal model-specific prompt optimization beyond the locally tested task contract documented in [docs/ANTIGRAVITY_DELEGATION.md](docs/ANTIGRAVITY_DELEGATION.md).
