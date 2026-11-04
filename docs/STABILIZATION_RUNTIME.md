# Aria Runtime and Vision Stabilization — 2026-11-04

Task ID: `ARIA-STABILIZE-RUNTIME-027` (`task_024ba3d9d32d`)  
Component Ownership: `runtime/**`, `speech/**` (narrowly reconciliation and IVoiceService.aidl), `AndroidManifest.xml`, related tests.

---

## 1. Executive Summary

This stabilization pass resolves critical static review findings in the camera lifecycle, foreground service coordination, cross-process state bridge, and hazard alert dispatching, including provider restart reconciliation session corruption and bounded binding lifecycle:

1. **Filtered Camera Availability & Single Retry Per Attempt**: Resolved CameraManager availability callbacks to the specific back camera (`resolveSelectedCameraId()`), handling initial registration callbacks without bypassing backoff, enforcing a single retry per attempt via atomic token fencing, and canceling retries upon explicit stop, permission loss, or shutdown.
2. **Session-Aware Runtime Bridge with IBinder Death Reconciliation**: Replaced raw volatile boolean flags with explicit session IDs and IBinder tokens in `AriaRuntimeStateBridge` and `AriaRuntimeProvider`. When a service process (`:wakeword` or main) crashes or terminates, `IBinder.DeathRecipient` immediately clears active status. Late stop updates from superseded sessions are ignored, and provider process restarts never revive stopped services.
3. **Provider Restart Reconciliation without Session Corruption**: Removed fabricated session timestamps (`voice_reconciled_*`). Session identity is strictly service-owned via explicit AIDL IPC (`IVoiceService.getServiceSessionId()`). Genuine stops from `VoiceService` are never rejected after reconciliation. Furthermore, `IVoiceService.isServiceActive()` prevents bound idle services from falsely asserting active status.
4. **Bounded Reconciliation Binding with Late Callback Fencing**: Wrapped provider reconciliation in an atomic `ReconcileConnection` with a single-execution cleanup guarantee. Cleans up exactly once on `onServiceConnected`, `onNullBinding`, `onBindingDied`, `onServiceDisconnected`, `bindFailed`, or daemon-scheduled timeout (default 3000ms). Late callbacks arriving after cleanup are strictly fenced out.
5. **Notification State Tuple Deduplication**: Added `NotificationStateTuple(voiceActive, visionActive, cameraUnavailable)` tracking in `AriaUnifiedNotificationCoordinator`. Persistent notification updates are deduplicated, posting to `NotificationManager` only when the active state tuple changes.
6. **Reliable Whole-Stop Termination**: Updated `AriaUnifiedStopReceiver` to terminate services using `context.stopService(...)` (avoiding background `startService` exceptions on Android 8+/12+/14+). Inactive status is acknowledged only when lifecycle cleanup (`onDestroy()`) or binder death confirms termination.
7. **Shared Hazard Event Sink**: Exposed `HazardAlertSink` and `HazardAlertEvent(session, sequence, timestampNanos, message, level)` through `HazardAlertCoordinator`. Callbacks are evaluated against confirmation frames, cooldown, and freshness, and delivered strictly outside synchronization locks without UI dependencies.

---

## 2. Architecture & Coordination Flow

