package com.ariateam.aria.cloud.auth

/**
 * Constants for OpenAI Sign in with ChatGPT (SIWC) protocol and local storage.
 * Follows official developers.openai.com/siwc specifications.
 */
object ChatGptAuthConstants {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORITY_HOST = "auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val AGENT_NAME_HINT = "Aria"

    const val SCOPE_OPENID = "openid"
    const val SCOPE_PROFILE = "profile"
    const val SCOPE_EMAIL = "email"
    const val SCOPE_OFFLINE_ACCESS = "offline_access"
    const val SCOPE_RESOURCE_INVOKE = "resource.invoke"
    const val SCOPE_CHATGPT_TOKENS_USE_DIRECT = "chatgpt.tokens.use.direct"

    val REQUIRED_PLAN_SCOPES = setOf(
        SCOPE_RESOURCE_INVOKE,
        SCOPE_CHATGPT_TOKENS_USE_DIRECT,
    )

    const val DEFAULT_REQUESTED_SCOPES =
        "$SCOPE_OPENID $SCOPE_PROFILE $SCOPE_EMAIL $SCOPE_OFFLINE_ACCESS $SCOPE_RESOURCE_INVOKE $SCOPE_CHATGPT_TOKENS_USE_DIRECT"

    const val DEFAULT_AUTHORIZE_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
    const val DEFAULT_TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
    const val DEFAULT_JWKS_URI = "https://auth.openai.com/.well-known/jwks.json"
    const val DEFAULT_REVOCATION_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/revoke"
    const val DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"

    const val CALLBACK_PATH = "/auth/callback"
    const val LOOPBACK_HOST = "127.0.0.1"

    const val PREFS_NAME = "aria_chatgpt_auth_prefs"
    const val KEYSTORE_ALIAS = "aria_chatgpt_auth_credential_key_v1"

    // Clock tolerance in seconds for JWT exp/iat validation
    const val CLOCK_TOLERANCE_SECONDS = 60L

    // Token refresh window in seconds before expiry (5 minutes)
    const val REFRESH_WINDOW_SECONDS = 300L
}
