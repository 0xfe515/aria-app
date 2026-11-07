package com.ariateam.aria.cloud.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Cryptographic helpers for PKCE, state/nonce generation, Base64Url encoding, and timing-safe comparison.
 */
object ChatGptCrypto {

    private val secureRandom = SecureRandom()

    fun randomBase64Url(byteCount: Int = 32): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return encodeBase64Url(bytes)
    }

    fun generatePkcePair(): PkcePair {
        val verifier = randomBase64Url(32)
        val challenge = computeSha256Base64Url(verifier)
        return PkcePair(codeVerifier = verifier, codeChallenge = challenge)
    }

    fun computeSha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.US_ASCII))
        return encodeBase64Url(hash)
    }

    fun encodeBase64Url(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decodeBase64Url(input: String): ByteArray {
        var normalized = input.trim()
            .replace('-', '+')
            .replace('_', '/')
        val remainder = normalized.length % 4
        if (remainder == 2) {
            normalized += "=="
        } else if (remainder == 3) {
            normalized += "="
        }
        return Base64.getDecoder().decode(normalized)
    }

    fun timingSafeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        val aBytes = a.toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.toByteArray(StandardCharsets.UTF_8)
        if (aBytes.size != bBytes.size) return false

        var result = 0
        for (i in aBytes.indices) {
            result = result or (aBytes[i].toInt() xor bBytes[i].toInt())
        }
        return result == 0
    }

    fun generateHostId(): String {
        return "urn:uuid:${UUID.randomUUID()}"
    }

    data class PkcePair(
        val codeVerifier: String,
        val codeChallenge: String,
    ) {
        override fun toString(): String {
            return "PkcePair(codeVerifier=[REDACTED], codeChallenge=[REDACTED])"
        }
    }
}
