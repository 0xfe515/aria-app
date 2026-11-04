package com.ariateam.aria.runtime

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ariateam.aria.speech.IVoiceService
import com.ariateam.aria.speech.VoiceService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented device tests validating true IPC binding, unified stop dispatch, and provider
 * reconciliation with the live [VoiceService] in the :wakeword process on real Android hardware (e.g. Pixel 3).
 *
 * Distinct from in-memory fake-service wiring (such as HazardSpeechIntegrationDeviceTest and
 * AriaUnifiedStopReceiverTest), this test exercises real Android IPC via AIDL, verifying:
 * 1. Binding to a live running VoiceService across the IPC boundary.
 * 2. AriaRuntimeStateBridge reconciliation with a live VoiceService without false activation.
 * 3. AriaUnifiedStopReceiver.requestVoiceStop binding with flag 0 to issue IVoiceService.stop()
 *    via AIDL IPC and cleanly terminating with fallback stopService.
 * 4. Bound and unbound service cleanup idempotency and absence of service revival under real
 *    Android framework bind semantics (flag 0 connection deferral and bounded timeout cleanup).
 * 5. Strictly bounded execution starting from a known state with zero audio recording,
 *    wake detection, or transcription.
 *
 * SCOPE & HARDWARE LIMITATION NOTE:
 * Zero audio recording permission or hardware audio capture is required or exercised for this idle
 * IPC binding test. While this exercises AIDL IPC stop delivery, ServiceConnection unbinding, and
 * fallback stopService invocation across process boundaries, an idle binding test cannot and does
 * not prove active ASR hardware stream release or live wake-word engine cleanup. Active audio
 * cleanup requires separate end-to-end audio pipeline verification with microphone permissions.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedStopDeviceTest {

    private lateinit var context: Context

    companion object {
        // Cold start of secondary :wakeword process on real hardware (Pixel 3) involves zygote fork,
        // application initialization, AssetManager KWS model validation, and native ONNX engine loading.
        private const val COLD_START_TIMEOUT_SECONDS = 25L
        private const val RECONCILE_TIMEOUT_MS = 4000L
        private const val STOP_IPC_TIMEOUT_MS = 4000L
        private const val UNBOUND_STOP_TIMEOUT_MS = 1500L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AriaRuntimeStateBridge.resetForTesting()
        AriaUnifiedNotificationCoordinator.resetForTesting()
        // Ensure any previous service instance is fully stopped before test execution to start from a known state
        runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
        // Allow brief settle time for any prior service process shutdown
        Thread.sleep(200)
    }

    @After
    fun tearDown() {
        runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
        AriaRuntimeStateBridge.resetForTesting()
        AriaUnifiedNotificationCoordinator.resetForTesting()
    }

    @Test
    fun voiceService_stopViaUnifiedStopReceiver_exercisesRealBoundIpcAndCleanup() {
        val voiceServiceIntent = Intent(context, VoiceService::class.java)
        val connectedLatch = CountDownLatch(1)
        val serviceBinderRef = AtomicReference<IBinder?>(null)
        val isTestBound = AtomicBoolean(false)
        val bindingDied = AtomicBoolean(false)
        val nullBindingReceived = AtomicBoolean(false)

        val testConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceBinderRef.set(service)
                connectedLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBinderRef.set(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                bindingDied.set(true)
                connectedLatch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                nullBindingReceived.set(true)
                connectedLatch.countDown()
            }
        }

        try {
            // 1. Bind to the real VoiceService in :wakeword process with BIND_AUTO_CREATE
            val bindResult = context.bindService(voiceServiceIntent, testConnection, Context.BIND_AUTO_CREATE)
            assertTrue("bindService with BIND_AUTO_CREATE should succeed", bindResult)
            isTestBound.set(true)

            val connected = connectedLatch.await(COLD_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Distinguish binding timeout from service crash or null binding
            if (bindingDied.get()) {
                fail("VoiceService binding died: :wakeword process crashed during startup/binding")
            }
            if (nullBindingReceived.get()) {
                fail("VoiceService returned null IBinder from onBind()")
            }
            if (!connected) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val wakewordProc = activityManager?.runningAppProcesses?.find { it.processName.endsWith(":wakeword") }
                val diag = if (wakewordProc != null) {
                    ":wakeword process is alive (pid=${wakewordProc.pid}), connection timed out after ${COLD_START_TIMEOUT_SECONDS}s (cold init / native ASR load took longer than timeout)"
                } else {
                    ":wakeword process is not running (crashed or failed to fork)"
                }
                fail("VoiceService failed to connect within $COLD_START_TIMEOUT_SECONDS seconds: $diag")
            }

            val binder = serviceBinderRef.get()
            assertNotNull("Binder proxy must be received across IPC", binder)
            assertTrue("Binder proxy must be alive", binder!!.isBinderAlive)

            // 2. Query VoiceService AIDL interface
            val voiceService = IVoiceService.Stub.asInterface(binder)
            assertNotNull("IVoiceService proxy must not be null", voiceService)

            // Idle bound service must NOT report active (no audio recording or wake listening started)
            assertFalse("Bound service without startCommand must not be active", voiceService.isServiceActive)
            val sessionId = voiceService.serviceSessionId
            assertNotNull("VoiceService must have a non-empty session ID", sessionId)
            assertTrue("VoiceService session ID must not be blank", sessionId.isNotBlank())
            assertTrue("VoiceService session ID must match expected prefix", sessionId.startsWith("voice_"))

            // Regression verification for VoiceService AIDL getter recursion:
            // Ensure successive queries return the identical stable session ID without crashing
            // the remote service process or causing DeadObjectException across IPC boundaries.
            repeat(5) { callIndex ->
                val repeatedSessionId = voiceService.serviceSessionId
                assertEquals(
                    "Service session ID must remain stable across repeated IPC queries (call $callIndex)",
                    sessionId,
                    repeatedSessionId,
                )
                assertTrue("Remote binder proxy must remain alive after repeated session queries", binder.isBinderAlive)
            }

            // 3. Verify provider reconciliation with running VoiceService
            val reconcileConn = AriaRuntimeStateBridge.reconcileWithRunningVoiceService(
                context = context,
                timeoutMs = RECONCILE_TIMEOUT_MS,
            )
            val reconcileDeadline = System.currentTimeMillis() + RECONCILE_TIMEOUT_MS + 1000L
            while (!reconcileConn.cleanedUp.get() && System.currentTimeMillis() < reconcileDeadline) {
                Thread.sleep(50)
            }
            assertTrue("Reconcile connection must clean up within bounded timeout", reconcileConn.cleanedUp.get())
            assertFalse("Reconciled state for idle VoiceService must remain inactive", AriaRuntimeStateBridge.isVoiceActive(context))

            // 4. Exercise AriaUnifiedStopReceiver.requestVoiceStop with live running service
            // requestVoiceStop uses flag 0, which successfully attaches to the already-running service,
            // issues IVoiceService.stop() IPC, unbinds, and calls stopService() fallback.
            val stopConnection = AriaUnifiedStopReceiver.requestVoiceStop(
                context = context,
                timeoutMs = STOP_IPC_TIMEOUT_MS,
            )

            // Await stop connection completion
            val stopDeadline = System.currentTimeMillis() + STOP_IPC_TIMEOUT_MS + 2000L
            while (!stopConnection.cleanedUp.get() && System.currentTimeMillis() < stopDeadline) {
                Thread.sleep(50)
            }

            assertTrue("Stop connection must clean up within timeout", stopConnection.cleanedUp.get())
            assertTrue("Stop connection should have successfully bound to running service with flag 0", stopConnection.isBound.get())
            assertTrue("Stop IPC should have been requested over real AIDL interface", stopConnection.stopIpcRequested.get())
            assertTrue("Stop connection should have executed unbind", stopConnection.unbindCount.get() >= 1)
            assertTrue("Fallback stopService should be invoked to terminate started state", stopConnection.fallbackStopInvoked.get())
        } finally {
            if (isTestBound.get()) {
                runCatching { context.unbindService(testConnection) }
            }
            runCatching { context.stopService(voiceServiceIntent) }
            AriaRuntimeStateBridge.resetForTesting()
            AriaUnifiedNotificationCoordinator.resetForTesting()
        }
    }

    @Test
    fun unboundVoiceService_requestVoiceStop_doesNotReviveServiceAndCleansUpImmediately() {
        // Ensure service is stopped to establish known initial state
        context.stopService(Intent(context, VoiceService::class.java))
        AriaRuntimeStateBridge.resetForTesting()
        Thread.sleep(200)

        // When VoiceService is NOT running, requestVoiceStop uses flag 0 and must NOT revive it.
        // Under Android framework semantics (Context.bindService without BIND_AUTO_CREATE):
        // 1. Android may return true indicating the binding intent is accepted/deferred without starting the service.
        //    In this case, no onServiceConnected callback is delivered, and VoiceStopConnection cleans up upon
        //    bounded timeout, unbinding the deferred registration and invoking fallback stopService.
        // 2. Alternatively, if the platform rejects flag 0 immediately, bindService returns false,
        //    and cleanup occurs synchronously with reason "bindFailed".
        // In neither case is the service revived.
        val stopConnection = AriaUnifiedStopReceiver.requestVoiceStop(
            context = context,
            timeoutMs = UNBOUND_STOP_TIMEOUT_MS,
        )

        // Await bounded cleanup (handles both immediate rejection and deferred flag 0 timeout)
        val cleanupDeadline = System.currentTimeMillis() + UNBOUND_STOP_TIMEOUT_MS + 2000L
        while (!stopConnection.cleanedUp.get() && System.currentTimeMillis() < cleanupDeadline) {
            Thread.sleep(50)
        }

        assertTrue("Connection must be cleaned up within bounded timeout", stopConnection.cleanedUp.get())
        val reason = stopConnection.cleanupReason.get()
        assertNotNull("Cleanup reason must be recorded", reason)
        assertTrue(
            "Cleanup reason must be 'timeout' (deferred flag 0) or 'bindFailed' (immediate rejection), got: $reason",
            reason == "timeout" || reason == "bindFailed",
        )

        if (stopConnection.isBound.get()) {
            assertEquals("Deferred flag 0 binding must clean up with reason 'timeout'", "timeout", reason)
            assertTrue("Deferred flag 0 binding must execute unbindService during cleanup", stopConnection.unbindCount.get() >= 1)
        } else {
            assertEquals("Immediate bind rejection must report 'bindFailed'", "bindFailed", reason)
        }

        assertFalse("No stop IPC should be requested if service was not connected", stopConnection.stopIpcRequested.get())
        assertTrue("Fallback stopService must still be invoked to clear any stale started state", stopConnection.fallbackStopInvoked.get())
        assertFalse("VoiceService must remain inactive in bridge", AriaRuntimeStateBridge.isVoiceActive(context))
    }
}
