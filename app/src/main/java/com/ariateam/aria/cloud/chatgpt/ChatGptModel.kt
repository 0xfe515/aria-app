package com.ariateam.aria.cloud.chatgpt

import com.ariateam.aria.cloud.GeminiErrorKind

data class ChatGptModelInfo(
    val slug: String,
    val displayName: String,
    val visibility: String? = "list",
)

sealed interface ChatGptCatalogResult {
    data class Success(
        val models: List<ChatGptModelInfo>,
        val defaultModel: ChatGptModelInfo,
    ) : ChatGptCatalogResult

    data class Error(
        val kind: GeminiErrorKind,
        val detail: String,
        val userMessageKorean: String,
    ) : ChatGptCatalogResult
}

data class ChatGptHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val bodyBytes: ByteArray = ByteArray(0),
    val connectTimeoutMs: Int = ChatGptConfig.CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = ChatGptConfig.READ_TIMEOUT_MS,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChatGptHttpRequest
        if (url != other.url) return false
        if (method != other.method) return false
        if (headers != other.headers) return false
        if (!bodyBytes.contentEquals(other.bodyBytes)) return false
        if (connectTimeoutMs != other.connectTimeoutMs) return false
        if (readTimeoutMs != other.readTimeoutMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        result = 31 * result + connectTimeoutMs
        result = 31 * result + readTimeoutMs
        return result
    }

    override fun toString(): String {
        val safeHeaders = headers.mapValues { (k, v) ->
            if (k.equals(ChatGptConfig.HEADER_AUTHORIZATION, ignoreCase = true) ||
                k.contains("token", ignoreCase = true) ||
                k.contains("key", ignoreCase = true)
            ) {
                "[REDACTED_TOKEN]"
            } else {
                v
            }
        }
        return "ChatGptHttpRequest(url=$url, method=$method, headers=$safeHeaders, bodyBytesLength=${bodyBytes.size})"
    }
}

data class ChatGptHttpResponse(
    val statusCode: Int,
    val body: String = "",
    val errorMessage: String? = null,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    override fun toString(): String {
        val safeErrorMessage = errorMessage?.let { sanitizeTokens(it) }
        return "ChatGptHttpResponse(statusCode=$statusCode, bodyLength=${body.length}, errorMessage=$safeErrorMessage)"
    }

    companion object {
        fun sanitizeTokens(input: String): String {
            if (input.isBlank()) return ""
            return input
                .replace(Regex("""Bearer\s+[A-Za-z0-9._~+/-]+=*""", RegexOption.IGNORE_CASE), "Bearer [REDACTED_TOKEN]")
                .replace(Regex("""(ey[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})"""), "[REDACTED_JWT]")
                .replace(Regex("""(sess-[A-Za-z0-9_-]{20,})"""), "[REDACTED_SESSION]")
        }
    }
}
