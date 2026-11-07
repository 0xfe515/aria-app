package com.ariateam.aria.cloud.auth

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

class ChatGptJwtVerifierTest {

    private lateinit var keyPair: KeyPair
    private val keyId = "test-key-id-001"
    private val issuer = ChatGptAuthConstants.ISSUER
    private val clientId = "oaiapp_test_client_123"
    private val nonce = "test_nonce_abcdef"
    private val subject = "usr_subject_789"
    private val currentEpochSec = 1790000000L

    private lateinit var fakeTransport: FakeJwtHttpTransport
    private lateinit var verifier: DefaultChatGptJwtVerifier

    @Before
    fun setUp() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        keyPair = kpg.generateKeyPair()

        val jwksJson = buildJwksJson(keyId, keyPair.public as RSAPublicKey)
        val discoveryJson = JSONObject().apply {
            put("issuer", issuer)
            put("jwks_uri", ChatGptAuthConstants.DEFAULT_JWKS_URI)
        }.toString()

        fakeTransport = FakeJwtHttpTransport(
            discoveryResponse = discoveryJson,
            jwksResponse = jwksJson,
        )

        verifier = DefaultChatGptJwtVerifier(fakeTransport)
    }

    @Test
    fun testValidIdTokenVerifiesSuccessfully() = runBlocking {
        val token = createSignedJwt(
            header = buildHeader(alg = "RS256", kid = keyId),
            payload = buildPayload(
                iss = issuer,
                aud = listOf(clientId),
                sub = subject,
                nonce = nonce,
                exp = currentEpochSec + 3600,
                iat = currentEpochSec,
                email = "user@example.com",
            ),
            privateKey = keyPair.private as RSAPrivateKey,
        )

        val claims = verifier.verifyIdToken(
            idToken = token,
            expectedClientId = clientId,
            expectedNonce = nonce,
            currentEpochSec = currentEpochSec,
        )

        assertEquals(subject, claims.sub)
        assertEquals(issuer, claims.iss)
        assertTrue(claims.aud.contains(clientId))
        assertEquals(nonce, claims.nonce)
        assertEquals("user@example.com", claims.email)
    }

    @Test
    fun testTamperedSignatureFailsClosed() = runBlocking {
        val token = createSignedJwt(
            header = buildHeader(alg = "RS256", kid = keyId),
            payload = buildPayload(
                iss = issuer,
                aud = listOf(clientId),
                sub = subject,
                nonce = nonce,
                exp = currentEpochSec + 3600,
                iat = currentEpochSec,
            ),
            privateKey = keyPair.private as RSAPrivateKey,
        )

        val parts = token.split(".")
        // Tamper with first signature byte
        val tamperedFirstChar = if (parts[2].startsWith("A")) "B" else "A"
        val tamperedSig = tamperedFirstChar + parts[2].substring(1)
        val tamperedToken = "${parts[0]}.${parts[1]}.$tamperedSig"

        try {
            verifier.verifyIdToken(
                idToken = tamperedToken,
                expectedClientId = clientId,
                expectedNonce = nonce,
                currentEpochSec = currentEpochSec,
            )
            fail("Expected TokenValidationException on tampered signature")
        } catch (e: ChatGptAuthException.TokenValidationException) {
            assertTrue(e.message?.contains("signature") == true)
        }
    }

    @Test
    fun testTamperedPayloadFailsClosed() = runBlocking {
        val token = createSignedJwt(
            header = buildHeader(alg = "RS256", kid = keyId),
            payload = buildPayload(
                iss = issuer,
                aud = listOf(clientId),
                sub = subject,
                nonce = nonce,
                exp = currentEpochSec + 3600,
                iat = currentEpochSec,
            ),
            privateKey = keyPair.private as RSAPrivateKey,
        )

        val parts = token.split(".")
        // Tamper with payload
        val tamperedPayloadJson = buildPayload(
            iss = issuer,
            aud = listOf(clientId),
            sub = "attacker_subject",
            nonce = nonce,
            exp = currentEpochSec + 3600,
            iat = currentEpochSec,
        )
        val tamperedPayload = ChatGptCrypto.encodeBase64Url(tamperedPayloadJson.toByteArray(StandardCharsets.UTF_8))
        val tamperedToken = "${parts[0]}.$tamperedPayload.${parts[2]}"

        try {
            verifier.verifyIdToken(
                idToken = tamperedToken,
                expectedClientId = clientId,
                expectedNonce = nonce,
                currentEpochSec = currentEpochSec,
            )
            fail("Expected TokenValidationException on tampered payload")
        } catch (e: ChatGptAuthException.TokenValidationException) {
            assertTrue(e.message?.contains("signature") == true)
        }
    }

    @Test
    fun testAlgNoneRejected() = runBlocking {
        val header = JSONObject().apply {
            put("alg", "none")
            put("kid", keyId)
        }.toString()
        val payload = buildPayload(
            iss = issuer,
            aud = listOf(clientId),
            sub = subject,
            nonce = nonce,
            exp = currentEpochSec + 3600,
            iat = currentEpochSec,
        )
        val headerB64 = ChatGptCrypto.encodeBase64Url(header.toByteArray(StandardCharsets.UTF_8))
        val payloadB64 = ChatGptCrypto.encodeBase64Url(payload.toByteArray(StandardCharsets.UTF_8))
        val token = "$headerB64.$payloadB64."

        try {
            verifier.verifyIdToken(
                idToken = token,
                expectedClientId = clientId,
                expectedNonce = nonce,
                currentEpochSec = currentEpochSec,
            )
            fail("Expected TokenValidationException on alg=none")
        } catch (e: ChatGptAuthException.TokenValidationException) {
            assertTrue(e.message?.contains("Unsupported or insecure JWT algorithm") == true)
        }
    }

    @Test
    fun testExpiredTokenRejected() = runBlocking {
        val token = createSignedJwt(
            header = buildHeader(alg = "RS256", kid = keyId),
            payload = buildPayload(
                iss = issuer,
                aud = listOf(clientId),
                sub = subject,
                nonce = nonce,
                exp = currentEpochSec - 3600, // Expired 1 hour ago
                iat = currentEpochSec - 7200,
            ),
            privateKey = keyPair.private as RSAPrivateKey,
        )

        try {
            verifier.verifyIdToken(
                idToken = token,
                expectedClientId = clientId,
                expectedNonce = nonce,
                currentEpochSec = currentEpochSec,
            )
            fail("Expected TokenValidationException on expired token")
        } catch (e: ChatGptAuthException.TokenValidationException) {
            assertTrue(e.message?.contains("expired") == true)
        }
    }

    @Test
    fun testAudienceMismatchRejected() = runBlocking {
        val token = createSignedJwt(
            header = buildHeader(alg = "RS256", kid = keyId),
            payload = buildPayload(
                iss = issuer,
                aud = listOf("other_client_id"),
                sub = subject,
                nonce = nonce,
                exp = currentEpochSec + 3600,
                iat = currentEpochSec,
            ),
            privateKey = keyPair.private as RSAPrivateKey,
        )

        try {
            verifier.verifyIdToken(
                idToken = token,
                expectedClientId = clientId,
                expectedNonce = nonce,
                currentEpochSec = currentEpochSec,
            )
            fail("Expected TokenValidationException on audience mismatch")
        } catch (e: ChatGptAuthException.TokenValidationException) {
            assertTrue(e.message?.contains("audience") == true)
        }
    }

    @Test
    fun testNonceMismatchRejected() = runBlocking {
        val token = createSignedJwt(
            header = buildHeader(alg = "RS256", kid = keyId),
            payload = buildPayload(
                iss = issuer,
                aud = listOf(clientId),
                sub = subject,
                nonce = "wrong_nonce",
                exp = currentEpochSec + 3600,
                iat = currentEpochSec,
            ),
            privateKey = keyPair.private as RSAPrivateKey,
        )

        try {
            verifier.verifyIdToken(
                idToken = token,
                expectedClientId = clientId,
                expectedNonce = nonce,
                currentEpochSec = currentEpochSec,
            )
            fail("Expected TokenValidationException on nonce mismatch")
        } catch (e: ChatGptAuthException.TokenValidationException) {
            assertTrue(e.message?.contains("nonce") == true)
        }
    }

    @Test
    fun testIssuerMismatchRejected() = runBlocking {
        val token = createSignedJwt(
            header = buildHeader(alg = "RS256", kid = keyId),
            payload = buildPayload(
                iss = "https://malicious-issuer.com",
                aud = listOf(clientId),
                sub = subject,
                nonce = nonce,
                exp = currentEpochSec + 3600,
                iat = currentEpochSec,
            ),
            privateKey = keyPair.private as RSAPrivateKey,
        )

        try {
            verifier.verifyIdToken(
                idToken = token,
                expectedClientId = clientId,
                expectedNonce = nonce,
                currentEpochSec = currentEpochSec,
            )
            fail("Expected TokenValidationException on issuer mismatch")
        } catch (e: ChatGptAuthException.TokenValidationException) {
            assertTrue(e.message?.contains("issuer") == true)
        }
    }

    // Helpers
    private fun buildHeader(alg: String, kid: String): String {
        return JSONObject().apply {
            put("alg", alg)
            put("kid", kid)
            put("typ", "JWT")
        }.toString()
    }

    private fun buildPayload(
        iss: String,
        aud: List<String>,
        sub: String,
        nonce: String,
        exp: Long,
        iat: Long,
        email: String? = null,
    ): String {
        return JSONObject().apply {
            put("iss", iss)
            val audArray = JSONArray()
            aud.forEach { audArray.put(it) }
            put("aud", audArray)
            put("sub", sub)
            put("nonce", nonce)
            put("exp", exp)
            put("iat", iat)
            if (email != null) put("email", email)
        }.toString()
    }

    private fun createSignedJwt(header: String, payload: String, privateKey: RSAPrivateKey): String {
        val headerB64 = ChatGptCrypto.encodeBase64Url(header.toByteArray(StandardCharsets.UTF_8))
        val payloadB64 = ChatGptCrypto.encodeBase64Url(payload.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerB64.$payloadB64".toByteArray(StandardCharsets.US_ASCII)

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(signingInput)
        val sigBytes = signer.sign()
        val sigB64 = ChatGptCrypto.encodeBase64Url(sigBytes)

        return "$headerB64.$payloadB64.$sigB64"
    }

    private fun buildJwksJson(kid: String, publicKey: RSAPublicKey): String {
        val nBytes = stripLeadingZero(publicKey.modulus.toByteArray())
        val eBytes = stripLeadingZero(publicKey.publicExponent.toByteArray())

        val keyObj = JSONObject().apply {
            put("kty", "RSA")
            put("use", "sig")
            put("alg", "RS256")
            put("kid", kid)
            put("n", ChatGptCrypto.encodeBase64Url(nBytes))
            put("e", ChatGptCrypto.encodeBase64Url(eBytes))
        }

        return JSONObject().apply {
            put("keys", JSONArray().apply { put(keyObj) })
        }.toString()
    }

    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        return if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    private class FakeJwtHttpTransport(
        val discoveryResponse: String,
        val jwksResponse: String,
    ) : ChatGptAuthHttpTransport {
        override suspend fun postForm(
            url: String,
            formParams: Map<String, String>,
            headers: Map<String, String>,
        ): AuthHttpResponse = AuthHttpResponse(200, "{}")

        override suspend fun getJson(
            url: String,
            headers: Map<String, String>,
        ): AuthHttpResponse {
            return if (url.contains("openid-configuration")) {
                AuthHttpResponse(200, discoveryResponse)
            } else if (url.contains("jwks.json")) {
                AuthHttpResponse(200, jwksResponse)
            } else {
                AuthHttpResponse(404, "Not Found")
            }
        }
    }
}
