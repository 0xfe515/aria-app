# Visual intent matcher review — 2026-11-09

## Changes

- Replaced broad topic substring matching with anchored spatial/deictic, camera/image, and OCR subject forms paired with bounded endings for questions, descriptions, reading, and writing.
- Spatial queries preserve arbitrary visible referents such as restaurant names and QR codes; bare camera mentions, restaurant recommendations, photo file formats, and screen settings do not satisfy the visual grammar.
- Added structural phrase/hypothetical guards, including quoted standalone questions, and strips longest anchored ChatGPT receiver/vocative and question prefixes while retaining the substantive question.
- Added parser and `RuleCommandInterpreter` regressions for the review counterexamples, prefix variants, legacy examples, explicit capture, and quoted/hypothetical phrase discussion.
- Existing descriptive prompt prefixes and capture behavior remain covered by assertions.

## Evidence and validation

- Source: `app/src/main/java/com/ariateam/aria/cloud/CloudCommandParser.kt`
- Tests: `app/src/test/java/com/ariateam/aria/cloud/CloudCommandParserTest.kt`
- Scoped `git diff --check`: exit 0.
- Gradle build, JVM tests, lint, and androidTest compilation: not run by this worker; coordinator owns those checks.
- Effective model: GPT-6 Luna medium (coordinator provided verified launch evidence).

## Next action

Coordinator reviews the scoped diff and runs required Gradle validation. No device/install tests or commit/push performed.
