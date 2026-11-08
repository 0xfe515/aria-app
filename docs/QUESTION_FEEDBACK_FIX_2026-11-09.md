# Question feedback and onboarding speech update

Removed the generic text and photo question progress acknowledgments. Both request handlers still emit their active request ID/type/prompt and `processing = true`; existing completion, timeout, and cancellation paths retain their behavior. Onboarding speech now converts standalone `ARIA`/`Aria` to `아리아`, including Korean suffixes, through one shared speech-only helper used to populate the pending prompt and immediate TTS call. Display strings and app branding are unchanged.

Static review confirms the queued prompt path calls TTS with the transformed `PendingPrompt.text`; prompt generation, utterance ID, completion callbacks, and lifecycle behavior were not changed. Added command-state assertions for text/photo questions and focused pronunciation helper coverage. Coordinator owns all build, test, lint, and device validation for this task.
