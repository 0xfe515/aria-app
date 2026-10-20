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
