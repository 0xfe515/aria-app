package com.ariateam.aria.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.ariateam.aria.speech.IVoiceCallback
import com.ariateam.aria.speech.IVoiceService
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression and unit tests for AriaUnifiedStopReceiver and stop semantics:
 * 1. Invoking existing contract IVoiceService.stop() when UI clients are bound.
 * 2. Fallback context.stopService() without BIND_AUTO_CREATE when service is not running.
 * 3. Bounded cleanup guarantees across connected, null binding, binding died, disconnected, timeout.
 * 4. Late callback fencing after cleanup.
 * 5. Ensuring VoicePhase.Stopping does not report active during provider reconciliation.
 */
class AriaUnifiedStopReceiverTest {

    @Before
    fun setUp() {
        AriaRuntimeStateBridge.resetForTesting()
        AriaUnifiedNotificationCoordinator.resetForTesting()
    }

    private class TestVoiceServiceBinder(
        val sessionId: String = "voice_session_test_123",
        var isActive: Boolean = true,
        var isAlive: Boolean = true,
    ) : IBinder, IVoiceService {
        var stopCount = 0
            private set

        override fun getInterfaceDescriptor(): String = "com.ariateam.aria.speech.IVoiceService"
        override fun pingBinder(): Boolean = isAlive
        override fun isBinderAlive(): Boolean = isAlive
        override fun queryLocalInterface(descriptor: String): IInterface = this
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
        override fun asBinder(): IBinder = this

        override fun getState(): Bundle = Bundle()
        override fun registerCallback(callback: IVoiceCallback?) {}
        override fun unregisterCallback(callback: IVoiceCallback?) {}
        override fun beginDictation() {}
        override fun finishDictation() {}
        override fun setAudibleFeedback(enabled: Boolean) {}

        override fun stop() {
            stopCount++
        }

        override fun setUiVisible(visible: Boolean) {}
        override fun setUiScreen(screen: String?) {}
        override fun consumeNavigation(requestId: Long) {}
        override fun consumeCloudQuestion(requestId: Long) {}
        override fun speakCloudResponse(questionId: Long, text: String?) {}
        override fun reportCloudError(questionId: Long, errorText: String?) {}
        override fun cancelCloudQuestion(questionId: Long) {}
        override fun speakHazardWarning(message: String?) {}
        override fun postHazardAlert(session: Long, sequence: Long, timestampNanos: Long, message: String?) {}
        override fun preemptSpeech() {}

        override fun isServiceActive(): Boolean = isActive
        override fun getServiceSessionId(): String = sessionId
    }

    private class TestVoiceStopDriver(
        var bindResult: Boolean = true,
    ) : AriaUnifiedStopReceiver.VoiceStopDriver {
        var unbindCount = 0
            private set
        var stopServiceCount = 0
            private set
        var boundConnection: ServiceConnection? = null
            private set
        var capturedFlags: Int = -1
            private set

        override fun bindVoiceService(context: Context?, connection: ServiceConnection, flags: Int): Boolean {
            capturedFlags = flags
            boundConnection = connection
            return bindResult
        }

        override fun unbindVoiceService(context: Context?, connection: ServiceConnection) {
            unbindCount++
            if (boundConnection === connection) {
                boundConnection = null
            }
        }

        override fun stopVoiceService(context: Context?): Boolean {
            stopServiceCount++
            return true
        }
    }

    @Test
    fun boundVoiceService_invokesStopIpc_andUnbindsCleanly() {
        val fakeService = TestVoiceServiceBinder(isActive = true)
        val driver = TestVoiceStopDriver(bindResult = true)

        val connection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = null,
            driver = driver,
            timeoutMs = 5000L,
            pendingResult = null,
        )

        // Verify flag 0 was strictly used (NEVER BIND_AUTO_CREATE)
        assertEquals("Must bind using flag 0", 0, driver.capturedFlags)
        assertTrue("isBound must be set when bind returns true", connection.isBound.get())
        assertFalse("Should not be cleaned up yet before callback", connection.cleanedUp.get())

