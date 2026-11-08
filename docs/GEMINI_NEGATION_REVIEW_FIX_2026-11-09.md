# Gemini Negation & Visual Request Routing Review & Fix (2026-11-09)

## 1. Executive Summary
We investigated and resolved a critical routing defect where explicit visual request prohibitions ('앞에 글씨 읽으면 안 돼', '앞에 사진 안 찍어줘') and question cancellation/stop forms ('앞에 글씨 읽기 취소', '사진 중지') erroneously bypassed negation vetoes and dispatched positive `CloudPhotoQuestion` or `CloudTextQuestion` actions. Reproduction failing tests were authored and verified failing (RED: 33 tests, 5 failures), followed by minimal, bounded fixes in `CloudCommandParser.kt` and `RuleCommandInterpreter.kt`, and version increments in `app/build.gradle.kts` (versionCode 12 -> 13, versionName 1.0.0-preview.20261109.3). All focused unit tests now pass cleanly (GREEN: 33 tests, 0 failures), and `git diff --check` reports zero whitespace or formatting errors.

---

## 2. Problem Description & Static Reproduction

Following high-recall visual routing changes, visual cues (`앞에`, `앞쪽`, `전방`, `눈앞`, `글씨`, `글자`, `텍스트`, `읽어`, `표지판`, `간판`) took precedence in `RuleCommandInterpreter`. Because `CloudCommandParser` only matched a narrow set of negation strings (omitting `안`-prefixed action verbs, inflectional prohibitions like `읽으면 안 돼` or `안 됩니다`, and cancellation/stop phrases like `읽기 취소` or `사진 중지`), these phrases erroneously satisfied `isNaturalVisualQuestion` and triggered `Interpretation.CloudPhotoQuestion`.

Specifically:
- `'앞에 글씨 읽으면 안 돼'`: lacked `읽으면안돼` in `negationKeywords`, matching `앞에` / `글씨` and dispatching camera capture and OCR to read prohibited text.
- `'앞에 사진 안 찍어줘'`: lacked `안찍` action stem, matching `앞에` and dispatching camera capture.
- `'앞에 글씨 읽기 취소'`: matched `앞에` / `글씨` without cancel interception, dispatching camera capture instead of cancelling.
- `'사진 취소'` / `'사진 중지'` / `'질문 취소'` / `'질문 중지'`: either dispatched text questions (`CloudTextQuestion("취소")`) or failed to stop active background network/capture tasks.

---

## 3. Implementation Details

### A. Direct Question Cancellation & Stop in `CloudCommandParser`
- Unified direct cancellation and stop detection for photo and question domains (`isDirectPhotoOrQuestionCancel`):
  - Endings: `취소`, `취소해`, `취소해줘`, `취소해주세요`, `취소하기`, `중지`, `중지해`, `중지해줘`, `중지해주세요`, `그만`, `그만해`, `그만해줘`.
  - Targets: `사진`, `촬영`, `찍기`, `찍는거`, `질문`, `물어보`, `챗지피티`, `chatgpt`, `제미나이`, `클로드`, `글씨`, `글자`, `텍스트`, `읽기`, `읽는거`, `보는거`, `보기`, `카메라`, `앞에`, `전방`, `눈앞`.
  - Returns: `Interpretation.Execute(AppCommand.Cancel)`. The coordinator clears the question state; the backing-state collector then propagates cancellation to the active cloud request. This is asynchronous cancellation, not a guarantee that a camera operation already in progress can be undone.
  - Standing whole-session stop exact aliases (`듣기중지`, `듣기중지해`, `그만들어`, `그만들어줘`, `음성인식꺼`, `전체중지`) remain handled by `RuleCommandInterpreter` as `AppCommand.Stop`.

### B. Bounded Explicit Negative Action Forms with Natural Inflections
- Expanded `isExplicitNegativeAction` to cover natural Korean inflections without over-generalizing:
  - `안` action verb stems: `안찍`, `안읽`, `안보내`, `안보낼`, `안물어`, `안묻`, `안봐`, `안볼`, `질문안`.
  - Prohibitions with `~면 안 돼/되/된/됨/됩/될`:
    - `읽으면안돼`, `읽으면안되`, `읽으면안된`, `읽으면안됨`, `읽으면안됩`, `읽으면안될`
    - `찍으면안돼`, `찍으면안되`, `찍으면안된`, `찍으면안됨`, `찍으면안됩`, `찍으면안될`
    - `촬영하면안돼`, `촬영하면안되`, `촬영하면안된`, `촬영하면안됨`, `촬영하면안됩`, `촬영하면안될`
    - `보내면안돼`, `보내면안되`, `보내면안된`, `보내면안됨`, `보내면안됩`, `보내면안될`
    - `전송하면안돼`, `전송하면안되`, `전송하면안된`, `전송하면안됨`, `전송하면안됩`, `전송하면안될`
    - `올리면안돼`, `올리면안되`, `올리면안된`, `올리면안됨`, `올리면안됩`, `올리면안될`
    - `보면안돼`, `보면안되`, `보면안된`, `보면안됨`, `보면안됩`, `보면안될`
    - `물어보면안돼`, `물어보면안되`, `물어보면안된`, `물어보면안됨`, `물어보면안됩`, `물어보면안될`
    - `질문하면안돼`, `질문하면안되`, `질문하면안된`, `질문하면안됨`, `질문하면안됩`, `질문하면안될`
  - Long-form `~지 마 / 말 / 않`:
    - `하지마`, `하지말`, `찍지마`, `찍지말`, `묻지마`, `묻지말`, `물어보지마`, `물어보지말`
    - `보내지마`, `보내지말`, `전송하지마`, `전송하지말`, `올리지마`, `올리지말`
    - `보지마`, `보지말`, `읽지마`, `읽지말`, `읽어주지마`, `읽어주지말`
    - `촬영하지마`, `촬영하지말`, `질문하지마`, `질문하지말`
    - `찍지않`, `촬영하지않`, `읽지않`, `보내지않`, `전송하지않`, `물어보지않`, `질문하지않`, `보지않`
  - Targets: `질문`, `사진`, `챗지피티`, `chatgpt`, `제미나이`, `클로드`, `클라우드`, `카메라`, `물어`, `묻`, `앞에`, `앞쪽`, `전방`, `눈앞`, `주변`, `이거`, `이게`, `글자`, `글씨`, `텍스트`, `문구`, `표지판`, `간판`, `읽어`, `찍`, `촬영`, `읽`, `보내`, `전송`, `올리`.
  - Returns: `Interpretation.Clarify("원하는 명령을 하나만 다시 말해 주세요.")`.

