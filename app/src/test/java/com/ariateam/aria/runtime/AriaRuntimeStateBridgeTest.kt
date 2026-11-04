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
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for:
 * 1. Session registration and liveness tracking.
 * 2. IBinder death reconciliation (process crash/death cleanup).
 * 3. Late session update fencing (older session stop ignored when newer session is active).
 * 4. Provider restart reconciliation (inactive defaults, no revive of stopped services).
 * 5. Notification state tuple deduplication.
 */
class AriaRuntimeStateBridgeTest {

    @Before
    fun setUp() {
        AriaRuntimeStateBridge.resetForTesting()
        AriaUnifiedNotificationCoordinator.resetForTesting()
    }

    private class TestBinder(
        var isAlive: Boolean = true,
        var linkShouldThrow: Boolean = false,
    ) : IBinder {
        var deathRecipient: IBinder.DeathRecipient? = null
            private set

        override fun getInterfaceDescriptor(): String? = "TestBinder"
        override fun pingBinder(): Boolean = isAlive
        override fun isBinderAlive(): Boolean = isAlive
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (linkShouldThrow) {
                throw android.os.RemoteException("Simulated linkToDeath failure")
            }
            deathRecipient = recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (deathRecipient === recipient) {
                deathRecipient = null
                return true
            }
            return false
        }