```mermaid
flowchart TD
    subgraph Vision Pipeline & Hazard
        VPC["VisionPipelineController\n(Main Process)"]
        HAC["HazardAlertCoordinator\n(Main Process)"]
        HAP["HazardAlertPolicy\n(Confirmation & Cooldown)"]
        HAS["HazardAlertSink\n(External Speech Sink)"]
    end

    subgraph Cross-Process Runtime Bridge
        ARP["AriaRuntimeProvider\n(ContentProvider, initOrder=100)"]
        ARSB["AriaRuntimeStateBridge\n(Session & Binder Death)"]
        AUNC["AriaUnifiedNotificationCoordinator\n(State Tuple Deduplication)"]
        AUSR["AriaUnifiedStopReceiver\n(stopService, no startService)"]
    end

    subgraph Voice Service
        VS["VoiceService\n(:wakeword Process)"]
        HSB["HazardSpeechBridge\n(Binds HazardAlertSink)"]
    end

    VPC -->|Frames| HAC
    HAC -->|Evaluates| HAP
    HAC -->|Dispatches outside lock| HAS
    HSB -.->|Implements| HAS
    HSB -->|Speech queue| VS

    VS -->|setVoiceActive(token, sessionId)| ARSB
    VPC -->|setVisionActive(token, sessionId)| ARSB
    ARSB <--> ARP
    ARSB -->|On State Change| AUNC
    AUSR -->|stopService| VS
    AUSR -->|requestStop| VPC
```

---

## 3. Detailed Component Improvements

### 3.1 Camera Availability Filtering and Attempt Fencing
- **Problem**: Previously, `VisionPipelineController` registered an `AvailabilityCallback` that responded to *any* camera becoming available (such as front camera or external camera). Furthermore, Android delivers initial status callbacks upon registration, which could prematurely trigger retry before the backoff interval expired.
- **Fix**:
  - `resolveSelectedCameraId()` queries `CameraManager` for `CameraCharacteristics.LENS_FACING_BACK` (and falls back to CameraX `c2Info.cameraId`).
  - `AvailabilityCallback` filters strictly on `cameraId == targetCameraId`.
  - `isInitialCallback` flag discards registration-time availability status, preventing backoff bypass.
  - `retryAttemptGeneration` atomic counter and `AtomicBoolean(false)` ensure exactly one retry execution per attempt between the delayed runnable and availability events.
  - `cancelScheduledRetry()` is called on `stopCamera()`, `permissionDenied()`, `failCameraSession()`, and `close()`.

### 3.2 Cross-Process State Bridge & Liveness Reconciliation
- **Problem**: `AriaRuntimeStateBridge` previously stored volatile booleans in process memory. If `:wakeword` died or was killed by the OS, the main process still reported voice active, leading to phantom notification state. Incidental queries or late stop callbacks from previous sessions could also corrupt state. Furthermore, passing dead tokens or failures in `linkToDeath` could leave the bridge in a phantom active state without death notifications.
- **Fix**:
  - `VoiceService` and `VisionForegroundService` generate unique per-service-instance session IDs (`voice_<timestamp>_<uuid>` and `vision_<timestamp>_<startId>_<uuid>`) and supply their lifetime IBinder tokens (`binder` and `serviceLifetimeToken = Binder()`) on every registration.
  - `handleSetVoiceActiveInternal` and `handleSetVisionActiveInternal` atomically validate token liveness (`token.isBinderAlive && token.pingBinder()`) and perform `token.linkToDeath(recipient, 0)`. If linking fails or the token is already dead, registration is atomically rejected without mutating active state or session IDs.
  - Death callbacks record both `sessionId` and exact `deadToken`, ignoring stale callbacks if the token has been replaced by a newer session.
  - Late stop callbacks with stale session IDs or superseded tokens are rejected.
  - **Provider Restart & Voice Reconciliation (027 Fix)**:
    - Removed fabricated session timestamps (`voice_reconciled_*`).
    - Added `boolean isServiceActive()` and `String getServiceSessionId()` to `IVoiceService.aidl`. When reconciling with an already running service, session identity is queried directly from `VoiceService`, preventing late stop rejection when the genuine session later stops.
    - Idle services (`state.isActive == false`) bound by other clients (e.g. `VoiceViewModel`) are not falsely inferred as active despite binder liveness.
    - Encapsulated lifecycle in `ReconcileConnection`: bounded cleanup executes exactly once across `onServiceConnected`, `onNullBinding`, `onBindingDied`, `onServiceDisconnected`, `bindFailed`, and timeout (default 3000ms daemon executor). Late callbacks after cleanup or timeout are strictly fenced out.
    - Zero `onCreate` blocking: timeout scheduling and cleanup are completely non-blocking.
  - **Camera Unavailable Preservation**: `AriaUnifiedNotificationCoordinator.updateUnifiedNotification(...)` accepts `cameraUnavailable: Boolean? = null`. When `null`, it preserves the active vision session's `cameraUnavailable` state across voice transitions, preventing transient voice status changes from masking camera contention warnings.

