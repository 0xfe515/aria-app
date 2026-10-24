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

/** One main-thread controller owns all platform recognition, including background wake listening. */
class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = object : RemoteCallbackList<IVoiceCallback>() {
        override fun onCallbackDied(callback: IVoiceCallback?) {
            main.post { if (!destroyed) coordinator.setVisible(false) }
        }
    }
    private data class PublishedState(val value: VoiceState, val revision: Long)
    @Volatile private var published = PublishedState(VoiceState(), 0)
    private val state get() = published.value
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
        override fun downloadModel(mobileData: Boolean) { main.post { coordinator.download(mobileData) } }
        override fun cancelModelDownload() { main.post { coordinator.cancelDownload() } }
        override fun deleteModel() { main.post { coordinator.deleteModel() } }
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.listening_channel), NotificationManager.IMPORTANCE_LOW))
        publish(state)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAIT, ACTION_DICTATE, ACTION_RETRY, ACTION_RESTART -> {
                try {
                    getSystemService(NotificationManager::class.java).cancel(ERROR_NOTIFICATION)
                    if (!foreground) {
                        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                        foreground = true
                    }
                    if (!wakeLock.isHeld) wakeLock.acquire()
                    if (!state.isActive) {
                        coordinator.started()
                    } else if (intent.action == ACTION_RETRY) {
                        coordinator.retryPreparation()
                    }
                    if (state.isActive && intent.action == ACTION_RESTART) {
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
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
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
        val manager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, ERROR_NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(ERROR_NOTIFICATION, notification)
    }

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.listening_notification))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_listening), stopIntent).build())
            .setOngoing(true)
            .build()
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
        private const val CHANNEL = "wakeword"
        private const val NOTIFICATION = 1
        private const val ERROR_NOTIFICATION = 2
        private const val TAG = "AriaVoiceService"
    }
}
