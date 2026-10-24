package com.ariateam.aria.ui.feedback

import androidx.compose.runtime.saveable.Saver
import com.ariateam.aria.speech.VoiceError
import com.ariateam.aria.speech.VoiceState

sealed interface FeedbackToast {
    data class CommandOutcome(val message: String, val eventId: Long) : FeedbackToast
    data class VoiceErrorNotification(val errorCode: Int) : FeedbackToast
}

/**
 * Manages transient Toast feedback rules:
 * - Suppresses initial stale/default state and delayed initial binder snapshots.
 * - Tracks live-service baseline independently from visibility/default initialization so the first
 *   ready snapshot ALWAYS absorbs baseline regardless of prior visibility sync.
 * - While service is not synced (!isServiceSynced), always suppresses service command events
 *   while independently allowing local permission/start failures.
 * - Deduplicates toasts across recomposition, configuration change, and resume.
 * - Allows repeated identical new command outcomes to toast when their event ID advances.
 * - Suppresses watchdog and retry-count ticks while reconnecting.
 * - Only delivers toasts when the app UI is visible.
 * - Automatically absorbs events while in background so resuming never replays stale background events.
 */
class ToastFeedbackPolicy(
    private var lastHandledCommandEventId: Long = 0L,
    private var lastHandledErrorCode: Int = VoiceError.NONE,
    private var wasInError: Boolean = false,
    private var initialSyncDone: Boolean = false,
    private var liveServiceBaselineSynced: Boolean = false,
) {
    /**
     * Explicitly syncs the baseline from a known state (e.g., initial live service connection
     * or unit test initialization), absorbing any pre-existing command or error without toasting.
     */
    fun syncInitialState(state: VoiceState) {
        initialSyncDone = true
        liveServiceBaselineSynced = true
        if (state.command.eventId > 0L) {
            lastHandledCommandEventId = state.command.eventId
        }
        lastHandledErrorCode = state.errorCode
        wasInError = state.errorCode != VoiceError.NONE
    }

    /**
     * Absorbs the initial snapshot from the bound service when connection is established.
     */
    fun absorbLiveServiceSnapshot(state: VoiceState) {
        liveServiceBaselineSynced = true
        initialSyncDone = true
        if (state.command.eventId > 0L) {
            lastHandledCommandEventId = state.command.eventId
        }
        lastHandledErrorCode = state.errorCode
        wasInError = state.errorCode != VoiceError.NONE
    }

    /**
     * Called when app UI visibility changes.
     * When transitioning from background to visible, consumes pre-existing background events
     * without setting liveServiceBaselineSynced (allowing the first ready snapshot to absorb baseline).
     */
    fun onAppVisibilityChanged(isAppVisible: Boolean, currentState: VoiceState) {
        if (isAppVisible) {
            if (currentState.command.eventId > 0L) {
                lastHandledCommandEventId = currentState.command.eventId
            }
            if (currentState.errorCode != VoiceError.NONE) {
                wasInError = true
                lastHandledErrorCode = currentState.errorCode
            }
        } else {
            // While going into background, mark current IDs as consumed
            if (currentState.command.eventId > 0L) {
                lastHandledCommandEventId = currentState.command.eventId
            }
            if (currentState.errorCode != VoiceError.NONE) {
                wasInError = true
                lastHandledErrorCode = currentState.errorCode
            }
        }
    }

    /**
     * Evaluates whether the incoming [state] warrants a Toast notification.
     *
     * @param state The current VoiceState.
     * @param isAppVisible Whether the app UI is currently visible (resumed).
     * @param isServiceSynced Whether the live voice service has performed its initial sync.
     *
     * Returns a [FeedbackToast] if an actionable new event occurred while [isAppVisible] is true,
     * or null if suppressed/deduplicated.
     */
    fun evaluate(
        state: VoiceState,
        isAppVisible: Boolean,
        isServiceSynced: Boolean = true,
    ): FeedbackToast? {
        // While !isServiceSynced: always suppress service command events
        // and independently allow local permission/start failures.
        // Do NOT condition this gate on policy initialSyncDone.
        if (!isServiceSynced) {
            if (state.command.eventId > 0L) {
                lastHandledCommandEventId = state.command.eventId
            }
            if (isAppVisible && state.errorCode != VoiceError.NONE) {
                val currentErrorCode = state.errorCode
                if (!wasInError || currentErrorCode != lastHandledErrorCode) {
                    wasInError = true
                    lastHandledErrorCode = currentErrorCode
                    return FeedbackToast.VoiceErrorNotification(currentErrorCode)
                }
            } else if (state.errorCode == VoiceError.NONE) {
                wasInError = false
                lastHandledErrorCode = VoiceError.NONE
            }
            return null
        }

        // When live service is ready (isServiceSynced == true):
        // First ready snapshot ALWAYS absorbs baseline regardless of prior visibility sync.
        if (!liveServiceBaselineSynced) {
            liveServiceBaselineSynced = true
            initialSyncDone = true
            if (state.command.eventId > 0L) {
                lastHandledCommandEventId = state.command.eventId
            }
            if (state.errorCode != VoiceError.NONE) {
                wasInError = true
                lastHandledErrorCode = state.errorCode
            } else {
                wasInError = false
                lastHandledErrorCode = VoiceError.NONE
            }
            return null
        }

        if (!initialSyncDone) {
            initialSyncDone = true
            if (state.command.eventId > 0L) {
                lastHandledCommandEventId = state.command.eventId
            }
            if (state.errorCode != VoiceError.NONE) {
                wasInError = true
                lastHandledErrorCode = state.errorCode
            } else {
                wasInError = false
                lastHandledErrorCode = VoiceError.NONE
            }
            return null
        }

        if (!isAppVisible) {
            // While not visible, consume/advance handled IDs so background events are never replayed when resuming
            if (state.command.eventId > 0L) {
                lastHandledCommandEventId = state.command.eventId
            }
            if (state.errorCode != VoiceError.NONE) {
                wasInError = true
                lastHandledErrorCode = state.errorCode
            } else {
                wasInError = false
                lastHandledErrorCode = VoiceError.NONE
            }
            return null
        }

        // 1. Check command outcome
        val commandEventId = state.command.eventId
        if (commandEventId > 0L && commandEventId != lastHandledCommandEventId) {
            lastHandledCommandEventId = commandEventId
            val message = state.command.message.trim()
            if (message.isNotEmpty() && !state.command.processing) {
                return FeedbackToast.CommandOutcome(message, commandEventId)
            }
        }

        // 2. Check voice error (suppressing watchdog ticks and repeated states)
        val currentErrorCode = state.errorCode
        if (currentErrorCode != VoiceError.NONE) {
            if (!wasInError || currentErrorCode != lastHandledErrorCode) {
                wasInError = true
                lastHandledErrorCode = currentErrorCode
                return FeedbackToast.VoiceErrorNotification(currentErrorCode)
            }
        } else {
            wasInError = false
            lastHandledErrorCode = VoiceError.NONE
        }

        return null
    }

    val currentLastHandledCommandEventId: Long get() = lastHandledCommandEventId
    val currentLastHandledErrorCode: Int get() = lastHandledErrorCode
    val isInitialSyncDone: Boolean get() = initialSyncDone
    val isLiveServiceBaselineSynced: Boolean get() = liveServiceBaselineSynced

    companion object {
        val Saver: Saver<ToastFeedbackPolicy, Any> = Saver(
            save = {
                listOf(
                    it.lastHandledCommandEventId,
                    it.lastHandledErrorCode,
                    it.wasInError,
                    it.initialSyncDone,
                    it.liveServiceBaselineSynced,
                )
            },
            restore = { value ->
                val list = value as? List<*> ?: return@Saver null
                ToastFeedbackPolicy(
                    lastHandledCommandEventId = (list.getOrNull(0) as? Number)?.toLong() ?: 0L,
                    lastHandledErrorCode = (list.getOrNull(1) as? Number)?.toInt() ?: 0,
                    wasInError = (list.getOrNull(2) as? Boolean) ?: false,
                    initialSyncDone = (list.getOrNull(3) as? Boolean) ?: false,
                    liveServiceBaselineSynced = (list.getOrNull(4) as? Boolean) ?: false,
                )
            }
        )
    }
}
