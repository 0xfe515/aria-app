package com.ariateam.aria.runtime

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.ariateam.aria.speech.IVoiceService
import com.ariateam.aria.speech.VoiceService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-process state bridge coordinating foreground service statuses between
 * the main process and the :wakeword process.
 *
 * Provides:
 * 1. Session and liveness registration with IBinder death reconciliation.
 * 2. Late session update fencing (ignores stale stop updates from superseded sessions).
 * 3. Provider restart reconciliation without reviving stopped services.
 * 4. Automatic notification updates when state tuples change.
 */
object AriaRuntimeStateBridge {
    internal const val TAG = "AriaRuntimeBridge"
    internal const val METHOD_SET_VOICE_ACTIVE = "setVoiceActive"
    internal const val METHOD_SET_VISION_ACTIVE = "setVisionActive"
    internal const val METHOD_GET_STATE = "getState"
    const val EXTRA_ACTIVE = "active"
    const val EXTRA_VOICE_ACTIVE = "voice_active"
    const val EXTRA_VISION_ACTIVE = "vision_active"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_BINDER_TOKEN = "binder_token"
    const val DEFAULT_RECONCILE_TIMEOUT_MS = 3000L

    private val localVoiceActive = AtomicBoolean(false)
    private val localVisionActive = AtomicBoolean(false)

    @Volatile
    private var voiceSessionId: String? = null
    @Volatile
    private var visionSessionId: String? = null

    private var voiceBinderToken: IBinder? = null
    private var visionBinderToken: IBinder? = null
    private var voiceDeathRecipient: IBinder.DeathRecipient? = null
    private var visionDeathRecipient: IBinder.DeathRecipient? = null

    private val stateLock = Any()
    @Volatile
    private var hostContext: Context? = null

