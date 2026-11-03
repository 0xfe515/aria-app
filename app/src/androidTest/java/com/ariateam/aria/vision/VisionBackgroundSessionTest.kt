package com.ariateam.aria.vision

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionBackgroundSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var startedLifecycleOwner: StartedLifecycleOwner? = null
    private var diagnosticController: VisionPipelineController? = null
    private var originallyStoppedByUser = false

    @Before
    fun prepareVisibleHome() {
        val permissions = StartupPermissionInventory.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        permissions.forEach(::grantRuntimePermission)
        originallyStoppedByUser = VisionSession.requiresExplicitRestart(context)
        if (originallyStoppedByUser) VisionSession.clearExplicitStop(context)

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
        diagnosticController?.close()
        diagnosticController = null
        context.stopService(Intent(context, VisionForegroundService::class.java))
        scenario?.close()
        startedLifecycleOwner?.let { owner ->
            instrumentation.runOnMainSync { owner.destroy() }
        }
        if (originallyStoppedByUser) {
            VisionSession.markExplicitStop(context)
        } else {
            VisionSession.clearExplicitStop(context)
        }
    }

    @Test
    fun analyzerDelivery_comparesResumedActivityAndStartedLifecycleOwners() {
        VisionSession.markExplicitStop(context)
        context.stopService(Intent(context, VisionForegroundService::class.java))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)
        scenario?.onActivity { activity ->
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
            diagnostics.lifecycleOwner == "MainActivity" &&
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
        scenario?.onActivity {
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
        VisionSession.markExplicitStop(context)
        context.stopService(Intent(context, VisionForegroundService::class.java))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val restoreCompleted = MutableStateFlow(false)
        val modelState = MutableStateFlow<VisionModelState>(VisionModelState.Loading("test model"))
        val controllerRef = arrayOfNulls<VisionPipelineController>(1)
        scenario?.onActivity { activity ->
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
                diagnostics.lifecycleOwner == "MainActivity" &&
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
    fun homeBackgroundAndReturn_keepsOneCameraSessionUntilExplicitStop() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val originalController = arrayOfNulls<VisionPipelineController>(1)
        scenario?.onActivity { activity ->
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
        assertTrue(
            "Home should start the visible camera service and publish a frame; state=${VisionSession.uiState.value}",
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
            scenario?.state != Lifecycle.State.RESUMED
        })
        val backgroundStartedAt = SystemClock.elapsedRealtime()
        assertTrue("Backgrounding must reopen Analysis after detaching Preview", awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            assertEquals("Service lifecycle owner must remain active", "VisionForegroundService", diagnostics.lifecycleOwner)
            assertEquals("Service lifecycle must remain STARTED", "STARTED", diagnostics.lifecycleState)
            assertTrue("Analysis must remain bound while CameraX reopens: $diagnostics", diagnostics.analysisBound)
            assertTrue("Camera rebind must not report an error: $diagnostics", diagnostics.cameraError == null)
            assertEquals("Vision session must remain running", CameraSessionStatus.Running, VisionSession.uiState.value.cameraStatus)
            diagnostics.lifecycleOwner == "VisionForegroundService" &&
                diagnostics.lifecycleState == "STARTED" &&
                diagnostics.analysisBound &&
                !diagnostics.previewBound &&
                diagnostics.cameraStateType == "OPEN"
        })
        val cameraReopenedAt = SystemClock.elapsedRealtime()
        val reopenedDiagnostics = controller.diagnosticSnapshot()
        Log.i(TAG, "Background camera reopened after ${cameraReopenedAt - backgroundStartedAt}ms: $reopenedDiagnostics")
        val firstBackgroundFrameAt = SystemClock.elapsedRealtime()
        assertTrue("Backgrounded service should publish a fresh analyzer result: ${controller.diagnosticSnapshot()}", awaitState(15_000L) {
            val diagnostics = controller.diagnosticSnapshot()
            assertEquals("Service lifecycle owner must remain active", "VisionForegroundService", diagnostics.lifecycleOwner)
            assertEquals("Service lifecycle must remain STARTED", "STARTED", diagnostics.lifecycleState)
            assertTrue("Analysis must remain bound while waiting for frames: $diagnostics", diagnostics.analysisBound)
            assertTrue("Camera must remain open without an error: $diagnostics", diagnostics.cameraStateType == "OPEN" && diagnostics.cameraError == null)
            VisionSession.uiState.value.groundContactDistance.timestampNanos > initialTimestamp &&
                diagnostics.analyzerEntries > 0L &&
                diagnostics.pipelineInputs > 0L &&
                diagnostics.completedFrames > 0L &&
                diagnostics.imageProxyCloses > 0L
        })
        var previousDiagnostics = controller.diagnosticSnapshot()
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
            scenario?.state == Lifecycle.State.RESUMED
        })
        assertTrue("Returning Home should reattach to the same active session", awaitState(10_000L) {
            VisionSession.uiState.value.cameraStatus == CameraSessionStatus.Running &&
                VisionSession.uiState.value.groundContactDistance.timestampNanos > backgroundTimestamp
        })
        Log.i(TAG, "Foreground service after Home reattach: ${controller.diagnosticSnapshot()}")
        scenario?.onActivity { activity ->
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
