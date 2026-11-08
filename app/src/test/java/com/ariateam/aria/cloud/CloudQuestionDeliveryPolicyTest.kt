package com.ariateam.aria.cloud

import com.ariateam.aria.command.CommandSnapshot
import com.ariateam.aria.speech.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudQuestionDeliveryPolicyTest {
    @Test
    fun activeCollectorDeliversNewQuestionAfterAdmissionAndServiceResyncExactlyOnce() = runBlocking {
        val initial = VoiceState(command = CommandSnapshot(cloudQuestionId = 20L))
        val voiceState = MutableStateFlow(initial)
        val serviceSynced = MutableStateFlow(true)
        val admitted = MutableStateFlow(false)
        val policy = CloudQuestionDeliveryPolicy(initialLastQuestionId = 20L, initialHasBaseline = true)
        val delivered = CompletableDeferred<CloudQuestionDelivery.Request>()
        var deliveryCount = 0

        val collector = launch {
            collectCloudQuestionDeliveries(voiceState, serviceSynced, admitted, policy) { event ->
                if (event is CloudQuestionDelivery.Request) {
                    deliveryCount++
                    delivered.complete(event)
                }
            }
        }

        // Update the backing flow while the admission gate is closed, as happens before UI readiness.
        voiceState.value = VoiceState(
            command = CommandSnapshot(
                cloudQuestionId = 21L,
                cloudQuestionType = "photo",
                cloudQuestionPrompt = "전방 표지판 글씨 읽어줘",
            ),
        )
        assertNull("Unadmitted photo requests must remain pending", policy.evaluate(voiceState.value, true, false))
        admitted.value = true
        val request = withTimeout(2_000L) { delivered.await() }
        assertEquals(21L, request.questionId)
        assertEquals("photo", request.type)
        assertEquals("전방 표지판 글씨 읽어줘", request.prompt)

        // Service resync and replay of the same StateFlow ID cannot dispatch it twice.
        serviceSynced.value = false
        serviceSynced.value = true
        withContext(Dispatchers.Default) { Thread.yield() }
        assertEquals(1, deliveryCount)
        collector.cancel()
    }

    @Test
    fun firstSynchronizedStateBaselinesWithoutReplayingAndZeroIdCancels() {
        val policy = CloudQuestionDeliveryPolicy(initialLastQuestionId = 0L, initialHasBaseline = false)
        val existing = VoiceState(command = CommandSnapshot(cloudQuestionId = 8L))
        assertNull(policy.evaluate(existing, serviceSynced = false, admitted = true))
        assertNull(policy.evaluate(existing, serviceSynced = true, admitted = true))
        assertEquals(8L, policy.lastQuestionId)
        assertTrue(policy.hasBaseline)
        assertEquals(
            CloudQuestionDelivery.Cancel,
            policy.evaluate(VoiceState(command = CommandSnapshot(cloudQuestionId = 0L)), true, true),
        )
    }

    @Test
    fun firstSynchronizedActiveRequestIsDeliveredAndWaitsForAdmission() {
        val policy = CloudQuestionDeliveryPolicy(initialLastQuestionId = 0L, initialHasBaseline = false)
        val activeQuestion = VoiceState(
            command = CommandSnapshot(
                cloudQuestionId = 42L,
                cloudQuestionType = "photo",
                cloudQuestionPrompt = "앞에 있는 게 뭐야",
                processing = true,
            ),
        )

        assertNull(policy.evaluate(activeQuestion, serviceSynced = true, admitted = false))
        assertEquals(
            CloudQuestionDelivery.Request(42L, "photo", "앞에 있는 게 뭐야"),
            policy.evaluate(activeQuestion, serviceSynced = true, admitted = true),
        )
        assertNull("Handled active ID cannot be replayed", policy.evaluate(activeQuestion, true, true))
    }
}
