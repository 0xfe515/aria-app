package com.ariateam.aria.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.HazardAlertCoordinator
import com.ariateam.aria.hazard.HazardAlertEvent
import com.ariateam.aria.hazard.HazardAlertSink
import com.ariateam.aria.runtime.AriaRuntimeStateBridge

/**
 * Background-safe bridge linking [HazardAlertCoordinator] events to [VoiceService] AIDL priority speech.
 *
 * Rules:
 * 1. Uses existing AIDL speech priority path (`IVoiceService.postHazardAlert`) without UI callbacks.
 * 2. Does NOT revive or restart VoiceService if explicitly stopped (`isVoiceActive == false`).
 * 3. Binds with flag 0 (NEVER BIND_AUTO_CREATE) to ensure stopped service cannot be created or retained.
 * 4. Does NOT hold bridge lock across Binder IPC transactions.
 * 5. Properly unbinds on onBindingDied / onNullBinding to prevent ServiceConnection leaks.
 * 6. Allocates a new ServiceConnection per binding attempt and fences callbacks by session generation,
 *    ensuring stale callbacks from older generations cannot mutate or unbind the active connection.
 */
class HazardSpeechBridge(
    private val context: Context? = null,
    private val voiceServiceProvider: (() -> IVoiceService?)? = null,
    private val serviceBinder: ((ServiceConnection, Int) -> Boolean)? = null,
    private val serviceUnbinder: ((ServiceConnection) -> Unit)? = null,
) : HazardAlertSink, AutoCloseable {

    private val lock = Any()
    private var remoteService: IVoiceService? = null
    private var currentConnection: BridgeConnection? = null
    private var currentGeneration = 0L
    private var closed = false

    private class BridgeConnection(
        val generation: Long,
        val onConnected: (BridgeConnection, IBinder?) -> Unit,
        val onDisconnected: (BridgeConnection) -> Unit,
        val onDied: (BridgeConnection) -> Unit,
        val onNull: (BridgeConnection) -> Unit,
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            onConnected(this, service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onDisconnected(this)
        }

        override fun onBindingDied(name: ComponentName?) {
            onDied(this)
        }

        override fun onNullBinding(name: ComponentName?) {
            onNull(this)
        }
    }

    private fun isVoiceActive(): Boolean {
        return if (context != null) {
            AriaRuntimeStateBridge.isVoiceActive(context)
        } else {
            AriaRuntimeStateBridge.isVoiceActiveLocal()
        }
    }

    private fun handleServiceConnected(caller: BridgeConnection, service: IBinder?) {
        synchronized(lock) {
            // Require matching connection identity and active generation
            if (closed || caller !== currentConnection || caller.generation != currentGeneration) {
                safeUnbindConnection(caller)
                return
            }
            remoteService = IVoiceService.Stub.asInterface(service)
        }
    }

    private fun handleServiceDisconnected(caller: BridgeConnection) {
        synchronized(lock) {
            if (caller !== currentConnection || caller.generation != currentGeneration) {
                return
            }
            remoteService = null
        }
    }

    private fun handleBindingDied(caller: BridgeConnection) {
        synchronized(lock) {
            safeUnbindConnection(caller)
            if (caller === currentConnection && caller.generation == currentGeneration) {
                remoteService = null
                currentConnection = null
            }
        }
    }

    private fun handleNullBinding(caller: BridgeConnection) {
        synchronized(lock) {
            safeUnbindConnection(caller)
            if (caller === currentConnection && caller.generation == currentGeneration) {
                remoteService = null
                currentConnection = null
            }
        }
    }

    private fun safeUnbindConnection(conn: ServiceConnection?) {
        if (conn != null) {
            try {
                if (serviceUnbinder != null) {
                    serviceUnbinder.invoke(conn)
                } else {
                    context?.unbindService(conn)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception during unbindService", e)
            }
        }
    }

    fun start() {
        synchronized(lock) {
            if (closed) return
            startLocked()
        }
    }

    private fun startLocked() {
        if (voiceServiceProvider != null) {
            remoteService = voiceServiceProvider.invoke()
            return
        }
        // Strict liveness check: never start or bind if voice service is inactive/stopped
        if (currentConnection == null && isVoiceActive()) {
            val gen = ++currentGeneration
            val conn = BridgeConnection(
                generation = gen,
                onConnected = { caller, binder -> handleServiceConnected(caller, binder) },
                onDisconnected = { caller -> handleServiceDisconnected(caller) },
                onDied = { caller -> handleBindingDied(caller) },
                onNull = { caller -> handleNullBinding(caller) },
            )
            try {
                // Must bind with flag 0, NOT BIND_AUTO_CREATE, to avoid reviving or retaining a stopped service
                val bound = if (serviceBinder != null) {
                    serviceBinder.invoke(conn, 0)
                } else if (context != null) {
                    val intent = Intent(context, VoiceService::class.java)
                    context.bindService(intent, conn, 0)
                } else {
                    false
                }
                if (bound) {
                    currentConnection = conn
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind to existing VoiceService", e)
                currentConnection = null
            }
        }
    }

    override fun onHazardAlert(event: HazardAlertEvent) {
        val service: IVoiceService? = synchronized(lock) {
            if (closed) return
            // Respect whole Stop and explicit voice stop: never restart voice service on hazard
            if (!isVoiceActive()) {
                return
            }

            if (currentConnection == null && voiceServiceProvider == null) {
                startLocked()
            }

            remoteService ?: voiceServiceProvider?.invoke()
        }

        if (service == null) {
            return
        }

        if (event.severity == AlertSeverity.WARNING) {
            runCatching {
                service.preemptSpeech()
            }.onFailure { e ->
                if (e is RemoteException) {
                    Log.w(TAG, "RemoteException calling preemptSpeech", e)
                }
            }
        }

        // Binder IPC executed safely outside synchronization lock
        try {
            service.postHazardAlert(
                event.session,
                event.sequence,
                event.timestampNanos,
                event.message,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException delivering hazard alert to VoiceService", e)
            synchronized(lock) {
                if (remoteService === service) {
                    remoteService = null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unexpected error delivering hazard alert", t)
        }
    }

    /** Publishes a payload-free assist status to VoiceService; only state transitions request speech. */
    fun updateWalkingAssistState(session: Long, sequence: Long, status: String, announce: Boolean, stopped: Boolean) {
        if (session <= 0L || sequence <= 0L || status.isBlank()) return
        val service = synchronized(lock) {
            if (closed || !isVoiceActive()) return
            if (currentConnection == null && voiceServiceProvider == null) startLocked()
            remoteService ?: voiceServiceProvider?.invoke()
        } ?: return
        try {
            service.updateWalkingAssistState(
                session,
                sequence,
                SystemClock.elapsedRealtimeNanos(),
                status,
                announce,
                stopped,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException delivering walking assist status", e)
            synchronized(lock) { if (remoteService === service) remoteService = null }
        } catch (t: Throwable) {
            Log.w(TAG, "Unexpected error delivering walking assist status", t)
        }
    }

    fun reportForwardCalibrationResult(requestId: Long, successful: Boolean) {
        if (requestId <= 0L) return
        val service = synchronized(lock) {
            if (closed || !isVoiceActive()) return
            if (currentConnection == null && voiceServiceProvider == null) startLocked()
            remoteService ?: voiceServiceProvider?.invoke()
        } ?: return
        try {
            service.reportForwardCalibrationResult(requestId, successful)
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException reporting forward calibration result", e)
            synchronized(lock) { if (remoteService === service) remoteService = null }
        } catch (t: Throwable) {
            Log.w(TAG, "Unexpected error reporting forward calibration result", t)
        }
    }

    fun stop() {
        synchronized(lock) {
            currentGeneration++
            remoteService = null
            val conn = currentConnection
            currentConnection = null
            safeUnbindConnection(conn)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            stop()
        }
        if (HazardAlertCoordinator.getSharedAlertSink() === this) {
            HazardAlertCoordinator.setSharedAlertSink(null)
        }
    }

    companion object {
        private const val TAG = "HazardSpeechBridge"

        /**
         * Instantiates and registers a shared bridge in [HazardAlertCoordinator].
         */
        fun attachShared(
            context: Context? = null,
            bridgeFactory: ((Context?) -> HazardSpeechBridge)? = null,
        ): HazardSpeechBridge {
            val bridge = bridgeFactory?.invoke(context) ?: HazardSpeechBridge(context?.applicationContext ?: context)
            bridge.start()
            HazardAlertCoordinator.setSharedAlertSink(bridge)
            return bridge
        }
    }
}
