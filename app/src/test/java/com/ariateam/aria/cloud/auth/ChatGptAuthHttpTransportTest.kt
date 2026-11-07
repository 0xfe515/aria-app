package com.ariateam.aria.cloud.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptAuthHttpTransportTest {

    private val transport = DefaultChatGptAuthHttpTransport()

    @Test
    fun testToStringRedacted() {
        val resp = AuthHttpResponse(200, "{\"access_token\":\"super_secret_token\"}")
        val str = resp.toString()
        assertFalse(str.contains("super_secret_token"))
        assertTrue(str.contains("statusCode=200"))
        assertTrue(str.contains("bodyBytes="))
    }

    @Test
    fun testRejectHttp() = runBlocking {
        try {
            transport.getJson("http://auth.openai.com/api/test")
            fail("Expected SecurityException on non-HTTPS")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    @Test
    fun testRejectUntrustedHost() = runBlocking {
        try {
            transport.getJson("https://malicious-site.com/api/test")
            fail("Expected SecurityException on unpinned authority")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    @Test
    fun testRejectNon443Port() = runBlocking {
        try {
            transport.getJson("https://auth.openai.com:8443/api/test")
            fail("Expected SecurityException on non-443 port")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    @Test
    fun testRejectUserInfo() = runBlocking {
        try {
            transport.getJson("https://user:pass@auth.openai.com/api/test")
            fail("Expected SecurityException on userinfo")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    @Test
    fun testRejectFragment() = runBlocking {
        try {
            transport.getJson("https://auth.openai.com/api/test#frag")
            fail("Expected SecurityException on fragment")
        } catch (_: SecurityException) {
            // Expected
        }
    }
}
