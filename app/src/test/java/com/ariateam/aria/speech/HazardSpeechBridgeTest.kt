package com.ariateam.aria.speech

import android.content.ServiceConnection
import android.os.IBinder
import com.ariateam.aria.hazard.AlertSeverity
import com.ariateam.aria.hazard.HazardAlertCoordinator
import com.ariateam.aria.hazard.HazardAlertEvent
import com.ariateam.aria.hazard.HazardCause
import com.ariateam.aria.hazard.HazardLevel
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class HazardSpeechBridgeTest {

    data class RecordedAlert(
        val session: Long,
        val sequence: Long,
        val timestampNanos: Long,
        val message: String,
        val severity: AlertSeverity
    )

    private val recordedAlerts = mutableListOf<RecordedAlert>()
    private lateinit var fakeVoiceService: IVoiceService

    @Before
    fun setUp() {
        recordedAlerts.clear()
        fakeVoiceService = Proxy.newProxyInstance(
            IVoiceService::class.java.classLoader,
            arrayOf(IVoiceService::class.java)
        ) { _, method, args ->
            if (method.name == "postHazardAlertWithSeverity" && args != null) {
                recordedAlerts.add(
                    RecordedAlert(
                        session = args[0] as Long,
                        sequence = args[1] as Long,
                        timestampNanos = args[2] as Long,
                        message = args[3] as String,
                        severity = AlertSeverity.entries[args[4] as Int]
                    )
                )
            }
            null
        } as IVoiceService

        AriaRuntimeStateBridge.resetForTesting()
        HazardAlertCoordinator.setSharedAlertSink(null)
    }

    @After
    fun tearDown() {
        AriaRuntimeStateBridge.resetForTesting()
        HazardAlertCoordinator.setSharedAlertSink(null)
    }

    private fun createBinderProxy(service: IVoiceService): IBinder {
        return Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)
        ) { _, method, _ ->
            if (method.name == "queryLocalInterface") {
                service
            } else {
                null
            }
        } as IBinder
    }

    @Test
    fun start_bindsWithFlagZero_neverAutoCreate() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        val capturedFlags = mutableListOf<Int>()
        var capturedConnection: ServiceConnection? = null

        val bridge = HazardSpeechBridge(
            context = null,
            serviceBinder = { conn, flags ->
                capturedConnection = conn
                capturedFlags.add(flags)
                true
            }
        )
        bridge.start()

        // MUST be flag 0, NEVER Context.BIND_AUTO_CREATE (which is 1)
        assertEquals(listOf(0), capturedFlags)
        assertNotNull(capturedConnection)

        bridge.close()
    }

    @Test
    fun onHazardAlert_whenVoiceActive_forwardsToVoiceService() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)

        val bridge = HazardSpeechBridge(
            context = null,
            voiceServiceProvider = { fakeVoiceService },
        )
        bridge.start()

        val event = HazardAlertEvent(
            session = 42L,
            sequence = 1L,
            timestampNanos = 100_000_000L,
            message = "전방 1m 장애물 주의",
            level = HazardLevel.NEAR_PATH_HAZARD,
        )

        bridge.onHazardAlert(event)

        assertEquals(1, recordedAlerts.size)
        assertEquals(
            RecordedAlert(
                session = 42L,
                sequence = 1L,
                timestampNanos = 100_000_000L,
                message = "전방 1m 장애물 주의",
                severity = AlertSeverity.WARNING
            ),
            recordedAlerts[0]
        )
    }

    @Test
    fun onHazardAlert_whenVoiceStopped_doesNotDeliverOrReviveVoice() {
        // Explicitly stopped or inactive:
        AriaRuntimeStateBridge.setVoiceActiveLocal(false)
        var bindAttempted = false

        val bridge = HazardSpeechBridge(
            context = null,
            voiceServiceProvider = { fakeVoiceService },
            serviceBinder = { _, _ ->
                bindAttempted = true
                true
            }
        )

        val event = HazardAlertEvent(
            session = 42L,
            sequence = 2L,
            timestampNanos = 200_000_000L,
            message = "경로 막힘",
            level = HazardLevel.PATH_BLOCKED,
        )

        bridge.onHazardAlert(event)

        // Must NOT deliver when voice is inactive
        assertEquals(0, recordedAlerts.size)
        // Must NOT attempt to bind or start
        assertTrue("Service binding must not be attempted when voice is stopped", !bindAttempted)
    }

    @Test
    fun onBindingDied_unbindsConnectionToPreventLeak() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        var capturedConnection: ServiceConnection? = null
        val unbindCalls = mutableListOf<ServiceConnection>()

        val bridge = HazardSpeechBridge(
            context = null,
            serviceBinder = { conn, _ ->
                capturedConnection = conn
                true
            },
            serviceUnbinder = { conn ->
                unbindCalls.add(conn)
            }
        )
        bridge.start()

        val connection = capturedConnection
        assertNotNull("Connection must have been provided during binding", connection)

        // Simulate binding died
        connection!!.onBindingDied(null)

        // Must unbind to prevent ActivityManager ServiceConnection leak
        assertEquals(1, unbindCalls.size)
        assertTrue(unbindCalls[0] === connection)
    }

    @Test
    fun onNullBinding_unbindsConnectionToPreventLeak() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        var capturedConnection: ServiceConnection? = null
        val unbindCalls = mutableListOf<ServiceConnection>()

        val bridge = HazardSpeechBridge(
            context = null,
            serviceBinder = { conn, _ ->
                capturedConnection = conn
                true
            },
            serviceUnbinder = { conn ->
                unbindCalls.add(conn)
            }
        )
        bridge.start()

        val connection = capturedConnection
        assertNotNull("Connection must have been provided during binding", connection)

        // Simulate null binding
        connection!!.onNullBinding(null)

        // Must unbind to prevent leak
        assertEquals(1, unbindCalls.size)
        assertTrue(unbindCalls[0] === connection)
    }

    @Test
    fun stopStart_allocatesNewConnection_andRejectsStaleConnectedCallbackFromPreviousGeneration() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        val connections = mutableListOf<ServiceConnection>()
        val unbindCalls = mutableListOf<ServiceConnection>()

        val bridge = HazardSpeechBridge(
            context = null,
            serviceBinder = { conn, _ ->
                connections.add(conn)
                true
            },
            serviceUnbinder = { conn ->
                unbindCalls.add(conn)
            }
        )

        bridge.start()
        assertEquals(1, connections.size)
        val conn1 = connections[0]

        // Stop generation 1
        bridge.stop()
        assertEquals(1, unbindCalls.size)
        assertTrue(unbindCalls.contains(conn1))

        // Start generation 2
        bridge.start()
        assertEquals(2, connections.size)
        val conn2 = connections[1]
        assertTrue("Must allocate a new ServiceConnection per binding attempt", conn1 !== conn2)

        val binderProxy1 = createBinderProxy(fakeVoiceService)
        // Late arrival from conn1
        conn1.onServiceConnected(null, binderProxy1)

        // Conn1 was rejected and safely unbound; conn2 is not connected yet
        val event = HazardAlertEvent(42L, 1L, 100_000L, "테스트 경고", HazardLevel.NEAR_PATH_HAZARD)
        bridge.onHazardAlert(event)
        // No alerts delivered because conn1 was rejected
        assertEquals(0, recordedAlerts.size)

        // Now conn2 connects
        val binderProxy2 = createBinderProxy(fakeVoiceService)
        conn2.onServiceConnected(null, binderProxy2)

        bridge.onHazardAlert(event)
        assertEquals(1, recordedAlerts.size)
        assertEquals("테스트 경고", recordedAlerts[0].message)

        bridge.close()
    }

    @Test
    fun stopStart_rejectsStaleBindingDiedFromPreviousGeneration() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        val connections = mutableListOf<ServiceConnection>()
        val unbindCalls = mutableListOf<ServiceConnection>()

        val bridge = HazardSpeechBridge(
            context = null,
            serviceBinder = { conn, _ ->
                connections.add(conn)
                true
            },
            serviceUnbinder = { conn ->
                unbindCalls.add(conn)
            }
        )

        bridge.start()
        val conn1 = connections[0]
        bridge.stop()

        bridge.start()
        val conn2 = connections[1]

        // Connect conn2
        val binderProxy = createBinderProxy(fakeVoiceService)
        conn2.onServiceConnected(null, binderProxy)

        // Late onBindingDied from conn1
        conn1.onBindingDied(null)

        // Conn1 should be unbound, but conn2 must remain active
        val event = HazardAlertEvent(42L, 2L, 200_000L, "주의", HazardLevel.PATH_CAUTION, severity = AlertSeverity.CAUTION)
        bridge.onHazardAlert(event)
        assertEquals(1, recordedAlerts.size)
        assertEquals("주의", recordedAlerts[0].message)
        assertEquals(AlertSeverity.CAUTION, recordedAlerts[0].severity)

        bridge.close()
    }

    @Test
    fun stopStart_rejectsStaleNullBindingFromPreviousGeneration() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        val connections = mutableListOf<ServiceConnection>()
        val unbindCalls = mutableListOf<ServiceConnection>()

        val bridge = HazardSpeechBridge(
            context = null,
            serviceBinder = { conn, _ ->
                connections.add(conn)
                true
            },
            serviceUnbinder = { conn ->
                unbindCalls.add(conn)
            }
        )

        bridge.start()
        val conn1 = connections[0]
        bridge.stop()

        bridge.start()
        val conn2 = connections[1]

        val binderProxy = createBinderProxy(fakeVoiceService)
        conn2.onServiceConnected(null, binderProxy)

        // Late onNullBinding from conn1
        conn1.onNullBinding(null)

        // Conn2 must remain active
        val event = HazardAlertEvent(42L, 3L, 300_000L, "전방 주의", HazardLevel.NEAR_PATH_HAZARD)
        bridge.onHazardAlert(event)
        assertEquals(1, recordedAlerts.size)
        assertEquals("전방 주의", recordedAlerts[0].message)

        bridge.close()
    }

    @Test
    fun attachShared_registersBridgeInHazardAlertCoordinator() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        val bridge = HazardSpeechBridge.attachShared(null)

        assertNotNull(HazardAlertCoordinator.getSharedAlertSink())
        assertTrue(HazardAlertCoordinator.getSharedAlertSink() === bridge)

        bridge.close()
        assertNull(HazardAlertCoordinator.getSharedAlertSink())
    }

    @Test
    fun onHazardAlert_warningPreemptsSpeechBeforePostHazardAlert_andCautionDoesNotPreempt() {
        AriaRuntimeStateBridge.setVoiceActiveLocal(true)
        val invocationLog = mutableListOf<String>()

        val voiceService = Proxy.newProxyInstance(
            IVoiceService::class.java.classLoader,
            arrayOf(IVoiceService::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "preemptSpeech" -> invocationLog.add("preemptSpeech")
                "postHazardAlertWithSeverity" -> invocationLog.add("postHazardAlertWithSeverity")
            }
            null
        } as IVoiceService

        val bridge = HazardSpeechBridge(
            context = null,
            voiceServiceProvider = { voiceService },
        )
        bridge.start()

        // 1. WARNING event: MUST invoke preemptSpeech before postHazardAlert
        val warningEvent = HazardAlertEvent(
            session = 42L,
            sequence = 1L,
            timestampNanos = 100_000_000L,
            message = "정지. 전방 차량 접근.",
            level = HazardLevel.PATH_BLOCKED,
            severity = AlertSeverity.WARNING,
            cause = HazardCause.W1_VEHICLE_APPROACH,
        )
        bridge.onHazardAlert(warningEvent)

        assertEquals(
            "WARNING must preempt speech before posting hazard alert",
            listOf("preemptSpeech", "postHazardAlertWithSeverity"),
            invocationLog,
        )

        invocationLog.clear()

        // 2. CAUTION event: MUST invoke only postHazardAlert without preemptSpeech
        val cautionEvent = HazardAlertEvent(
            session = 42L,
            sequence = 2L,
            timestampNanos = 200_000_000L,
            message = "전방 경로 장애물 주의.",
            level = HazardLevel.PATH_CAUTION,
            severity = AlertSeverity.CAUTION,
            cause = HazardCause.C1_OBJECT_IN_PATH,
        )
        bridge.onHazardAlert(cautionEvent)

        assertEquals(
            "CAUTION must not preempt speech",
            listOf("postHazardAlertWithSeverity"),
            invocationLog,
        )

        bridge.close()
    }
}
