package com.ariateam.aria.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import com.ariateam.aria.R
import java.util.concurrent.ConcurrentHashMap

/** Bundled sound cues also work when no TTS language pack is installed. */
class VoiceFeedback(context: Context) : AutoCloseable {
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    @Volatile var audibleEnabled: Boolean = true
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val start: Int
    private val end: Int

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        start = pool.load(context, R.raw.voice_start_clear, 1)
        end = pool.load(context, R.raw.voice_end_clear, 1)
    }

    /** Returns an audio-discard deadline, or zero when no sound is requested. */
    fun play(cue: VoiceCue, audible: Boolean = true): Long {
        val timings = when (cue) {
            VoiceCue.Start -> longArrayOf(0, 90)
            VoiceCue.Stop -> longArrayOf(0, 180)
            VoiceCue.Detected -> longArrayOf(0, 70, 70, 70)
            VoiceCue.Error -> longArrayOf(0, 250, 80, 250)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        val id = if (cue == VoiceCue.Stop || cue == VoiceCue.Error) end else start
        // Different pitches distinguish start/stop/detection/error without a network or speech engine.
        val rate = when (cue) {
            VoiceCue.Start -> 1f
            VoiceCue.Stop -> 0.8f
            VoiceCue.Detected -> 1.25f
            VoiceCue.Error -> 0.6f
        }
        val playsSound = audible && audibleEnabled
        if (playsSound) {
            val streamId = if (loaded.contains(id)) pool.play(id, 1f, 1f, 1, 0, rate) else 0
            if (streamId == 0) Log.w("AriaVoiceFeedback", "Cue unavailable: cue=$cue, loaded=${loaded.contains(id)}")
            else Log.d("AriaVoiceFeedback", "Cue playback requested: cue=$cue")
        }
        return if (playsSound) SystemClock.elapsedRealtime() + 1_500 else 0
    }

    override fun close() { pool.release() }
}
