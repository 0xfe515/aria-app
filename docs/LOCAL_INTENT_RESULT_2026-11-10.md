# Offline local intent analysis — 2026-11-10

## Implementation

Added `LocalIntentAnalysis` as a platform-independent evidence layer. Local commands combine bounded target/particle/action patterns; compound checks merge overlapping evidence and clarify only when separate executable actions occur. A capture followed by its question remains one workflow, while explicit 그리고/다음에 separators around a photo request, visual requests followed by local commands, and multiple local commands clarify. Unrelated text requests joined with 하고 remain text questions. Existing `RuleCommandInterpreter.interpret` and `CloudCommandParser.parse` entry points and `Interpretation` models remain unchanged.

Examples covered by regression cases include polite settings/sound/screen/status commands, photo capture followed by one ask (`사진 찍고 확인하고 설명해줘`), independent action conflicts, ChatGPT/GPT wrappers, and OCR requests such as reading a medicine package. Provider aliases are stripped only as leading wrappers after capture; the actual question text, names, and punctuation are retained. Abstract color and provider-topic questions do not trigger a photo request, while explicit 앞에/글씨/텍스트/읽어 recall stays in the existing visual parser. Existing cancellation, explicit negation, unsupported-provider, and conversational text fallback behavior remains in its existing path.

Natural demonstrative photo questions also consume the shared bounded photo evidence after the phrase-discussion guard. Direct `이게 뭔지 알려줘`, `지피티, 이게 뭔지 알려줘`, and `GPT 이거 무슨 색인지 알려줘` are covered through both parser entry points, with their question wording retained. Abstract color and GPT-topic questions remain excluded, and explicit capture retains `GPT 모델이 뭔지` in its payload.

## Validation

- `git diff --check`: exit 0.
- Gradle/build/JVM/Lint/androidTest compilation: not run by this worker; coordinator owns all Gradle outputs and will run the assigned validation set.
- A coordinator isolated preliminary JVM snapshot (before the latest correction) ran 1,076 tests with one failure in `CloudCommandParserTest.compoundCommandsReturnClarify`; the final source now detects capture actions after a prefixed question and after explicit compound separators. The fix is awaiting coordinator rerun, so this report does not claim the suite passes.
- Device validation/install: not run; excluded in this field iteration.

## Next action

Coordinator should run `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin`, then review any parser edge-case failures and request a focused correction if needed.
