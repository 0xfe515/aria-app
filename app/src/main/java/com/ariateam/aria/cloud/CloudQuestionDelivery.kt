package com.ariateam.aria.cloud

import com.ariateam.aria.speech.VoiceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

internal sealed interface CloudQuestionDelivery {
    data object Cancel : CloudQuestionDelivery
    data class Request(val questionId: Long, val type: String, val prompt: String) : CloudQuestionDelivery
}

/** Retains an unadmitted or unsynchronized request until its gates open, with ID replay protection. */
internal class CloudQuestionDeliveryPolicy(
    initialLastQuestionId: Long,
    initialHasBaseline: Boolean,
) {
    var lastQuestionId: Long = initialLastQuestionId
        private set
    var hasBaseline: Boolean = initialHasBaseline
        private set

    fun evaluate(state: VoiceState, serviceSynced: Boolean, admitted: Boolean): CloudQuestionDelivery? {
        if (!serviceSynced) return null
        val questionId = state.command.cloudQuestionId
        if (!hasBaseline) {
            hasBaseline = true
            if (questionId == 0L || !state.command.processing) {
                lastQuestionId = questionId
                return null
            }
            // A live active request observed at first sync is pending work, not stale replay.
            lastQuestionId = questionId - 1L
        }
        if (questionId == 0L) return CloudQuestionDelivery.Cancel
        if (questionId <= lastQuestionId || !admitted) return null
        lastQuestionId = questionId
        return CloudQuestionDelivery.Request(
            questionId = questionId,
            type = state.command.cloudQuestionType,
            prompt = state.command.cloudQuestionPrompt,
        )
    }
}

/** Collects the replayable request state independent of Activity STARTED/RESUMED collection. */
internal suspend fun collectCloudQuestionDeliveries(
    voiceState: StateFlow<VoiceState>,
    serviceSynced: StateFlow<Boolean>,
    admitted: Flow<Boolean>,
    policy: CloudQuestionDeliveryPolicy,
    onDelivery: (CloudQuestionDelivery?) -> Unit,
) {
    combine(voiceState, serviceSynced, admitted, ::Triple).collect { (state, synced, isAdmitted) ->
        onDelivery(policy.evaluate(state, synced, isAdmitted))
    }
}
