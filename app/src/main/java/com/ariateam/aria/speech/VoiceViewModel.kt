package com.ariateam.aria.speech

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableAudible = MutableStateFlow(true)
    val audibleFeedback = mutableAudible.asStateFlow()
    private val feedback = VoiceFeedback(context).apply { audibleEnabled = mutableAudible.value }
    private val mutableState = MutableStateFlow(VoiceState())
    val state = mutableState.asStateFlow()
    private val mutableServiceSynced = MutableStateFlow(false)
    val isServiceSynced = mutableServiceSynced.asStateFlow()
    private var uiVisible = false
    private var uiScreen = "home"
    private var remote: IVoiceService? = null
    private var bound = false
    private var processDied = false
    private var startPending = false
    private var cleared = false
    private val receiver = VoiceStateReceiver()
    private val callback = object : IVoiceCallback.Stub() {
        override fun onState(state: android.os.Bundle?) {
            val bundle = state ?: return
            val incoming = voiceStateFromBundle(bundle)
            main.post { acceptState(incoming, bundle.getLong("revision"), "callback") }
        }
    }

    private fun acceptState(incoming: VoiceState, revision: Long, source: String) {
        if (cleared || processDied) return
        if (startPending && incoming.phase == VoicePhase.Stopped) return
        if (!receiver.accept(revision)) return
        if (incoming.phase != VoicePhase.Initializing) {
            startPending = false
        }
        val previous = mutableState.value
        if (incoming.phase != previous.phase || incoming.errorCode != previous.errorCode ||
            incoming.retryCount != previous.retryCount) {
            Log.i(TAG, "UI state via $source: phase=${incoming.phase}, chars=${incoming.transcript.length}, revision=$revision")
        }
        mutableAudible.value = incoming.command.audible
        feedback.audibleEnabled = incoming.command.audible
        mutableState.value = incoming
        mutableServiceSynced.value = true
    }

    /** Read on an IO thread; visible screens also recover if a one-way callback was missed. */
    suspend fun refreshState() {
        val service = remote ?: return
        try {
            val snapshot = withContext(Dispatchers.IO) { service.getState() } ?: return
            if (remote !== service || cleared || processDied) return
            acceptState(voiceStateFromBundle(snapshot), snapshot.getLong("revision"), "snapshot")
        } catch (error: RemoteException) {
            Log.w(TAG, "UI snapshot read failed", error)
            if (remote === service) handleDeath()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "Voice service connected; restoring UI state")
            processDied = false
            receiver.reconnect()
            remote = IVoiceService.Stub.asInterface(service)
            try {
                remote?.setUiVisible(uiVisible)
                remote?.setUiScreen(uiScreen)
                remote?.registerCallback(callback)
                viewModelScope.launch { refreshState() }
            } catch (_: RemoteException) { handleDeath() }
        }
        override fun onServiceDisconnected(name: ComponentName) { handleDeath() }
        override fun onBindingDied(name: ComponentName) { handleDeath(); unbind() }
        override fun onNullBinding(name: ComponentName) { handleDeath(); unbind() }
    }

    init { bind() }

    private fun bind() {
        if (!bound) bound = context.bindService(Intent(context, VoiceService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) mutableState.update { it.copy(phase = VoicePhase.Error, errorCode = VoiceError.START_FAILED) }
    }

    fun startWaiting() {
        if (!mutableState.value.isActive) start(VoiceService.ACTION_WAIT)
    }

    fun beginDictation() {
        if (mutableState.value.phase in listOf(VoicePhase.Dictating, VoicePhase.Stopping)) return
        // The service serializes manual input with recognition, retries and TTS.
        start(VoiceService.ACTION_DICTATE)
    }

    fun finishDictation() {
        try { remote?.finishDictation() } catch (_: RemoteException) { handleDeath() }
    }

    /** Invoked by the visible Activity after runtime permission handling. */
    private fun start(action: String) {
        if (mutableState.value.phase == VoicePhase.Stopping) return
        processDied = false
        receiver.clearFailure()
        startPending = true
        mutableState.update { it.copy(phase = VoicePhase.Initializing, errorCode = VoiceError.NONE) }
        try {
            bind()
            check(bound) { "Could not bind voice service" }
            ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java).setAction(action))
        } catch (error: Exception) {
            Log.e(TAG, "Voice service start failed", error)
            startPending = false
            receiver.fail(VoiceError.START_FAILED)
            mutableState.update { it.copy(phase = VoicePhase.Error, errorCode = VoiceError.START_FAILED) }
        }
    }

    fun setAudibleFeedback(enabled: Boolean) {
        try { remote?.setAudibleFeedback(enabled) } catch (_: RemoteException) { handleDeath() }
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        try { remote?.setUiVisible(visible) } catch (_: RemoteException) { handleDeath() }
    }
    fun setUiScreen(screen: String) {
        uiScreen = screen
        try { remote?.setUiScreen(screen) } catch (_: RemoteException) { handleDeath() }
    }
    fun consumeNavigation(id: Long) { try { remote?.consumeNavigation(id) } catch (_: RemoteException) { handleDeath() } }
    fun downloadModel(mobileData: Boolean) { try { remote?.downloadModel(mobileData) } catch (_: RemoteException) { handleDeath() } }
    fun cancelModelDownload() { try { remote?.cancelModelDownload() } catch (_: RemoteException) { handleDeath() } }
    fun deleteModel() { try { remote?.deleteModel() } catch (_: RemoteException) { handleDeath() } }

    fun stop() {
        startPending = false
        try {
            val service = remote
            if (service != null) service.stop()
            else context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
        } catch (_: Exception) { handleDeath() }
    }

    fun permissionDenied() {
        feedback.play(VoiceCue.Error)
        receiver.fail(VoiceError.PERMISSION)
        mutableState.update { it.copy(phase = VoicePhase.Error, errorCode = VoiceError.PERMISSION) }
    }

    fun retry(restartListening: Boolean = false) {
        if (mutableState.value.phase == VoicePhase.Error) {
            mutableState.update { it.copy(phase = VoicePhase.Stopped, errorCode = VoiceError.NONE) }
        }
        start(if (restartListening) VoiceService.ACTION_RESTART else VoiceService.ACTION_RETRY)
    }

    private fun handleDeath() {
        if (!processDied && !cleared) feedback.play(VoiceCue.Error)
        remote = null
        processDied = true
        startPending = false
        mutableState.update { it.copy(phase = VoicePhase.Error, errorCode = VoiceError.PROCESS_DIED) }
    }

    private fun unbind() {
        if (bound) { context.unbindService(connection); bound = false }
    }

    override fun onCleared() {
        cleared = true
        main.removeCallbacksAndMessages(null)
        try { remote?.setUiVisible(false); remote?.unregisterCallback(callback) } catch (_: RemoteException) { }
        unbind()
        feedback.close()
        super.onCleared()
    }

    companion object { private const val TAG = "AriaVoiceUI" }
}
