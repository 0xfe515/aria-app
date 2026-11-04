package com.ariateam.aria.vision

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.MainActivity
import com.ariateam.aria.startup.StartupPermissionInventory
import com.ariateam.aria.startup.StartupViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real device regression test verifying natural cold startup of [MainActivity].
 *
 * Requirements:
 * - Must NOT call admit(), acceptDegraded(), direct private flags, injected model states,
 *   or clear application data.
 * - Asserts naturally admitted Home ([StartupViewModel.hasBeenAdmitted] == true).
 * - Asserts real model Ready state and truthful invocation readiness ([VisionModelState.Ready]
 *   and [LiteRtVisionSegmenter.isInvocationReady] == true).
 * - Asserts >= 3 live inference returns and balanced ImageProxy closures.
 * - Runnable via direct am instrument class selection:
 *   `am instrument -w -e class com.ariateam.aria.vision.VisionNaturalStartupDeviceTest com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class VisionNaturalStartupDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originallyStoppedByUser = false

    @Before
    fun setUpDeviceAndPermissions() {
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

        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            executeShellCommand("input keyevent KEYCODE_WAKEUP")
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            executeShellCommand("wm dismiss-keyguard")
        }
        assertTrue("Device screen must be interactive before startup", power.isInteractive)
        assertTrue("Device keyguard must be dismissed", !keyguard.isKeyguardLocked)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
        }
        scenario?.close()
        scenario = null
        instrumentation.waitForIdleSync()
        if (originallyStoppedByUser) {
            VisionSession.markExplicitStop(context)
        } else {
            VisionSession.clearExplicitStop(context)
        }
    }

    @Test
    fun naturalColdStartup_reachesAdmittedHomeAndInferenceWithoutManualAdmission() {
        val startupModelRef = arrayOfNulls<StartupViewModel>(1)

        // Launch MainActivity naturally cold WITHOUT calling admit(), acceptDegraded(),
        // direct private flags, or injected model states.
        val sc = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        sc.onActivity { activity: MainActivity ->
            startupModelRef[0] = ViewModelProvider(activity)[StartupViewModel::class.java]
        }

        val startupModel = requireNotNull(startupModelRef[0]) {
            "StartupViewModel must be bound to MainActivity"
        }

        // 1. Natural admission to Home: MainScreen startup policy must evaluate to Ready,
        // model must restore to Ready without gate deadlock, and admit naturally.
        // We only observe VisionSession once MainScreen has created the controller (no side-effect creation).
        val admittedNaturally = awaitState(35_000L) {
            val isAdmitted = startupModel.hasBeenAdmitted
            val isModelReady = VisionSession.modelState.value is VisionModelState.Ready
            val hasController = VisionSession.isInitialized
            val isInvocationReady = if (hasController) {
                VisionSession.ensureController(context).modelStore.segmenter.isInvocationReady
            } else {
                false
            }
            isAdmitted && isModelReady && hasController && isInvocationReady
        }

        val modelState = VisionSession.modelState.value
        val controller = VisionSession.ensureController(context)
        val initialSnapshot = controller.diagnosticSnapshot()

        assertTrue(
            "MainActivity must naturally reach admitted Home without manual admission: " +
                "admitted=${startupModel.hasBeenAdmitted}, modelState=$modelState, " +
                "isInvocationReady=${controller.modelStore.segmenter.isInvocationReady}, " +
                "snapshot=$initialSnapshot",
            admittedNaturally,
        )

        assertTrue(
            "StartupViewModel must be naturally admitted",
            startupModel.hasBeenAdmitted,
        )
        assertTrue(
            "Model state must be Ready, but was $modelState",
            modelState is VisionModelState.Ready,
        )
        assertTrue(
            "Segmenter invocation readiness must be true",
            controller.modelStore.segmenter.isInvocationReady,
        )

        val readyMetadata = (modelState as VisionModelState.Ready).metadata
        assertNotNull("Model metadata must be populated", readyMetadata)
        Log.i(TAG, "Natural startup model ready: ${readyMetadata.displayName}, backend=${readyMetadata.backendDescription}")

        // 2. Camera automatically starts on Home and executes live inference:
        // Must observe at least 3 completed inference returns.
        val inferenceReturnsObserved = awaitState(35_000L) {
            val snapshot = controller.diagnosticSnapshot()
            snapshot.inferenceReturns >= 3L && snapshot.analyzerEntries >= 3L
        }

        val inferenceSnapshot = controller.diagnosticSnapshot()
        Log.i(TAG, "Inference snapshot before stopCamera: $inferenceSnapshot")

        assertTrue(
            "Live camera on Home must produce >= 3 inference returns: $inferenceSnapshot",
            inferenceReturnsObserved,
        )
        assertTrue(
            "Inference returns (${inferenceSnapshot.inferenceReturns}) must be >= 3",
            inferenceSnapshot.inferenceReturns >= 3L,
        )

        // 3. Stop camera on main thread to eliminate race with subsequent frame arrivals,
        // then await quiescent balanced snapshot as existing Home test does.
        instrumentation.runOnMainSync {
            controller.stopCamera()
        }

        val settledSnapshot = awaitSettledSnapshot(controller, timeoutMillis = 15_000L, minCompletedFrames = 3L)
        Log.i(TAG, "Settled snapshot after stopCamera: $settledSnapshot")

        assertEquals(
            "Proxy closures must balance analyzer entries without leaks",
            settledSnapshot.analyzerEntries,
            settledSnapshot.imageProxyCloses,
        )
        assertTrue(
            "No frame should remain in flight after stopping camera",
            !settledSnapshot.isFrameInFlight,
        )
        assertTrue(
            "Inference returns must be at least 3",
            settledSnapshot.inferenceReturns >= 3L,
        )
    }

    private fun grantRuntimePermission(permission: String) {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        assertEquals(
            "Permission $permission must be granted",
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
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = controller.diagnosticSnapshot()
            if (!snapshot.isFrameInFlight &&
                snapshot.imageProxyCloses >= snapshot.analyzerEntries &&
                snapshot.completedFrames >= minCompletedFrames
            ) {
                return snapshot
            }
            SystemClock.sleep(50L)
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
                "snapshot=$finalSnapshot",
        )
    }

    companion object {
        private const val TAG = "VisionNaturalStartup"
    }
}
