package com.ariateam.aria.speech

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real ViewModel service binding, stop fence, late callback, and cleanup paths. */
@RunWith(AndroidJUnit4::class)
class VoiceViewModelLifecycleTest {
    @Test
    fun userStopCallsRemoteAndLateActiveCallbackCannotRestartUi() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceContext = RecordingServiceContext(app)
        val viewModel = VoiceViewModel(app, serviceContext, Handler(Looper.getMainLooper()), true)
        val binder = RecordingVoiceService()
        val store = ViewModelStore()
        store.put("voice", viewModel)
        val connection = requireNotNull(serviceContext.connection)
        val component = ComponentName(app, VoiceService::class.java)

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                connection.onServiceConnected(component, binder)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertNotNull("ViewModel registers its state callback after binding", binder.callback)

            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.stop() }
            assertEquals(1, binder.stopCalls.get())
            sendState(binder, VoicePhase.Waiting, revision = 20L)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals("late active state must not undo a user stop", VoicePhase.Stopped, viewModel.state.value.phase)
        } finally {
            store.clear()
        }
        assertEquals(1, serviceContext.unbindCalls.get())
        assertEquals(1, binder.unregisterCalls.get())
    }

    @Test
    fun callbackDeliveredAfterViewModelClearIsIgnoredAndServiceIsUnbound() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceContext = RecordingServiceContext(app)
        val viewModel = VoiceViewModel(app, serviceContext, Handler(Looper.getMainLooper()), true)
        val binder = RecordingVoiceService()
        val store = ViewModelStore()
        store.put("voice", viewModel)
        val component = ComponentName(app, VoiceService::class.java)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(serviceContext.connection).onServiceConnected(component, binder)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val callback = requireNotNull(binder.callback)
        store.clear()
        assertEquals(1, serviceContext.unbindCalls.get())
        assertEquals(1, binder.unregisterCalls.get())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            callback.onState(VoiceState(phase = VoicePhase.Waiting).toBundle(revision = 1L))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("late callback after clear must be ignored", VoicePhase.Stopped, viewModel.state.value.phase)
    }

    @Test
    fun callbacksFromOldRevisionAreRejectedAfterNewerState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceContext = RecordingServiceContext(app)
        val viewModel = VoiceViewModel(app, serviceContext, Handler(Looper.getMainLooper()), true)
        val binder = RecordingVoiceService()
        val connection = requireNotNull(serviceContext.connection)
        val component = ComponentName(app, VoiceService::class.java)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.onServiceConnected(component, binder)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        sendState(binder, VoicePhase.Waiting, revision = 4L)
        sendState(binder, VoicePhase.Dictating, revision = 7L)
        sendState(binder, VoicePhase.Error, revision = 6L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(VoicePhase.Dictating, viewModel.state.value.phase)
        ViewModelStore().also { it.put("voice", viewModel); it.clear() }
    }

    @Test
    fun serviceReconnectResetsRevisionWindowForReplacementBinder() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceContext = RecordingServiceContext(app)
        val viewModel = VoiceViewModel(app, serviceContext, Handler(Looper.getMainLooper()), true)
        val first = RecordingVoiceService()
        val second = RecordingVoiceService()
        val connection = requireNotNull(serviceContext.connection)
        val component = ComponentName(app, VoiceService::class.java)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.onServiceConnected(component, first)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        sendState(first, VoicePhase.Waiting, revision = 90L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection.onServiceDisconnected(component)
            connection.onServiceConnected(component, second)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        sendState(second, VoicePhase.Dictating, revision = 1L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(VoicePhase.Dictating, viewModel.state.value.phase)
        ViewModelStore().also { it.put("voice", viewModel); it.clear() }
    }

    private fun sendState(service: RecordingVoiceService, phase: VoicePhase, revision: Long) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.callback?.onState(VoiceState(phase = phase).toBundle(revision))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private class RecordingServiceContext(base: Context) : ContextWrapper(base) {
        @Volatile var connection: ServiceConnection? = null
        val unbindCalls = AtomicInteger()

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            return true
        }

        override fun unbindService(conn: ServiceConnection) {
            unbindCalls.incrementAndGet()
        }
    }

    private class RecordingVoiceService : IVoiceService.Stub() {
        @Volatile var callback: IVoiceCallback? = null
        val stopCalls = AtomicInteger()
        val unregisterCalls = AtomicInteger()

        override fun getState(): Bundle = VoiceState().toBundle(0L)
        override fun registerCallback(callback: IVoiceCallback?) { this.callback = callback }
        override fun unregisterCallback(callback: IVoiceCallback?) { unregisterCalls.incrementAndGet() }
        override fun beginDictation() {}
        override fun finishDictation() {}
        override fun setAudibleFeedback(enabled: Boolean) {}
        override fun stop() { stopCalls.incrementAndGet() }
        override fun setUiVisible(visible: Boolean) {}
        override fun setUiScreen(screen: String?) {}
        override fun consumeNavigation(requestId: Long) {}
        override fun consumeCloudQuestion(requestId: Long) {}
        override fun speakCloudResponse(questionId: Long, text: String?) {}
        override fun reportCloudError(questionId: Long, errorText: String?) {}
        override fun cancelCloudQuestion(questionId: Long) {}
        override fun speakHazardWarning(message: String?) {}
        override fun postHazardAlert(session: Long, sequence: Long, timestampNanos: Long, message: String?) {}
        override fun postHazardAlertWithSeverity(session: Long, sequence: Long, timestampNanos: Long, message: String?, severityOrdinal: Int) {}
        override fun updateWalkingAssistState(session: Long, sequence: Long, timestampNanos: Long, status: String?, announce: Boolean, stopped: Boolean) {}
        override fun reportForwardCalibrationResult(requestId: Long, successful: Boolean) {}
        override fun preemptSpeech() {}
        override fun isServiceActive(): Boolean = true
        override fun getServiceSessionId(): String = "test"
    }
}
