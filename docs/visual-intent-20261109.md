# Visual question routing — 2026-11-09

## Outcome

Broadened `CloudCommandParser` natural visual intent matching from exact phrases to bounded visual-context plus question intent, with OCR-specific terms. Photo prompts retain the user's substantive wording after leading ChatGPT/question prefixes are removed, including details on location, color, and signs; descriptive prefixes preserve established object/forward context. Explicit capture remains supported and now keeps detailed follow-up wording.

## Evidence

- Changed: `app/src/main/java/com/ariateam/aria/cloud/CloudCommandParser.kt`
- Tests added: `app/src/test/java/com/ariateam/aria/cloud/CloudCommandParserTest.kt`
- Coverage includes the two requested examples, spatial/OCR and polite forms, detailed prompts, explicit capture, negated and compound visual requests, quoted/abstract topics, and camera/photo/settings/local-service false positives.
- Caller reviewed: `CommandInterpreter` invokes navigation first, then cloud parsing; no caller/interface edits.
- `git diff --check` (scoped files): exit 0.
- Gradle/JVM tests, build, lint, and androidTest compilation: not run by this worker; coordinator owns Gradle validation.
- Effective model: GPT-6 Luna, medium (coordinator launch evidence reported as verified).

## Next action

Coordinator reviews this diff and runs the assigned Gradle validation. No device/install tests or commit/push performed.
