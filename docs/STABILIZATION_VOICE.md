# Voice Stabilization and Hazard Speech Integration (ARIA-STABILIZE-VOICE-023 / ARIA-STABILIZE-VOICE-024 / ARIA-STABILIZE-VOICE-025)

## Overview
This stabilization pass completes the voice architecture transition for ARiA:
1. Real hazard alerts from `HazardAlertCoordinator` are connected to the speech priority queue via `HazardSpeechBridge` and `IVoiceService.postHazardAlert`.
2. Obsolete local conversation LLM (`Gemma`, `LanguageClient`, `LanguageService`, `litert-lm`, and related AIDL) is completely removed, while preserving local ASR (Sherpa) and local vision (LiteRT).
3. The automatic Gemini cloud fallback policy is established: local commands are prioritized offline; cancellations, negations, and incomplete local intents are rejected on-device; and unresolved conversational queries fall back to Gemini.
4. An explicit, user-initiated storage cleanup action in the Account settings allows reclaiming ~2.6 GB of legacy conversation model artifacts without touching API keys, vision models, or ASR assets.
5. Review stabilization corrections (ARIA-STABILIZE-VOICE-024 / ARIA-STABILIZE-VOICE-025):
   - Eliminated `BIND_AUTO_CREATE` in `HazardSpeechBridge` (flag `0`), ensuring stopped VoiceService cannot be revived or kept alive.
   - Fixed connection leaks by calling `context.unbindService(connection)` inside `onBindingDied`, `onNullBinding`, and late connection arrivals.
   - Binder IPC transactions in `HazardSpeechBridge` are dispatched outside lock monitors.
   - Allocated a new `ServiceConnection` per binding attempt and enforced connection identity plus session generation fencing across all callbacks (`onServiceConnected`, `onServiceDisconnected`, `onBindingDied`, `onNullBinding`), preventing stale callbacks from unbinding or corrupting the active connection.
   - `LegacyConversationModelCleaner` cancels DownloadManager before file deletion, immediately aborts file deletion on cancellation failure, and conservatively fails without clearing retry metadata when storage is inaccessible.
   - Converted cleaner test seams to instance-scoped configuration, eliminating globally mutable state.
   - Decoupled testing architecture: Replaced all Mockito dependencies in unit tests (`HazardSpeechBridgeTest`, `LegacyConversationModelCleanerTest`, `CommandCoordinatorBehaviorTest`) with hand-crafted fakes, dynamic proxies, and test seams, ensuring zero-external-dependency JVM compilation.

---

## Architecture Changes

### 1. Hazard Speech Priority and Preemption
- **IPC Contract**: `IVoiceService.postHazardAlert(session, sequence, timestampNanos, message)` and `speakHazardWarning(message)` provide dedicated one-way priority speech IPC from the camera/vision process to the `:wakeword` process.
- **`HazardSpeechBridge`**:
  - Implements `HazardAlertSink` from `com.ariateam.aria.hazard`.
  - Connects to `HazardAlertCoordinator` via `setSharedAlertSink`.
  - Enforces background liveness: if `AriaRuntimeStateBridge.isVoiceActive(context)` is `false`, hazard alerts are dropped without restarting or reviving VoiceService.
  - Binds using flag `0` (never `BIND_AUTO_CREATE`): attaches only if VoiceService is already running, preventing service revival.
  - Unbinds dead or null connections safely via `context.unbindService(connection)` in `onBindingDied`, `onNullBinding`, and when closed.
  - Executes Binder IPC outside `synchronized(lock)` to prevent thread blocking and priority inversions.
  - Re-uses the existing TTS engine; no duplicate TTS instance is created.
  - Unbinds and releases bindings on lifecycle shutdown.
- **Dedup & Freshness Gating in `CommandCoordinator`**:
  - Rejects stale hazard events older than 2.0 seconds (`timestampNanos`).
  - Filters out duplicate session sequences (`sequence <= lastHazardSequence`).
  - Imposes a 2.5-second cooldown on identical announcement messages.
  - Preempts lower-priority speech (`SpeechPriority.HazardWarning` preempts `CloudResponse` and `NormalCommand`).
  - Mute/vibration behavior: vibrates in all cases; speaks TTS only when `snapshot.audible` is enabled.

### 2. Removal of Obsolete Local Conversation LLM
- Removed files:
  - `app/src/main/aidl/com/ariateam/aria/speech/ILanguageService.aidl`
  - `app/src/main/aidl/com/ariateam/aria/speech/ILanguageCallback.aidl`
  - `app/src/main/java/com/ariateam/aria/command/LanguageService.kt`
  - `app/src/main/java/com/ariateam/aria/command/LanguageClient.kt`
  - `app/src/main/java/com/ariateam/aria/command/GemmaModelStore.kt`
  - `app/src/main/java/com/ariateam/aria/command/ModelCommandParser.kt`
  - `app/src/main/assets/licenses/litertlm_third_party_notice.txt`
  - `LanguageClientTest.kt`, `ModelCommandParserTest.kt`
- Removed `<service android:name=".command.LanguageService" ... />` from `AndroidManifest.xml` (coordinated with Peer A).
- Removed dependency `libs.litert.lm` (`com.google.ai.edge.litertlm:litertlm-android`) from `app/build.gradle.kts` and `gradle/libs.versions.toml`.
- Preserved local vision dependencies (`libs.litert`, `libs.litert.gpu`, `libs.litert.gpu.api`) and local ASR (`sherpa-onnx`).
- Updated `StartupPolicy` and `StartupPolicyTest`: startup no longer gates on Gemma initialization or download.

