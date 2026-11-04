package com.ariateam.aria.runtime

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.ariateam.aria.speech.IVoiceService
import com.ariateam.aria.speech.VoiceService
import com.ariateam.aria.vision.VisionForegroundService
import com.ariateam.aria.vision.VisionSession
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles the unified Stop action from the persistent foreground notification.
 * Terminates both VoiceService and VisionForegroundService.
 *
 * For VoiceService:
 * 1. Binds to an existing VoiceService with flag 0 (Context.BIND_AUTO_CREATE is strictly NOT used)
 *    to invoke existing contract IVoiceService.stop(), ensuring recognizer, wakelock, and speech
 *    cleanup occurs even when UI clients (VoiceViewModel) remain bound.
 * 2. Employs a bounded connection lifecycle with single-execution cleanup across onServiceConnected,
 *    onNullBinding, onBindingDied, onServiceDisconnected, bindFailed, or timeout, followed by
 *    context.stopService() fallback to ensure the started state is cleared.
 * 3. Does NOT unilaterally report false inactive in the bridge; acknowledges inactive status
 *    only upon confirmed service lifecycle cleanup (releaseResources/onDestroy) or binder death.
 * 4. Uses goAsync() when available so one-way IPC and bounded cleanup complete reliably.
 */
class AriaUnifiedStopReceiver : BroadcastReceiver() {

    internal interface VoiceStopDriver {
        fun bindVoiceService(context: Context?, connection: ServiceConnection, flags: Int): Boolean
        fun unbindVoiceService(context: Context?, connection: ServiceConnection)
        fun stopVoiceService(context: Context?): Boolean
    }

    internal object AndroidVoiceStopDriver : VoiceStopDriver {
        override fun bindVoiceService(context: Context?, connection: ServiceConnection, flags: Int): Boolean {
            if (context == null) return false
            val intent = Intent(context, VoiceService::class.java)
            return runCatching { context.bindService(intent, connection, flags) }.getOrDefault(false)
        }

        override fun unbindVoiceService(context: Context?, connection: ServiceConnection) {
            if (context == null) return
            runCatching { context.unbindService(connection) }
        }

        override fun stopVoiceService(context: Context?): Boolean {
            if (context == null) return false
            val intent = Intent(context, VoiceService::class.java)
            return runCatching { context.stopService(intent) }.getOrDefault(false)
        }
    }

    internal class VoiceStopConnection(
        private val appContext: Context?,
        private val driver: VoiceStopDriver,
        private val pendingResult: PendingResult?,
        private val timeoutMs: Long,
        scheduler: ScheduledExecutorService,
    ) : ServiceConnection {
        val cleanedUp = AtomicBoolean(false)
        val unbindCount = AtomicInteger(0)
        val isBound = AtomicBoolean(false)
        val stopIpcRequested = AtomicBoolean(false)
        val fallbackStopInvoked = AtomicBoolean(false)
        val cleanupReason = AtomicReference<String?>(null)

        @Volatile
        private var timeoutFuture: ScheduledFuture<*>? = null

        init {
            if (timeoutMs > 0) {
                timeoutFuture = scheduler.schedule({
                    onTimeout()
                }, timeoutMs, TimeUnit.MILLISECONDS)
            }
        }

        fun cleanup(reason: String) {
            if (!cleanedUp.compareAndSet(false, true)) return
            cleanupReason.set(reason)
            timeoutFuture?.cancel(false)
            timeoutFuture = null

            if (isBound.get()) {
                unbindCount.incrementAndGet()
                driver.unbindVoiceService(appContext, this)
            }

            // Fallback stopService guarantees started state termination
            invokeFallbackStop()

            // Complete broadcast if running asynchronously
            runCatching { pendingResult?.finish() }
        }

        private fun invokeFallbackStop() {
            if (fallbackStopInvoked.compareAndSet(false, true)) {
                driver.stopVoiceService(appContext)
            }
        }

        private fun onTimeout() {
            if (cleanedUp.get()) return
            runCatching { Log.w(TAG, "VoiceService stop connection timed out after ${timeoutMs}ms") }
            cleanup("timeout")
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (cleanedUp.get()) {
                runCatching { Log.i(TAG, "Ignoring late onServiceConnected after cleanup (${cleanupReason.get()})") }
                return
            }
            try {
                if (service != null && service.isBinderAlive) {
                    val voiceService = IVoiceService.Stub.asInterface(service)
                    if (voiceService != null) {
                        runCatching {
                            voiceService.stop()
                            stopIpcRequested.set(true)
                            Log.i(TAG, "Requested voice resource cleanup via IVoiceService.stop() IPC")
                        }.onFailure { err ->
                            Log.w(TAG, "Failed calling IVoiceService.stop() IPC", err)
                        }
                    }
                }
            } catch (t: Throwable) {
                runCatching { Log.w(TAG, "Exception during voice stop onServiceConnected", t) }
            } finally {
                cleanup("onServiceConnected")
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            if (cleanedUp.get()) return
            runCatching { Log.i(TAG, "VoiceService returned null binding during stop") }
            cleanup("onNullBinding")
        }

        override fun onBindingDied(name: ComponentName?) {
            if (cleanedUp.get()) return
            runCatching { Log.w(TAG, "VoiceService binding died during stop") }
            cleanup("onBindingDied")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (cleanedUp.get()) return
            runCatching { Log.i(TAG, "VoiceService disconnected during stop") }
            cleanup("onServiceDisconnected")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AriaUnifiedNotificationCoordinator.ACTION_STOP_ALL) return
        runCatching { Log.i(TAG, "AriaUnifiedStopReceiver: Stopping all foreground services upon user action") }

        // 1. Mark explicit user stop and stop camera foreground service
        VisionSession.markExplicitStop(context)
        VisionForegroundService.requestStop(context)

        // 2. Terminate VoiceService via bound IPC using flag 0 with fallback stopService
        val pendingResult = runCatching { goAsync() }.getOrNull()
        requestVoiceStop(
            context = context,
            pendingResult = pendingResult,
        )

        // 3. Do NOT unilaterally write services inactive here.
        // Acknowledge inactive state only when lifecycle cleanup (onDestroy/releaseResources)
        // or binder death proof confirms termination.
    }

    companion object {
        private const val TAG = "AriaUnifiedStopReceiver"
        const val DEFAULT_STOP_TIMEOUT_MS = 2000L

        private val defaultStopScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "aria-unified-stop").apply { isDaemon = true }
            }

        internal fun requestVoiceStop(
            context: Context?,
            driver: VoiceStopDriver = AndroidVoiceStopDriver,
            timeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
            scheduler: ScheduledExecutorService = defaultStopScheduler,
            pendingResult: PendingResult? = null,
        ): VoiceStopConnection {
            val appContext = context?.applicationContext ?: context
            val connection = VoiceStopConnection(
                appContext = appContext,
                driver = driver,
                pendingResult = pendingResult,
                timeoutMs = timeoutMs,
                scheduler = scheduler,
            )

            val bound = runCatching {
                driver.bindVoiceService(appContext, connection, 0)
            }.getOrDefault(false)

            if (bound) {
                connection.isBound.set(true)
            } else {
                runCatching { Log.i(TAG, "VoiceService not running or bind returned false (flag 0)") }
                connection.cleanup("bindFailed")
            }

            return connection
        }
    }
}