### 3.3 Notification State Tuple Deduplication
- **Problem**: Rapid frame updates in `VisionForegroundService.watchSession()` repeatedly invoked `NotificationManager.notify()`, causing binder transaction thrashing and battery consumption.
- **Fix**:
  - Defined `NotificationStateTuple(voiceActive: Boolean, visionActive: Boolean, cameraUnavailable: Boolean)`.
  - `AriaUnifiedNotificationCoordinator.updateUnifiedNotification(...)` compares the new tuple against `lastPostedTuple`. If identical, it returns `false` without posting to `NotificationManager`.
  - If both services become inactive, `NotificationManager.cancel(NOTIFICATION_ID)` is called.

### 3.4 Whole-Stop Consistency
- **Problem**: `AriaUnifiedStopReceiver` previously invoked `context.startService(Intent(context, VoiceService::class.java).setAction(ACTION_STOP))`, which throws `ForegroundServiceStartNotAllowedException` on Android 12+ when called from background receivers, then immediately set both services inactive in the bridge despite failed stop delivery.
- **Fix**:
  - Replaced `startService` with `context.stopService(Intent(context, VoiceService::class.java))`.
  - `VisionForegroundService.requestStop(context)` similarly uses `stopService`.
  - `AriaUnifiedStopReceiver` no longer prematurely overwrites bridge status; active flags are set to `false` only when the service lifecycles (`onDestroy()`) execute or binder death triggers.

### 3.5 Shared Hazard Interface
- **Problem**: Hazard alerts were restricted to haptics and transient Toasts with no decoupled integration point for speech or external systems.
- **Fix**:
  - Defined `data class HazardAlertEvent(val session: Long, val sequence: Long, val timestampNanos: Long, val message: String, val level: HazardLevel)`.
  - Defined `fun interface HazardAlertSink { fun onHazardAlert(event: HazardAlertEvent) }`.
  - In `HazardAlertCoordinator`, `alertSequence` increments monotonically per alert in the session.
  - The sink is invoked safely outside `synchronized(lock)` after policy confirmation, cooldown, and freshness checks pass.
  - Exceptions inside the sink are caught and logged, preventing external failures from affecting vision processing.

---

## 4. Test Coverage

- `HazardAlertCoordinatorLifecycleTest.kt`:
  - `testHazardAlertSink_receivesMonotonicSequenceAndCorrectEvent`: Verifies session ticket, sequence increment (1, 2, ...), timestamps, and Korean text.
  - `testHazardAlertSink_exceptionDoesNotCrashOrCorruptState`: Verifies exception containment.
  - `testSharedAlertSink_invokedWhenInstanceSinkIsNull`: Verifies process-wide shared sink injection.
  - Existing lifecycle tests: session boundary rejection, confirmation requirement, toast expiry.
- `CameraRecoveryAndNotificationTest.kt`:
  - `selectedCameraAvailabilityFiltering_ignoresOtherCamerasAndOnlyTriggersForTarget`: Verifies filtering of non-selected camera IDs and initial callback handling.
  - `singleRetryPerAttempt_fencesConcurrentDelayAndAvailabilityEvents`: Verifies single retry execution per attempt.
  - `notificationDeduplication_suppressesDuplicatePostsForIdenticalStateTuple`: Verifies state tuple equality and change detection.