        // Simulate onServiceConnected delivery
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), fakeService)

        // Verify IVoiceService.stop() was called
        assertEquals("IVoiceService.stop() must be invoked", 1, fakeService.stopCount)
        assertTrue("stopIpcRequested must be true", connection.stopIpcRequested.get())
        assertTrue("Connection must be marked cleanedUp", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be onServiceConnected", "onServiceConnected", connection.cleanupReason.get())
        assertEquals("Driver unbind must be called exactly once", 1, driver.unbindCount)
        assertEquals("Fallback stopVoiceService must be invoked once", 1, driver.stopServiceCount)
    }

    @Test
    fun serviceNotRunning_bindReturnsFalse_invokesFallbackStopWithoutUnbind() {
        val driver = TestVoiceStopDriver(bindResult = false)

        val connection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = null,
            driver = driver,
            timeoutMs = 5000L,
            pendingResult = null,
        )

        assertEquals("Must bind using flag 0", 0, driver.capturedFlags)
        assertFalse("isBound must be false when bind fails", connection.isBound.get())
        assertTrue("Connection must be cleanedUp immediately on bind failure", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be bindFailed", "bindFailed", connection.cleanupReason.get())
        assertEquals("Must NOT call unbind when bind returned false", 0, driver.unbindCount)
        assertEquals("Fallback stopVoiceService must still be invoked", 1, driver.stopServiceCount)
    }

    @Test
    fun nullBinding_cleansUpAndInvokesFallback() {
        val driver = TestVoiceStopDriver(bindResult = true)

        val connection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = null,
            driver = driver,
            timeoutMs = 5000L,
            pendingResult = null,
        )

        connection.onNullBinding(ComponentName("com.ariateam.aria", "VoiceService"))

        assertTrue("Connection must be cleanedUp after onNullBinding", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be onNullBinding", "onNullBinding", connection.cleanupReason.get())
        assertEquals("Unbind must execute exactly once", 1, driver.unbindCount)
        assertEquals("Fallback stopVoiceService must be called", 1, driver.stopServiceCount)
    }

    @Test
    fun bindingDied_cleansUpAndInvokesFallback() {
        val driver = TestVoiceStopDriver(bindResult = true)

        val connection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = null,
            driver = driver,
            timeoutMs = 5000L,
            pendingResult = null,
        )

        connection.onBindingDied(ComponentName("com.ariateam.aria", "VoiceService"))

        assertTrue("Connection must be cleanedUp after onBindingDied", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be onBindingDied", "onBindingDied", connection.cleanupReason.get())
        assertEquals("Unbind must execute exactly once", 1, driver.unbindCount)
        assertEquals("Fallback stopVoiceService must be called", 1, driver.stopServiceCount)
    }

    @Test
    fun serviceDisconnected_cleansUpAndInvokesFallback() {
        val driver = TestVoiceStopDriver(bindResult = true)

        val connection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = null,
            driver = driver,
            timeoutMs = 5000L,
            pendingResult = null,
        )

        connection.onServiceDisconnected(ComponentName("com.ariateam.aria", "VoiceService"))

        assertTrue("Connection must be cleanedUp after onServiceDisconnected", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be onServiceDisconnected", "onServiceDisconnected", connection.cleanupReason.get())
        assertEquals("Unbind must execute exactly once", 1, driver.unbindCount)
        assertEquals("Fallback stopVoiceService must be called", 1, driver.stopServiceCount)
    }

    @Test
    fun timeout_cleansUpAndFencesLateCallback() {
        val driver = TestVoiceStopDriver(bindResult = true)
        val shortTimeoutMs = 30L

        val connection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = null,
            driver = driver,
            timeoutMs = shortTimeoutMs,
            pendingResult = null,
        )

        // Wait for daemon timeout scheduler
        Thread.sleep(80L)

        assertTrue("Connection must be cleanedUp after timeout", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be timeout", "timeout", connection.cleanupReason.get())
        assertEquals("Unbind must execute on timeout", 1, driver.unbindCount)
        assertEquals("Fallback stopVoiceService must be called on timeout", 1, driver.stopServiceCount)

        // Late onServiceConnected arrives after timeout
        val lateService = TestVoiceServiceBinder()
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), lateService)

        // Fenced out!
        assertEquals("Late callback must NOT invoke stop()", 0, lateService.stopCount)
        assertEquals("Unbind count must not increment on late callback", 1, driver.unbindCount)
        assertEquals("Stop service count must not increment on late callback", 1, driver.stopServiceCount)
    }

    @Test
    fun multipleCallbacks_cleanupExecutesExactlyOnce() {
        val fakeService = TestVoiceServiceBinder()
        val driver = TestVoiceStopDriver(bindResult = true)

        val connection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = null,
            driver = driver,
            timeoutMs = 5000L,
            pendingResult = null,
        )

        // 1. Deliver onServiceConnected
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), fakeService)

        // 2. Deliver onBindingDied
        connection.onBindingDied(ComponentName("com.ariateam.aria", "VoiceService"))

        // 3. Deliver onServiceDisconnected
        connection.onServiceDisconnected(ComponentName("com.ariateam.aria", "VoiceService"))

        // 4. Deliver onNullBinding
        connection.onNullBinding(ComponentName("com.ariateam.aria", "VoiceService"))

        // Verify cleanup executed exactly once
        assertTrue(connection.cleanedUp.get())
        assertEquals("onServiceConnected", connection.cleanupReason.get())
        assertEquals("Unbind must be called exactly once", 1, driver.unbindCount)
        assertEquals("Stop service must be called exactly once", 1, driver.stopServiceCount)
    }

    @Test
    fun stoppingPhase_isServiceActiveReturnsFalse_preventsProviderReconciliationResurrection() {
        // When a service is in VoicePhase.Stopping, isServiceActive() returns false
        val stoppingService = TestVoiceServiceBinder(
            sessionId = "voice_stopping_session",
            isActive = false, // VoiceService.isEffectivelyActive returns false for Stopping
        )

        val driver = object : AriaRuntimeStateBridge.VoiceBindingDriver {
            var unbindCount = 0
            override fun bindVoiceService(context: Context?, connection: ServiceConnection, flags: Int): Boolean = true
            override fun unbindVoiceService(context: Context?, connection: ServiceConnection) {
                unbindCount++
            }
        }

        // Bridge starts inactive
        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())

        // Reconcile with the stopping VoiceService
        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), stoppingService)

        // Bridge MUST remain inactive (user stop must NOT be undone by provider reconciliation)
        assertFalse("Service in Stopping phase must NOT be marked active during reconciliation", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull("Bridge must not record sessionId for stopping service", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals(1, driver.unbindCount)
    }
}
