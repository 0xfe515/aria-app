package com.ariateam.aria.speech

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ariateam.aria.hazard.HazardAlertEvent
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented device tests validating actual cross-process Android Binder IPC to the real
 * [VoiceService] running in the secondary `:wakeword` process.
 *
 * Distinct from in-memory fake/stub fixtures (e.g. [HazardSpeechIntegrationDeviceTest]), these tests exercise
 * real Android IPC via AIDL, verifying:
 * 1. Actual cross-process Binder transport: `binder.queryLocalInterface(...) == null` confirms
 *    a real remote `BinderProxy` (not a same-process local Java Stub).
 * 2. Real AIDL method execution on an idle/stopped service without unintended service revival or mic activation.
 * 3. Stable, non-blank remote session identity across IPC calls.
 * 4. Transparent active-session precondition for live speech preemption testing without starting
 *    unprompted microphone capture or audio recording.
 */
@RunWith(AndroidJUnit4::class)
class HazardSpeechRealServiceDeviceTest {

    private lateinit var context: Context

    companion object {
        private const val TAG = "HazardSpeechRealTest"
        private const val COLD_START_TIMEOUT_SECONDS = 25L
        private const val IPC_TIMEOUT_SECONDS = 5L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Real-service test MUST NOT reset real runtime state bridge or shared alert sink
        // to avoid corrupting running user session or active service listeners.
    }