- `AriaRuntimeStateBridgeTest.kt`:
  - `sessionRegistration_tracksVoiceAndVisionSessionsIndependently`: Verifies distinct session tracking.
  - `lateSessionUpdate_isIgnoredWhenNewerSessionIsActive`: Verifies that late stop from older session does not deactivate newer session.
  - `binderDeathReconciliation_clearsActiveStateWhenProcessDies`: Verifies IBinder death recipient callback.
  - `visionBinderDeathReconciliation_clearsVisionActiveState`: Verifies vision binder death handling.
  - `providerRestartReconciliation_defaultsToInactiveWithoutServiceRevival`: Verifies cold provider start behavior.
  - `notificationDeduplication_returnsFalseWhenStateTupleIsUnchanged`: Verifies notification deduplication logic.
  - `rejectRegistration_whenTokenIsAlreadyDead`: Verifies atomic rejection when token `isBinderAlive` is false.
  - `rejectRegistration_whenLinkToDeathThrowsRemoteException`: Verifies atomic rejection when `linkToDeath` fails.
  - `staleDeathCallback_afterTokenReplacement_doesNotClearNewActiveSession`: Verifies that death callback from a replaced token does not clear the new session.
  - `staleStopCallback_withDifferentToken_isIgnored`: Verifies that a stop call with a superseded token is ignored.
  - `cameraUnavailable_preservedAcrossVoiceActiveChanges`: Verifies preservation of `cameraUnavailable` warning state during voice state changes.
  - `reconciliation_followedByActualSessionStop_clearsState`: Verifies that reconciliation preserves the service-owned genuine sessionId (no `voice_reconciled_*`), allowing subsequent genuine session stop to be accepted and cleanly clear bridge state.
  - `alreadyBoundService_reRegistersAccuratelyWithoutOnBind`: Verifies that an already-bound service re-registers accurately via explicit AIDL IPC even when Android framework does not fire `onBind()` again.
  - `boundIdleService_doesNotInferActiveFromAliveBinder`: Verifies that a bound service in idle/stopped state is not inferred as active just because the binder is alive.
  - `boundedCleanup_onNullBinding_cleansUpExactlyOnceAndFencesLateCallback`: Verifies single cleanup execution on null binding and late callback fencing.
  - `boundedCleanup_onBindingDied_cleansUpExactlyOnceAndFencesLateCallback`: Verifies single cleanup execution on binding died.
  - `boundedCleanup_onTimeout_cleansUpExactlyOnceAndFencesLateConnected`: Verifies timeout cleanup and fencing of late `onServiceConnected` callbacks.
  - `boundedCleanup_onBindFailed_cleansUpImmediately`: Verifies immediate cleanup when bind returns false.
  - `boundedCleanup_onServiceDisconnected_cleansUpExactlyOnce`: Verifies single cleanup on service disconnect.

---

## 5. ARIA-STABILIZE-RELEASE-028: Final Stop Semantics & Preview Artifact Identity

Task ID: `ARIA-STABILIZE-RELEASE-028` (`task_d35e7b0306b6`)  
Ownership: `runtime/AriaUnifiedStopReceiver.kt`, `speech/VoiceService.kt`, `runtime/AriaUnifiedStopReceiverTest.kt`, `app/build.gradle.kts`.

### 5.1 Bound-Service Stop Semantics via Flag 0 IPC
- **Problem**: When `VoiceViewModel` (or any UI client) binds to `VoiceService` with `BIND_AUTO_CREATE`, calling `context.stopService(...)` alone does not destroy the service. Android keeps the service alive in memory until all bindings unbind, leaving microphone capture, Sherpa/Android wake-word listening, wakelocks, and TTS running in the background despite the user pressing Stop in the persistent notification.
- **Fix**:
  - `AriaUnifiedStopReceiver` now initiates bound-service IPC using flag `0` (never `BIND_AUTO_CREATE`), ensuring it only attaches to an already-running service without ever auto-creating one.
  - When connected, it invokes `IVoiceService.stop()` (an existing one-way IPC method).
  - Inside `VoiceService`, `requestStop()` executes on the main thread: synchronously stopping `VoiceListeningSession`, releasing the wakeLock, publishing `VoicePhase.Stopping` -> `VoicePhase.Stopped`, setting `AriaRuntimeStateBridge.setVoiceActive(..., false)`, detaching/canceling the notification, and calling `stopSelf()`.
  - The connection is managed by `VoiceStopConnection` with atomic single-execution cleanup across `onServiceConnected`, `onNullBinding`, `onBindingDied`, `onServiceDisconnected`, `bindFailed`, or timeout (default 2000ms daemon executor).
  - Unbinding occurs only if bound (`isBound == true`), preventing `IllegalArgumentException` ("Service not registered") if `bindService` returned false.
  - `context.stopService(...)` fallback is guaranteed to execute upon cleanup to clear the service's started state.
  - Uses `goAsync()` so the broadcast receiver awaits asynchronous IPC cleanup safely.
  - Does NOT unilaterally report false inactive in the bridge; authentic inactive status is driven by `VoiceService.releaseResources()` or binder death.

