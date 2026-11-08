# Gemini Continuation Review — Speech, Image & Onboarding Fixes (2026-11-09)

## Executive Summary
We performed an independent read-only review of all uncommitted speech, photo, and onboarding fixes across `app/src` and `app/build.gradle.kts`. The changes correctly implement loose visual question routing with negative veto, silence progress announcements while preserving processing state, convert setup speech pronunciation to Korean 아리아, and broaden wake-word recall across ASR and offline KWS. One minor actionable defect was identified in `CloudQuestionDeliveryPolicy`: when idling at question ID 0, it continuously evaluates to `Cancel` on every flow emission rather than latching once; a minimal fix is documented.

## Evidence & Environment
- **Model:** Gemini 3.8 Flash (Medium).
- **Worker Validation:** `git diff --check` exited with code 0.
- **Coordinator Host Validation (reported in inbox):** All five Gradle host targets (`:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebugAndroidTest`, `:app:compileDebugAndroidTestKotlin`) passed with exit code 0; 1065 JVM tests executed with 0 failures, 0 errors, 0 skips.
- **Device Limitations:** Connected device testing and installation are excluded by standing user field-fix policy; no real-device network or camera capture was executed by workers.

---

## Detailed Findings & Plausibility Analysis

### Finding 1 (Low / Robustness): Unlatched `Cancel` Emission in `CloudQuestionDeliveryPolicy`
- **Location:** [`CloudQuestionDelivery.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/cloud/CloudQuestionDelivery.kt#L36-L38)
- **Observed Behavior:**
  ```kotlin
  if (questionId == 0L) return CloudQuestionDelivery.Cancel
  if (questionId <= lastQuestionId || !admitted) return null
  lastQuestionId = questionId
  ```
  When the app is idle or after a request has completed/cleared (`questionId == 0L`), `evaluate` returns `CloudQuestionDelivery.Cancel` on every emission of `voiceState` (e.g., partial text updates, RMS amplitude changes, phase transitions).
  In [`MainScreen.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/ui/screen/MainScreen.kt#L461-L463), each `Cancel` calls `cloudCoordinator.cancel()`.
  While `cloudCoordinator.cancel()` safely returns early when `activeRequest == null`, acquiring the internal monitor and dispatching `Cancel` repeatedly during ordinary idle speech recognition is redundant and unlatched. Additionally, `lastQuestionId` is not reset to `0L` upon cancellation.
- **Proposed Minimal Fix:**
  ```kotlin
  if (questionId == 0L) {
      if (lastQuestionId != 0L) {
          lastQuestionId = 0L
          return CloudQuestionDelivery.Cancel
      }
      return null
  }
  ```
  This emits `Cancel` exactly once when transitioning from an active question to 0, resets `lastQuestionId = 0L`, and returns `null` for all subsequent idle emissions.

---

### Finding 2 (Confirmed Valid): Flow-Based Photo Request Collection
- **Location:** [`CloudQuestionDelivery.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/cloud/CloudQuestionDelivery.kt#L48-L58), [`MainScreen.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/ui/screen/MainScreen.kt#L447-L485)
- **Analysis:**
  - Collection combines `model.state` and `model.isServiceSynced` directly from backing `StateFlow`s, bypassing `collectAsStateWithLifecycle`. This resolves the prior defect where stopped Activities paused request collection below `STARTED`.
  - Gate admission waits for `startupModel.hasBeenAdmitted` via `snapshotFlow`.
  - `hasBaseline` protects against replaying stale pre-existing IDs on initial sync while preserving active in-flight requests (`processing == true`).
  - Request IDs are consumed once (`questionId <= lastQuestionId` returns `null`).

---

### Finding 3 (Confirmed Valid): Photo Payload Path & Test Plausibility
- **Location:** [`CloudQuestionCoordinator.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/cloud/CloudQuestionCoordinator.kt#L227-L375), [`ChatGptClient.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/cloud/chatgpt/ChatGptClient.kt#L364-L399)
- **Analysis:**
  - `askPhotoQuestion` enforces auth token resolution before triggering camera capture (`authRepository.validAccessToken()`).
  - Captured frames are downscaled and compressed via `CloudImageProcessor.compressBitmapToBase64` with automatic recycling in `finally` blocks.
  - In `ChatGptClient.buildRequestBodyJson`, OpenAI Responses API formatting is used: `input_image` with `image_url: "data:image/jpeg;base64,..."` and `input_text` with `prompt`.
  - One plausible explanation for the reported "cannot see the picture" symptom is the previous strict visual regex allowing cues like "앞에", "글씨", "읽어줘" to fall through to `CloudTextQuestion` (which sends `imageBase64 = null`). The loose routing fix ensures these route to `CloudPhotoQuestion`.
  - Device tests [`CloudImageCaptureDeviceTest.kt`](file:///C:/Users/felice/orca/aria-app/app/src/androidTest/java/com/ariateam/aria/cloud/CloudImageCaptureDeviceTest.kt) and [`ChatGptLiveImageProbeTest.kt`](file:///C:/Users/felice/orca/aria-app/app/src/androidTest/java/com/ariateam/aria/cloud/ChatGptLiveImageProbeTest.kt) compile cleanly under Kotlin androidTest compiler.

---

### Finding 4 (Confirmed Valid): Loose Image Cues with Negative Veto
- **Location:** [`CloudCommandParser.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/cloud/CloudCommandParser.kt#L16-L32), [`CommandInterpreter.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/command/CommandInterpreter.kt#L41-L57)
- **Analysis:**
  - High-recall visual cue set (`앞에`, `앞쪽`, `전방`, `눈앞`, `글씨`, `글자`, `텍스트`, `읽어`, `표지판`, `간판`) takes precedence over navigation matching.
  - Negation check occurs before visual extraction: 21 negation keywords paired with visual/provider targets return `Interpretation.Clarify`.
  - Compound command guard `secondCommandAfterHago` prevents executing multi-action compound sentences containing "하고".
  - Unit tests in `CloudCommandParserTest` and `RuleCommandInterpreterTest` rigorously verify both positive recall and negation vetoes.

---

### Finding 5 (Confirmed Valid): Question-Processing Toast & Spoken Announce Removal
- **Location:** [`CommandCoordinator.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/command/CommandCoordinator.kt#L435-L457)
- **Analysis:**
  - Spoken announcements "질문을 확인하고 있어요." and "사진을 촬영하여 ChatGPT에 질문을 보냅니다." are removed.
  - Active request tracking and `processing = true` state remain intact.
  - Tested in `CommandCoordinatorBehaviorTest` and `CommandSpeechPlaybackTest` (zero spoken text before cloud response).

---

### Finding 6 (Confirmed Valid): Speech-Only Setup ARIA -> 아리아 Pronunciation
- **Location:** [`OnboardingSpeechText.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/ui/screen/onboarding/OnboardingSpeechText.kt#L4-L5), [`OnboardingController.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/ui/screen/onboarding/OnboardingController.kt#L137-L147)
- **Analysis:**
  - Regex `(?i)(?<![A-Za-z])ARIA(?=[^A-Za-z]|$)` replaces Latin "ARIA"/"Aria" with Korean "아리아" only for TTS speech input.
  - Display branding in layout and resources is preserved.
  - Standalone, particle-suffixed, and non-target Latin words are verified in `OnboardingSpeechTextTest`.

---

### Finding 7 (Confirmed Valid): Wake Alias and KWS Expansion
- **Location:** [`KoreanWakePhraseMatcher.kt`](file:///C:/Users/felice/orca/aria-app/app/src/main/java/com/ariateam/aria/speech/KoreanWakePhraseMatcher.kt#L35-L44), [`app/build.gradle.kts`](file:///C:/Users/felice/orca/aria-app/app/build.gradle.kts#L47-L68)
- **Analysis:**
  - 22 KWS variants provisioned in `build.gradle.kts` matching the Sherpa vocabulary.
  - `WAKE_PATTERN` properly builds regex with character separators to handle spacing and punctuation.
  - Version bumped to 12 (1.0.0-preview.20261109.2).
  - Native spotter test `WakeRecallNativeSpotterTest` is authored to verify initialization and silence ingestion; it compiled but was not executed.

---

## Conclusion & Concrete Next Action
The speech, image, and onboarding changes meet all requirements and pass full host compilation and test suites. Finding 1 is a recommended non-blocking optimization for `CloudQuestionDeliveryPolicy`. The coordinator may proceed with evaluating Finding 1, committing the scoped changes, and generating the release distribution.

## Coordinator independent disposition

Review accepted as read-only evidence, with these qualifications. Finding 1 is a non-blocking redundant operation: CloudQuestionCoordinator.cancel immediately returns when no request is active. The proposed reset of lastQuestionId to zero is not accepted because this field is a replay high-water mark; retaining it ensures an already handled ID stays suppressed after cancellation and service resynchronization. No performance impact was measured, so an idle cancellation latch is deferred rather than mixed into this release.

The routing-to-text explanation in Finding 3 is a hypothesis consistent with static paths, not a confirmed cause of the user's particular server response. Real capture/model behavior was not tested. Findings labelled Confirmed Valid mean static review plus the stated host tests, not device execution. Coordinator independently reviewed scoped production/test diffs and verified all five host targets, 1065 JVM tests and the KWS token vocabulary. No production changes occurred after that validation.