### C. Inability Phrasing Preserved for Accessibility
- Per coordinator review correction, inability stems (`못찍`, `못읽`, `못보내`, `~지못`) were intentionally excluded. Phrases such as `'앞에 글씨 못 읽겠어 읽어줘'` describe user inability rather than command refusal, and legitimately request visual assistance. A dedicated positive regression test was added to guarantee this routes to `CloudPhotoQuestion`.

### D. Single Ownership & Clean Precedence in `RuleCommandInterpreter`
- Avoided duplicating cancellation tables in `RuleCommandInterpreter`.
- `CloudCommandParser` owns question-level cancellation and stop; `RuleCommandInterpreter` checks visual cues and returns `visualInterpretation` when it evaluates to `CloudPhotoQuestion`, `Clarify`, or `Execute(AppCommand.Cancel)`. Non-visual question cancellations fall through to `CloudCommandParser.parse` and return `Execute(AppCommand.Cancel)` directly.
- General session command `Cancel` (`취소`, `아니`, `됐어`) and `Stop` (`듣기중지`, `그만들어`, `음성인식꺼`, `전체중지`) remain intact at top-level.

### E. Version Bump
- `app/build.gradle.kts`:
  - `versionCode = 13` (incremented from 12)
  - `versionName = "1.0.0-preview.20261109.3"` (incremented from 1.0.0-preview.20261109.2)

---

## 4. Test Evidence & Verification Matrix

### Test Runs Summary:
1. **Baseline Test Run:**
   - Command: `.\gradlew.bat :app:testDebugUnitTest --tests com.ariateam.aria.cloud.CloudCommandParserTest --tests com.ariateam.aria.command.RuleCommandInterpreterTest`
   - Outcome: PASS (Exit code 0, 27 tests).
2. **Regression Reproduction (RED):**
   - Added static reproduction test cases (`앞에 글씨 읽으면 안 돼`, `앞에 사진 안 찍어줘`, `앞에 글씨 읽기 취소`, etc.).
   - Outcome: FAIL (Exit code 1, 33 tests completed, 5 failed).
   - Log path: `$TEMP\test_regression_red.log`.
3. **Final Focused Verification (GREEN):**
   - Command: `.\gradlew.bat :app:testDebugUnitTest --tests com.ariateam.aria.cloud.CloudCommandParserTest --tests com.ariateam.aria.command.RuleCommandInterpreterTest`
   - Outcome: PASS (Exit code 0, 33 tests completed, 0 failed, 0 errors, 0 skipped in 15s).
   - `CloudCommandParserTest`: 22 tests passed.
   - `RuleCommandInterpreterTest`: 11 tests passed.
   - Log path: `$TEMP\test_regression_final.log`.
4. **Git Diff Hygiene Check:**
   - Command: `git diff --check`
   - Outcome: Exit code 0 (zero whitespace/CRLF formatting issues).

---

## 5. Scope & Remaining Work
- **Modified files:**
  - `app/src/main/java/com/ariateam/aria/cloud/CloudCommandParser.kt`
  - `app/src/main/java/com/ariateam/aria/command/CommandInterpreter.kt`
  - `app/src/test/java/com/ariateam/aria/cloud/CloudCommandParserTest.kt`
  - `app/src/test/java/com/ariateam/aria/command/RuleCommandInterpreterTest.kt`
  - `app/build.gradle.kts` (versionCode 13, versionName 1.0.0-preview.20261109.3)
  - `docs/GEMINI_NEGATION_REVIEW_FIX_2026-11-09.md`
  - `.orca/drops/continuation/ARIA-GEMINI-REVIEW-NEGATION-20261109.md`
- **Coordinator ownership:**
  - Full host test/lint/compilation suite (`assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest`, `compileDebugAndroidTestKotlin`).
  - Git commit, tag, and distribution.
  - Non-destructive connected device installation and direct instrumentation on API 31 device.