### 5.2 Prevention of Stop Resurrection via Provider Reconciliation
- **Problem**: Previously, `VoiceService.isServiceActive()` directly returned `state.isActive`. Because `VoicePhase.Stopping` was not in `[Stopped, Error]`, `state.isActive` evaluated to `true`. If provider reconciliation or client binding occurred while the service was in `Stopping`, reconciliation observed `isServiceActive == true` and called `setVoiceActive(..., true)`, inadvertently undoing the user's stop request and resurrecting active status.
- **Fix**:
  - Added `isEffectivelyActive: Boolean get() = state.isActive && state.phase != VoicePhase.Stopping` in `VoiceService.kt`.
  - `IVoiceService.isServiceActive()` now returns `isEffectivelyActive` (reporting `false` for `Stopping`).
  - `VoiceService.onBind()` only registers `setVoiceActive(true)` when `isEffectivelyActive` is true.
  - Provider reconciliation now maintains inactive status when observing a stopping service.

### 5.3 Preview Artifact Identity
- Bumped release identity in `app/build.gradle.kts`:
  - `versionCode = 4`
  - `versionName = "1.0.0-preview.20261104.2"`

### 5.4 Hardware Limitations & Scope
- **Hardware Limitations**: On physical Android devices, hardware sensor release (Camera2 device HAL close, microphone audio record HAL release, native Sherpa-ONNX model unload, and kernel wakelock release) depends on OEM driver timing and Android AudioFlinger/CameraService teardown latency. Unit tests verify the IPC stop contract, bounded connection lifecycle, and late-callback fencing; physical hardware performance and recovery verification are excluded per repository guidelines for headless test execution.

### 5.5 Test Coverage for 028
- `AriaUnifiedStopReceiverTest.kt`:
  - `boundVoiceService_invokesStopIpc_andUnbindsCleanly`: Verifies that `IVoiceService.stop()` is invoked when UI clients are bound, unbinds cleanly, and invokes fallback stop.
  - `serviceNotRunning_bindReturnsFalse_invokesFallbackStopWithoutUnbind`: Verifies that flag 0 bind failure immediately cleans up without unbind and invokes fallback stopService.
  - `nullBinding_cleansUpAndInvokesFallback`: Verifies single cleanup and fallback on null binding.
  - `bindingDied_cleansUpAndInvokesFallback`: Verifies single cleanup and fallback on binding death.
  - `serviceDisconnected_cleansUpAndInvokesFallback`: Verifies single cleanup on service disconnect.
  - `timeout_cleansUpAndFencesLateCallback`: Verifies daemon timeout cleanup and strict fencing of late `onServiceConnected` callbacks.
  - `multipleCallbacks_cleanupExecutesExactlyOnce`: Verifies atomic single-execution cleanup despite multiple lifecycle events.
  - `stoppingPhase_isServiceActiveReturnsFalse_preventsProviderReconciliationResurrection`: Verifies that `VoicePhase.Stopping` reports `isServiceActive == false` and prevents provider reconciliation from undoing user stop.

