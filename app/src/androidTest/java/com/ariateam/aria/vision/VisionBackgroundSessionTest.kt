package com.ariateam.aria.vision

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.MainActivity
import com.ariateam.aria.startup.StartupPermissionInventory
import com.ariateam.aria.startup.StartupViewModel
import com.ariateam.aria.vision.debug.GpuDiagnosticOverlayActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionBackgroundSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<*>? = null
    private var startedLifecycleOwner: StartedLifecycleOwner? = null
    private var diagnosticController: VisionPipelineController? = null
    private var originallyStoppedByUser = false

    @Before
    fun prepareVisibleHome() {
        val permissions = StartupPermissionInventory.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        permissions.forEach(::grantRuntimePermission)
        originallyStoppedByUser = VisionSession.requiresExplicitRestart(context)

        // Ensure any lingering shared background service and controller from prior runs or tests are cleaned up
        instrumentation.runOnMainSync {
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
            VisionSession.clearExplicitStop(context)
        }
        instrumentation.waitForIdleSync()

        if (!context.getSystemService(PowerManager::class.java).isInteractive) {
            executeShellCommand("input keyevent KEYCODE_WAKEUP")
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) executeShellCommand("wm dismiss-keyguard")
        assertTrue(
            "Pixel screen must be awake before launching Home",
            context.getSystemService(PowerManager::class.java).isInteractive,
        )
        assertTrue("Test requires a dismissible, unlocked keyguard", !keyguard.isKeyguardLocked)
    }

    @After
    fun stopSessionAndCloseActivity() {
        instrumentation.runOnMainSync {
            diagnosticController?.close()
            diagnosticController = null
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
        }
        scenario?.close()
        scenario = null
        startedLifecycleOwner?.let { owner ->
            instrumentation.runOnMainSync { owner.destroy() }
        }
        startedLifecycleOwner = null
        instrumentation.waitForIdleSync()
        if (originallyStoppedByUser) {
            VisionSession.markExplicitStop(context)
        } else {
            VisionSession.clearExplicitStop(context)
        }
    }

    @Test
    fun analyzerDelivery_comparesResumedActivityAndStartedLifecycleOwners() {
        // Stop any automatic shared session first
        VisionSession.markExplicitStop(context)
        context.stopService(Intent(context, VisionForegroundService::class.java))
        VisionSession.closeController()

        // Launch neutral diagnostic activity to isolate direct test controller from MainActivity auto-start
        val sc = ActivityScenario.launch(GpuDiagnosticOverlayActivity::class.java).also { scenario = it }
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)
        sc.onActivity { activity: GpuDiagnosticOverlayActivity ->
            // Clear explicit stop latch only when deliberately starting the diagnostic controller
            VisionSession.clearExplicitStop(activity.applicationContext)
            val controller = VisionPipelineController(
                context = activity.applicationContext,
                modelStore = VisionModelStore(activity.applicationContext, restoreOnInit = false),
            )
            controllerRef[0] = controller
            diagnosticController = controller
            controller.startCamera(
                lifecycleOwner = activity,
                surfaceProvider = null,
                mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                targetRotation = Surface.ROTATION_0,
            )
        }

        val controller = requireNotNull(controllerRef[0])
        val activitySensorBaseline = controller.diagnosticSnapshot().motionSensorCallbacks
        val activityDeliveredFrames = awaitState(30_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            diagnostics.lifecycleOwner == "GpuDiagnosticOverlayActivity" &&
                diagnostics.lifecycleState == "RESUMED" &&
                diagnostics.cameraStateType == "OPEN" &&
                diagnostics.cameraError == null &&
                diagnostics.analysisBound &&
                !diagnostics.previewBound &&
                diagnostics.analyzerEntries >= 3L &&
                diagnostics.pipelineInputs >= 3L &&
                diagnostics.inferenceCallEntries >= 3L &&
                diagnostics.inferenceReturns >= 3L &&
                diagnostics.completedFrames >= 3L &&
                diagnostics.imageProxyCloses >= 3L &&
                diagnostics.cameraCaptureCallbacks > 0L &&
                diagnostics.motionSensorCallbacks > activitySensorBaseline
        }
        val activityDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "RESUMED Activity control: $activityDiagnostics")
        assertTrue(
            "RESUMED Activity control did not deliver all expected camera/sensor stages: $activityDiagnostics",
            activityDeliveredFrames,
        )
        assertEquals("RESUMED", activityDiagnostics.lifecycleState)
        assertTrue("Analysis-only control must not bind Preview: $activityDiagnostics", !activityDiagnostics.previewBound)

        val startedOwner = StartedLifecycleOwner()
        startedLifecycleOwner = startedOwner
        val startedSensorBaseline = activityDiagnostics.motionSensorCallbacks
        sc.onActivity {
            startedOwner.start()
            controller.startCamera(
                lifecycleOwner = startedOwner,
                surfaceProvider = null,
                mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                targetRotation = Surface.ROTATION_0,
            )
        }

        val startedDeliveredFrames = awaitState(30_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            diagnostics.lifecycleOwner == "StartedLifecycleOwner" &&
                diagnostics.lifecycleState == "STARTED" &&
                diagnostics.cameraStateType == "OPEN" &&
                diagnostics.cameraError == null &&
                diagnostics.analysisBound &&
                !diagnostics.previewBound &&
                diagnostics.analyzerEntries >= 3L &&
                diagnostics.pipelineInputs >= 3L &&
                diagnostics.inferenceCallEntries >= 3L &&
                diagnostics.inferenceReturns >= 3L &&
                diagnostics.completedFrames >= 3L &&
                diagnostics.imageProxyCloses >= 3L &&
                diagnostics.cameraCaptureCallbacks > 0L &&
                diagnostics.motionSensorCallbacks > startedSensorBaseline
        }
        val startedDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "STARTED lifecycle control: $startedDiagnostics")
        assertTrue(
            "STARTED lifecycle-owner control did not deliver all expected camera/sensor stages: $startedDiagnostics",
            startedDeliveredFrames,
        )
        assertEquals("STARTED", startedDiagnostics.lifecycleState)
        assertTrue("Analysis-only control must not bind Preview: $startedDiagnostics", !startedDiagnostics.previewBound)
    }

    @Test
    fun framesContinueWhileModelRestoreIsPending_thenInferenceResumesAfterRestore() {
        // Stop any automatic shared session first
        VisionSession.markExplicitStop(context)
        context.stopService(Intent(context, VisionForegroundService::class.java))
        VisionSession.closeController()

        // Launch neutral diagnostic activity to isolate direct test controller from MainActivity auto-start
        val sc = ActivityScenario.launch(GpuDiagnosticOverlayActivity::class.java).also { scenario = it }
        val restoreCompleted = MutableStateFlow(false)
        val modelState = MutableStateFlow<VisionModelState>(VisionModelState.Loading("test model"))
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)
        sc.onActivity { activity: GpuDiagnosticOverlayActivity ->
            // Clear explicit stop latch only when deliberately starting the diagnostic controller
            VisionSession.clearExplicitStop(activity.applicationContext)
            val controller = VisionPipelineController(
                context = activity.applicationContext,
                modelStore = VisionModelStore(
                    context = activity.applicationContext,
                    restoreOnInit = false,
                    mutableState = modelState,
                    mutableInitialRestoreCompleted = restoreCompleted,
                ),
            )
            controllerRef[0] = controller
            diagnosticController = controller
            controller.startCamera(
                lifecycleOwner = activity,
                surfaceProvider = null,
                mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                targetRotation = Surface.ROTATION_0,
            )
        }

        val controller = requireNotNull(controllerRef[0])
        val sensorBaseline = controller.diagnosticSnapshot().motionSensorCallbacks
        val pendingFramesDelivered = awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            !diagnostics.modelRestoreComplete &&
                diagnostics.modelState == "Loading" &&
                diagnostics.lifecycleOwner == "GpuDiagnosticOverlayActivity" &&
                diagnostics.lifecycleState == "RESUMED" &&
                diagnostics.analyzerEntries >= 3L &&
                diagnostics.pipelineInputs >= 3L &&
                diagnostics.inferenceCallEntries == 0L &&
                diagnostics.inferenceReturns == 0L &&
                diagnostics.completedFrames >= 3L &&
                diagnostics.imageProxyCloses >= 3L &&
                diagnostics.cameraCaptureCallbacks > 0L &&
                diagnostics.motionSensorCallbacks > sensorBaseline
        }
        val pendingDiagnostics = controller.diagnosticSnapshot()
        val pendingState = controller.uiState.value
        Log.i(TAG, "Frames while model restore pending: $pendingDiagnostics")
        assertTrue("Frames must complete without waiting for model restore: $pendingDiagnostics", pendingFramesDelivered)
        assertTrue(
            "Hazard state must identify pending model restore: ${pendingState.hazardAssessment}",
            pendingState.hazardAssessment.messageKorean.contains("모델 복원 중"),
        )
        assertTrue(
            "Distance output must retain a fresh frame timestamp while degraded: ${pendingState.groundContactDistance}",
            pendingState.groundContactDistance.timestampNanos > 0L,
        )

        SystemClock.sleep(750L)
        val continuedDiagnostics = controller.diagnosticSnapshot()
        assertTrue(
            "Analyzer, close, and IMU counters must advance during pending restore: $continuedDiagnostics",
            continuedDiagnostics.analyzerEntries > pendingDiagnostics.analyzerEntries &&
                continuedDiagnostics.imageProxyCloses > pendingDiagnostics.imageProxyCloses &&
                continuedDiagnostics.motionSensorCallbacks > pendingDiagnostics.motionSensorCallbacks,
        )

        modelState.value = VisionModelState.Missing
        restoreCompleted.value = true
        val inferenceResumed = awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            diagnostics.modelRestoreComplete &&
                diagnostics.modelState == "Missing" &&
                diagnostics.inferenceCallEntries >= 3L &&
                diagnostics.inferenceReturns >= 3L &&
                diagnostics.completedFrames >= continuedDiagnostics.completedFrames + 3L &&
                diagnostics.imageProxyCloses >= continuedDiagnostics.imageProxyCloses + 3L
        }
        val restoredDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "Frames after model restore completes: $restoredDiagnostics")
        assertTrue("Frame processing must resume inference calls after restore: $restoredDiagnostics", inferenceResumed)
    }

    @Test
    fun framesFlowWhileInferenceExecutorIsBlocked_thenResumeAfterUnblock() {
        // Stop any automatic shared session first
        VisionSession.markExplicitStop(context)
        context.stopService(Intent(context, VisionForegroundService::class.java))
        VisionSession.closeController()

        val sc = ActivityScenario.launch(GpuDiagnosticOverlayActivity::class.java).also { scenario = it }
        val restoreCompleted = MutableStateFlow(false)
        val modelState = MutableStateFlow<VisionModelState>(VisionModelState.Loading("blocked model"))
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)
        val blockLatch = CountDownLatch(1)
        val threadRunningLatch = CountDownLatch(1)

        sc.onActivity { activity: GpuDiagnosticOverlayActivity ->
            VisionSession.clearExplicitStop(activity.applicationContext)
            val store = VisionModelStore(
                context = activity.applicationContext,
                restoreOnInit = false,
                mutableState = modelState,
                mutableInitialRestoreCompleted = restoreCompleted,
            )
            // Block the segmenter's inference executor completely to simulate long native GPU startup
            store.segmenter.pipelineExecutor.execute {
                threadRunningLatch.countDown()
                blockLatch.await(30, TimeUnit.SECONDS)
            }
            val controller = VisionPipelineController(
                context = activity.applicationContext,
                modelStore = store,
            )
            controllerRef[0] = controller
            diagnosticController = controller
            controller.startCamera(
                lifecycleOwner = activity,
                surfaceProvider = null,
                mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                targetRotation = Surface.ROTATION_0,
            )
        }

        assertTrue("Inference executor should be blocked", threadRunningLatch.await(5, TimeUnit.SECONDS))
        val controller = requireNotNull(controllerRef[0])
        val pendingFramesDelivered = awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            !diagnostics.modelRestoreComplete &&
                diagnostics.modelState == "Loading" &&
                diagnostics.analyzerEntries >= 3L &&
                diagnostics.completedFrames >= 3L &&
                diagnostics.imageProxyCloses >= 3L &&
                diagnostics.inferenceCallEntries == 0L &&
                diagnostics.inferenceReturns == 0L
        }
        val pendingDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "Frames while inference executor blocked: $pendingDiagnostics")
        assertTrue("Frames must progress even while inference executor is blocked: $pendingDiagnostics", pendingFramesDelivered)

        // Unblock the inference executor and complete restore
        blockLatch.countDown()
        modelState.value = VisionModelState.Missing
        restoreCompleted.value = true

        val inferenceResumed = awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            diagnostics.modelRestoreComplete &&
                diagnostics.inferenceCallEntries >= 3L &&
                diagnostics.inferenceReturns >= 3L
        }
        val resumedDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "Inference resumed after unblock: $resumedDiagnostics")
        assertTrue("Inference must resume once executor unblocked: $resumedDiagnostics", inferenceResumed)
    }

    @Test
    fun homeBackgroundAndReturn_keepsOneCameraSessionUntilExplicitStop() {
        // Ensure clean session baseline before Home launch
        context.stopService(Intent(context, VisionForegroundService::class.java))
        VisionSession.closeController()
        VisionSession.clearExplicitStop(context)

        val sc = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        val originalController = arrayOfNulls<VisionPipelineController>(1)
        sc.onActivity { activity: MainActivity ->
            val startupModel = ViewModelProvider(activity)[StartupViewModel::class.java]
            val granted = StartupPermissionInventory.requiredRuntimePermissions(Build.VERSION.SDK_INT)
                .filter { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                .toSet()
            startupModel.admit(granted)
            originalController[0] = VisionSession.ensureController(activity)
        }

        val initialFrameObserved = awaitState(20_000L) {
            val state = VisionSession.uiState.value
            state.cameraStatus == CameraSessionStatus.Running &&
                state.groundContactDistance.timestampNanos > 0L
        }
        val ctrl = originalController[0] ?: VisionSession.ensureController(context)
        val snapshot = ctrl.diagnosticSnapshot()
        val sessionSummary = VisionSession.diagnosticSummary(context)
        assertTrue(
            "Home should start the visible camera service and publish a frame; state=${VisionSession.uiState.value}, snapshot=$snapshot, session=$sessionSummary",
            initialFrameObserved,
        )
        val controller = requireNotNull(originalController[0])
        val initialTimestamp = VisionSession.uiState.value.groundContactDistance.timestampNanos
        val initialDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "Foreground service before background: $initialDiagnostics")
        assertEquals("VisionForegroundService", initialDiagnostics.lifecycleOwner)
        assertEquals("STARTED", initialDiagnostics.lifecycleState)
        assertTrue("Service CameraX state must be open: $initialDiagnostics", initialDiagnostics.cameraStateType == "OPEN")
        assertTrue("Service analysis use case must remain bound: $initialDiagnostics", initialDiagnostics.analysisBound)
        val notification = requireNotNull(
            context.getSystemService(NotificationManager::class.java)
            .activeNotifications
                .firstOrNull { it.notification.actions?.any { action -> action.title == "모니터링 중지" } == true },
        ) { "Foreground notification must include a stop action" }
        val stopActionIntent = requireNotNull(
            notification.notification.actions
                ?.firstOrNull { it.title == "모니터링 중지" }
                ?.actionIntent,
        ) { "Foreground notification stop action must be actionable" }

        executeShellCommand("input keyevent KEYCODE_HOME")
        assertTrue("Home launcher should move Aria out of the foreground", awaitState(5_000L) {
            sc.state != Lifecycle.State.RESUMED
        })
        val backgroundStartedAt = SystemClock.elapsedRealtime()
        val reopened = awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            val state = VisionSession.uiState.value
            diagnostics.lifecycleOwner == "VisionForegroundService" &&
                diagnostics.lifecycleState == "STARTED" &&
                diagnostics.analysisBound &&
                !diagnostics.previewBound &&
                diagnostics.cameraStateType == "OPEN" &&
                diagnostics.cameraError == null &&
                state.cameraStatus == CameraSessionStatus.Running
        }
        val reopenedDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "Background camera reopened after ${SystemClock.elapsedRealtime() - backgroundStartedAt}ms: $reopenedDiagnostics")
        assertTrue("Backgrounding must reopen Analysis after detaching Preview: $reopenedDiagnostics", reopened)
        assertEquals("Service lifecycle owner must remain active", "VisionForegroundService", reopenedDiagnostics.lifecycleOwner)
        assertEquals("Service lifecycle must remain STARTED", "STARTED", reopenedDiagnostics.lifecycleState)
        assertTrue("Analysis must remain bound while CameraX reopens: $reopenedDiagnostics", reopenedDiagnostics.analysisBound)
        assertFalse("Preview must be detached in background: $reopenedDiagnostics", reopenedDiagnostics.previewBound)
        assertTrue("Camera rebind must not report an error: $reopenedDiagnostics", reopenedDiagnostics.cameraError == null)
        assertEquals("Vision session must remain running", CameraSessionStatus.Running, VisionSession.uiState.value.cameraStatus)

        val firstBackgroundFrameAt = SystemClock.elapsedRealtime()
        val firstBackgroundFrameDelivered = awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            diagnostics.lifecycleOwner == "VisionForegroundService" &&
                diagnostics.lifecycleState == "STARTED" &&
                diagnostics.analysisBound &&
                diagnostics.cameraStateType == "OPEN" &&
                diagnostics.cameraError == null &&
                VisionSession.uiState.value.groundContactDistance.timestampNanos > initialTimestamp &&
                diagnostics.analyzerEntries > 0L &&
                diagnostics.pipelineInputs > 0L &&
                diagnostics.completedFrames > 0L &&
                diagnostics.imageProxyCloses > 0L
        }
        val backgroundFrameDiagnostics = controller.diagnosticSnapshot()
        Log.i(
            TAG,
            "First background result after ${SystemClock.elapsedRealtime() - firstBackgroundFrameAt}ms: $backgroundFrameDiagnostics",
        )
        assertTrue("Backgrounded service should publish a fresh analyzer result: $backgroundFrameDiagnostics", firstBackgroundFrameDelivered)
        assertEquals("Service lifecycle owner must remain active", "VisionForegroundService", backgroundFrameDiagnostics.lifecycleOwner)
        assertEquals("Service lifecycle must remain STARTED", "STARTED", backgroundFrameDiagnostics.lifecycleState)
        assertTrue("Analysis must remain bound while waiting for frames: $backgroundFrameDiagnostics", backgroundFrameDiagnostics.analysisBound)
        assertTrue("Camera must remain open without an error: $backgroundFrameDiagnostics", backgroundFrameDiagnostics.cameraStateType == "OPEN" && backgroundFrameDiagnostics.cameraError == null)
        var previousDiagnostics = backgroundFrameDiagnostics
        Log.i(
            TAG,
            "First background result after ${SystemClock.elapsedRealtime() - firstBackgroundFrameAt}ms: $previousDiagnostics",
        )
        Log.i(TAG, "Foreground service after background detach: $previousDiagnostics")
        var previousTimestamp = initialTimestamp
        repeat(4) {
            SystemClock.sleep(1_000L)
            val state = VisionSession.uiState.value
            val diagnostics = controller.diagnosticSnapshot()
            assertTrue(
                "Camera analysis must continue while Aria is backgrounded",
                state.cameraStatus == CameraSessionStatus.Running,
            )
            assertTrue(
                "Analyzer timestamps must keep advancing during the background interval",
                state.groundContactDistance.timestampNanos > previousTimestamp,
            )
            assertTrue(
                "Camera/IMU frame pipeline must keep advancing in background: $diagnostics",
                diagnostics.lifecycleOwner == "VisionForegroundService" &&
                    diagnostics.lifecycleState == "STARTED" &&
                    diagnostics.cameraStateType == "OPEN" &&
                    diagnostics.cameraError == null &&
                    diagnostics.analyzerEntries > previousDiagnostics.analyzerEntries &&
                    diagnostics.pipelineInputs > previousDiagnostics.pipelineInputs &&
                    (!diagnostics.modelRestoreComplete ||
                        diagnostics.inferenceReturns > previousDiagnostics.inferenceReturns) &&
                    diagnostics.completedFrames > previousDiagnostics.completedFrames &&
                    diagnostics.imageProxyCloses > previousDiagnostics.imageProxyCloses &&
                    diagnostics.cameraCaptureCallbacks > previousDiagnostics.cameraCaptureCallbacks &&
                    diagnostics.motionSensorCallbacks > previousDiagnostics.motionSensorCallbacks
            )
            previousDiagnostics = diagnostics
            previousTimestamp = state.groundContactDistance.timestampNanos
        }

        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        val backgroundTimestamp = VisionSession.uiState.value.groundContactDistance.timestampNanos
        assertTrue("Returning Home should resume the activity", awaitState(10_000L) {
            sc.state == Lifecycle.State.RESUMED
        })
        assertTrue("Returning Home should reattach to the same active session", awaitState(10_000L) {
            VisionSession.uiState.value.cameraStatus == CameraSessionStatus.Running &&
                VisionSession.uiState.value.groundContactDistance.timestampNanos > backgroundTimestamp
        })
        Log.i(TAG, "Foreground service after Home reattach: ${controller.diagnosticSnapshot()}")
        sc.onActivity { activity: MainActivity ->
            assertSame(controller, VisionSession.ensureController(activity))
        }

        stopActionIntent.send()
        assertTrue("Explicit stop should release the controller and clear camera results", awaitState(10_000L) {
            VisionSession.uiState.value.cameraStatus == CameraSessionStatus.Stopped &&
                VisionSession.uiState.value.latestSummary == null &&
                VisionSession.uiState.value.latestOverlayBitmap == null &&
                !VisionSession.isInitialized
        })
        assertTrue("An explicit notification stop must require a visible restart", VisionSession.requiresExplicitRestart(context))
    }

    @Test
    fun homeSharedSession_preprocessesImmediatelyThenRunsInferenceWhenModelReady() {
        instrumentation.runOnMainSync {
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
            VisionSession.clearExplicitStop(context)
        }
        instrumentation.waitForIdleSync()

        val sc = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)
        sc.onActivity { activity: MainActivity ->
            val startupModel = ViewModelProvider(activity)[StartupViewModel::class.java]
            val granted = StartupPermissionInventory.requiredRuntimePermissions(Build.VERSION.SDK_INT)
                .filter { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                .toSet()
            startupModel.admit(granted)
            controllerRef[0] = VisionSession.ensureController(activity)
        }

        val liveController = checkNotNull(controllerRef[0] ?: VisionSession.ensureController(context))
        val initialIdentity = liveController.diagnosticSnapshot().controllerIdentity

        // 1. Initial degraded frame delivery happens immediately (< 20s) even if model is still loading
        val initialFrameObserved = awaitState(20_000L) {
            val state = VisionSession.uiState.value
            val snap = liveController.diagnosticSnapshot()
            state.cameraStatus == CameraSessionStatus.Running &&
                snap.analyzerEntries > 0L &&
                snap.completedFrames > 0L &&
                state.groundContactDistance.timestampNanos > 0L
        }
        val initialSnapshot = liveController.diagnosticSnapshot()
        Log.i(TAG, "Initial frame snapshot: $initialSnapshot, modelState=${liveController.modelState.value}")
        assertTrue(
            "Home must start camera and preprocess initial frames immediately: state=${VisionSession.uiState.value}, snapshot=$initialSnapshot",
            initialFrameObserved,
        )
        assertEquals("Should maintain single session ticket during startup: $initialSnapshot", 1L, initialSnapshot.sessionGeneration)
        assertEquals("Should maintain initial controller identity: $initialSnapshot", initialIdentity, initialSnapshot.controllerIdentity)
        assertFalse("Initial controller must remain open", liveController.isClosed)

        // 2. Eventual model Ready (up to 120s for real GPU delegate compilation / model loading)
        val modelBecameReady = awaitState(120_000L) {
            val state = liveController.modelState.value
            val snap = liveController.diagnosticSnapshot()
            snap.modelRestoreComplete && state is VisionModelState.Ready
        }
        val modelReadySnapshot = liveController.diagnosticSnapshot()
        Log.i(TAG, "Model restore completion snapshot: $modelReadySnapshot, state=${liveController.modelState.value}")
        assertTrue(
            "Packaged model must complete restore within deadline: state=${liveController.modelState.value}, snapshot=$modelReadySnapshot",
            modelBecameReady,
        )
        assertTrue("Model must be invocation ready when Ready: $modelReadySnapshot", modelReadySnapshot.isInvocationReady)
        assertEquals("Controller identity must remain unchanged across restore (no substitution)", initialIdentity, modelReadySnapshot.controllerIdentity)
        assertFalse("Controller must not be closed across restore", liveController.isClosed)

        // 3. Real inference returns advance once model is Ready
        val inferencesObserved = awaitState(45_000L) {
            val snap = liveController.diagnosticSnapshot()
            snap.modelRestoreComplete &&
                snap.modelState == "Ready" &&
                snap.isInvocationReady &&
                snap.inferenceCallEntries >= 3L &&
                snap.inferenceReturns >= 3L
        }
        val priorObservedSnapshot = liveController.diagnosticSnapshot()
        assertTrue(
            "Inference calls must execute and return once model is ready: snapshot=$priorObservedSnapshot",
            inferencesObserved,
        )
        assertTrue(
            "Model must remain invocation ready prior to stopping: $priorObservedSnapshot",
            priorObservedSnapshot.isInvocationReady,
        )
        assertTrue(
            "Inference returns must match entries and achieve >= 3 prior to stopping: entries=${priorObservedSnapshot.inferenceCallEntries}, returns=${priorObservedSnapshot.inferenceReturns}",
            priorObservedSnapshot.inferenceReturns >= 3L,
        )

        instrumentation.runOnMainSync {
            liveController.stopCamera()
        }
        instrumentation.waitForIdleSync()

        val finalSnapshot = awaitSettledSnapshot(
            controller = liveController,
            timeoutMillis = 10_000L,
            minCompletedFrames = priorObservedSnapshot.completedFrames,
        )
        Log.i(TAG, "Post-restore inference snapshot: $finalSnapshot")
        assertEquals("Controller identity must remain unchanged across inferences (no substitution)", initialIdentity, finalSnapshot.controllerIdentity)
        assertFalse("Controller must not be closed after stopCamera", liveController.isClosed)
        assertTrue(
            "Model must remain invocation ready in final snapshot: $finalSnapshot",
            finalSnapshot.isInvocationReady,
        )
        assertTrue(
            "Every analyzed image proxy must be closed: analyzerEntries=${finalSnapshot.analyzerEntries}, imageProxyCloses=${finalSnapshot.imageProxyCloses}",
            finalSnapshot.imageProxyCloses >= finalSnapshot.analyzerEntries,
        )
    }

    @Test
    fun packagedModelWarmup_completesBeforeReady_andFirstInferenceRunsUnderWatchdogTimeout() {
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            assertFalse("Segmenter must not be ready before restore", store.segmenter.isReady)
            assertFalse("Segmenter must not be invocation ready before restore", store.segmenter.isInvocationReady)

            val restoreState = store.restorePersistedModelSync()
            assertTrue("Packaged model must reach Ready: $restoreState", restoreState is VisionModelState.Ready)
            assertTrue("Segmenter must be ready once restored", store.segmenter.isReady)
            assertTrue("Segmenter must be invocation ready once restored", store.segmenter.isInvocationReady)

            val inputBuf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
            val startMs = SystemClock.elapsedRealtime()
            val output = store.segmenter.runIfLoaded(inputBuf, produceOverlayBitmap = true)
            val durationMs = SystemClock.elapsedRealtime() - startMs

            assertNotNull("First inference after Ready must produce output", output)
            assertTrue(
                "First inference duration (${durationMs}ms) must complete well under 15s in-flight watchdog",
                durationMs < 5000L,
            )
            assertTrue("Invoke duration must be positive", output!!.invokeDurationNanos > 0L)
            assertNotNull("Overlay bitmap must be produced", output.overlayBitmap)
        } finally {
            store.close()
        }
    }

    @Test
    fun concurrentPendingStartAndProviderUpdate_maintainsSingleSessionTicketAndDeliversFrames() {
        instrumentation.runOnMainSync {
            VisionSession.markExplicitStop(context)
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
        }
        instrumentation.waitForIdleSync()

        val sc = ActivityScenario.launch(GpuDiagnosticOverlayActivity::class.java).also { scenario = it }
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)
        val surfaceTexture1 = SurfaceTexture(1).apply { setDefaultBufferSize(640, 480) }
        val surface1 = Surface(surfaceTexture1)
        val provider1 = androidx.camera.core.Preview.SurfaceProvider { request ->
            request.provideSurface(surface1, ContextCompat.getMainExecutor(context)) {}
        }

        val surfaceTexture2 = SurfaceTexture(2).apply { setDefaultBufferSize(640, 480) }
        val surface2 = Surface(surfaceTexture2)
        val provider2 = androidx.camera.core.Preview.SurfaceProvider { request ->
            request.provideSurface(surface2, ContextCompat.getMainExecutor(context)) {}
        }

        try {
            sc.onActivity { activity: GpuDiagnosticOverlayActivity ->
                VisionSession.clearExplicitStop(activity.applicationContext)
                val controller = VisionPipelineController(
                    context = activity.applicationContext,
                    modelStore = VisionModelStore(activity.applicationContext, restoreOnInit = false),
                )
                controllerRef[0] = controller
                diagnosticController = controller

                // 1. Initial start without preview
                controller.startCamera(
                    lifecycleOwner = activity,
                    surfaceProvider = null,
                    mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    targetRotation = Surface.ROTATION_0,
                )
                // 2. Rapid concurrent surface provider updates racing before binding finishes
                controller.updateSurfaceProvider(provider1, Surface.ROTATION_0)
                controller.updateSurfaceProvider(provider2, Surface.ROTATION_90)
            }

            val controller = requireNotNull(controllerRef[0])
            val framesDelivered = awaitState(20_000L) {
                val diagnostics = controller.diagnosticSnapshot()
                diagnostics.sessionGeneration == 1L &&
                    diagnostics.boundTicket == 1L &&
                    diagnostics.activeSurfaceProviderSet &&
                    diagnostics.previewBound &&
                    diagnostics.cameraStateType == "OPEN" &&
                    diagnostics.analyzerEntries >= 3L &&
                    diagnostics.completedFrames >= 3L
            }
            val diagnostics = controller.diagnosticSnapshot()
            Log.i(TAG, "Concurrent pending start diagnostics: $diagnostics")
            assertTrue("Rapid surface provider updates must coalesce to single session ticket and deliver frames: $diagnostics", framesDelivered)
            assertEquals("Session generation must not be incremented by concurrent updates: $diagnostics", 1L, diagnostics.sessionGeneration)
            assertEquals("Bound ticket must remain 1: $diagnostics", 1L, diagnostics.boundTicket)
            assertTrue("Latest surface provider must be set: $diagnostics", diagnostics.activeSurfaceProviderSet)
            assertTrue("Preview must be bound from coalesced provider: $diagnostics", diagnostics.previewBound)
        } finally {
            instrumentation.runOnMainSync {
                controllerRef[0]?.stopCamera()
            }
            instrumentation.waitForIdleSync()
            surface1.release()
            surfaceTexture1.release()
            surface2.release()
            surfaceTexture2.release()
        }
    }

    @Test
    fun stallRecovery_whenRetriesStuckInOpeningWithoutFrames_terminatesInBoundedActionableError() {
        instrumentation.runOnMainSync {
            VisionSession.markExplicitStop(context)
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
        }
        instrumentation.waitForIdleSync()

        val sc = ActivityScenario.launch(GpuDiagnosticOverlayActivity::class.java).also { scenario = it }
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)

        try {
            sc.onActivity { activity: GpuDiagnosticOverlayActivity ->
                VisionSession.clearExplicitStop(activity.applicationContext)
                val controller = VisionPipelineController(
                    context = activity.applicationContext,
                    modelStore = VisionModelStore(activity.applicationContext, restoreOnInit = false),
                )
                controllerRef[0] = controller
                diagnosticController = controller

                controller.startCamera(
                    lifecycleOwner = activity,
                    surfaceProvider = null,
                    mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    targetRotation = Surface.ROTATION_0,
                )
            }

            val controller = requireNotNull(controllerRef[0])
            val initialFrames = awaitState(20_000L) {
                val diag = controller.diagnosticSnapshot()
                diag.cameraStateType == "OPEN" && diag.completedFrames >= 3L
            }
            assertTrue("Initial frames must flow before triggering stall: ${controller.diagnosticSnapshot()}", initialFrames)

            val initialTicket = controller.currentSessionTicket

            // Verify test seam rejects invalid ticket without altering running state
            instrumentation.runOnMainSync {
                controller.triggerStartupWatchdogForTesting(initialTicket + 999L)
            }
            instrumentation.waitForIdleSync()
            val invalidTicketDiag = controller.diagnosticSnapshot()
            assertTrue("Invalid ticket must be rejected as no-op: $invalidTicketDiag", controller.isRunning)
            assertEquals("Stall recovery attempts must remain 0 after invalid ticket: $invalidTicketDiag", 0, invalidTicketDiag.stallRecoveryAttempts)

            // Trigger deterministic startup watchdog expiration during stall recovery stuck without frames (attempt bound reached)
            instrumentation.runOnMainSync {
                controller.triggerStartupWatchdogForTesting(initialTicket)
            }
            instrumentation.waitForIdleSync()

            val terminatedInError = awaitState(5_000L) {
                val state = controller.uiState.value
                val diag = controller.diagnosticSnapshot()
                state.cameraStatus == CameraSessionStatus.Error &&
                    diag.stallRecoveryAttempts >= VisionPipelineController.MAX_STALL_RECOVERY_ATTEMPTS
            }
            val finalDiag = controller.diagnosticSnapshot()
            val finalState = controller.uiState.value
            Log.i(TAG, "Stall recovery bounded termination diagnostics: $finalDiag, state=$finalState")

            assertTrue(
                "Stall recovery retries stuck without frames must terminate cleanly in Error status: diag=$finalDiag, state=$finalState",
                terminatedInError,
            )
            assertNotNull("Error detail must be present: $finalState", finalState.cameraErrorDetail)
            assertTrue(
                "Error detail must state stall recovery retry limit exceeded: ${finalState.cameraErrorDetail}",
                finalState.cameraErrorDetail?.contains("한도를 초과했습니다") == true,
            )
            assertFalse("Controller must not remain running after bounded stall failure", controller.isRunning)
        } finally {
            instrumentation.runOnMainSync {
                controllerRef[0]?.stopCamera()
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun fatalCameraDeviceError_boundsRecovery_andTerminatesInActionableError() {
        instrumentation.runOnMainSync {
            VisionSession.markExplicitStop(context)
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
        }
        instrumentation.waitForIdleSync()

        val sc = ActivityScenario.launch(GpuDiagnosticOverlayActivity::class.java).also { scenario = it }
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)

        try {
            sc.onActivity { activity: GpuDiagnosticOverlayActivity ->
                VisionSession.clearExplicitStop(activity.applicationContext)
                val controller = VisionPipelineController(
                    context = activity.applicationContext,
                    modelStore = VisionModelStore(activity.applicationContext, restoreOnInit = false),
                )
                controllerRef[0] = controller
                diagnosticController = controller

                controller.startCamera(
                    lifecycleOwner = activity,
                    surfaceProvider = null,
                    mode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                    targetRotation = Surface.ROTATION_0,
                )
            }

            val controller = requireNotNull(controllerRef[0])
            val initialFrames = awaitState(20_000L) {
                val diag = controller.diagnosticSnapshot()
                diag.cameraStateType == "OPEN" && diag.completedFrames >= 3L
            }
            assertTrue("Initial frames must flow before testing fatal device error: ${controller.diagnosticSnapshot()}", initialFrames)

            val ticket = controller.currentSessionTicket

            // 1. First fatal camera device error: triggers bounded recovery attempt 1
            instrumentation.runOnMainSync {
                controller.handleCameraFailureForTesting(
                    ticket = ticket,
                    failure = CameraFailureKind.Fatal(
                        code = androidx.camera.core.CameraState.ERROR_CAMERA_FATAL_ERROR,
                        description = "카메라 하드웨어 치명적 오류가 발생했습니다",
                    ),
                )
            }
            instrumentation.waitForIdleSync()

            val snap1 = controller.diagnosticSnapshot()
            val state1 = controller.uiState.value
            assertEquals("Attempt 1 must increment deviceErrorRecoveryAttempts to 1: $snap1", 1, snap1.deviceErrorRecoveryAttempts)
            assertEquals("Status must transition to CameraUnavailable during recovery: $state1", CameraSessionStatus.CameraUnavailable, state1.cameraStatus)

            // 2. Second fatal camera device error: exceeds bound (MAX_DEVICE_ERROR_RECOVERY_ATTEMPTS = 1)
            instrumentation.runOnMainSync {
                controller.handleCameraFailureForTesting(
                    ticket = ticket,
                    failure = CameraFailureKind.Fatal(
                        code = androidx.camera.core.CameraState.ERROR_CAMERA_FATAL_ERROR,
                        description = "카메라 하드웨어 치명적 오류가 발생했습니다",
                    ),
                )
            }
            instrumentation.waitForIdleSync()

            val finalSnap = controller.diagnosticSnapshot()
            val finalState = controller.uiState.value
            Log.i(TAG, "Fatal device error bounded termination: $finalSnap, state=$finalState")

            assertEquals("Attempt 2 must transition cleanly to Error status: $finalState", CameraSessionStatus.Error, finalState.cameraStatus)
            assertNotNull("Error detail must be present: $finalState", finalState.cameraErrorDetail)
            assertTrue(
                "Error detail must state limit exceeded: ${finalState.cameraErrorDetail}",
                finalState.cameraErrorDetail?.contains("치명적 오류 복구 한도를 초과했습니다") == true,
            )
            assertFalse("Controller must not remain running after fatal limit reached", controller.isRunning)
        } finally {
            instrumentation.runOnMainSync {
                controllerRef[0]?.stopCamera()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun grantRuntimePermission(permission: String) {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        assertEquals(
            "Instrumentation must grant $permission before launching Home",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(permission),
        )
    }

    private fun executeShellCommand(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun awaitState(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(100L)
        }
        return predicate()
    }

    private fun awaitSettledSnapshot(
        controller: VisionPipelineController,
        timeoutMillis: Long = 10_000L,
        minCompletedFrames: Long = 0L,
    ): VisionPipelineDiagnosticSnapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        if (!controller.analyzerExecutor.isShutdown) {
            try {
                val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(100L)
                val snapshot = controller.analyzerExecutor.submit(Callable<VisionPipelineDiagnosticSnapshot> {
                    controller.diagnosticSnapshot()
                }).get(remainingMs, TimeUnit.MILLISECONDS)
                if (!snapshot.isFrameInFlight &&
                    snapshot.imageProxyCloses >= snapshot.analyzerEntries &&
                    snapshot.completedFrames >= minCompletedFrames
                ) {
                    return snapshot
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Analyzer executor settling task did not complete cleanly; falling back to polling", t)
            }
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = controller.diagnosticSnapshot()
            if (!snapshot.isFrameInFlight &&
                snapshot.imageProxyCloses >= snapshot.analyzerEntries &&
                snapshot.completedFrames >= minCompletedFrames
            ) {
                return snapshot
            }
            SystemClock.sleep(20L)
        }
        val finalSnapshot = controller.diagnosticSnapshot()
        if (!finalSnapshot.isFrameInFlight &&
            finalSnapshot.imageProxyCloses >= finalSnapshot.analyzerEntries &&
            finalSnapshot.completedFrames >= minCompletedFrames
        ) {
            return finalSnapshot
        }
        throw AssertionError(
            "Analyzer pipeline failed to reach quiescent state within ${timeoutMillis}ms " +
                "(expected: !isFrameInFlight, imageProxyCloses >= analyzerEntries, completedFrames >= $minCompletedFrames): " +
                "snapshot=$finalSnapshot"
        )
    }

    private class StartedLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun start() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun destroy() {
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }

    }

    companion object {
        private const val TAG = "VisionBackgroundTest"
    }
}