### 3. Automatic Gemini Fallback & Local Command Prioritization
- **Policy Enforcement in `LocalFallbackPolicy`**:
  1. Valid local offline commands (navigation, Home/Settings, screen hide/show, sound control, status, help, stop, cancel) execute locally.
  2. Utterances indicating cancellation (`취소`, `그만`, `멈춰`, `중지`, `안해`, etc.) are recognized locally and announce `"취소했어요."` without cloud upload.
  3. Utterances with negation (`하지마`, `하지 마`, `안내하지 마`, `열지 마`, etc.) are rejected locally and prompt `"원하는 명령을 하나만 다시 말해 주세요."` without cloud upload.
  4. Incomplete command fragments (`홈`, `설정`, `화면`, `소리`, `안내`, `지도`, `길안내`, etc.) are clarified locally without cloud upload.
  5. Genuine conversational questions (e.g., `"오늘 서울 날씨 어때?"`, `"세종대왕에 대해 알려줘"`) route to `CloudQuestionCoordinator` for Gemini processing.

### 4. Account Settings UX & Legacy Model Cleanup
- Added policy disclosure in `AccountSettings` explaining local command priority and safe cloud fallback.
- Added explicit one-time legacy model storage cleanup action backed by `LegacyConversationModelCleaner`:
  - Detects legacy files in `DIRECTORY_DOWNLOADS`: `gemma-e2b.litertlm`, `gemma.pending`, `gemma.verified`, and `gemma_download` preferences.
  - Confirms via AlertDialog before deletion.
  - Strictly preserves API key (`aria_keys`), Sherpa models, vision models, and body mount profile.

### 5. Licenses and Documentation Alignment
- Updated `LicenseRepository.kt`, `LicenseItem.kt`, `LicenseHtmlDocument.kt`, `LicenseRepositoryTest.kt`, `THIRD_PARTY_NOTICES.md`, and `README.md` to remove `litert-lm` and `gemma-model` entries while preserving all legally required notices.

---

## Test Inventory

### JVM Unit Tests
1. `com.ariateam.aria.command.LocalFallbackPolicyTest`
   - `cancellationUtterances_areRejectedBeforeCloudFallback`
   - `negationUtterances_areRejectedBeforeCloudFallback`
   - `incompleteLocalIntents_areRejectedBeforeCloudFallback`
   - `conversationalUtterances_areAllowedToFallBackToGemini`
   - `blankUtterance_isSafelyRejected`
2. `com.ariateam.aria.command.LegacyConversationModelCleanerTest`
   - `hasLegacyArtifacts_returnsFalseWhenDirectoryEmptyAndNoPrefs`
   - `hasLegacyArtifacts_detectsModelFileAndCalculatesSize`
   - `hasLegacyArtifacts_detectsPendingAndMarkerFiles`
   - `hasLegacyArtifacts_detectsActiveDownloadPref`
   - `deleteLegacyArtifacts_deletesOnlyLegacyFilesAndPreservesOtherFiles`
   - `hasLegacyArtifacts_detectsDownloadIdEvenWhenExternalDirIsNull`
   - `deleteLegacyArtifacts_cancelsDownloaderBeforeFiles_andPreservesPrefAndFilesOnFailure`
   - `deleteLegacyArtifacts_inaccessibleStorageNeverReportsSuccess_andPreservesMetadata`
   - `deleteLegacyArtifacts_cancelsDownloaderBeforeDeletingFiles`
3. `com.ariateam.aria.command.CommandCoordinatorBehaviorTest`
   - `postHazardAlert_rejectsStaleAlertsOlderThan2Seconds`
   - `postHazardAlert_acceptsFreshAlertAndVibratesAndEmits`
   - `postHazardAlert_deduplicatesIdenticalMessageWithinCooldown`
   - `postHazardAlert_whenMuted_vibratesWithoutAudio`
   - `finalized_localCommand_executesLocallyWithoutCloudQuestion`
   - `finalized_cancellation_rejectsLocallyWithoutCloudQuestion`
   - `finalized_negation_rejectsLocallyWithoutCloudQuestion`
   - `finalized_conversationalQuery_fallsBackToGeminiCloudQuestion`
4. `com.ariateam.aria.speech.HazardSpeechBridgeTest`
   - `start_bindsWithFlagZero_neverAutoCreate`
   - `onHazardAlert_whenVoiceActive_forwardsToVoiceService`
   - `onHazardAlert_whenVoiceStopped_doesNotDeliverOrReviveVoice`
   - `onBindingDied_unbindsConnectionToPreventLeak`
   - `onNullBinding_unbindsConnectionToPreventLeak`
   - `stopStart_allocatesNewConnection_andRejectsStaleConnectedCallbackFromPreviousGeneration`
   - `stopStart_rejectsStaleBindingDiedFromPreviousGeneration`
   - `stopStart_rejectsStaleNullBindingFromPreviousGeneration`
   - `attachShared_registersBridgeInHazardAlertCoordinator`
5. `com.ariateam.aria.startup.StartupPolicyTest`
   - `startupDoesNotBlockOnObsoleteLanguageStatus_returnsReady`
6. `com.ariateam.aria.licenses.LicenseRepositoryTest`
   - Verified removal of `litert-lm` and `gemma-model` and presence of remaining dependencies.

### Android Instrumented Tests
1. `com.ariateam.aria.speech.HazardSpeechIntegrationDeviceTest`
   - `hazardAlertCoordinator_dispatchesToRegisteredHazardSpeechBridge`
   - `hazardSpeechBridge_doesNotRestartStoppedVoiceService`
2. `com.ariateam.aria.command.ModelCommandValidationTest`
   - `voiceSnapshotCarriesRecoveryStateThroughTheSameCallbackBundle`
   - `snapshotRoundTripPreservesNavigationAndPersistedSoundState`
