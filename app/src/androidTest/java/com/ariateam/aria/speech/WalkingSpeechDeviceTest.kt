package com.ariateam.aria.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.Manifest
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ariateam.aria.MainActivity
import com.ariateam.aria.command.CommandCoordinator
import com.ariateam.aria.command.CommandSpeech
import com.ariateam.aria.command.CommandSpeechPlaybackEngine
import com.ariateam.aria.command.SpeechPriority
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import com.ariateam.aria.vision.VisionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Separates synthetic coordinator/TTS policy coverage from real cross-process AIDL transport. */
@RunWith(AndroidJUnit4::class)
class WalkingSpeechDeviceTest {
    @Test
    fun isolatedCoordinatorSpeaksFreshStatusAndRejectsStaleSessionSequence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = RecordingEngine()
        val speech = CommandSpeech(context = null, suppress = {}, engine = engine)
        var elapsedNanos = 1_000_000_000L
        val coordinator = CommandCoordinator(
            context = context,
            update = {}, soundChanged = {}, suppress = {}, followUp = {}, finishFollowUp = {},
            stopListening = {}, vibrate = {}, clockElapsedNanos = { elapsedNanos }, speechOverride = speech,
        )
        try {
            coordinator.started()
            val now = elapsedNanos
            coordinator.updateWalkingAssistState(12L, 5L, now, "실제 센서 상태", false, false)
            coordinator.updateWalkingAssistState(11L, 99L, now, "오래된 세션", true, false)
            coordinator.updateWalkingAssistState(12L, 4L, now, "오래된 순번", true, false)
            coordinator.finalized("보행 상태")
            assertEquals("Fresh session status should be read by local status command", "실제 센서 상태", engine.lastText)
            assertEquals(SpeechPriority.NormalCommand, speech.currentPriority)

            engine.lastText = null
            coordinator.updateWalkingAssistState(12L, 6L, now - 10_000_000_000L, "stale timestamp", true, false)
            coordinator.finalized("보행 상태")
            assertEquals("Rejected stale update must leave the fresh status unchanged", "실제 센서 상태", engine.lastText)
            elapsedNanos += 2_100_000_000L
            engine.lastText = null
            coordinator.finalized("보행 상태")
            assertTrue("Expired status must fall back to explicit unknown status", engine.lastText?.contains("확인할 수 없어요") == true)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun realVoiceServiceReceivesWalkingAidlWhileIdleWithoutRevival() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Assume.assumeFalse("Skip while user voice session is active", AriaRuntimeStateBridge.isVoiceActive(context))
        val latch = CountDownLatch(1)
        val binderRef = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binderRef.set(service); latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent(context, VoiceService::class.java)
        var bound = false
        try {
            assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)); bound = true
            assertTrue("Idle VoiceService should bind", latch.await(25, TimeUnit.SECONDS))
            val binder = requireNotNull(binderRef.get())
            assertNull("VoiceService should be in its real :wakeword process", binder.queryLocalInterface("com.ariateam.aria.speech.IVoiceService"))
            val service = requireNotNull(IVoiceService.Stub.asInterface(binder))
            assertFalse(service.isServiceActive)
            service.updateWalkingAssistState(22L, 1L, SystemClock.elapsedRealtimeNanos(), "기기 AIDL 상태 전달", false, false)
            service.reportForwardCalibrationResult(998877L, false)
            SystemClock.sleep(200L)
            assertFalse("Status/calibration AIDL calls must not start capture", service.isServiceActive)
            assertTrue(binder.isBinderAlive)
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            // Bound-only idle service can be stopped safely because no user session was active.
            runCatching { context.stopService(intent) }
        }
    }

    @Test
    fun foregroundStartedVoiceSessionReceivesFreshWalkingStatusAndRejectsStaleIpc() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Assume.assumeTrue(
            "Real capture requires already granted microphone permission",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        val voiceWasActiveBeforeActivity = AriaRuntimeStateBridge.isVoiceActive(context)
        Assume.assumeFalse("Do not inject AIDL test state into a pre-existing user session", voiceWasActiveBeforeActivity)
        val ownsVoiceLifecycle = !voiceWasActiveBeforeActivity
        var scenario: ActivityScenario<MainActivity>? = null
        var bound = false
        var previousAudible = true
        var audibleStateCaptured = false
        val connected = CountDownLatch(1)
        val statusReceived = CountDownLatch(1)
        val binderRef = AtomicReference<IBinder?>()
        val serviceRef = AtomicReference<IVoiceService?>()
        val callbackRef = AtomicReference<IVoiceCallback?>()
        val targetText = "기기 보행 상태 ${SystemClock.elapsedRealtime()}"
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binderRef.set(service); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { binderRef.set(null) }
        }
        val intent = Intent(context, VoiceService::class.java)
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            if (!AriaRuntimeStateBridge.isVoiceActive(context)) {
                context.startForegroundService(intent.setAction(VoiceService.ACTION_WAIT))
            }
            val deadline = SystemClock.elapsedRealtime() + 25_000L
            while (SystemClock.elapsedRealtime() < deadline && !AriaRuntimeStateBridge.isVoiceActive(context)) SystemClock.sleep(100L)
            assertTrue("VoiceService must become active through its real WAIT start path", AriaRuntimeStateBridge.isVoiceActive(context))
            assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)); bound = true
            assertTrue("Active VoiceService binder must connect", connected.await(10, TimeUnit.SECONDS))
            val binder = requireNotNull(binderRef.get())
            assertNull("Walking status must traverse cross-process AIDL", binder.queryLocalInterface("com.ariateam.aria.speech.IVoiceService"))
            val service = requireNotNull(IVoiceService.Stub.asInterface(binder))
            serviceRef.set(service)
            assertTrue(service.isServiceActive)
            assertTrue("MainActivity must naturally initialize Vision before IPC generation is read", VisionSession.isInitialized)
            val diagnosticController = VisionSession.ensureController(context)
            val diagnosticsDeadline = SystemClock.elapsedRealtime() + 12_000L
            var liveSession: Long? = null
            while (SystemClock.elapsedRealtime() < diagnosticsDeadline && liveSession == null) {
                liveSession = diagnosticController.uiState.value.walkingDiagnosticEvents
                    .lastOrNull { it.sessionGeneration > 0L }?.sessionGeneration
                if (liveSession == null) SystemClock.sleep(100L)
            }
            val realWalkingSession = liveSession
                ?: throw AssertionError("A real walking session generation must exist before injecting AIDL test state")
            val state = service.state
            previousAudible = state?.getBundle("command")?.getBoolean("audible", true) ?: true
            audibleStateCaptured = true
            service.setAudibleFeedback(false)
            val callback = object : IVoiceCallback.Stub() {
                override fun onState(stateBundle: Bundle?) {
                    val message = stateBundle?.getBundle("command")?.getString("message")
                    if (message == targetText) statusReceived.countDown()
                }
            }
            callbackRef.set(callback)
            service.registerCallback(callback)
            SystemClock.sleep(100L)
            val now = SystemClock.elapsedRealtimeNanos()
            service.updateWalkingAssistState(realWalkingSession, Long.MAX_VALUE - 2L, now, targetText, true, false)
            assertTrue("Fresh walking status should be processed by active service", statusReceived.await(5, TimeUnit.SECONDS))
            val afterFresh = service.state?.getBundle("command")?.getString("message")
            assertEquals(targetText, afterFresh)
            service.updateWalkingAssistState(realWalkingSession - 1L, Long.MAX_VALUE, now, "stale session", true, false)
            service.updateWalkingAssistState(realWalkingSession, Long.MAX_VALUE - 3L, now, "stale sequence", true, false)
            SystemClock.sleep(250L)
            assertEquals("Stale session/sequence must not replace fresh status", targetText, service.state?.getBundle("command")?.getString("message"))

            val hazardText = "기기 AIDL 보행 위험 ${SystemClock.elapsedRealtime()}"
            val hazardReceived = CountDownLatch(1)
            val hazardCallback = object : IVoiceCallback.Stub() {
                override fun onState(stateBundle: Bundle?) {
                    if (stateBundle?.getBundle("command")?.getString("message") == hazardText) hazardReceived.countDown()
                }
            }
            service.registerCallback(hazardCallback)
            service.postHazardAlert(700L, 1L, SystemClock.elapsedRealtimeNanos(), hazardText)
            assertTrue("Fresh hazard must be delivered over active cross-process AIDL", hazardReceived.await(5, TimeUnit.SECONDS))
            assertEquals(hazardText, service.state?.getBundle("command")?.getString("message"))
            runCatching { service.unregisterCallback(hazardCallback) }
            assertTrue("AIDL status processing must leave real listening session active", service.isServiceActive)
        } finally {
            serviceRef.get()?.let { service ->
                callbackRef.get()?.let { callback -> runCatching { service.unregisterCallback(callback) } }
                if (audibleStateCaptured) runCatching { service.setAudibleFeedback(previousAudible) }
                if (ownsVoiceLifecycle) runCatching { service.stop() }
            }
            if (bound) runCatching { context.unbindService(connection) }
            if (ownsVoiceLifecycle) runCatching { context.stopService(intent) }
            scenario?.close()
        }
    }

    private class RecordingEngine : CommandSpeechPlaybackEngine {
        override val isAvailable = true
        override val isReady = true
        private var listener: CommandSpeechPlaybackEngine.UtteranceListener? = null
        var lastText: String? = null
        override fun setUtteranceListener(listener: CommandSpeechPlaybackEngine.UtteranceListener?) { this.listener = listener }
        override fun refreshVoice() = true
        override fun speak(text: String, utteranceId: String): Int {
            lastText = text
            listener?.onStart(utteranceId)
            return CommandSpeechPlaybackEngine.SUCCESS
        }
        override fun stop() { }
        override fun shutdown() { }
    }
}
