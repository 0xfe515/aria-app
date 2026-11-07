package com.ariateam.aria.cloud.auth

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.ConcurrentHashMap

interface ChatGptJwtVerifier {
    suspend fun verifyIdToken(
        idToken: String,
        expectedClientId: String,
        expectedNonce: String?,
        currentEpochSec: Long,
    ): IdTokenClaims
}

class DefaultChatGptJwtVerifier(
    private val httpTransport: ChatGptAuthHttpTransport,
    private val clockToleranceSec: Long = ChatGptAuthConstants.CLOCK_TOLERANCE_SECONDS,
) : ChatGptJwtVerifier {

    private val cachedPublicKeys = ConcurrentHashMap<String, PublicKey>()
    private var discoveryJwksUri: String? = null

    override suspend fun verifyIdToken(
        idToken: String,
        expectedClientId: String,
        expectedNonce: String?,
        currentEpochSec: Long,
    ): IdTokenClaims {
        val parts = idToken.trim().split(".")
        if (parts.size != 3) {
            throw ChatGptAuthException.TokenValidationException("ID token does not have 3 parts (header.payload.signature)")
        }

        val headerJsonStr = String(ChatGptCrypto.decodeBase64Url(parts[0]), StandardCharsets.UTF_8)
        val headerJson = JSONObject(headerJsonStr)

        if (headerJson.has("crit")) {
            throw ChatGptAuthException.TokenValidationException("Critical (crit) JWT headers unsupported")
        }

        val alg = headerJson.optString("alg", "")
        if (alg != "RS256") {
            throw ChatGptAuthException.TokenValidationException("Unsupported or insecure JWT algorithm: '$alg'. RS256 required.")
        }

        val kid = headerJson.optString("kid", "")
        if (kid.isEmpty()) {
            throw ChatGptAuthException.TokenValidationException("ID token header missing required 'kid'")
        }

        var publicKey = cachedPublicKeys[kid]
        if (publicKey == null) {
            refreshJwks()
            publicKey = cachedPublicKeys[kid]
        }

        if (publicKey == null) {
            throw ChatGptAuthException.TokenValidationException("Unable to find matching public key for kid: $kid in trusted JWKS")
        }

        // Verify cryptographic signature: SHA256withRSA over "${parts[0]}.${parts[1]}"
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII)
        val signatureBytes = ChatGptCrypto.decodeBase64Url(parts[2])

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(signingInput)
        val signatureValid = try {
            verifier.verify(signatureBytes)
        } catch (t: Throwable) {
            throw ChatGptAuthException.TokenValidationException("Cryptographic signature check failed with error", t)
        }

        if (!signatureValid) {
            throw ChatGptAuthException.TokenValidationException("Cryptographic signature verification failed for ID token")
        }

        // Verify claims
        val payloadJsonStr = String(ChatGptCrypto.decodeBase64Url(parts[1]), StandardCharsets.UTF_8)
        val payloadJson = JSONObject(payloadJsonStr)

        val iss = payloadJson.optString("iss", "")
        if (iss != ChatGptAuthConstants.ISSUER) {
            throw ChatGptAuthException.TokenValidationException("ID token issuer '$iss' does not match pinned issuer '${ChatGptAuthConstants.ISSUER}'")
        }

        val audiences = mutableListOf<String>()
        val audVal = payloadJson.opt("aud")
        when (audVal) {
            is String -> audiences.add(audVal)
            is JSONArray -> {
                for (i in 0 until audVal.length()) {
                    audiences.add(audVal.getString(i))
                }
            }
            else -> throw ChatGptAuthException.TokenValidationException("Missing or invalid 'aud' claim in ID token")
        }

        if (!audiences.contains(expectedClientId)) {
            throw ChatGptAuthException.TokenValidationException("ID token audience $audiences does not contain expected client_id '$expectedClientId'")
        }

        val azp = if (payloadJson.has("azp")) payloadJson.optString("azp") else null
        if (azp != null && azp != expectedClientId) {
            throw ChatGptAuthException.TokenValidationException("ID token 'azp' claim '$azp' does not match client_id '$expectedClientId'")
        }
        if (audiences.size > 1 && azp != expectedClientId) {
            throw ChatGptAuthException.TokenValidationException("Multi-audience ID token requires matching 'azp'")
        }

        val sub = payloadJson.optString("sub", "")
        if (sub.isEmpty()) {
            throw ChatGptAuthException.TokenValidationException("ID token missing non-empty 'sub' claim")
        }

        val exp = payloadJson.optLong("exp", 0L)
        if (exp == 0L || (currentEpochSec - clockToleranceSec) > exp) {
            throw ChatGptAuthException.TokenValidationException("ID token has expired (exp=$exp, current=$currentEpochSec)")
        }

        val iat = payloadJson.optLong("iat", 0L)
        if (iat != 0L && (currentEpochSec + clockToleranceSec) < iat) {
            throw ChatGptAuthException.TokenValidationException("ID token issued in the future (iat=$iat, current=$currentEpochSec)")
        }

        val nbf = payloadJson.optLong("nbf", 0L)
        if (nbf != 0L && (currentEpochSec + clockToleranceSec) < nbf) {
            throw ChatGptAuthException.TokenValidationException("ID token not yet valid (nbf=$nbf, current=$currentEpochSec)")
        }

        if (expectedNonce != null) {
            val tokenNonce = payloadJson.optString("nonce", "")
            if (!ChatGptCrypto.timingSafeEquals(tokenNonce, expectedNonce)) {
                throw ChatGptAuthException.TokenValidationException("ID token nonce does not match session nonce")
            }
        }

        val email = if (payloadJson.has("email")) payloadJson.optString("email") else null
        val name = if (payloadJson.has("name")) payloadJson.optString("name") else null

        return IdTokenClaims(
            sub = sub,
            iss = iss,
            aud = audiences,
            azp = azp,
            exp = exp,
            iat = iat,
            nonce = payloadJson.optString("nonce", null),
            email = email,
            name = name,
        )
    }

    private suspend fun refreshJwks() {
        val jwksUri = discoveryJwksUri ?: run {
            val discoveryResponse = httpTransport.getJson(ChatGptAuthConstants.DISCOVERY_URL)
            if (!discoveryResponse.isSuccessful) {
                throw ChatGptAuthException.NetworkAuthException("Failed to fetch discovery config: HTTP ${discoveryResponse.statusCode}")
            }
            val discoveryObj = JSONObject(discoveryResponse.body)
            val iss = discoveryObj.optString("issuer", "")
            if (iss != ChatGptAuthConstants.ISSUER) {
                throw ChatGptAuthException.TokenValidationException("Discovery document issuer '$iss' does not match pinned issuer '${ChatGptAuthConstants.ISSUER}'")
            }
            val uri = discoveryObj.optString("jwks_uri", ChatGptAuthConstants.DEFAULT_JWKS_URI)
            validateDiscoveryEndpointUrl(uri)
            discoveryJwksUri = uri
            uri
        }

        validateDiscoveryEndpointUrl(jwksUri)
        val response = httpTransport.getJson(jwksUri)
        if (!response.isSuccessful) {
            throw ChatGptAuthException.NetworkAuthException("Failed to fetch JWKS keys: HTTP ${response.statusCode}")
        }

        val root = JSONObject(response.body)
        val keysArray = root.optJSONArray("keys") ?: return

        val keyFactory = KeyFactory.getInstance("RSA")
        for (i in 0 until keysArray.length()) {
            val keyObj = keysArray.getJSONObject(i)
            val kty = keyObj.optString("kty")
            val kid = keyObj.optString("kid")
            val use = keyObj.optString("use", "")
            val alg = keyObj.optString("alg", "")

            if (use.isNotEmpty() && use != "sig") continue
            if (alg.isNotEmpty() && alg != "RS256") continue

            if (kty == "RSA" && kid.isNotEmpty()) {
                val nStr = keyObj.optString("n")
                val eStr = keyObj.optString("e")
                if (nStr.isNotEmpty() && eStr.isNotEmpty()) {
                    val nBytes = ChatGptCrypto.decodeBase64Url(nStr)
                    val eBytes = ChatGptCrypto.decodeBase64Url(eStr)
                    val modulus = BigInteger(1, nBytes)
                    val exponent = BigInteger(1, eBytes)
                    val spec = RSAPublicKeySpec(modulus, exponent)
                    val publicKey = keyFactory.generatePublic(spec)
                    cachedPublicKeys[kid] = publicKey
                }
            }
        }
    }

    private fun validateDiscoveryEndpointUrl(urlString: String) {
        val parsed = URL(urlString)
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("HTTPS required for discovery endpoint: $urlString")
        }
        val host = parsed.host.lowercase()
        if (host != ChatGptAuthConstants.AUTHORITY_HOST) {
            throw SecurityException("Untrusted authority host '$host' in discovery. Pinned is '${ChatGptAuthConstants.AUTHORITY_HOST}'")
        }
        if (parsed.port != -1 && parsed.port != 443) {
            throw SecurityException("Non-standard HTTPS port rejected: ${parsed.port}")
        }
        if (parsed.userInfo != null) {
            throw SecurityException("UserInfo component forbidden in discovery URL")
        }
    }
}
