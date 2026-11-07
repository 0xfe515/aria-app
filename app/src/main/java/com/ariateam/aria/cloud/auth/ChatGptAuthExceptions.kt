package com.ariateam.aria.cloud.auth

/**
 * Sanitized exceptions for ChatGPT authentication operations.
 * Designed to never expose access tokens, refresh tokens, or client credentials in messages or stack traces.
 */
sealed class ChatGptAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class PlanUsageNotEnabledException(
        message: String = "ChatGPT plan usage permission is not enabled. Required scopes: resource.invoke, chatgpt.tokens.use.direct",
    ) : ChatGptAuthException(message)

    class UnusableRefreshTokenException(
        message: String,
        val errorCode: String? = null,
    ) : ChatGptAuthException(message)

    class TokenValidationException(
        message: String,
        cause: Throwable? = null,
    ) : ChatGptAuthException(message, cause)

    class AuthenticationFailedException(
        message: String,
        val errorCode: String? = null,
    ) : ChatGptAuthException(message)

    class AuthenticationCancelledException(
        message: String = "Authentication was cancelled by user",
    ) : ChatGptAuthException(message)

    class NetworkAuthException(
        message: String,
        cause: Throwable? = null,
    ) : ChatGptAuthException(message, cause)
}
