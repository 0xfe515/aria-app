package com.ariateam.aria.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        bridge.start()
        HazardAlertCoordinator.setSharedAlertSink(bridge)

        val coordinator = HazardAlertCoordinator(
            context = context,
            toastDispatcher = {},
            vibratorDispatcher = {},
        )
        coordinator.start()

        // Feed two path blocked assessments to exceed hysteresis requirement (confirmationFramesRequired = 2)
        val now = System.nanoTime()
        val assessment = HazardAssessment(
            level = HazardLevel.PATH_BLOCKED,
            motionConfidence = 0.95f,
            messageKorean = "전방 경로 막힘",
            timestampNanos = now,
            isAlertCandidate = true,
        )

        coordinator.processAssessment(assessment, now)
        coordinator.processAssessment(assessment, now + 1_000_000L)

        val received = latch.await(2, TimeUnit.SECONDS)
        assertTrue("Hazard alert should be dispatched to registered bridge", received)
        assertEquals(1, deliveredEvents.size)
        assertEquals("전방 경로 막힘", deliveredEvents[0].message)

        coordinator.stop()
        coordinator.close()
        bridge.close()
        assertNull(HazardAlertCoordinator.getSharedAlertSink())
    }

    @Test
    fun hazardSpeechBridge_doesNotRestartStoppedVoiceService() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(false)

        val deliveredEvents = CopyOnWriteArrayList<HazardAlertEvent>()
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
                    override fun postHazardAlert(session: Long, seq: Long, ts: Long, message: String?) {
                        deliveredEvents.add(HazardAlertEvent(session, seq, ts, message.orEmpty()))
                    }
                    override fun preemptSpeech() {}
                    override fun isServiceActive(): Boolean = false
                    override fun getServiceSessionId(): String = ""
                }
            },
        )

        bridge.onHazardAlert(
            HazardAlertEvent(
                session = 1L,
                sequence = 1L,
                timestampNanos = System.nanoTime(),
                message = "주의",
            )
        )

        assertEquals("When voice service is stopped, alerts must be dropped without revival", 0, deliveredEvents.size)
        bridge.close()
    }
}
