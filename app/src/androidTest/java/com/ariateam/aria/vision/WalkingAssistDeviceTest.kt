package com.ariateam.aria.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.MainActivity
import com.ariateam.aria.command.CommandCoordinator
import com.ariateam.aria.startup.StartupPermissionInventory
import com.ariateam.aria.walking.WalkingDiagnosticStage
import com.ariateam.aria.startup.StartupViewModel
import com.ariateam.aria.walking.WalkingAssistPhase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device coverage of natural walking state fed by the live camera/inference pipeline. */
@RunWith(AndroidJUnit4::class)
class WalkingAssistDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun naturalSessionPublishesFreshWalkingDiagnosticsAndFencesStoppedCamera() {
        val originallyStopped = VisionSession.requiresExplicitRestart(context)
        val permissions = StartupPermissionInventory.requiredRuntimePermissions(android.os.Build.VERSION.SDK_INT)
        permissions.forEach { permission ->
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            VisionSession.clearExplicitStop(context)
            val startup = arrayOfNulls<StartupViewModel>(1)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { startup[0] = ViewModelProvider(it)[StartupViewModel::class.java] }
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            var controller: VisionPipelineController? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                if (VisionSession.isInitialized) {
                    controller = VisionSession.ensureController(context)
                    val snap = controller.diagnosticSnapshot()
                    if (startup[0]?.hasBeenAdmitted == true && snap.inferenceReturns >= 3L) break
                }
                SystemClock.sleep(100L)
            }
            val live = requireNotNull(controller) { "Natural Home did not create Vision controller" }
            val liveSnapshot = live.diagnosticSnapshot()
            assertTrue("Home must be admitted naturally", startup[0]?.hasBeenAdmitted == true)
            assertTrue("Live inference must return frames: $liveSnapshot", liveSnapshot.inferenceReturns >= 3L)
            assertTrue("Walking session must publish live state", live.uiState.value.walkingAssistState.phase != WalkingAssistPhase.STOPPED)
            val events = live.uiState.value.walkingDiagnosticEvents
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val capture = events.lastOrNull { it.stage == WalkingDiagnosticStage.CAPTURE && it.frameSequence > 0L }
            assertTrue("Diagnostics must contain a recent real capture event: $events", capture != null)
            val actualCapture = requireNotNull(capture)
            assertTrue("Capture must be tied to a live controller session", actualCapture.sessionGeneration > 0L)
            assertTrue("Capture timestamp must be fresh and from elapsed realtime", actualCapture.captureTimestampNanos in (nowNanos - 3_000_000_000L)..nowNanos)
            assertTrue("Capture event must follow the image timestamp", actualCapture.elapsedRealtimeNanos >= actualCapture.captureTimestampNanos)
            assertTrue(
                "A decision must reference the same actual captured frame",
                events.any {
                    it.stage == WalkingDiagnosticStage.DECISION &&
                        it.sessionGeneration == actualCapture.sessionGeneration &&
                        it.frameSequence == actualCapture.frameSequence &&
                        it.captureTimestampNanos == actualCapture.captureTimestampNanos &&
                        it.elapsedRealtimeNanos >= actualCapture.elapsedRealtimeNanos
                },
            )

            // Exercise the production local calibration broadcast and compare against the real
            // tracker. The outcome may be success or failure depending on live sensor quality.
            context.sendBroadcast(
                Intent(CommandCoordinator.ACTION_FORWARD_CALIBRATION)
                    .setPackage(context.packageName)
                    .putExtra(CommandCoordinator.EXTRA_CALIBRATION_REQUEST_ID, 884422L),
            )
            SystemClock.sleep(250L)
            val trackerOutcome = live.motionTracker.stateAt(SystemClock.elapsedRealtimeNanos()).isCalibrated
            assertTrue(
                "Calibration broadcast must publish the real tracker outcome, success or failure",
                live.uiState.value.motionState.isCalibrated == trackerOutcome,
            )

            instrumentation.runOnMainSync { live.stopCamera() }
            val stoppedDeadline = SystemClock.elapsedRealtime() + 8_000L
            while (SystemClock.elapsedRealtime() < stoppedDeadline &&
                live.uiState.value.walkingAssistState.phase != WalkingAssistPhase.STOPPED) SystemClock.sleep(50L)
            assertTrue("Camera stop must stop walking session", live.uiState.value.walkingAssistState.phase == WalkingAssistPhase.STOPPED)
            val stoppedEvents = live.uiState.value.walkingDiagnosticEvents
            SystemClock.sleep(1_800L)
            assertTrue("Cancelled tick/callback must not mutate stopped session", live.uiState.value.walkingAssistState.phase == WalkingAssistPhase.STOPPED)
            val afterStopEvents = live.uiState.value.walkingDiagnosticEvents
            assertTrue(
                "Stopped session diagnostics must remain stable; before=${stoppedEvents.map { it.stage to it.sequence }}, " +
                    "after=${afterStopEvents.map { it.stage to it.sequence }}",
                afterStopEvents == stoppedEvents,
            )
        } finally {
            scenario?.close()
            instrumentation.runOnMainSync {
                context.stopService(Intent(context, VisionForegroundService::class.java))
                VisionSession.closeController()
            }
            if (originallyStopped) VisionSession.markExplicitStop(context) else VisionSession.clearExplicitStop(context)
        }
    }
}
