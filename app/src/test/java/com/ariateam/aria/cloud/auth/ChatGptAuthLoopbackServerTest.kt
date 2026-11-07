package com.ariateam.aria.cloud.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ChatGptAuthLoopbackServerTest {

    private lateinit var server: DefaultChatGptAuthLoopbackServer
    private val expectedState = "sec_state_xyz789"
    private val savedClientId = "oaiapp_test_client_456"

    @Before
    fun setUp() {
        server = DefaultChatGptAuthLoopbackServer()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun testSuccessfulCallbackDelivery() {
        val resultRef = AtomicReference<LoopbackCallbackResult>()
        val latch = CountDownLatch(1)

        val port = server.start(
            expectedState = expectedState,
            savedClientId = null,
        ) { result ->
            resultRef.set(result)
            latch.countDown()
        }

        assertTrue(port > 0)

        val url = URL("http://127.0.0.1:$port/auth/callback?code=test_auth_code_123&state=$expectedState&client_id=oaiapp_issued_789&scope=openid+chatgpt.tokens.use.direct")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        val statusCode = conn.responseCode
        assertEquals(200, statusCode)

        val responseHtml = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        assertTrue(responseHtml.contains("Sign in complete"))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val result = resultRef.get()
        assertNotNull(result)
        assertTrue(result is LoopbackCallbackResult.Success)
        val success = result as LoopbackCallbackResult.Success
        assertEquals("test_auth_code_123", success.code)
        assertEquals("oaiapp_issued_789", success.clientId)

        // Verify toString redacts authorization code
        val str = success.toString()
        assertFalse(str.contains("test_auth_code_123"))
        assertTrue(str.contains("hasCode=true"))
    }

    @Test
    fun testMismatchedStateThenValidState() {
        val resultRef = AtomicReference<LoopbackCallbackResult>()
        val latch = CountDownLatch(1)

        val port = server.start(
            expectedState = expectedState,
            savedClientId = null,
        ) { result ->
            resultRef.set(result)
            latch.countDown()
        }

        // 1. Send invalid state
        val urlBad = URL("http://127.0.0.1:$port/auth/callback?code=bad_code&state=wrong_state&client_id=oaiapp_1")
        val connBad = urlBad.openConnection() as HttpURLConnection
        connBad.requestMethod = "GET"
        assertEquals(400, connBad.responseCode)
        assertNull(resultRef.get())

        // 2. Send valid state on same listener
        val urlGood = URL("http://127.0.0.1:$port/auth/callback?code=good_code&state=$expectedState&client_id=oaiapp_1")
        val connGood = urlGood.openConnection() as HttpURLConnection
        connGood.requestMethod = "GET"
        assertEquals(200, connGood.responseCode)

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val result = resultRef.get()
        assertTrue(result is LoopbackCallbackResult.Success)
        assertEquals("good_code", (result as LoopbackCallbackResult.Success).code)
    }

    @Test
    fun testRejectDuplicateQueryParams() {
        val port = server.start(
            expectedState = expectedState,
            savedClientId = null,
        ) {}

        // Duplicate code
        val urlDupCode = URL("http://127.0.0.1:$port/auth/callback?code=c1&code=c2&state=$expectedState&client_id=oaiapp_1")
        val connDupCode = urlDupCode.openConnection() as HttpURLConnection
        assertEquals(400, connDupCode.responseCode)

        // Duplicate client_id
        val urlDupClient = URL("http://127.0.0.1:$port/auth/callback?code=c1&state=$expectedState&client_id=oaiapp_1&client_id=oaiapp_2")
        val connDupClient = urlDupClient.openConnection() as HttpURLConnection
        assertEquals(400, connDupClient.responseCode)
    }

    @Test
    fun testRejectOversizedLineInput() {
        val port = server.start(
            expectedState = expectedState,
            savedClientId = null,
        ) {}

        // Send a request line with 5000 characters without newline
        Socket("127.0.0.1", port).use { socket ->
            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            val oversizedPath = "A".repeat(5000)
            writer.write("GET /$oversizedPath HTTP/1.1\r\n\r\n")
            writer.flush()

            val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val responseLine = reader.readLine()
            assertNotNull(responseLine)
            assertTrue(responseLine.contains("400"))
        }
    }

    @Test
    fun testCancelRestartRacePreservesNewListener() {
        val resultRef = AtomicReference<LoopbackCallbackResult>()
        val latch = CountDownLatch(1)

        // Start attempt 1
        server.start(
            expectedState = "state_1",
            savedClientId = null,
        ) {}

        // Immediately start attempt 2 (cancels attempt 1)
        val port2 = server.start(
            expectedState = "state_2",
            savedClientId = null,
        ) { result ->
            resultRef.set(result)
            latch.countDown()
        }

        // Send valid request to attempt 2
        val url = URL("http://127.0.0.1:$port2/auth/callback?code=code_2&state=state_2&client_id=oaiapp_attempt_2")
        val conn = url.openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val result = resultRef.get()
        assertNotNull(result)
        assertEquals("code_2", (result as LoopbackCallbackResult.Success).code)
    }

    @Test
    fun testErrorCallbackHandled() {
        val resultRef = AtomicReference<LoopbackCallbackResult>()
        val latch = CountDownLatch(1)

        val port = server.start(
            expectedState = expectedState,
            savedClientId = savedClientId,
        ) { result ->
            resultRef.set(result)
            latch.countDown()
        }

        val url = URL("http://127.0.0.1:$port/auth/callback?error=access_denied&error_description=User+declined+authorization&state=$expectedState")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        val statusCode = conn.responseCode
        assertEquals(200, statusCode)

        val responseHtml = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        assertTrue(responseHtml.contains("Sign in was not completed"))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val result = resultRef.get()
        assertNotNull(result)
        assertTrue(result is LoopbackCallbackResult.Error)
        val error = result as LoopbackCallbackResult.Error
        assertEquals("access_denied", error.errorCode)
        assertEquals("User declined authorization", error.errorDescription)
    }

    @Test
    fun testWrongPathReturns404() {
        val port = server.start(
            expectedState = expectedState,
            savedClientId = savedClientId,
        ) {}

        val url = URL("http://127.0.0.1:$port/unexpected_path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        val statusCode = conn.responseCode
        assertEquals(404, statusCode)
    }

    private fun assertNull(value: Any?) {
        org.junit.Assert.assertNull(value)
    }
}
