package com.ariateam.aria.startup

/**
 * Gating policy for the startup boot completion sound cue.
 *
 * Requirements:
 * - Plays once when actual loading completes and main entry is admitted.
 * - Plays only while the application is in the foreground.
 * - Must not repeat on recomposition, tab/settings navigation, rotation, or ordinary resume.
 * - Must not play prematurely during loading or error/action-required stages.
 * - Respects the user's audible feedback preference.
 */
object StartupCuePolicy {

    /**
     * Evaluates whether the startup session should record completion and attempt to play the boot cue.
     *
     * @param hasPlayed Whether the boot cue has already played for this startup session.
     * @param decision The current startup evaluation decision.
     * @param isForeground Whether the screen is currently visible/resumed in the foreground.
     * @return `true` if startup loading is complete and the app is ready in the foreground for the first time.
     */
    fun shouldTriggerAdmissionCue(
        hasPlayed: Boolean,
        decision: StartupDecision,
        isForeground: Boolean,
    ): Boolean {
        if (hasPlayed) return false
        if (!isForeground) return false
        return decision is StartupDecision.Ready
    }

    /**
     * Evaluates whether the audio cue sound should actually be produced, taking audible feedback preference into account.
     *
     * @param hasPlayed Whether the boot cue has already played for this startup session.
     * @param decision The current startup evaluation decision.
     * @param isForeground Whether the screen is currently visible/resumed in the foreground.
     * @param audibleEnabled Whether audible feedback cues are enabled in user settings.
     * @return `true` if sound should be played.
     */
    fun shouldPlaySound(
        hasPlayed: Boolean,
        decision: StartupDecision,
        isForeground: Boolean,
        audibleEnabled: Boolean,
    ): Boolean {
        return shouldTriggerAdmissionCue(hasPlayed, decision, isForeground) && audibleEnabled
    }
}