        fun triggerBinderDied() {
            deathRecipient?.binderDied()
        }
    }

    @Test
    fun sessionRegistration_tracksVoiceAndVisionSessionsIndependently() {
        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertFalse(AriaRuntimeStateBridge.isVisionActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertNull(AriaRuntimeStateBridge.getVisionSessionIdForTesting())

        // Register Voice session 1
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "voice_session_1",
            token = null,
        )
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertFalse(AriaRuntimeStateBridge.isVisionActiveLocal())
        assertEquals("voice_session_1", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())

        // Register Vision session 1
        AriaRuntimeStateBridge.handleSetVisionActiveInternal(
            context = null,
            active = true,
            sessionId = "vision_session_1",
            token = null,
        )
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertTrue(AriaRuntimeStateBridge.isVisionActiveLocal())
        assertEquals("vision_session_1", AriaRuntimeStateBridge.getVisionSessionIdForTesting())

        // Unregister Voice session 1
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = false,
            sessionId = "voice_session_1",
            token = null,
        )
        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertTrue(AriaRuntimeStateBridge.isVisionActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
    }

    @Test
    fun lateSessionUpdate_isIgnoredWhenNewerSessionIsActive() {
        // Voice session 1 registers
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "voice_session_1",
            token = null,
        )
        assertEquals("voice_session_1", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())

        // Voice session 2 supersedes session 1
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "voice_session_2",
            token = null,
        )
        assertEquals("voice_session_2", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())

        // Delayed stop callback from old session 1 arrives
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = false,
            sessionId = "voice_session_1",
            token = null,
        )

        // Session 2 MUST NOT be deactivated by old session 1 stop
        assertTrue("Late stop from old session must be ignored", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertEquals("voice_session_2", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())

        // Legitimate stop from session 2 deactivates
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = false,
            sessionId = "voice_session_2",
            token = null,
        )
        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
    }

    @Test
    fun binderDeathReconciliation_clearsActiveStateWhenProcessDies() {
        val voiceBinder = TestBinder()

        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "voice_pid_123",
            token = voiceBinder,
        )
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertEquals("voice_pid_123", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())

        // Simulate process death / binder died
        voiceBinder.triggerBinderDied()

        assertFalse("Voice service must be marked inactive upon binder death", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull("Session ID must be cleared upon binder death", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
    }

    @Test
    fun visionBinderDeathReconciliation_clearsVisionActiveState() {
        val visionBinder = TestBinder()

        AriaRuntimeStateBridge.handleSetVisionActiveInternal(
            context = null,
            active = true,
            sessionId = "vision_pid_456",
            token = visionBinder,
        )
        assertTrue(AriaRuntimeStateBridge.isVisionActiveLocal())
        assertEquals("vision_pid_456", AriaRuntimeStateBridge.getVisionSessionIdForTesting())

        visionBinder.triggerBinderDied()

        assertFalse("Vision service must be marked inactive upon binder death", AriaRuntimeStateBridge.isVisionActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVisionSessionIdForTesting())
    }

    @Test
    fun providerRestartReconciliation_defaultsToInactiveWithoutServiceRevival() {
        // Simulate previous active state
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(null, true, "old_voice", null)
        AriaRuntimeStateBridge.handleSetVisionActiveInternal(null, true, "old_vision", null)
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertTrue(AriaRuntimeStateBridge.isVisionActiveLocal())

        // Provider recreation: reset initializes to inactive
        AriaRuntimeStateBridge.resetForTesting()
        assertFalse("Provider restart must not retain stale active voice state", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertFalse("Provider restart must not retain stale active vision state", AriaRuntimeStateBridge.isVisionActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertNull(AriaRuntimeStateBridge.getVisionSessionIdForTesting())
    }

    @Test
    fun notificationDeduplication_returnsFalseWhenStateTupleIsUnchanged() {
        AriaUnifiedNotificationCoordinator.resetForTesting()

        // 1. First state: voice = true, vision = false, cam = false
        val tuple1 = NotificationStateTuple(voiceActive = true, visionActive = false, cameraUnavailable = false)
        val tuple2 = NotificationStateTuple(voiceActive = true, visionActive = false, cameraUnavailable = false)
        assertEquals(tuple1, tuple2)

        // Same tuple comparison
        assertTrue(tuple1 == tuple2)

        // 2. Different state tuple
        val tuple3 = NotificationStateTuple(voiceActive = true, visionActive = true, cameraUnavailable = false)
        assertFalse(tuple1 == tuple3)

        // 3. Camera unavailable state change
        val tuple4 = NotificationStateTuple(voiceActive = true, visionActive = true, cameraUnavailable = true)
        assertFalse(tuple3 == tuple4)
    }

    @Test
    fun rejectRegistration_whenTokenIsAlreadyDead() {
        val deadToken = TestBinder(isAlive = false)
        val success = AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "dead_session_1",
            token = deadToken,
        )

        assertFalse("Registration with already-dead token must fail", success)
        assertFalse("Bridge voiceActive must remain false on dead token registration", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull("Session ID must not be registered for dead token", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertNull("Death recipient must not be retained", deadToken.deathRecipient)
    }

    @Test
    fun rejectRegistration_whenLinkToDeathThrowsRemoteException() {
        val failingToken = TestBinder(isAlive = true, linkShouldThrow = true)
        val success = AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "failing_link_session",
            token = failingToken,
        )

        assertFalse("Registration when linkToDeath fails must return false", success)
        assertFalse("Bridge voiceActive must remain false when linkToDeath fails", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull("Session ID must not be registered on linkToDeath failure", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
    }

    @Test
    fun staleDeathCallback_afterTokenReplacement_doesNotClearNewActiveSession() {
        val token1 = TestBinder(isAlive = true)
        val token2 = TestBinder(isAlive = true)

        // Session 1 registers with token1
        val success1 = AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "session_1",
            token = token1,
        )
        assertTrue(success1)
        assertEquals("session_1", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals(token1, AriaRuntimeStateBridge.getVoiceTokenForTesting())

        // Session 2 replaces session 1 with token2
        val success2 = AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = true,
            sessionId = "session_2",
            token = token2,
        )
        assertTrue(success2)
        assertEquals("session_2", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals(token2, AriaRuntimeStateBridge.getVoiceTokenForTesting())

        // Late death callback from token1 arrives
        token1.triggerBinderDied()

        // Active state and session 2 MUST NOT be cleared by token1 death
        assertTrue("Late death from old token must not deactivate new session", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertEquals("session_2", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals(token2, AriaRuntimeStateBridge.getVoiceTokenForTesting())

        // Legitimate death of token2 clears active state
        token2.triggerBinderDied()
        assertFalse("Active token death must deactivate voice service", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
    }

    @Test
    fun staleStopCallback_withDifferentToken_isIgnored() {
        val token1 = TestBinder(isAlive = true)
        val token2 = TestBinder(isAlive = true)

        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(null, true, "sess_1", token1)
        AriaRuntimeStateBridge.handleSetVoiceActiveInternal(null, true, "sess_2", token2)

        // Stale stop from token1
        val stopped = AriaRuntimeStateBridge.handleSetVoiceActiveInternal(null, false, "sess_1", token1)
        assertFalse("Stale stop from old token must be rejected", stopped)
        assertTrue(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertEquals("sess_2", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())

        // Legitimate stop from token2
        val legitimateStop = AriaRuntimeStateBridge.handleSetVoiceActiveInternal(null, false, "sess_2", token2)
        assertTrue(legitimateStop)
        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
    }

    @Test
    fun cameraUnavailable_preservedAcrossVoiceActiveChanges() {
        // 1. Initial state: Vision is active with cameraUnavailable = true
        AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
            context = null,
            voiceActive = false,
            visionActive = true,
            cameraUnavailable = true,
        )
        assertEquals(
            NotificationStateTuple(voiceActive = false, visionActive = true, cameraUnavailable = true),
            AriaUnifiedNotificationCoordinator.getLastPostedTuple(),
        )

        // 2. Voice becomes active: cameraUnavailable should be preserved
        AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
            context = null,
            voiceActive = true,
            visionActive = true,
            cameraUnavailable = null,
        )
        assertEquals(
            NotificationStateTuple(voiceActive = true, visionActive = true, cameraUnavailable = true),
            AriaUnifiedNotificationCoordinator.getLastPostedTuple(),
        )

        // 3. Vision becomes inactive: cameraUnavailable should reset to false
        AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
            context = null,
            voiceActive = true,
            visionActive = false,
            cameraUnavailable = null,
        )
        assertEquals(
            NotificationStateTuple(voiceActive = true, visionActive = false, cameraUnavailable = false),
            AriaUnifiedNotificationCoordinator.getLastPostedTuple(),
        )
    }

    private class FakeVoiceServiceBinder(
        var sessionId: String = "voice_genuine_session_1",
        var isActive: Boolean = true,
        var isAlive: Boolean = true,
        var linkShouldThrow: Boolean = false,
    ) : IBinder, IVoiceService {
        var deathRecipient: IBinder.DeathRecipient? = null
            private set

        override fun getInterfaceDescriptor(): String = "com.ariateam.aria.speech.IVoiceService"
        override fun pingBinder(): Boolean = isAlive
        override fun isBinderAlive(): Boolean = isAlive
        override fun queryLocalInterface(descriptor: String): IInterface? = this
        override fun dump(fd: FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (linkShouldThrow) throw android.os.RemoteException("Simulated linkToDeath failure")
            deathRecipient = recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (deathRecipient === recipient) {
                deathRecipient = null
                return true
            }
            return false
        }

        override fun asBinder(): IBinder = this

        override fun getState(): Bundle = Bundle()
        override fun registerCallback(callback: IVoiceCallback?) {}
        override fun unregisterCallback(callback: IVoiceCallback?) {}
        override fun beginDictation() {}
        override fun finishDictation() {}
        override fun setAudibleFeedback(enabled: Boolean) {}
        override fun stop() {}
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

    private class FakeBindingDriver(
        var bindResult: Boolean = true,
    ) : AriaRuntimeStateBridge.VoiceBindingDriver {
        var unbindCount = 0
            private set
        var boundConnection: ServiceConnection? = null

        override fun bindVoiceService(context: Context?, connection: ServiceConnection, flags: Int): Boolean {
            boundConnection = connection
            return bindResult
        }

        override fun unbindVoiceService(context: Context?, connection: ServiceConnection) {
            unbindCount++
            if (boundConnection === connection) {
                boundConnection = null
            }
        }
    }

    @Test
    fun reconciliation_followedByActualSessionStop_clearsState() {
        val genuineSessionId = "voice_session_genuine_999"
        val fakeService = FakeVoiceServiceBinder(sessionId = genuineSessionId, isActive = true)
        val driver = FakeBindingDriver(bindResult = true)

        // 1. Reconcile with running VoiceService
        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        // 2. Deliver onServiceConnected
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), fakeService)

        // 3. Verify bridge registered genuine session ID (NEVER fabricated "voice_reconciled_...")
        assertTrue("Bridge must be voice active after reconciliation", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertEquals("Bridge must record genuine serviceSessionId", genuineSessionId, AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertFalse("Bridge voiceSessionId must NOT contain fabricated prefix", AriaRuntimeStateBridge.getVoiceSessionIdForTesting()!!.startsWith("voice_reconciled_"))

        // 4. Verify bounded cleanup unbinds the temporary reconciliation connection exactly once
        assertTrue("Connection must be marked cleanedUp", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be onServiceConnected", "onServiceConnected", connection.cleanupReason.get())
        assertEquals("Driver unbind must be called exactly once", 1, driver.unbindCount)

        // 5. Genuine stop from VoiceService arrives with the genuine sessionId
        val stopAccepted = AriaRuntimeStateBridge.handleSetVoiceActiveInternal(
            context = null,
            active = false,
            sessionId = genuineSessionId,
            token = fakeService,
        )

        // 6. Stop MUST be accepted and state cleared (previously failed due to fabricated session mismatch!)
        assertTrue("Genuine stop matching registered sessionId must be accepted", stopAccepted)
        assertFalse("Voice active state must be cleared after genuine stop", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull("Voice sessionId must be null after genuine stop", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
    }

    @Test
    fun alreadyBoundService_reRegistersAccuratelyWithoutOnBind() {
        val genuineSessionId = "voice_already_bound_456"
        val fakeService = FakeVoiceServiceBinder(sessionId = genuineSessionId, isActive = true)
        val driver = FakeBindingDriver(bindResult = true)

        // Simulates case where another client was already bound to VoiceService,
        // so Android framework onBind() does NOT execute again.
        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        // onServiceConnected is delivered with cached service binder
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), fakeService)

        // Explicit AIDL IPC queries getVoiceSessionInfo() directly on binder
        assertTrue("Already-bound active service must be reconciled as active", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertEquals("Already-bound serviceSessionId must be accurately recorded", genuineSessionId, AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals("Reconciliation connection must unbind cleanly", 1, driver.unbindCount)
    }

    @Test
    fun boundIdleService_doesNotInferActiveFromAliveBinder() {
        // VoiceService is bound (e.g. by VoiceViewModel), but state.isActive is false (VoicePhase.Stopped)
        val fakeService = FakeVoiceServiceBinder(sessionId = "voice_idle_333", isActive = false, isAlive = true)
        val driver = FakeBindingDriver(bindResult = true)

        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), fakeService)

        // Bridge MUST NOT infer active just because binder is alive
        assertFalse("Bound idle service must NOT cause phantom active state", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull("Bridge must not record sessionId for idle service", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals("Driver unbind must be called exactly once", 1, driver.unbindCount)
    }

    @Test
    fun boundedCleanup_onNullBinding_cleansUpExactlyOnceAndFencesLateCallback() {
        val driver = FakeBindingDriver(bindResult = true)
        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        // onNullBinding arrives
        connection.onNullBinding(ComponentName("com.ariateam.aria", "VoiceService"))

        assertTrue("Connection must be cleanedUp after onNullBinding", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be onNullBinding", "onNullBinding", connection.cleanupReason.get())
        assertEquals("Unbind must execute exactly once", 1, driver.unbindCount)

        // Late onServiceConnected arrives after onNullBinding
        val lateService = FakeVoiceServiceBinder(sessionId = "late_sess", isActive = true)
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), lateService)

        // Must be fenced: no state mutation, no double unbind
        assertFalse("Late callback must not register active state", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull("Late callback must not register session", AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals("Unbind count must not increment on late callback", 1, driver.unbindCount)
    }

    @Test
    fun boundedCleanup_onBindingDied_cleansUpExactlyOnceAndFencesLateCallback() {
        val driver = FakeBindingDriver(bindResult = true)
        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        connection.onBindingDied(ComponentName("com.ariateam.aria", "VoiceService"))

        assertTrue("Connection must be cleanedUp after onBindingDied", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be onBindingDied", "onBindingDied", connection.cleanupReason.get())
        assertEquals("Unbind must execute exactly once", 1, driver.unbindCount)

        val lateService = FakeVoiceServiceBinder(sessionId = "late_died_sess", isActive = true)
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), lateService)

        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertEquals(1, driver.unbindCount)
    }

    @Test
    fun boundedCleanup_onTimeout_cleansUpExactlyOnceAndFencesLateConnected() {
        val driver = FakeBindingDriver(bindResult = true)
        val shortTimeoutMs = 30L

        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = shortTimeoutMs,
            bindingDriver = driver,
        )

        // Wait for timeout to fire
        Thread.sleep(80L)

        assertTrue("Connection must be cleanedUp after timeout", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be timeout", "timeout", connection.cleanupReason.get())
        assertEquals("Unbind must execute exactly once on timeout", 1, driver.unbindCount)

        // Late onServiceConnected arrives after timeout expiration
        val lateService = FakeVoiceServiceBinder(sessionId = "late_timeout_sess", isActive = true)
        connection.onServiceConnected(ComponentName("com.ariateam.aria", "VoiceService"), lateService)

        // Fenced out!
        assertFalse("Late onServiceConnected after timeout must be fenced out", AriaRuntimeStateBridge.isVoiceActiveLocal())
        assertNull(AriaRuntimeStateBridge.getVoiceSessionIdForTesting())
        assertEquals("Driver unbind must remain 1 (no double unbind)", 1, driver.unbindCount)
    }

    @Test
    fun boundedCleanup_onBindFailed_cleansUpImmediately() {
        val driver = FakeBindingDriver(bindResult = false)

        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        assertTrue("Connection must be cleanedUp immediately on bind failure", connection.cleanedUp.get())
        assertEquals("Cleanup reason must be bindFailed", "bindFailed", connection.cleanupReason.get())
        assertEquals("Driver unbind must be invoked on bind failure", 1, driver.unbindCount)
        assertFalse(AriaRuntimeStateBridge.isVoiceActiveLocal())
    }

    @Test
    fun boundedCleanup_onServiceDisconnected_cleansUpExactlyOnce() {
        val driver = FakeBindingDriver(bindResult = true)
        val connection = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
            context = null,
            timeoutMs = 5000L,
            bindingDriver = driver,
        )

        connection.onServiceDisconnected(ComponentName("com.ariateam.aria", "VoiceService"))

        assertTrue(connection.cleanedUp.get())
        assertEquals("onServiceDisconnected", connection.cleanupReason.get())
        assertEquals(1, driver.unbindCount)
    }
}
