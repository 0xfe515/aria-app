package com.ariateam.aria.cloud.auth

/**
 * Public, safe authentication state for ChatGPT account and session management.
 * Designed to be safely observed by UI (Settings, MainScreen) and logged without leaking credentials.
 */
data class ChatGptAuthState(
    val sessionGeneration: Long = 1L,
    val status: ChatGptAuthStatus = ChatGptAuthStatus.UNAUTHENTICATED,
    val account: ChatGptAccountInfo? = null,
    val remoteRevocationConfirmed: Boolean? = null,
    val lastError: String? = null,
) {
    override fun toString(): String {
        return "ChatGptAuthState(generation=$sessionGeneration, status=$status, " +
            "account=${account?.email ?: account?.subject ?: "none"}, " +
            "revocationConfirmed=$remoteRevocationConfirmed, error=${lastError != null})"
    }
}

/**
 * High-level status of the ChatGPT authentication session.
 */
enum class ChatGptAuthStatus {
    UNAUTHENTICATED,
    AUTHORIZING,
    AUTHENTICATED,
    PLAN_USE_NOT_ENABLED,
    REFRESH_FAILED_RECONNECT,
}

/**
 * Safe public representation of an authorized ChatGPT account.
 * Never holds tokens or secrets.
 */
data class ChatGptAccountInfo(
    val subject: String,
    val email: String?,
    val name: String?,
    val clientId: String,
    val scopes: Set<String>,
    val hasPlanUsagePermission: Boolean,
) {
    override fun toString(): String {
        return "ChatGptAccountInfo(subject=$subject, email=$email, clientId=$clientId, " +
            "hasPlanUsage=$hasPlanUsagePermission, scopesCount=${scopes.size})"
    }
}

/**
 * Summary for the active account picker UI.
 */
data class ChatGptAccountSummary(
    val subject: String,
    val email: String?,
    val name: String?,
    val clientId: String,
    val isActive: Boolean,
    val hasPlanUsagePermission: Boolean,
) {
    override fun toString(): String {
        return "ChatGptAccountSummary(subject=$subject, email=$email, clientId=$clientId, active=$isActive)"
    }
}

/**
 * Internal storage representation of a ChatGPT client registration and session.
 * Encrypted at rest via Android Keystore AES-GCM; toString masks all sensitive credentials.
 */
data class ChatGptStoredCredentials(
    val clientId: String,
    val subject: String,
    val email: String? = null,
    val name: String? = null,
    val extAgentHostId: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresAtEpochSec: Long = 0L,
    val earliestRefreshAtEpochSec: Long? = null,
    val scopes: Set<String> = emptySet(),
    val savedAtIso: String = "",
) {
    val hasActiveTokens: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    val hasPlanUsagePermission: Boolean
        get() = scopes.contains(ChatGptAuthConstants.SCOPE_CHATGPT_TOKENS_USE_DIRECT) &&
            scopes.contains(ChatGptAuthConstants.SCOPE_RESOURCE_INVOKE)

    override fun toString(): String {
        return "ChatGptStoredCredentials(clientId=$clientId, subject=$subject, email=$email, " +
            "hasAccessToken=${!accessToken.isNullOrBlank()}, hasRefreshToken=${!refreshToken.isNullOrBlank()}, " +
            "expiresAtEpochSec=$expiresAtEpochSec, scopesCount=${scopes.size})"
    }
}

/**
 * Raw response parsed from OpenAI OAuth token endpoint.
 */
data class ChatGptTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val refreshToken: String?,
    val idToken: String?,
    val scope: String,
    val earliestRefreshAt: Long?,
) {
    override fun toString(): String {
        return "ChatGptTokenResponse(tokenType=$tokenType, expiresIn=$expiresIn, " +
            "hasRefreshToken=${refreshToken != null}, hasIdToken=${idToken != null})"
    }
}

/**
 * Validated OIDC ID token claims.
 */
data class IdTokenClaims(
    val sub: String,
    val iss: String,
    val aud: List<String>,
    val azp: String?,
    val exp: Long,
    val iat: Long,
    val nonce: String?,
    val email: String?,
    val name: String?,
)
