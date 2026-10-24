package com.ariateam.aria.startup

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.ariateam.aria.R

/**
 * Interface representing a startup sound cue player.
 */
interface StartupSoundPlayer : AutoCloseable {

    /**
     * Requests playback of the startup completion sound cue.
     *
     * @param audible Whether audible cues are enabled.
     * @param isForeground Whether the host screen is currently in the foreground.
     * @return `true` if playback was initiated or queued pending asynchronous load completion.
     */
    fun playCue(audible: Boolean, isForeground: Boolean): Boolean

    /**
     * Called when the host activity or screen enters the background to cancel pending playback
     * and stop any ongoing audio.
     */
    fun onAppBackgrounded()

    /**
     * Releases all underlying sound resources and callbacks immediately.
     */
    fun release()

    override fun close() = release()
}

/**
 * Default implementation of [StartupSoundPlayer] using [SoundPool].
 *
 * Handles asynchronous sound loading, foreground gating, and lifecycle cleanup without
 * leaking callbacks or producing stale playback after backgrounding or disposal.
 */
class DefaultStartupSoundPlayer(
    private val context: Context,
    private val soundResId: Int = R.raw.startup_ready,
    soundPoolFactory: () -> SoundPool = {
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    },
) : StartupSoundPlayer {

    private val lock = Any()
    private var pool: SoundPool? = null
    private var soundId: Int = 0
    private var isLoaded: Boolean = false
    private var isReleased: Boolean = false
    private var isForeground: Boolean = true
    private var pendingPlay: Boolean = false
    private var activeStreamId: Int = 0

    init {
        try {
            val createdPool = soundPoolFactory()
            pool = createdPool
            createdPool.setOnLoadCompleteListener { _, sampleId, status ->
                synchronized(lock) {
                    if (isReleased) return@setOnLoadCompleteListener
                    if (sampleId == soundId) {
                        if (status == 0) {
                            isLoaded = true
                            if (pendingPlay && isForeground) {
                                pendingPlay = false
                                playInternalLocked()
                            } else {
                                pendingPlay = false
                            }
                        } else {
                            pendingPlay = false
                        }
                    }
                }
            }
            soundId = createdPool.load(context, soundResId, 1)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize SoundPool for startup cue", e)
        }
    }

    override fun playCue(audible: Boolean, isForeground: Boolean): Boolean {
        synchronized(lock) {
            this.isForeground = isForeground
            if (isReleased || !audible || !isForeground) {
                pendingPlay = false
                return false
            }
            return if (isLoaded) {
                playInternalLocked()
                true
            } else {
                pendingPlay = true
                true
            }
        }
    }

    override fun onAppBackgrounded() {
        synchronized(lock) {
            isForeground = false
            pendingPlay = false
            stopStreamLocked()
        }
    }

    override fun release() {
        synchronized(lock) {
            if (isReleased) return
            isReleased = true
            pendingPlay = false
            stopStreamLocked()
            try {
                pool?.setOnLoadCompleteListener(null)
                pool?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing SoundPool", e)
            } finally {
                pool = null
            }
        }
    }

    private fun playInternalLocked() {
        val currentPool = pool ?: return
        if (isReleased || soundId == 0) return
        try {
            activeStreamId = currentPool.play(soundId, 1f, 1f, 1, 0, 1f)
            if (activeStreamId == 0) {
                Log.w(TAG, "Startup cue playback returned stream 0 (load incomplete or error)")
            } else {
                Log.d(TAG, "Startup cue playback started: stream=$activeStreamId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play startup sound", e)
        }
    }

    private fun stopStreamLocked() {
        if (activeStreamId != 0) {
            try {
                pool?.stop(activeStreamId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop active sound stream", e)
            }
            activeStreamId = 0
        }
    }

    companion object {
        private const val TAG = "StartupSoundPlayer"
    }
}
