package com.ariateam.aria.cloud

import java.util.concurrent.atomic.AtomicBoolean

class GeminiClient(
    private val transport: GeminiTransport = DefaultGeminiTransport(),
) {
    fun generateContent(
        prompt: String,
        imageBase64: String? = null,
        apiKey: String,
        model: String = GeminiConfig.DEFAULT_MODEL,
        cancellationToken: GeminiCancellationToken? = null,
    ): GeminiResult {
        if (cancellationToken?.isCancelled == true) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.CANCELLED,
                detail = "Request cancelled before execution",
                userMessageKorean = "질문이 취소되었어요.",
            )
        }

        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.NO_KEY,
                detail = "Gemini API key is empty or not configured",
                userMessageKorean = "Gemini API 키가 설정되지 않았어요. 설정에서 API 키를 등록해 주세요.",
            )
        }

        val safePrompt = prompt.trim().take(GeminiConfig.MAX_PROMPT_CHARS).ifEmpty {
            if (imageBase64 != null) "앞에 무엇이 있는지 설명해 주세요." else "안녕하세요."
        }

        val requestJson = try {
            buildRequestBodyJson(safePrompt, imageBase64)
        } catch (e: Throwable) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.SERVER_ERROR,
                detail = sanitizeDetail("Failed to construct request JSON: ${e.message}", trimmedKey),
                userMessageKorean = "요청 데이터를 준비하는 중 오류가 발생했어요.",
            )
        }

        val requestUrl = "${GeminiConfig.BASE_URL}$model:generateContent"
        val httpRequest = GeminiHttpRequest(
            url = requestUrl,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json; charset=UTF-8",
                GeminiConfig.HEADER_API_KEY to trimmedKey,
            ),
            bodyBytes = requestJson.toByteArray(Charsets.UTF_8),
            connectTimeoutMs = GeminiConfig.CONNECT_TIMEOUT_MS,
            readTimeoutMs = GeminiConfig.READ_TIMEOUT_MS,
        )

        val response = transport.execute(httpRequest, cancellationToken)

        if (cancellationToken?.isCancelled == true || response.statusCode == 499) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.CANCELLED,
                detail = sanitizeDetail(response.errorMessage ?: "Request was cancelled", trimmedKey),
                userMessageKorean = "질문이 취소되었어요.",
            )
        }

        return when (response.statusCode) {
            in 200..299 -> parseSuccessResponse(response.body)
            400, 401, 403 -> {
                val detail = extractErrorDetail(response.body) ?: (response.errorMessage ?: "HTTP ${response.statusCode}")
                GeminiResult.Error(
                    kind = GeminiErrorKind.AUTH_FAILED,
                    detail = sanitizeDetail(detail, trimmedKey),
                    userMessageKorean = "Gemini API 키가 올바르지 않거나 권한이 없어요. 설정에서 확인해 주세요.",
                )
            }
            404 -> {
                val detail = extractErrorDetail(response.body) ?: (response.errorMessage ?: "HTTP 404 Not Found")
                GeminiResult.Error(
                    kind = GeminiErrorKind.MODEL_UNAVAILABLE,
                    detail = sanitizeDetail(detail, trimmedKey),
                    userMessageKorean = "요청한 Gemini 모델을 사용할 수 없거나 지원이 종료되었어요.",
                )
            }
            429 -> {
                val detail = extractErrorDetail(response.body) ?: (response.errorMessage ?: "HTTP 429")
                GeminiResult.Error(
                    kind = GeminiErrorKind.RATE_LIMIT,
                    detail = sanitizeDetail(detail, trimmedKey),
                    userMessageKorean = "Gemini 사용 한도를 초과했어요. 잠시 후 다시 시도해 주세요.",
                )
            }
            408, 504 -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.TIMEOUT,
                    detail = sanitizeDetail(response.errorMessage ?: "HTTP ${response.statusCode} Timeout", trimmedKey),
                    userMessageKorean = "Gemini 응답 시간이 초과되었어요. 잠시 후 다시 질문해 주세요.",
                )
            }
            503 -> {
                if (response.errorMessage?.contains("Network unavailable", ignoreCase = true) == true) {
                    GeminiResult.Error(
                        kind = GeminiErrorKind.NETWORK_UNAVAILABLE,
                        detail = sanitizeDetail(response.errorMessage, trimmedKey),
                        userMessageKorean = "네트워크에 연결할 수 없어요. 인터넷 연결을 확인해 주세요.",
                    )
                } else {
                    GeminiResult.Error(
                        kind = GeminiErrorKind.SERVER_ERROR,
                        detail = sanitizeDetail(extractErrorDetail(response.body) ?: (response.errorMessage ?: "HTTP 503"), trimmedKey),
                        userMessageKorean = "Gemini 서비스에 일시적인 문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
                    )
                }
            }
            else -> {
                val detail = extractErrorDetail(response.body) ?: (response.errorMessage ?: "HTTP ${response.statusCode}")
                GeminiResult.Error(
                    kind = GeminiErrorKind.SERVER_ERROR,
                    detail = sanitizeDetail(detail, trimmedKey),
                    userMessageKorean = "Gemini 서비스에 일시적인 문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
                )
            }
        }
    }

    fun generateContent(
        prompt: String,
        imageBase64: String? = null,
        apiKey: String,
        model: String = GeminiConfig.DEFAULT_MODEL,
        cancelFlag: AtomicBoolean,
    ): GeminiResult {
        val token = GeminiCancellationToken()
        if (cancelFlag.get()) {
            token.cancel()
        }
        return generateContent(prompt, imageBase64, apiKey, model, token)
    }

    internal fun buildRequestBodyJson(prompt: String, imageBase64: String?): String {
        val escapedPrompt = escapeJsonString(prompt)
        val escapedSysInstruction = escapeJsonString(GeminiConfig.SYSTEM_INSTRUCTION)

        val partsJson = if (!imageBase64.isNullOrBlank()) {
            """[{"inline_data":{"mime_type":"image/jpeg","data":"$imageBase64"}},{"text":"$escapedPrompt"}]"""
        } else {
            """[{"text":"$escapedPrompt"}]"""
        }

        return """{"contents":[{"role":"user","parts":$partsJson}],"systemInstruction":{"parts":[{"text":"$escapedSysInstruction"}]},"generationConfig":{"temperature":0.4,"maxOutputTokens":300}}"""
    }

    internal fun parseSuccessResponse(responseBody: String): GeminiResult {
        if (responseBody.isBlank()) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.EMPTY_RESPONSE,
                detail = "Empty response body from Gemini API",
                userMessageKorean = "답변을 생성하지 못했어요. 다른 내용으로 다시 질문해 주세요.",
            )
        }

        return try {
            val blockReason = extractStringField(responseBody, "blockReason")
            if (blockReason != null) {
                return GeminiResult.Error(
                    kind = GeminiErrorKind.EMPTY_RESPONSE,
                    detail = "Prompt blocked with reason: $blockReason",
                    userMessageKorean = "안전 기준에 따라 답변이 제한되었어요. 다른 내용으로 질문해 주세요.",
                )
            }

            val textParts = extractTextParts(responseBody)
            if (textParts.isEmpty()) {
                return GeminiResult.Error(
                    kind = GeminiErrorKind.EMPTY_RESPONSE,
                    detail = "No text parts found in response",
                    userMessageKorean = "답변을 생성하지 못했어요. 다른 내용으로 다시 질문해 주세요.",
                )
            }

            val fullText = textParts.joinToString(separator = "").trim()
            if (fullText.isEmpty()) {
                GeminiResult.Error(
                    kind = GeminiErrorKind.EMPTY_RESPONSE,
                    detail = "Candidate content contained empty text",
                    userMessageKorean = "답변을 생성하지 못했어요. 다른 내용으로 다시 질문해 주세요.",
                )
            } else {
                GeminiResult.Success(fullText.take(GeminiConfig.MAX_RESPONSE_CHARS))
            }
        } catch (e: Throwable) {
            GeminiResult.Error(
                kind = GeminiErrorKind.SERVER_ERROR,
                detail = sanitizeDetail("Response parsing error: ${e.message}"),
                userMessageKorean = "Gemini 응답을 해석하지 못했어요.",
            )
        }
    }

    internal fun extractErrorDetail(responseBody: String): String? {
        if (responseBody.isBlank()) return null
        return try {
            val msg = extractStringField(responseBody, "message")
            val status = extractStringField(responseBody, "status")
            when {
                !msg.isNullOrBlank() && !status.isNullOrBlank() -> "$status: $msg"
                !msg.isNullOrBlank() -> msg
                !status.isNullOrBlank() -> status
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        internal fun sanitizeDetail(detail: String?, apiKey: String = ""): String {
            if (detail.isNullOrBlank()) return ""
            var sanitized = detail
            if (apiKey.isNotBlank()) {
                sanitized = sanitized.replace(apiKey, "[REDACTED_KEY]")
            }
            sanitized = sanitized.replace(Regex("""AIzaSy[A-Za-z0-9_-]{20,}"""), "[REDACTED_KEY]")
            return sanitized
        }

        fun escapeJsonString(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (c < ' ') {
                            val hex = Integer.toHexString(c.code).padStart(4, '0')
                            sb.append("\\u").append(hex)
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            return sb.toString()
        }

        fun unescapeJsonString(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val next = s[i + 1]) {
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        '/' -> { sb.append('/'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'u' -> {
                            if (i + 5 < s.length) {
                                val hex = s.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    i += 6
                                    continue
                                }
                            }
                            sb.append(c)
                            i++
                        }
                        else -> { sb.append(next); i += 2 }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun extractTextParts(json: String): List<String> {
            val results = mutableListOf<String>()
            var index = 0
            while (index < json.length) {
                val textKey = json.indexOf("\"text\"", index)
                if (textKey == -1) break
                val colon = json.indexOf(':', textKey + 6)
                if (colon == -1) break
                val startQuote = json.indexOf('"', colon + 1)
                if (startQuote == -1) break

                var pos = startQuote + 1
                var endQuote = -1
                while (pos < json.length) {
                    val c = json[pos]
                    if (c == '\\') {
                        pos += 2
                    } else if (c == '"') {
                        endQuote = pos
                        break
                    } else {
                        pos++
                    }
                }
                if (endQuote != -1) {
                    results.add(unescapeJsonString(json.substring(startQuote + 1, endQuote)))
                    index = endQuote + 1
                } else {
                    break
                }
            }
            return results
        }

        private fun extractStringField(json: String, fieldName: String): String? {
            val key = "\"$fieldName\""
            val keyPos = json.indexOf(key)
            if (keyPos == -1) return null
            val colon = json.indexOf(':', keyPos + key.length)
            if (colon == -1) return null
            val startQuote = json.indexOf('"', colon + 1)
            if (startQuote == -1) return null

            var pos = startQuote + 1
            var endQuote = -1
            while (pos < json.length) {
                val c = json[pos]
                if (c == '\\') {
                    pos += 2
                } else if (c == '"') {
                    endQuote = pos
                    break
                } else {
                    pos++
                }
            }
            return if (endQuote != -1) {
                unescapeJsonString(json.substring(startQuote + 1, endQuote))
            } else {
                null
            }
        }
    }
}
