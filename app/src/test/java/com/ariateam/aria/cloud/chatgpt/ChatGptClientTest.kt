package com.ariateam.aria.cloud.chatgpt

import com.ariateam.aria.cloud.GeminiCancellationToken
import com.ariateam.aria.cloud.GeminiErrorKind
import com.ariateam.aria.cloud.GeminiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class ChatGptClientTest {

    private class FakeChatGptTransport(
        var executeStatusCode: Int = 200,
        var executeBody: String = """{"models":[{"slug":"gpt-6.1-sol","display_name":"ChatGPT 6.1 Sol","visibility":"list"},{"slug":"custom-explicit-model","display_name":"Custom Model","visibility":"list"}]}""",
        var executeErrorMessage: String? = null,
        var executeHandler: ((ChatGptHttpRequest) -> ChatGptHttpResponse)? = null,
        var streamStatusCode: Int = 200,
        var streamOutcome: SseParseOutcome? = null,
        var streamRawErrorBody: String? = null,
        var streamErrorMessage: String? = null,
    ) : ChatGptTransport {

        var capturedExecuteRequest: ChatGptHttpRequest? = null
        var capturedStreamRequest: ChatGptHttpRequest? = null
        val executeRequests = mutableListOf<ChatGptHttpRequest>()

        override fun execute(
            request: ChatGptHttpRequest,
            cancellationToken: GeminiCancellationToken?,
        ): ChatGptHttpResponse {
            capturedExecuteRequest = request
            executeRequests.add(request)
            if (cancellationToken?.isCancelled == true) {
                return ChatGptHttpResponse(499, "", "Cancelled")
            }
            if (executeHandler != null) {
                return executeHandler!!.invoke(request)
            }
            return ChatGptHttpResponse(executeStatusCode, executeBody, executeErrorMessage)
        }

        override fun executeStream(
            request: ChatGptHttpRequest,
            cancellationToken: GeminiCancellationToken?,
            onDelta: ((String) -> Unit)?,
        ): ChatGptStreamResponse {
            capturedStreamRequest = request
            if (cancellationToken?.isCancelled == true) {
                return ChatGptStreamResponse(499, SseParseOutcome.Interrupted("Cancelled"), "Cancelled")
            }
            return ChatGptStreamResponse(
                statusCode = streamStatusCode,
                outcome = streamOutcome,
                rawErrorBody = streamRawErrorBody,
                errorMessage = streamErrorMessage,
            )
        }
    }

    @Test
    fun textQuestionBuildsCorrectPayloadAndHeaders() {
        val fakeTransport = FakeChatGptTransport(
            streamStatusCode = 200,
            streamOutcome = SseParseOutcome.Completed("오늘 날씨는 맑습니다."),
        )

        val client = ChatGptClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "오늘 날씨 어때?",
            imageBase64 = null,
            accessToken = "test_access_token_12345",
            model = "gpt-6.1-sol",
        )

        assertTrue(result is GeminiResult.Success)
        assertEquals("오늘 날씨는 맑습니다.", (result as GeminiResult.Success).text)

        val req = fakeTransport.capturedStreamRequest
        assertNotNull(req)
        assertEquals(ChatGptConfig.RESPONSES_URL, req!!.url)
        assertEquals("POST", req.method)
        assertEquals("Bearer test_access_token_12345", req.headers[ChatGptConfig.HEADER_AUTHORIZATION])
        assertEquals(ChatGptConfig.MIME_APPLICATION_JSON, req.headers[ChatGptConfig.HEADER_CONTENT_TYPE])
        assertEquals(ChatGptConfig.MIME_EVENT_STREAM, req.headers[ChatGptConfig.HEADER_ACCEPT])

        val body = String(req.bodyBytes, Charsets.UTF_8)
        assertTrue(body.contains("\"model\":\"gpt-6.1-sol\""))
        assertTrue(body.contains("\"store\":false"))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"type\":\"input_text\""))
        assertTrue(body.contains("오늘 날씨 어때?"))

        // Korean instructions should be general and spoken-friendly
        assertTrue(body.contains("Aria 음성 비서로서"))
        assertFalse(body.contains("보행 보조"))

        // Must omit unsupported fields
        assertFalse(body.contains("temperature"))
        assertFalse(body.contains("max_output_tokens"))
        assertFalse(body.contains("previous_response_id"))
    }

    @Test
    fun photoQuestionBuildsInputImageAndInputText() {
        val fakeTransport = FakeChatGptTransport(
            streamStatusCode = 200,
            streamOutcome = SseParseOutcome.Completed("우유 팩입니다."),
        )

        val client = ChatGptClient(transport = fakeTransport)
        val fakePhotoBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val result = client.generateContent(
            prompt = "이 물건이 무엇인가요?",
            imageBase64 = fakePhotoBase64,
            accessToken = "test_access_token_12345",
            model = "gpt-6.1-sol",
        )

        assertTrue(result is GeminiResult.Success)
        assertEquals("우유 팩입니다.", (result as GeminiResult.Success).text)

        val req = fakeTransport.capturedStreamRequest
        assertNotNull(req)
        val body = String(req!!.bodyBytes, Charsets.UTF_8)
        assertTrue(body.contains("\"type\":\"input_image\""))
        assertTrue(body.contains("data:image/jpeg;base64,$fakePhotoBase64"))
        assertTrue(body.contains("\"type\":\"input_text\""))
        assertTrue(body.contains("이 물건이 무엇인가요?"))
    }

    @Test
    fun modelCatalogFetchAndFirstVisibleSelection() {
        val catalogJson = """
            {
              "object": "list",
              "models": [
                {
                  "slug": "hidden-internal-model",
                  "display_name": "Hidden Model",
                  "visibility": "hide"
                },
                {
                  "slug": "gpt-6.1-sol",
                  "display_name": "ChatGPT 6.1 Sol",
                  "visibility": "list"
                },
                {
                  "slug": "gpt-5-general",
                  "display_name": "ChatGPT 5 General",
                  "visibility": "list"
                }
              ]
            }
        """.trimIndent()

        val fakeTransport = FakeChatGptTransport(
            executeStatusCode = 200,
            executeBody = catalogJson,
        )

        val client = ChatGptClient(transport = fakeTransport)
        val catalogResult = client.fetchAvailableModels("test_token_abc")

        assertTrue(catalogResult is ChatGptCatalogResult.Success)
        val success = catalogResult as ChatGptCatalogResult.Success
        assertEquals(2, success.models.size)
        assertEquals("gpt-6.1-sol", success.defaultModel.slug)
        assertEquals("ChatGPT 6.1 Sol", success.defaultModel.displayName)
    }

    @Test
    fun modelCatalogIsolatedBetweenTokenAAndTokenB() {
        val catalogA = """{"models":[{"slug":"model-a","display_name":"Model A","visibility":"list"}]}"""
        val catalogB = """{"models":[{"slug":"model-b","display_name":"Model B","visibility":"list"}]}"""

        val fakeTransport = FakeChatGptTransport(
            executeHandler = { req ->
                val authHeader = req.headers["Authorization"].orEmpty()
                if (authHeader.contains("token-a")) {
                    ChatGptHttpResponse(200, catalogA)
                } else if (authHeader.contains("token-b")) {
                    ChatGptHttpResponse(200, catalogB)
                } else {
                    ChatGptHttpResponse(401, "", "Unauthorized")
                }
            },
            streamStatusCode = 200,
            streamOutcome = SseParseOutcome.Completed("응답 완료"),
        )

        val client = ChatGptClient(transport = fakeTransport)

        // Query with token A
        val resultA = client.generateContent(
            prompt = "질문 A",
            accessToken = "token-a",
            model = null,
        )
        assertTrue(resultA is GeminiResult.Success)
        val reqA = fakeTransport.capturedStreamRequest
        assertNotNull(reqA)
        val bodyA = String(reqA!!.bodyBytes, Charsets.UTF_8)
        assertTrue(bodyA.contains("\"model\":\"model-a\""))

        // Query with token B - MUST NOT reuse catalog from token A!
        val resultB = client.generateContent(
            prompt = "질문 B",
            accessToken = "token-b",
            model = null,
        )
        assertTrue(resultB is GeminiResult.Success)
        val reqB = fakeTransport.capturedStreamRequest
        assertNotNull(reqB)
        val bodyB = String(reqB!!.bodyBytes, Charsets.UTF_8)
        assertTrue(bodyB.contains("\"model\":\"model-b\""))
        assertFalse(bodyB.contains("\"model\":\"model-a\""))

        // Exactly two GET /v1/models requests occurred (one for token A, one for token B)
        assertEquals(2, fakeTransport.executeRequests.size)
    }

    @Test
    fun explicitModelMustBeValidatedAgainstAccountCatalog() {
        val catalogJson = """{"models":[{"slug":"model-valid-1","display_name":"Valid 1","visibility":"list"}]}"""
        val fakeTransport = FakeChatGptTransport(
            executeStatusCode = 200,
            executeBody = catalogJson,
            streamStatusCode = 200,
            streamOutcome = SseParseOutcome.Completed("유효 모델 응답"),
        )

        val client = ChatGptClient(transport = fakeTransport)

        // Request with model NOT in catalog -> MODEL_UNAVAILABLE
        val invalidResult = client.generateContent(
            prompt = "질문",
            accessToken = "token-xyz",
            model = "nonexistent-model",
        )
        assertTrue(invalidResult is GeminiResult.Error)
        val error = invalidResult as GeminiResult.Error
        assertEquals(GeminiErrorKind.MODEL_UNAVAILABLE, error.kind)
        assertTrue(error.userMessageKorean.contains("지원되는 모델을 확인해 주세요"))

        // Request with model IN catalog -> Success
        val validResult = client.generateContent(
            prompt = "질문",
            accessToken = "token-xyz",
            model = "model-valid-1",
        )
        assertTrue(validResult is GeminiResult.Success)
    }

    @Test
    fun emptyModelCatalogReturnsModelUnavailableWithoutInventingFallback() {
        val emptyCatalogJson = """
            {
              "models": [
                {
                  "slug": "internal-only",
                  "display_name": "Internal",
                  "visibility": "private"
                }
              ]
            }
        """.trimIndent()

        val fakeTransport = FakeChatGptTransport(
            executeStatusCode = 200,
            executeBody = emptyCatalogJson,
        )

        val client = ChatGptClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "질문",
            accessToken = "test_token",
            model = null,
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.MODEL_UNAVAILABLE, error.kind)
        assertTrue(error.userMessageKorean.contains("사용 가능한 ChatGPT 모델을 찾을 수 없어요"))
    }

    @Test
    fun stopsPromptlyAtResponseCompletedWithoutWaitingForEof() {
        val rawSse = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"완료된 "}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"답변입니다."}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed"}}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"후속 무시되어야 하는 텍스트"}

        """.trimIndent()

        val parser = ChatGptSseParser()
        val reader = BufferedReader(StringReader(rawSse))
        val outcome = parser.parseStream(reader)

        assertTrue(outcome is SseParseOutcome.Completed)
        assertEquals("완료된 답변입니다.", (outcome as SseParseOutcome.Completed).text)
    }

    @Test
    fun stopsPromptlyAtResponseFailedWithoutWaitingForEof() {
        val rawSse = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"시작 텍스트"}

            event: response.failed
            data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Limit exceeded"}}}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"도달하면 안 되는 텍스트"}

        """.trimIndent()

        val parser = ChatGptSseParser()
        val reader = BufferedReader(StringReader(rawSse))
        val outcome = parser.parseStream(reader)

        assertTrue(outcome is SseParseOutcome.Failed)
        val failed = outcome as SseParseOutcome.Failed
        assertEquals("subscription_sharing_usage_limit_exceeded", failed.code)
    }

    @Test
    fun malformedRecognizedEventFailsClosed() {
        val rawSse = """
            event: response.completed
            data: {not_valid_json: 1234}

        """.trimIndent()

        val parser = ChatGptSseParser()
        val reader = BufferedReader(StringReader(rawSse))
        val outcome = parser.parseStream(reader)

        assertTrue(outcome is SseParseOutcome.Interrupted)
        assertTrue((outcome as SseParseOutcome.Interrupted).detail.contains("Malformed event payload"))
    }

    @Test
    fun spoofedNestedTextDoesNotSpoofOutput() {
        val rawSse = """
            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"tool_call","data":{"type":"output_text","text":"SPOOFED_TOOL_TEXT"}}],"refusal":null}}

        """.trimIndent()

        val parser = ChatGptSseParser()
        val reader = BufferedReader(StringReader(rawSse))
        val outcome = parser.parseStream(reader)

        assertTrue(outcome is SseParseOutcome.Completed)
        // Should NOT extract text from tool_call or non-message types
        assertEquals("", (outcome as SseParseOutcome.Completed).text)
    }

    @Test
    fun lineLengthExceedingMaxCharsFailsClosedImmediately() {
        val giantLine = "data: " + "A".repeat(ChatGptConfig.MAX_EVENT_LINE_CHARS + 10) + "\n\n"
        val parser = ChatGptSseParser()
        val reader = BufferedReader(StringReader(giantLine))
        val outcome = parser.parseStream(reader)

        assertTrue(outcome is SseParseOutcome.Interrupted)
        assertTrue((outcome as SseParseOutcome.Interrupted).detail.contains("maximum limit"))
    }

    @Test
    fun accumulatedDeltasCappedAtMaxResponseChars() {
        val parser = ChatGptSseParser()
        val chunk1 = "가".repeat(600)
        val chunk2 = "나".repeat(600)
        val rawSse = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"$chunk1"}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"$chunk2"}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed"}}

        """.trimIndent()

        val reader = BufferedReader(StringReader(rawSse))
        val outcome = parser.parseStream(reader)

        assertTrue(outcome is SseParseOutcome.Completed)
        val text = (outcome as SseParseOutcome.Completed).text
        assertEquals(ChatGptConfig.MAX_RESPONSE_CHARS, text.length)
        assertEquals(1000, text.length)
    }

    @Test
    fun streamFailureWithUsageLimitExceededReturnsRateLimit() {
        val fakeTransport = FakeChatGptTransport(
            streamStatusCode = 200,
            streamOutcome = SseParseOutcome.Failed(
                code = "subscription_sharing_usage_limit_exceeded",
                message = "Monthly limit reached",
                param = null,
            ),
        )

        val client = ChatGptClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트 질문",
            accessToken = "token_abc",
            model = "gpt-6.1-sol",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.RATE_LIMIT, error.kind)
        assertTrue(error.userMessageKorean.contains("사용 한도"))
    }

    @Test
    fun http401ReturnsAuthFailed() {
        val fakeTransport = FakeChatGptTransport(
            streamStatusCode = 401,
            streamRawErrorBody = """{"error":{"code":"invalid_token","message":"Invalid token"}}""",
            streamErrorMessage = "HTTP 401 Unauthorized",
        )

        val client = ChatGptClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트",
            accessToken = "bad_token",
            model = "gpt-6.1-sol",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.AUTH_FAILED, error.kind)
        assertTrue(error.userMessageKorean.contains("인증이 만료되었거나"))
    }

    @Test
    fun cancellationTokenCancelsBeforeRequest() {
        val fakeTransport = FakeChatGptTransport()
        val client = ChatGptClient(transport = fakeTransport)
        val token = GeminiCancellationToken()
        token.cancel()

        val result = client.generateContent(
            prompt = "테스트 질문",
            accessToken = "token_abc",
            model = "gpt-6.1-sol",
            cancellationToken = token,
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.CANCELLED, error.kind)
        assertEquals("질문이 취소되었어요.", error.userMessageKorean)
    }

    @Test
    fun secretRedactionInHeadersAndErrorMessages() {
        val secretToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secretpayload.validsignature"
        val req = ChatGptHttpRequest(
            url = ChatGptConfig.RESPONSES_URL,
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $secretToken",
                "X-Api-Key" to "sensitive_key_val",
            ),
        )

        val reqStr = req.toString()
        assertFalse(reqStr.contains(secretToken))
        assertFalse(reqStr.contains("sensitive_key_val"))
        assertTrue(reqStr.contains("[REDACTED_TOKEN]"))

        val sanitizedError = ChatGptErrorMapper.sanitize("Failed request with Bearer $secretToken", secretToken)
        assertFalse(sanitizedError.contains(secretToken))
        assertTrue(sanitizedError.contains("[REDACTED_TOKEN]"))
    }
}