    private fun logI(tag: String, msg: String) {
        runCatching { Log.i(tag, msg) }
    }
    private fun logW(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg) }
    }

    fun getAuthority(context: Context): String = "${context.packageName}.runtime.provider"

    private fun getUri(context: Context): Uri =
        Uri.parse("content://${getAuthority(context)}")

    internal fun initHostContext(context: Context?) {
        hostContext = context?.applicationContext
    }

    /**
     * Registers or unregisters VoiceService active state with optional session ID and IBinder token.
     */
    fun setVoiceActive(
        context: Context,
        active: Boolean,
        sessionId: String? = null,
        token: IBinder? = null,
    ) {
        try {
            val bundle = Bundle().apply {
                putBoolean(EXTRA_ACTIVE, active)
                if (sessionId != null) putString(EXTRA_SESSION_ID, sessionId)
                if (token != null) putBinder(EXTRA_BINDER_TOKEN, token)
            }
            context.contentResolver.call(getUri(context), METHOD_SET_VOICE_ACTIVE, null, bundle)
        } catch (e: Exception) {
            logW(TAG, "Failed to call runtime provider for setVoiceActive: ${e.message}")
            // Fallback for same-process or unit test environments
            handleSetVoiceActiveInternal(context, active, sessionId, token)
        }
    }

    /**
     * Registers or unregisters VisionForegroundService active state with optional session ID and IBinder token.
     */
    fun setVisionActive(
        context: Context,
        active: Boolean,
        sessionId: String? = null,
        token: IBinder? = null,
    ) {
        try {
            val bundle = Bundle().apply {
                putBoolean(EXTRA_ACTIVE, active)
                if (sessionId != null) putString(EXTRA_SESSION_ID, sessionId)
                if (token != null) putBinder(EXTRA_BINDER_TOKEN, token)
            }
            context.contentResolver.call(getUri(context), METHOD_SET_VISION_ACTIVE, null, bundle)
        } catch (e: Exception) {
            logW(TAG, "Failed to call runtime provider for setVisionActive: ${e.message}")
            handleSetVisionActiveInternal(context, active, sessionId, token)
        }
    }

    fun isVoiceActive(context: Context): Boolean {
        return try {
            val res = context.contentResolver.call(getUri(context), METHOD_GET_STATE, null, null)
            res?.getBoolean(EXTRA_VOICE_ACTIVE, localVoiceActive.get()) ?: localVoiceActive.get()
        } catch (e: Exception) {
            localVoiceActive.get()
        }
    }

    fun isVisionActive(context: Context): Boolean {
        return try {
            val res = context.contentResolver.call(getUri(context), METHOD_GET_STATE, null, null)
            res?.getBoolean(EXTRA_VISION_ACTIVE, localVisionActive.get()) ?: localVisionActive.get()
        } catch (e: Exception) {
            localVisionActive.get()
        }
    }

    internal fun handleSetVoiceActiveInternal(
        context: Context?,
        active: Boolean,
        sessionId: String?,
        token: IBinder?,
    ): Boolean {
        var stateChanged = false
        synchronized(stateLock) {
            if (active) {
                if (token != null) {
                    if (!token.isBinderAlive || !token.pingBinder()) {
                        logW(TAG, "Rejecting registration: voice token is already dead")
                        return false
                    }
                    if (token != voiceBinderToken) {
                        var linkSucceeded = false
                        val recipient = IBinder.DeathRecipient {
                            onVoiceBinderDied(sessionId, token)
                        }
                        runCatching {
                            token.linkToDeath(recipient, 0)
                            linkSucceeded = true
                        }.onFailure {
                            logW(TAG, "Failed to link death recipient to voice token", it)
                        }

                        if (!linkSucceeded) {
                            logW(TAG, "Rejecting registration: linkToDeath failed for voice token")
                            return false
                        }

                        voiceDeathRecipient?.let { old ->
                            runCatching { voiceBinderToken?.unlinkToDeath(old, 0) }
                        }
                        voiceBinderToken = token
                        voiceDeathRecipient = recipient
                    }
                }
                if (sessionId != null) {
                    voiceSessionId = sessionId
                }
                val prev = localVoiceActive.getAndSet(true)
                if (!prev) stateChanged = true
            } else {
                // Ignore late session update if another newer session is currently registered
                if (sessionId != null && voiceSessionId != null && sessionId != voiceSessionId) {
                    logI(TAG, "Ignoring late setVoiceActive(false) for session $sessionId; active is $voiceSessionId")
                    return false
                }
                if (token != null && voiceBinderToken != null && token != voiceBinderToken) {
                    logI(TAG, "Ignoring late setVoiceActive(false) for superseded voice token")
                    return false
                }
                val prev = localVoiceActive.getAndSet(false)
                if (prev) stateChanged = true
                if (sessionId == null || sessionId == voiceSessionId) {
                    voiceSessionId = null
                }
                voiceDeathRecipient?.let { old ->
                    runCatching { voiceBinderToken?.unlinkToDeath(old, 0) }
                }
                voiceBinderToken = null
                voiceDeathRecipient = null
            }
        }

        val ctx = context ?: hostContext
        if (ctx != null && stateChanged) {
            AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                context = ctx,
                voiceActive = localVoiceActive.get(),
                visionActive = localVisionActive.get(),
                cameraUnavailable = null,
            )
        }
        return true
    }

    internal fun handleSetVisionActiveInternal(
        context: Context?,
        active: Boolean,
        sessionId: String?,
        token: IBinder?,
    ): Boolean {
        var stateChanged = false
        synchronized(stateLock) {
            if (active) {
                if (token != null) {
                    if (!token.isBinderAlive || !token.pingBinder()) {
                        logW(TAG, "Rejecting registration: vision token is already dead")
                        return false
                    }
                    if (token != visionBinderToken) {
                        var linkSucceeded = false
                        val recipient = IBinder.DeathRecipient {
                            onVisionBinderDied(sessionId, token)
                        }
                        runCatching {
                            token.linkToDeath(recipient, 0)
                            linkSucceeded = true
                        }.onFailure {
                            logW(TAG, "Failed to link death recipient to vision token", it)
                        }

                        if (!linkSucceeded) {
                            logW(TAG, "Rejecting registration: linkToDeath failed for vision token")
                            return false
                        }

                        visionDeathRecipient?.let { old ->
                            runCatching { visionBinderToken?.unlinkToDeath(old, 0) }
                        }
                        visionBinderToken = token
                        visionDeathRecipient = recipient
                    }
                }
                if (sessionId != null) {
                    visionSessionId = sessionId
                }
                val prev = localVisionActive.getAndSet(true)
                if (!prev) stateChanged = true
            } else {
                if (sessionId != null && visionSessionId != null && sessionId != visionSessionId) {
                    logI(TAG, "Ignoring late setVisionActive(false) for session $sessionId; active is $visionSessionId")
                    return false
                }
                if (token != null && visionBinderToken != null && token != visionBinderToken) {
                    logI(TAG, "Ignoring late setVisionActive(false) for superseded vision token")
                    return false
                }
                val prev = localVisionActive.getAndSet(false)
                if (prev) stateChanged = true
                if (sessionId == null || sessionId == visionSessionId) {
                    visionSessionId = null
                }
                visionDeathRecipient?.let { old ->
                    runCatching { visionBinderToken?.unlinkToDeath(old, 0) }
                }
                visionBinderToken = null
                visionDeathRecipient = null
            }
        }

        val ctx = context ?: hostContext
        if (ctx != null && stateChanged) {
            AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                context = ctx,
                voiceActive = localVoiceActive.get(),
                visionActive = localVisionActive.get(),
                cameraUnavailable = if (!localVisionActive.get()) false else null,
            )
        }
        return true
    }

    private fun onVoiceBinderDied(deadSessionId: String?, deadToken: IBinder) {
        var stateChanged = false
        synchronized(stateLock) {
            if (deadToken != voiceBinderToken) {
                logI(TAG, "Ignoring stale death callback for superseded voice token")
                return
            }
            if (deadSessionId != null && voiceSessionId != null && deadSessionId != voiceSessionId) {
                logI(TAG, "Ignoring stale death callback for superseded voice session $deadSessionId (active is $voiceSessionId)")
                return
            }
            logW(TAG, "Voice process death detected via IBinder death recipient")
            val prev = localVoiceActive.getAndSet(false)
            if (prev) stateChanged = true
            voiceSessionId = null
            voiceBinderToken = null
            voiceDeathRecipient = null
        }
        val ctx = hostContext
        if (ctx != null && stateChanged) {
            AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                context = ctx,
                voiceActive = false,
                visionActive = localVisionActive.get(),
                cameraUnavailable = null,
            )
        }
    }

    private fun onVisionBinderDied(deadSessionId: String?, deadToken: IBinder) {
        var stateChanged = false
        synchronized(stateLock) {
            if (deadToken != visionBinderToken) {
                logI(TAG, "Ignoring stale death callback for superseded vision token")
                return
            }
            if (deadSessionId != null && visionSessionId != null && deadSessionId != visionSessionId) {
                logI(TAG, "Ignoring stale death callback for superseded vision session $deadSessionId (active is $visionSessionId)")
                return
            }
            logW(TAG, "Vision process death detected via IBinder death recipient")
            val prev = localVisionActive.getAndSet(false)
            if (prev) stateChanged = true
            visionSessionId = null
            visionBinderToken = null
            visionDeathRecipient = null
        }
        val ctx = hostContext
        if (ctx != null && stateChanged) {
            AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                context = ctx,
                voiceActive = localVoiceActive.get(),
                visionActive = false,
                cameraUnavailable = false,
            )
        }
    }

    internal interface VoiceBindingDriver {
        fun bindVoiceService(context: Context?, connection: ServiceConnection, flags: Int): Boolean
        fun unbindVoiceService(context: Context?, connection: ServiceConnection)
    }

    internal object AndroidVoiceBindingDriver : VoiceBindingDriver {
        override fun bindVoiceService(context: Context?, connection: ServiceConnection, flags: Int): Boolean {
            if (context == null) return false
            val intent = Intent(context, VoiceService::class.java)
            return context.bindService(intent, connection, flags)
        }

        override fun unbindVoiceService(context: Context?, connection: ServiceConnection) {
            context?.unbindService(connection)
        }
    }

    private val reconcileTimeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "aria-reconcile-timeout").apply { isDaemon = true }
        }

    internal class ReconcileConnection(
        private val appContext: Context?,
        private val bindingDriver: VoiceBindingDriver,
        private val timeoutMs: Long,
        scheduler: ScheduledExecutorService,
    ) : ServiceConnection {
        val cleanedUp = AtomicBoolean(false)
        val unbindCount = AtomicInteger(0)
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
            unbindCount.incrementAndGet()
            runCatching { bindingDriver.unbindVoiceService(appContext, this) }
        }

        private fun onTimeout() {
            if (cleanedUp.get()) return
            logW(TAG, "VoiceService reconciliation timed out after ${timeoutMs}ms")
            cleanup("timeout")
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (cleanedUp.get()) {
                logI(TAG, "Ignoring late onServiceConnected after cleanup (${cleanupReason.get()})")
                return
            }
            try {
                if (service != null && service.isBinderAlive) {
                    val voiceService = IVoiceService.Stub.asInterface(service)
                    if (voiceService != null) {
                        val isActive = voiceService.isServiceActive
                        val sessionId = voiceService.serviceSessionId
                        if (isActive && !sessionId.isNullOrEmpty()) {
                            logI(TAG, "Reconciled active VoiceService session: $sessionId")
                            handleSetVoiceActiveInternal(appContext, true, sessionId, service)
                        } else {
                            logI(TAG, "Reconciled VoiceService is idle/stopped, maintaining inactive bridge state")
                        }
                    }
                }
            } catch (t: Throwable) {
                logW(TAG, "Failed to reconcile state from running voice service", t)
            } finally {
                cleanup("onServiceConnected")
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            if (cleanedUp.get()) return
            logI(TAG, "VoiceService returned null binding during reconciliation")
            cleanup("onNullBinding")
        }

        override fun onBindingDied(name: ComponentName?) {
            if (cleanedUp.get()) return
            logW(TAG, "VoiceService binding died during reconciliation")
            cleanup("onBindingDied")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (cleanedUp.get()) return
            logI(TAG, "VoiceService disconnected during reconciliation")
            cleanup("onServiceDisconnected")
        }
    }

    /**
     * Reconciles state with a live VoiceService in :wakeword process if running.
     * Uses flag 0 (Context.BIND_AUTO_CREATE is strictly NOT used), ensuring stopped services are never revived.
     * Never synthesizes session IDs; queries actual VoiceService via explicit AIDL IPC.
     * Bounded attempt cleans up exactly once on connected/null/died/timeout with late callback fencing.
     */
    internal fun reconcileWithRunningVoiceService(
        context: Context?,
        timeoutMs: Long = DEFAULT_RECONCILE_TIMEOUT_MS,
        bindingDriver: VoiceBindingDriver = AndroidVoiceBindingDriver,
        scheduler: ScheduledExecutorService = reconcileTimeoutScheduler,
    ): ReconcileConnection {
        val appContext = context?.applicationContext ?: context
        val connection = ReconcileConnection(
            appContext = appContext,
            bindingDriver = bindingDriver,
            timeoutMs = timeoutMs,
            scheduler = scheduler,
        )

        val bound = runCatching {
            bindingDriver.bindVoiceService(appContext, connection, 0)
        }.getOrDefault(false)

        if (!bound) {
            logI(TAG, "VoiceService is not running or bind returned false (flag 0)")
            connection.cleanup("bindFailed")
        }
        return connection
    }

    // Direct methods for the host provider process
    internal fun handleCall(context: Context, method: String, extras: Bundle?): Bundle? {
        hostContext = context.applicationContext
        val result = Bundle()
        when (method) {
            METHOD_SET_VOICE_ACTIVE -> {
                val active = extras?.getBoolean(EXTRA_ACTIVE, false) ?: false
                val sessionId = extras?.getString(EXTRA_SESSION_ID)
                val token = extras?.getBinder(EXTRA_BINDER_TOKEN)
                val success = handleSetVoiceActiveInternal(context, active, sessionId, token)
                result.putBoolean("success", success)
            }
            METHOD_SET_VISION_ACTIVE -> {
                val active = extras?.getBoolean(EXTRA_ACTIVE, false) ?: false
                val sessionId = extras?.getString(EXTRA_SESSION_ID)
                val token = extras?.getBinder(EXTRA_BINDER_TOKEN)
                val success = handleSetVisionActiveInternal(context, active, sessionId, token)
                result.putBoolean("success", success)
            }
            METHOD_GET_STATE -> {
                synchronized(stateLock) {
                    result.putBoolean(EXTRA_VOICE_ACTIVE, localVoiceActive.get())
                    result.putBoolean(EXTRA_VISION_ACTIVE, localVisionActive.get())
                    if (voiceSessionId != null) result.putString(EXTRA_SESSION_ID + "_voice", voiceSessionId)
                    if (visionSessionId != null) result.putString(EXTRA_SESSION_ID + "_vision", visionSessionId)
                }
            }
        }
        return result
    }

    internal fun resetForTesting() {
        synchronized(stateLock) {
            localVoiceActive.set(false)
            localVisionActive.set(false)
            voiceSessionId = null
            visionSessionId = null
            voiceDeathRecipient?.let { runCatching { voiceBinderToken?.unlinkToDeath(it, 0) } }
            visionDeathRecipient?.let { runCatching { visionBinderToken?.unlinkToDeath(it, 0) } }
            voiceBinderToken = null
            visionBinderToken = null
            voiceDeathRecipient = null
            visionDeathRecipient = null
        }
        AriaUnifiedNotificationCoordinator.resetForTesting()
    }

    internal fun setVoiceActiveLocal(active: Boolean, sessionId: String? = null) {
        handleSetVoiceActiveInternal(null, active, sessionId, null)
    }

    internal fun setVisionActiveLocal(active: Boolean, sessionId: String? = null) {
        handleSetVisionActiveInternal(null, active, sessionId, null)
    }

    internal fun isVoiceActiveLocal(): Boolean = localVoiceActive.get()

    internal fun isVisionActiveLocal(): Boolean = localVisionActive.get()

    internal fun getVoiceSessionIdForTesting(): String? = voiceSessionId

    internal fun getVisionSessionIdForTesting(): String? = visionSessionId

    internal fun getVoiceTokenForTesting(): IBinder? = voiceBinderToken

    internal fun getVisionTokenForTesting(): IBinder? = visionBinderToken

    internal fun simulateVoiceBinderDeathForTesting(
        sessionId: String? = voiceSessionId,
        token: IBinder? = voiceBinderToken,
    ) {
        if (token != null) {
            onVoiceBinderDied(sessionId, token)
        }
    }

    internal fun simulateVisionBinderDeathForTesting(
        sessionId: String? = visionSessionId,
        token: IBinder? = visionBinderToken,
    ) {
        if (token != null) {
            onVisionBinderDied(sessionId, token)
        }
    }
}

/**
 * Initializes app runtime environment and hosts cross-process state for Aria.
 * Strictly lightweight: performs no heavy main-thread work, no service revival,
 * and does not touch or clear persisted user stop latches on incidental queries or creation.
 */
class AriaRuntimeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext
        AriaRuntimeStateBridge.initHostContext(ctx)
        if (ctx != null) {
            AriaRuntimeStateBridge.reconcileWithRunningVoiceService(ctx)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return AriaRuntimeStateBridge.handleCall(ctx, method, extras)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "AriaRuntimeProvider"
    }
}