    @Test
    fun realVoiceService_crossProcessBinder_executesPostHazardAlertOnIdleServiceWithoutRevival() {
        Assume.assumeFalse(
            "Skipping idle VoiceService test: active voice session is currently running",
            AriaRuntimeStateBridge.isVoiceActive(context),
        )

        val voiceServiceIntent = Intent(context, VoiceService::class.java)
        val connectedLatch = CountDownLatch(1)
        val binderRef = AtomicReference<IBinder?>(null)
        val isBound = AtomicBoolean(false)
        val bindingDied = AtomicBoolean(false)
        val nullBindingReceived = AtomicBoolean(false)

        val testConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                connectedLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef.set(null)
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
            // Starts idle service without microphone permissions or wake listening.
            val bindResult = context.bindService(voiceServiceIntent, testConnection, Context.BIND_AUTO_CREATE)
            assertTrue("bindService with BIND_AUTO_CREATE should return true", bindResult)
            isBound.set(true)

            val connected = connectedLatch.await(COLD_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (bindingDied.get()) {
                fail(":wakeword process died during startup")
            }
            if (nullBindingReceived.get()) {
                fail("VoiceService returned null IBinder")
            }
            if (!connected) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val proc = am?.runningAppProcesses?.find { it.processName.endsWith(":wakeword") }
                val diag = if (proc != null) {
                    ":wakeword process is alive (pid=${proc.pid}), startup timed out after ${COLD_START_TIMEOUT_SECONDS}s"
                } else {
                    ":wakeword process failed to fork or crashed"
                }
                fail("VoiceService failed to connect within $COLD_START_TIMEOUT_SECONDS seconds: $diag")
            }

            val binder = binderRef.get()
            assertNotNull("IBinder must be received across IPC", binder)
            assertTrue("IBinder must be alive", binder!!.isBinderAlive)

            // Positive proof of cross-process Binder IPC:
            // A local Stub in the same process returns non-null from queryLocalInterface.
            // A remote BinderProxy in a secondary process (:wakeword) returns null!
            assertNull(
                "Binder must be a cross-process BinderProxy from :wakeword process, not a local in-process Java object",
                binder.queryLocalInterface("com.ariateam.aria.speech.IVoiceService"),
            )

            val voiceService = IVoiceService.Stub.asInterface(binder)
            assertNotNull("IVoiceService proxy must be non-null", voiceService)

            // 2. Query real service state over AIDL IPC
            assertFalse("Bound service without startCommand must not be active", voiceService.isServiceActive)
            val sessionId = voiceService.serviceSessionId
            assertNotNull("VoiceService session ID must not be null", sessionId)
            assertTrue("VoiceService session ID must not be blank", sessionId.isNotBlank())
            assertTrue("VoiceService session ID must start with voice_", sessionId.startsWith("voice_"))

            // 3. Post hazard alert over real cross-process AIDL IPC to idle service
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            voiceService.postHazardAlert(
                1L,
                1L,
                nowNanos,
                "실제 AIDL 크로스 프로세스 경고 테스트",
            )

            // Allow brief settle for remote main handler processing
            Thread.sleep(150)

            // 4. Invariant: Posting hazard alert to idle service must NOT revive or activate it
            assertFalse(
                "Posting hazard alert must not revive an idle/stopped VoiceService into active listening",
                voiceService.isServiceActive,
            )
            assertTrue("Remote binder proxy must remain alive after hazard alert IPC", binder.isBinderAlive)
        } finally {
            if (isBound.get()) {
                runCatching { context.unbindService(testConnection) }
            }
            runCatching { context.stopService(voiceServiceIntent) }
        }
    }

    @Test
    fun hazardSpeechBridge_withFlagZero_doesNotReviveStoppedRealService() {
        Assume.assumeFalse(
            "Skipping stopped bridge test: active voice session is currently running",
            AriaRuntimeStateBridge.isVoiceActive(context),
        )

        val bridge = HazardSpeechBridge(context = context)
        try {
            bridge.start()

            // Post hazard alert through production bridge code path while voice is stopped
            bridge.onHazardAlert(
                HazardAlertEvent(
                    session = 100L,
                    sequence = 1L,
                    timestampNanos = SystemClock.elapsedRealtimeNanos(),
                    message = "중지 상태 경고",
                    level = HazardLevel.NEAR_PATH_HAZARD,
                )
            )

            // Bridge must respect flag 0 invariant: VoiceService must not be active or revived
            assertFalse(
                "HazardSpeechBridge must not activate voice service when voice is stopped",
                AriaRuntimeStateBridge.isVoiceActive(context),
            )
        } finally {
            bridge.close()
        }
    }

    /**
     * Positive active real-AIDL test verifying prioritized hazard delivery during active speech.
     *
     * EXPLICIT PRECONDITION POLICY:
     * This test requires an existing, user-initiated active VoiceService session and granted
     * RECORD_AUDIO permission. If either is absent, the test is cleanly skipped (via Assume.assumeTrue)
     * to avoid triggering unwanted microphone capture or showing permission prompts in test automation.
     */
    @Test
    fun realVoiceService_whenActivePreconditionMet_receivesPrioritizedHazardAlert() {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val isVoiceActive = AriaRuntimeStateBridge.isVoiceActive(context)

        Assume.assumeTrue(
            "Skipping active real-AIDL test: requires pre-existing active VoiceService session and RECORD_AUDIO permission to prevent unexpected mic capture",
            hasMicPermission && isVoiceActive,
        )

        Log.i(TAG, "Active voice session and mic permission confirmed; proceeding with real-AIDL hazard test")

        val voiceServiceIntent = Intent(context, VoiceService::class.java)
        val connectedLatch = CountDownLatch(1)
        val binderRef = AtomicReference<IBinder?>(null)
        val isBound = AtomicBoolean(false)

        val testConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                connectedLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef.set(null)
            }
        }

        var voiceService: IVoiceService? = null
        var testCallback: IVoiceCallback? = null
        var previousAudible = true
        val alertReceivedLatch = CountDownLatch(1)
        val receivedMessageRef = AtomicReference<String?>(null)

        try {
            // Bind strictly with flag 0 to attach to already-running service without reviving or auto-creating
            val bound = context.bindService(voiceServiceIntent, testConnection, 0)
            assertTrue("bindService with flag 0 must attach to confirmed active VoiceService", bound)
            isBound.set(true)

            val connected = connectedLatch.await(IPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue("Timed out connecting to active VoiceService", connected)

            val binder = binderRef.get()
            assertNotNull(binder)
            assertNull("Must be a real BinderProxy", binder!!.queryLocalInterface("com.ariateam.aria.speech.IVoiceService"))

            val service = IVoiceService.Stub.asInterface(binder)
            voiceService = service

            // Read previous audible feedback and temporarily mute during test to prevent audio blast
            val currentState = service.state
            previousAudible = currentState?.getBundle("command")?.getBoolean("audible", true) ?: true
            service.setAudibleFeedback(false)

            // Register real IVoiceCallback
            val uniqueAlertMessage = "보행로 공사 주의 [${System.currentTimeMillis()}]"
            testCallback = object : IVoiceCallback.Stub() {
                override fun onState(stateBundle: Bundle?) {
                    val cmdBundle = stateBundle?.getBundle("command")
                    val msg = cmdBundle?.getString("message")
                    if (msg == uniqueAlertMessage) {
                        receivedMessageRef.set(msg)
                        alertReceivedLatch.countDown()
                    }
                }
            }
            service.registerCallback(testCallback)

            // Post fresh hazard alert over real AIDL IPC
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            service.postHazardAlert(
                999L,
                1L,
                nowNanos,
                uniqueAlertMessage,
            )

            val delivered = alertReceivedLatch.await(IPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue("Hazard alert should be processed by active VoiceService and reflected in callback", delivered)
            assertEquals(uniqueAlertMessage, receivedMessageRef.get())
        } finally {
            // Restore previous audible setting, unregister callback, and unbind WITHOUT terminating user's session
            voiceService?.let { s ->
                runCatching { s.setAudibleFeedback(previousAudible) }
                testCallback?.let { cb -> runCatching { s.unregisterCallback(cb) } }
            }
            if (isBound.get()) {
                runCatching { context.unbindService(testConnection) }
            }
        }
    }
}
