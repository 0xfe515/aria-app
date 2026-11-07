package com.ariateam.aria.cloud.chatgpt

import com.ariateam.aria.cloud.GeminiCancellationToken
import com.ariateam.aria.cloud.GeminiErrorKind
import com.ariateam.aria.cloud.GeminiResult
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class ChatGptClient(
    private val transport: ChatGptTransport = DefaultChatGptTransport(),
) {

    private val lock = Any()
    // Model catalog cache isolated per account by SHA-256 token fingerprint (non-logged)
    private val catalogCache = mutableMapOf<String, List<ChatGptModelInfo>>()

    private fun tokenFingerprint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(token.trim().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun clearCachedModels() {
        synchronized(lock) {
            catalogCache.clear()
        }
    }

    fun getCachedModels(accessToken: String): List<ChatGptModelInfo> {
        val fp = tokenFingerprint(accessToken)
        return synchronized(lock) {
            catalogCache[fp]?.toList() ?: emptyList()
        }
    }

    /**
     * Fetches the model catalog from GET https://api.openai.com/v1/models.
     * Filters for visibility == "list", preserves server ordering,
     * and selects the first visible model as default.
     * Caches models strictly bound to this account's token fingerprint.
     */
    fun fetchAvailableModels(
        accessToken: String,
        cancellationToken: GeminiCancellationToken? = null,
    ): ChatGptCatalogResult {
        if (cancellationToken?.isCancelled == true) {
            return ChatGptCatalogResult.Error(
                kind = GeminiErrorKind.CANCELLED,
                detail = "Request cancelled before model fetch",
                userMessageKorean = "질문이 취소되었어요.",
            )
        }

        val trimmedToken = accessToken.trim()
        if (trimmedToken.isEmpty()) {
            return ChatGptCatalogResult.Error(
                kind = GeminiErrorKind.AUTH_FAILED,
                detail = "Access token is empty",
                userMessageKorean = "ChatGPT 계정 인증 정보가 없어요. 설정에서 계정을 연결해 주세요.",
            )
        }

        val request = ChatGptHttpRequest(
            url = ChatGptConfig.MODELS_URL,
            method = "GET",
            headers = mapOf(
                ChatGptConfig.HEADER_AUTHORIZATION to "Bearer $trimmedToken",
                ChatGptConfig.HEADER_ACCEPT to ChatGptConfig.MIME_APPLICATION_JSON,
            ),
            connectTimeoutMs = ChatGptConfig.CONNECT_TIMEOUT_MS,
            readTimeoutMs = ChatGptConfig.READ_TIMEOUT_MS,
        )

        val response = transport.execute(request, cancellationToken)

        if (cancellationToken?.isCancelled == true || response.statusCode == 499) {
            return ChatGptCatalogResult.Error(
                kind = GeminiErrorKind.CANCELLED,
                detail = response.errorMessage ?: "Cancelled",
                userMessageKorean = "질문이 취소되었어요.",
            )
        }

        if (response.statusCode !in 200..299) {
            val err = ChatGptErrorMapper.mapHttpError(
                statusCode = response.statusCode,
                responseBody = response.body,
                errorMessage = response.errorMessage,
                accessToken = trimmedToken,
            )
            return ChatGptCatalogResult.Error(
                kind = err.kind,
                detail = err.detail,
                userMessageKorean = err.userMessageKorean,
            )
        }

        val parsedModels = parseModelCatalog(response.body)
        if (parsedModels.isEmpty()) {
            return ChatGptCatalogResult.Error(
                kind = GeminiErrorKind.MODEL_UNAVAILABLE,
                detail = "No models with visibility=list found in account catalog",
                userMessageKorean = "사용 가능한 ChatGPT 모델을 찾을 수 없어요. 계정 상태를 확인해 주세요.",
            )
        }

        val fp = tokenFingerprint(trimmedToken)
        synchronized(lock) {
            catalogCache[fp] = parsedModels
        }

        return ChatGptCatalogResult.Success(
            models = parsedModels,
            defaultModel = parsedModels.first(),
        )
    }

    /**
     * Shared interface for generating content via ChatGPT OAuth Responses API.
     * Compatible with CloudQuestionCoordinator.
     */
    fun generateContent(
        prompt: String,
        imageBase64: String? = null,
        accessToken: String,
        cancellationToken: GeminiCancellationToken? = null,
    ): GeminiResult {
        return generateContent(
            prompt = prompt,
            imageBase64 = imageBase64,
            accessToken = accessToken,
            model = null,
            cancellationToken = cancellationToken,
        )
    }

    /**
     * Generates content with explicit model selection (or falls back to catalog discovery).
     * Validates explicit model against current account's catalog; never reuses catalogs across different accounts.
     */
    fun generateContent(
        prompt: String,
        imageBase64: String? = null,
        accessToken: String,
        model: String? = null,
        cancellationToken: GeminiCancellationToken? = null,
    ): GeminiResult {
        if (cancellationToken?.isCancelled == true) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.CANCELLED,
                detail = "Request cancelled before execution",
                userMessageKorean = "질문이 취소되었어요.",
            )
        }

        val trimmedToken = accessToken.trim()
        if (trimmedToken.isEmpty()) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.AUTH_FAILED,
                detail = "ChatGPT access token is empty or not configured",
                userMessageKorean = "ChatGPT 계정 인증 정보가 없어요. 설정에서 계정을 연결해 주세요.",
            )
        }

        val fp = tokenFingerprint(trimmedToken)

        // Resolve model: explicit parameter -> account cached model -> fetch from account /models
        val targetModel: String = if (!model.isNullOrBlank()) {
            val candidate = model.trim()
            // Validate explicit model against current account catalog
            val availableModels = synchronized(lock) { catalogCache[fp] }
                ?: when (val catalog = fetchAvailableModels(trimmedToken, cancellationToken)) {
                    is ChatGptCatalogResult.Success -> catalog.models
                    is ChatGptCatalogResult.Error -> {
                        return GeminiResult.Error(
                            kind = catalog.kind,
                            detail = catalog.detail,
                            userMessageKorean = catalog.userMessageKorean,
                        )
                    }
                }
            val isValid = availableModels.any { it.slug.equals(candidate, ignoreCase = true) }
            if (!isValid) {
                return GeminiResult.Error(
                    kind = GeminiErrorKind.MODEL_UNAVAILABLE,
                    detail = "Requested model '$candidate' is not available in current account catalog",
                    userMessageKorean = "선택한 ChatGPT 모델을 사용할 수 없어요. 계정에서 지원되는 모델을 확인해 주세요.",
                )
            }
            candidate
        } else {
            val cached = synchronized(lock) { catalogCache[fp]?.firstOrNull()?.slug }
            if (cached != null) {
                cached
            } else {
                when (val catalog = fetchAvailableModels(trimmedToken, cancellationToken)) {
                    is ChatGptCatalogResult.Success -> catalog.defaultModel.slug
                    is ChatGptCatalogResult.Error -> {
                        return GeminiResult.Error(
                            kind = catalog.kind,
                            detail = catalog.detail,
                            userMessageKorean = catalog.userMessageKorean,
                        )
                    }
                }
            }
        }

        if (cancellationToken?.isCancelled == true) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.CANCELLED,
                detail = "Request cancelled before payload construction",
                userMessageKorean = "질문이 취소되었어요.",
            )
        }

        val safePrompt = prompt.trim().take(ChatGptConfig.MAX_PROMPT_CHARS).ifEmpty {
            if (imageBase64 != null) "앞에 무엇이 있는지 설명해 주세요." else "안녕하세요."
        }

        val requestJson = try {
            buildRequestBodyJson(
                prompt = safePrompt,
                imageBase64 = imageBase64,
                model = targetModel,
                instructions = ChatGptConfig.DEFAULT_INSTRUCTIONS,
            )
        } catch (e: Throwable) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.SERVER_ERROR,
                detail = "Failed to construct request JSON: ${e.message}",
                userMessageKorean = "요청 데이터를 준비하는 중 오류가 발생했어요.",
            )
        }

        val httpRequest = ChatGptHttpRequest(
            url = ChatGptConfig.RESPONSES_URL,
            method = "POST",
            headers = mapOf(
                ChatGptConfig.HEADER_AUTHORIZATION to "Bearer $trimmedToken",
                ChatGptConfig.HEADER_CONTENT_TYPE to ChatGptConfig.MIME_APPLICATION_JSON,
                ChatGptConfig.HEADER_ACCEPT to ChatGptConfig.MIME_EVENT_STREAM,
            ),
            bodyBytes = requestJson.toByteArray(Charsets.UTF_8),
            connectTimeoutMs = ChatGptConfig.CONNECT_TIMEOUT_MS,
            readTimeoutMs = ChatGptConfig.READ_TIMEOUT_MS,
        )

        val streamResponse = transport.executeStream(httpRequest, cancellationToken)

        if (cancellationToken?.isCancelled == true || streamResponse.statusCode == 499) {
            return GeminiResult.Error(
                kind = GeminiErrorKind.CANCELLED,
                detail = streamResponse.errorMessage ?: "Request was cancelled",
                userMessageKorean = "질문이 취소되었어요.",
            )
        }

        if (streamResponse.statusCode !in 200..299) {
            return ChatGptErrorMapper.mapHttpError(
                statusCode = streamResponse.statusCode,
                responseBody = streamResponse.rawErrorBody,
                errorMessage = streamResponse.errorMessage,
                accessToken = trimmedToken,
            )
        }

        return when (val outcome = streamResponse.outcome) {
            is SseParseOutcome.Completed -> {
                val fullText = outcome.text.trim()
                if (fullText.isEmpty()) {
                    GeminiResult.Error(
                        kind = GeminiErrorKind.EMPTY_RESPONSE,
                        detail = "Candidate content contained empty text",
                        userMessageKorean = "답변을 생성하지 못했어요. 다른 내용으로 다시 질문해 주세요.",
                    )
                } else {
                    GeminiResult.Success(fullText.take(ChatGptConfig.MAX_RESPONSE_CHARS))
                }
            }
            is SseParseOutcome.Failed -> {
                ChatGptErrorMapper.mapStreamFailure(
                    errorCode = outcome.code,
                    errorMessage = outcome.message,
                    param = outcome.param,
                    accessToken = trimmedToken,
                )
            }
            is SseParseOutcome.Incomplete -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.SERVER_ERROR,
                    detail = "Stream stopped incomplete: ${outcome.reason ?: "unknown reason"}",
                    userMessageKorean = "답변 생성이 중단되었어요. 다시 질문해 주세요.",
                )
            }
            is SseParseOutcome.Refused -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.EMPTY_RESPONSE,
                    detail = "Prompt blocked/refused: ${outcome.reason ?: "refusal"}",
                    userMessageKorean = "안전 기준 또는 정책에 따라 답변이 제한되었어요. 다른 내용으로 질문해 주세요.",
                )
            }
            is SseParseOutcome.Interrupted -> {
                if (cancellationToken?.isCancelled == true) {
                    GeminiResult.Error(
                        kind = GeminiErrorKind.CANCELLED,
                        detail = outcome.detail,
                        userMessageKorean = "질문이 취소되었어요.",
                    )
                } else {
                    GeminiResult.Error(
                        kind = GeminiErrorKind.SERVER_ERROR,
                        detail = outcome.detail,
                        userMessageKorean = "ChatGPT 응답이 중단되었거나 올바르지 않아요. 잠시 후 다시 질문해 주세요.",
                    )
                }
            }
            null -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.SERVER_ERROR,
                    detail = streamResponse.errorMessage ?: "Stream terminated without parse outcome",
                    userMessageKorean = "ChatGPT 응답을 수신하지 못했어요. 잠시 후 다시 시도해 주세요.",
                )
            }
        }
    }

    internal fun parseModelCatalog(jsonBody: String): List<ChatGptModelInfo> {
        return try {
            val root = JSONObject(jsonBody)
            val modelsArray = root.optJSONArray("models") ?: root.optJSONArray("data")
                ?: return emptyList()

            val result = mutableListOf<ChatGptModelInfo>()
            for (i in 0 until modelsArray.length()) {
                val mObj = modelsArray.optJSONObject(i) ?: continue
                val visibility = mObj.optString("visibility", null)
                if (visibility != null && visibility != "list") {
                    continue
                }
                val slug = mObj.optString("slug", null)?.ifEmpty { null }
                    ?: mObj.optString("id", null)?.ifEmpty { null }
                    ?: continue

                val displayName = mObj.optString("display_name", null)?.ifEmpty { null }
                    ?: mObj.optString("name", null)?.ifEmpty { null }
                    ?: slug

                result.add(
                    ChatGptModelInfo(
                        slug = slug,
                        displayName = displayName,
                        visibility = visibility ?: "list",
                    )
                )
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    internal fun buildRequestBodyJson(
        prompt: String,
        imageBase64: String?,
        model: String,
        instructions: String,
    ): String {
        val root = JSONObject()
        root.put("model", model)

        val inputArray = JSONArray()
        val userMsg = JSONObject()
        userMsg.put("role", "user")

        val contentArray = JSONArray()
        if (!imageBase64.isNullOrBlank()) {
            val imgPart = JSONObject()
            imgPart.put("type", "input_image")
            imgPart.put("image_url", "data:image/jpeg;base64,$imageBase64")
            contentArray.put(imgPart)
        }

        val textPart = JSONObject()
        textPart.put("type", "input_text")
        textPart.put("text", prompt)
        contentArray.put(textPart)

        userMsg.put("content", contentArray)
        inputArray.put(userMsg)
        root.put("input", inputArray)

        root.put("instructions", instructions)
        root.put("store", false)
        root.put("stream", true)

        return root.toString()
    }
}
