package com.ariateam.aria.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.util.Log
import com.ariateam.aria.MainActivity
import com.ariateam.aria.R
import com.ariateam.aria.command.CommandCoordinator
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import com.ariateam.aria.runtime.AriaUnifiedNotificationCoordinator

import java.util.UUID

/** One main-thread controller owns all platform recognition, including background wake listening. */
class VoiceService : Service() {
    private val serviceSessionId = "voice_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = object : RemoteCallbackList<IVoiceCallback>() {
        override fun onCallbackDied(callback: IVoiceCallback?) {
            main.post { if (!destroyed) coordinator.setVisible(false) }
        }
    }
    private data class PublishedState(val value: VoiceState, val revision: Long)
    @Volatile private var published = PublishedState(VoiceState(), 0)
    private val state get() = published.value
    private val isEffectivelyActive: Boolean
        get() = state.isActive && state.phase != VoicePhase.Stopping
    private var destroyed = false
    private var foreground = false
    private lateinit var session: VoiceListeningSession
    private lateinit var coordinator: CommandCoordinator
    private lateinit var feedback: VoiceFeedback
    private lateinit var wakeLock: PowerManager.WakeLock

    private val binder = object : IVoiceService.Stub() {
        override fun getState(): Bundle = published.let { it.value.toBundle(it.revision) }
        override fun registerCallback(callback: IVoiceCallback) {
            main.post { callbacks.register(callback); deliver(callback) }
        }
        override fun unregisterCallback(callback: IVoiceCallback) { main.post { callbacks.unregister(callback) } }
        override fun beginDictation() { main.post { if (!destroyed && foreground) session.beginManual() } }
        override fun finishDictation() { main.post { if (!destroyed) session.finishInput() } }
        override fun setAudibleFeedback(enabled: Boolean) { main.post { coordinator.setAudible(enabled) } }
        override fun stop() { main.post { requestStop() } }
        override fun setUiVisible(visible: Boolean) { main.post { coordinator.setVisible(visible) } }
        override fun setUiScreen(screen: String) { main.post { coordinator.setScreen(screen) } }
        override fun consumeNavigation(requestId: Long) { main.post { coordinator.consumeNavigation(requestId) } }
        override fun consumeCloudQuestion(requestId: Long) { main.post { if (!destroyed) coordinator.consumeCloudQuestion(requestId) } }
        override fun speakCloudResponse(questionId: Long, text: String) { main.post { if (!destroyed) coordinator.speakCloudResponse(questionId, text) } }
        override fun reportCloudError(questionId: Long, errorText: String) { main.post { if (!destroyed) coordinator.reportCloudError(questionId, errorText) } }
        override fun cancelCloudQuestion(questionId: Long) { main.post { if (!destroyed) coordinator.cancelCloudQuestion(questionId) } }
        override fun speakHazardWarning(message: String) { main.post { if (!destroyed) coordinator.speakHazardWarning(message) } }
        override fun postHazardAlert(session: Long, sequence: Long, timestampNanos: Long, message: String) {
            main.post { if (!destroyed) coordinator.postHazardAlert(session, sequence, timestampNanos, message) }
        }
        override fun preemptSpeech() { main.post { if (!destroyed) coordinator.preemptSpeech() } }
        override fun isServiceActive(): Boolean = this@VoiceService.isEffectivelyActive
        override fun getServiceSessionId(): String = this@VoiceService.serviceSessionId
    }

    override fun onCreate() {
        super.onCreate()
        feedback = VoiceFeedback(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aria:Voice").apply { setReferenceCounted(false) }

        val wakeDetector = try {
            SherpaWakeWordDetector.createIfAvailable(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Sherpa wake word detector failed to initialize, falling back to Android SpeechRecognizer", t)
            null
        }

        session = VoiceListeningSession(
            driver = AndroidRecognitionDriver.production(this),
            scheduler = MainRecognitionScheduler(),
            listener = object : VoiceListeningSession.Listener {
                override fun state(snapshot: ListeningSnapshot) {
                    if (!destroyed) publish(state.copy(
                        phase = snapshot.phase,
                        transcript = snapshot.transcript,
                        errorCode = snapshot.errorCode,
                        retryCount = snapshot.retryCount,
                        retryAtMillis = snapshot.retryAtMillis,
                        wakeFallback = snapshot.wakeFallback,
                    ))
                }
                override fun inputStarted() { coordinator.inputStarted() }
                override fun wakeDetected() { feedback.play(VoiceCue.Detected, audible = false) }
                override fun command(text: String) { coordinator.finalized(text) }
                override fun emptyInput() { coordinator.recognitionFailed(getString(R.string.dictation_no_result)) }
                override fun fatal(errorCode: Int) {
                    // The controller is already stopped. Keep its Error snapshot visible.
                    coordinator.stopped()
                    releaseResources()
                    postErrorNotification(errorCode)
                    stopSelf()
                }
            },
            wakeDetector = wakeDetector,
        )
        coordinator = CommandCoordinator(this,
            update = { next -> if (!destroyed) publish(state.copy(command = next)) },
            soundChanged = { feedback.audibleEnabled = it },
            suppress = { session.playbackChanged(it) },
            followUp = { session.beginFollowUp() },
            finishFollowUp = { session.cancelInput() },
            stopListening = { requestStop() },
            vibrate = { feedback.play(VoiceCue.Detected, audible = false) },
        )
        AriaUnifiedNotificationCoordinator.ensureChannel(this)
        publish(state)
    }

    override fun onBind(intent: Intent): IBinder {
        if (isEffectivelyActive) {
            runCatching {
                AriaRuntimeStateBridge.setVoiceActive(this, true, sessionId = serviceSessionId, token = binder)
            }
        }
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAIT, ACTION_DICTATE, ACTION_RETRY, ACTION_RESTART -> {
                try {
                    AriaUnifiedNotificationCoordinator.cancelErrorNotification(this)
                    AriaRuntimeStateBridge.setVoiceActive(this, true, sessionId = serviceSessionId, token = binder)
                    if (!foreground) {
                        val visionActive = AriaRuntimeStateBridge.isVisionActive(this)
                        startForeground(
                            AriaUnifiedNotificationCoordinator.NOTIFICATION_ID,
                            AriaUnifiedNotificationCoordinator.buildUnifiedNotification(
                                context = this,
                                voiceActive = true,
                                visionActive = visionActive,
                            ),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                        )
                        foreground = true
                    }
                    if (!wakeLock.isHeld) wakeLock.acquire()
                    if (!isEffectivelyActive) {
                        coordinator.started()
                    } else if (intent.action == ACTION_RETRY) {
                        coordinator.retryPreparation()
                    }
                    if (isEffectivelyActive && intent.action == ACTION_RESTART) {
                        session.stop()
                    }
                    session.start(manual = intent.action == ACTION_DICTATE)
                } catch (_: Exception) {
                    session.stop()
                    coordinator.stopped()
                    releaseResources()
                    publish(state.copy(phase = VoicePhase.Error, errorCode = VoiceError.START_FAILED))
                    stopSelf()
                }
            }
            ACTION_STOP -> requestStop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        if (destroyed) return
        publish(state.copy(phase = VoicePhase.Stopping))
        session.stop() // Invalidates recognizer callbacks, watchdogs and scheduled restarts synchronously.
        coordinator.stopped()
        releaseResources()
        stopSelf()
    }

    private fun releaseResources() {
        if (wakeLock.isHeld) wakeLock.release()
        AriaRuntimeStateBridge.setVoiceActive(this, false, sessionId = serviceSessionId, token = binder)
        if (foreground) {
            val visionActive = AriaRuntimeStateBridge.isVisionActive(this)
            if (visionActive) {
                stopForeground(STOP_FOREGROUND_DETACH)
                val notif = AriaUnifiedNotificationCoordinator.buildUnifiedNotification(
                    context = this,
                    voiceActive = false,
                    visionActive = true,
                )
                getSystemService(NotificationManager::class.java)?.notify(
                    AriaUnifiedNotificationCoordinator.NOTIFICATION_ID,
                    notif,
                )
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            foreground = false
        }
    }

    private fun publish(next: VoiceState) {
        if (state.phase != next.phase || state.errorCode != next.errorCode) {
            Log.i(TAG, "phase=${next.phase}, error=${next.errorCode}, retry=${next.retryCount}, wakeFallback=${next.wakeFallback}")
        }
        published = PublishedState(next.copy(command = coordinator.snapshot), published.revision + 1)
        val count = callbacks.beginBroadcast()
        try { repeat(count) { deliver(callbacks.getBroadcastItem(it)) } }
        finally { callbacks.finishBroadcast() }
    }

    private fun deliver(callback: IVoiceCallback) {
        val value = published
        runCatching { callback.onState(value.value.toBundle(value.revision)) }
    }

    private fun postErrorNotification(errorCode: Int) {
        val message = when (errorCode) {
            VoiceError.PERMISSION -> getString(R.string.error_permission)
            VoiceError.MICROPHONE -> getString(R.string.error_microphone)
            VoiceError.RECOGNITION_UNAVAILABLE -> getString(R.string.error_recognition_unavailable)
            VoiceError.RECOGNITION_LANGUAGE -> getString(R.string.error_recognition_language)
            VoiceError.RECOGNITION_RATE_LIMIT -> getString(R.string.error_recognition_rate_limit)
            VoiceError.RECOGNITION_NETWORK -> getString(R.string.error_recognition_network)
            VoiceError.TIMEOUT -> getString(R.string.error_timeout)
            else -> getString(R.string.error_engine)
        }
        AriaUnifiedNotificationCoordinator.postErrorNotification(
            context = this,
            title = getString(R.string.app_name),
            detail = message,
        )
    }

    override fun onDestroy() {
        destroyed = true
        session.shutdown()
        coordinator.close()
        releaseResources()
        feedback.close()
        callbacks.kill()
        super.onDestroy()
    }

    companion object {
        const val ACTION_WAIT = "com.ariateam.aria.WAIT"
        const val ACTION_DICTATE = "com.ariateam.aria.DICTATE"
        const val ACTION_RETRY = "com.ariateam.aria.RETRY"
        const val ACTION_RESTART = "com.ariateam.aria.RESTART"
        const val ACTION_STOP = "com.ariateam.aria.STOP"
        private const val TAG = "AriaVoiceService"
    }
}
