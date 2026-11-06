package com.ariateam.aria.speech

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ariateam.aria.R
import com.ariateam.aria.hazard.HazardAlertCoordinator
import com.ariateam.aria.hazard.HazardAlertEvent
import com.ariateam.aria.hazard.HazardAssessment
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented integration tests verifying HazardAlertCoordinator -> HazardSpeechBridge event flow.
 *
 * NOTE: These tests use in-memory fake-service wiring (IVoiceService.Stub provider) to isolate and
 * deterministically verify speech bridge event translation, clock domain handling, and temporal
 * hysteresis without starting the multi-process :wakeword VoiceService or recording real audio.
 * For actual IPC binding, stop semantics, and provider reconciliation across process boundaries,
 * see [com.ariateam.aria.runtime.UnifiedStopDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class HazardSpeechIntegrationDeviceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AriaRuntimeStateBridge.resetForTesting()
        HazardAlertCoordinator.setSharedAlertSink(null)
    }

    @After
    fun tearDown() {
        AriaRuntimeStateBridge.resetForTesting()
        HazardAlertCoordinator.setSharedAlertSink(null)
    }

    @Test
    fun hazardAlertCoordinator_dispatchesToRegisteredHazardSpeechBridge() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)

        val deliveredEvents = CopyOnWriteArrayList<HazardAlertEvent>()
        val latch = CountDownLatch(1)

        // In-memory fake-service wiring: provides an IVoiceService.Stub directly to HazardSpeechBridge
        // for deterministic verification of bridge callback routing.
        val bridge = HazardSpeechBridge(
            context = context,
            voiceServiceProvider = {
                object : IVoiceService.Stub() {
                    override fun getState(): android.os.Bundle = android.os.Bundle()
                    override fun registerCallback(cb: IVoiceCallback?) {}
                    override fun unregisterCallback(cb: IVoiceCallback?) {}
                    override fun beginDictation() {}
                    override fun finishDictation() {}
                    override fun setAudibleFeedback(e: Boolean) {}
                    override fun stop() {}
                    override fun setUiVisible(v: Boolean) {}
                    override fun setUiScreen(s: String?) {}
                    override fun consumeNavigation(id: Long) {}
                    override fun consumeCloudQuestion(id: Long) {}
                    override fun speakCloudResponse(id: Long, t: String?) {}
                    override fun reportCloudError(id: Long, e: String?) {}
                    override fun cancelCloudQuestion(id: Long) {}
                    override fun speakHazardWarning(m: String?) {}
                    override fun updateWalkingAssistState(session: Long, seq: Long, ts: Long, status: String?, announce: Boolean, stopped: Boolean) {}
                    override fun reportForwardCalibrationResult(requestId: Long, successful: Boolean) {}
                    override fun postHazardAlert(session: Long, seq: Long, ts: Long, message: String?) {
                        deliveredEvents.add(
                            HazardAlertEvent(session, seq, ts, message.orEmpty())
                        )
                        latch.countDown()
                    }
                    override fun preemptSpeech() {}
                    override fun isServiceActive(): Boolean = false
                    override fun getServiceSessionId(): String = ""
                }
            },
        )

        var coordinator: HazardAlertCoordinator? = null
        try {
            bridge.start()
            HazardAlertCoordinator.setSharedAlertSink(bridge)

            val coord = HazardAlertCoordinator(
                context = context,
                toastDispatcher = {},
                vibratorDispatcher = {},
            )
            coordinator = coord
            coord.start()

            // Clock domain alignment: HazardAlertCoordinator tracks session start and evaluates
            // staleness using SystemClock.elapsedRealtimeNanos(). Using System.nanoTime() is prone
            // to cross-domain mismatch across device sleep (CLOCK_MONOTONIC vs CLOCK_BOOTTIME).
            val t0 = SystemClock.elapsedRealtimeNanos()
            val firstAssessment = HazardAssessment(
                level = HazardLevel.PATH_BLOCKED,
                motionConfidence = 0.95f,
                messageKorean = "전방 경로 막힘",
                timestampNanos = t0,
                isAlertCandidate = true,
            )

            // Feed frame 1: Policy requires confirmationFramesRequired = 2.
            // A single frame must NOT trigger an alert; verify hysteresis suppression.
            coord.processAssessment(firstAssessment, t0)
            assertEquals("Single frame must be suppressed by confirmationFramesRequired = 2 hysteresis", 0, deliveredEvents.size)
            assertEquals("Latch must not count down on single frame", 1L, latch.count)

            // Feed frame 2: Distinct, monotonically increasing timestamp in the same elapsedRealtimeNanos clock domain.
            val t1 = t0 + 33_000_000L // +33ms later (~30fps camera cadence)
            val secondAssessment = firstAssessment.copy(timestampNanos = t1)
            coord.processAssessment(secondAssessment, t1)

            val received = latch.await(2, TimeUnit.SECONDS)
            assertTrue("Hazard alert should be dispatched to registered bridge after 2 confirmation frames", received)
            assertEquals(1, deliveredEvents.size)
            assertEquals(context.getString(R.string.walking_hazard_blocked), deliveredEvents[0].message)
            assertEquals(t1, deliveredEvents[0].timestampNanos)
            assertTrue("AIDL session ticket must be assigned", deliveredEvents[0].session > 0L)
            assertEquals("AIDL sequence must be 1 for first alert", 1L, deliveredEvents[0].sequence)
        } finally {
            coordinator?.stop()
            coordinator?.close()
            bridge.close()
            HazardAlertCoordinator.setSharedAlertSink(null)
            AriaRuntimeStateBridge.resetForTesting()
        }
        assertNull(HazardAlertCoordinator.getSharedAlertSink())
    }

    @Test
    fun hazardSpeechBridge_doesNotRestartStoppedVoiceService() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(false)

        val deliveredEvents = CopyOnWriteArrayList<HazardAlertEvent>()
        // In-memory fake-service wiring: tests that bridge drops alerts when voice service is stopped
        // without attempting to restart or bind to VoiceService.
        val bridge = HazardSpeechBridge(
            context = context,
            voiceServiceProvider = {
                object : IVoiceService.Stub() {
                    override fun getState(): android.os.Bundle = android.os.Bundle()
                    override fun registerCallback(cb: IVoiceCallback?) {}
                    override fun unregisterCallback(cb: IVoiceCallback?) {}
                    override fun beginDictation() {}
                    override fun finishDictation() {}
                    override fun setAudibleFeedback(e: Boolean) {}
                    override fun stop() {}
                    override fun setUiVisible(v: Boolean) {}
                    override fun setUiScreen(s: String?) {}
                    override fun consumeNavigation(id: Long) {}
                    override fun consumeCloudQuestion(id: Long) {}
                    override fun speakCloudResponse(id: Long, t: String?) {}
                    override fun reportCloudError(id: Long, e: String?) {}
                    override fun cancelCloudQuestion(id: Long) {}
                    override fun speakHazardWarning(m: String?) {}
                    override fun updateWalkingAssistState(session: Long, seq: Long, ts: Long, status: String?, announce: Boolean, stopped: Boolean) {}
                    override fun reportForwardCalibrationResult(requestId: Long, successful: Boolean) {}
                    override fun postHazardAlert(session: Long, seq: Long, ts: Long, message: String?) {
                        deliveredEvents.add(HazardAlertEvent(session, seq, ts, message.orEmpty()))
                    }
                    override fun preemptSpeech() {}
                    override fun isServiceActive(): Boolean = false
                    override fun getServiceSessionId(): String = ""
                }
            },
        )

        try {
            bridge.onHazardAlert(
                HazardAlertEvent(
                    session = 1L,
                    sequence = 1L,
                    timestampNanos = SystemClock.elapsedRealtimeNanos(),
                    message = "주의",
                )
            )

            assertEquals("When voice service is stopped, alerts must be dropped without revival", 0, deliveredEvents.size)
        } finally {
            bridge.close()
            AriaRuntimeStateBridge.resetForTesting()
        }
    }
}
