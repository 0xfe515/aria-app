package com.ariateam.aria.cloud.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ChatGptCryptoTest {

    @Test
    fun testRandomBase64Url() {
        val rand1 = ChatGptCrypto.randomBase64Url(32)
        val rand2 = ChatGptCrypto.randomBase64Url(32)

        assertFalse(rand1.contains("+"))
        assertFalse(rand1.contains("/"))
        assertFalse(rand1.contains("="))
        assertNotEquals(rand1, rand2)
    }

    @Test
    fun testPkceGeneration() {
        val pkce = ChatGptCrypto.generatePkcePair()
        assertTrue(pkce.codeVerifier.isNotEmpty())
        assertTrue(pkce.codeChallenge.isNotEmpty())
        assertNotEquals(pkce.codeVerifier, pkce.codeChallenge)

        // Known RFC 7636 Appendix B test vector:
        // verifier: dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
        // challenge: E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
        val testVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val actualChallenge = ChatGptCrypto.computeSha256Base64Url(testVerifier)
        assertEquals(expectedChallenge, actualChallenge)
    }

    @Test
    fun testBase64UrlRoundTrip() {
        val original = "Hello OpenAI ChatGPT 2026! Special chars: ?&=/~+_-".toByteArray(StandardCharsets.UTF_8)
        val encoded = ChatGptCrypto.encodeBase64Url(original)
        assertFalse(encoded.contains("="))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))

        val decoded = ChatGptCrypto.decodeBase64Url(encoded)
        assertEquals(String(original, StandardCharsets.UTF_8), String(decoded, StandardCharsets.UTF_8))
    }

    @Test
    fun testTimingSafeEquals() {
        assertTrue(ChatGptCrypto.timingSafeEquals("test_state_12345", "test_state_12345"))
        assertFalse(ChatGptCrypto.timingSafeEquals("test_state_12345", "test_state_12346"))
        assertFalse(ChatGptCrypto.timingSafeEquals("test_state_12345", "short"))
        assertFalse(ChatGptCrypto.timingSafeEquals("test_state_12345", null))
        assertFalse(ChatGptCrypto.timingSafeEquals(null, "test_state_12345"))
        assertTrue(ChatGptCrypto.timingSafeEquals(null, null))
    }

    @Test
    fun testHostIdFormat() {
        val hostId = ChatGptCrypto.generateHostId()
        assertTrue(hostId.startsWith("urn:uuid:"))
        assertEquals(45, hostId.length) // "urn:uuid:" (9) + 36 uuid chars
    }

    @Test
    fun testPkceRedactionInToString() {
        val pkce = ChatGptCrypto.generatePkcePair()
        val str = pkce.toString()
        assertTrue(str.contains("[REDACTED]"))
        assertFalse(str.contains(pkce.codeVerifier))
        assertFalse(str.contains(pkce.codeChallenge))
    }
}
