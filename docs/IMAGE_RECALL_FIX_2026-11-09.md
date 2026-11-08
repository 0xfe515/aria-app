# Image question recall and dispatch trace (2026-11-09)

## Changes

- Natural visual recognition now accepts direct cues `앞에`, `앞쪽`, `전방`, `눈앞`, `글씨`, `글자`, `텍스트`, `읽어`, `표지판`, and `간판` without requiring a particular question ending. This intentionally accepts false positives in quoted or abstract questions when those cues appear.
- The rule interpreter gives an explicit visual request first chance before navigation substring matching. Cloud parser safety handling still rejects negated/compound requests and clarifies unsupported-provider targeting.
- MainScreen no longer drops delivery solely because `screenVisible` is false. The request remains pending until startup admission and service synchronization, then is consumed and passed to the coordinator.

## Dispatch evidence and limits

`CommandInterpreter` returns `CloudPhotoQuestion`; the command coordinator publishes the question ID/type/prompt. `MainScreen` observes the ID, waits for startup admission and service synchronization, consumes it, and calls `CloudQuestionCoordinator.askPhotoQuestion`. That method obtains a valid OAuth token before asking `VisionSession.captureSingleFrame`; VisionSession returns null if no live controller exists, and the coordinator reports a camera-unavailable error. A captured frame is compressed by `CloudImageProcessor.compressBitmapToBase64` and passed to `ChatGptClient.generateContent(imageBase64=...)`. The Responses API JSON builder attaches nonblank image data as `input_image` with a JPEG data URL alongside `input_text`; the existing ChatGptClient test asserts both parts.

The confirmed dispatch defect was the Compose visibility guard, which could leave a newly issued question unconsumed while the screen was hidden. The confirmed recognition defect was restrictive keyword grammar. No defect was found in the image JSON construction. The model catalog only provides slug/display name/visibility, so this code cannot establish model image capability; no capability inference or live provider success is claimed. Camera availability, app/service lifetime outside this Compose screen, model-side image acceptance, and network response still require coordinator/device validation. This change does not establish general background cloud support.

## Validation

Added parser cases for standalone/common visual cues, changed the former strict quoted/abstract negatives to reflect the requested recall preference, retained unrelated no-visual-cue negatives, and added top-level interpreter precedence cases. Worker validation is limited to `git diff --check` and static tracing; the coordinator owns Gradle and device validation.
