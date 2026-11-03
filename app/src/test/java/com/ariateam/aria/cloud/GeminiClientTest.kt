package com.ariateam.aria.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GeminiClientTest {

    private class FakeTransport(
        var responseStatus: Int = 200,
        var responseBody: String = "",
        var responseError: String? = null,
        var capturedRequest: GeminiHttpRequest? = null,
    ) : GeminiTransport {
        override fun execute(
            request: GeminiHttpRequest,
            cancellationToken: GeminiCancellationToken?,
        ): GeminiHttpResponse {
            capturedRequest = request
            if (cancellationToken?.isCancelled == true) {
                return GeminiHttpResponse(499, "", "Cancelled")
            }
            return GeminiHttpResponse(responseStatus, responseBody, responseError)
        }
    }

    @Test
    fun generatesTextRequestWithSystemInstructionAndApiKeyHeader() {
        val fakeTransport = FakeTransport(
            responseStatus = 200,
            responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "파리는 프랑스의 수도입니다." }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "프랑스의 수도는 어디인가요?",
            apiKey = "AIzaSyFakeKey12345",
            model = GeminiConfig.DEFAULT_MODEL,
        )

        assertTrue("Expected Success result", result is GeminiResult.Success)
        assertEquals("파리는 프랑스의 수도입니다.", (result as GeminiResult.Success).text)

        val req = fakeTransport.capturedRequest
        assertNotNull(req)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", req!!.url)
        assertEquals("AIzaSyFakeKey12345", req.headers[GeminiConfig.HEADER_API_KEY])
        assertEquals("application/json; charset=UTF-8", req.headers["Content-Type"])

        val bodyString = String(req.bodyBytes, Charsets.UTF_8)
        assertTrue(bodyString.contains("프랑스의 수도는 어디인가요?"))
        assertTrue(bodyString.contains("systemInstruction"))
        assertTrue(bodyString.contains("Aria"))
        assertTrue(bodyString.contains("maxOutputTokens"))
    }

    @Test
    fun generatesMultimodalPhotoRequestWithInlineData() {
        val fakeTransport = FakeTransport(
            responseStatus = 200,
            responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "앞에 빨간색 텀블러가 놓여 있습니다." }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )

        val client = GeminiClient(transport = fakeTransport)
        val dummyBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val result = client.generateContent(
            prompt = "이거 뭐야",
            imageBase64 = dummyBase64,
            apiKey = "AIzaSyFakeKey12345",
        )

        assertTrue(result is GeminiResult.Success)
        assertEquals("앞에 빨간색 텀블러가 놓여 있습니다.", (result as GeminiResult.Success).text)

        val req = fakeTransport.capturedRequest
        assertNotNull(req)
        val bodyString = String(req!!.bodyBytes, Charsets.UTF_8)
        assertTrue(bodyString.contains("inline_data"))
        assertTrue(bodyString.contains("image/jpeg"))
        assertTrue(bodyString.contains(dummyBase64))
        assertTrue(bodyString.contains("이거 뭐야"))
    }

    @Test
    fun returnsNoKeyErrorWhenApiKeyIsEmpty() {
        val fakeTransport = FakeTransport()
        val client = GeminiClient(transport = fakeTransport)

        val result = client.generateContent(
            prompt = "질문",
            apiKey = "   ",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.NO_KEY, error.kind)
        assertTrue(error.userMessageKorean.contains("설정에서 API 키를 등록"))
    }

    @Test
    fun handlesAuthFailureHttp401And403Truthfully() {
        val fakeTransport = FakeTransport(
            responseStatus = 403,
            responseBody = """
                {
                  "error": {
                    "code": 403,
                    "message": "API key not valid. Please pass a valid API key.",
                    "status": "PERMISSION_DENIED"
                  }
                }
            """.trimIndent(),
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트 질문",
            apiKey = "InvalidKey",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.AUTH_FAILED, error.kind)
        assertTrue(error.userMessageKorean.contains("올바르지 않거나 권한이 없어요"))
    }

    @Test
    fun handlesModelUnavailableHttp404Truthfully() {
        val fakeTransport = FakeTransport(
            responseStatus = 404,
            responseBody = """
                {
                  "error": {
                    "code": 404,
                    "message": "models/gemini-1.5-flash is not found for API version v1beta",
                    "status": "NOT_FOUND"
                  }
                }
            """.trimIndent(),
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트 질문",
            apiKey = "ValidKey",
            model = "gemini-1.5-flash",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.MODEL_UNAVAILABLE, error.kind)
        assertTrue(error.userMessageKorean.contains("모델을 사용할 수 없거나"))
    }

    @Test
    fun handlesRateLimitHttp429Truthfully() {
        val fakeTransport = FakeTransport(
            responseStatus = 429,
            responseBody = """
                {
                  "error": {
                    "code": 429,
                    "message": "Resource has been exhausted (e.g. check quota).",
                    "status": "RESOURCE_EXHAUSTED"
                  }
                }
            """.trimIndent(),
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트 질문",
            apiKey = "ValidKey",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.RATE_LIMIT, error.kind)
        assertTrue(error.userMessageKorean.contains("사용 한도를 초과"))
    }

    @Test
    fun handlesTimeoutTruthfully() {
        val fakeTransport = FakeTransport(
            responseStatus = 408,
            responseBody = "",
            responseError = "SocketTimeoutException: Read timed out",
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트 질문",
            apiKey = "ValidKey",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.TIMEOUT, error.kind)
        assertTrue(error.userMessageKorean.contains("응답 시간이 초과"))
    }

    @Test
    fun handlesCancellationTruthfully() {
        val token = GeminiCancellationToken()
        token.cancel()

        val fakeTransport = FakeTransport(responseStatus = 200, responseBody = "{}")
        val client = GeminiClient(transport = fakeTransport)

        val result = client.generateContent(
            prompt = "테스트 질문",
            apiKey = "ValidKey",
            cancellationToken = token,
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.CANCELLED, error.kind)
        assertTrue(error.userMessageKorean.contains("취소"))
    }

    @Test
    fun handlesEmptyResponseGracefully() {
        val fakeTransport = FakeTransport(
            responseStatus = 200,
            responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": []
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트 질문",
            apiKey = "ValidKey",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.EMPTY_RESPONSE, error.kind)
        assertTrue(error.userMessageKorean.contains("답변을 생성하지 못했어요"))
    }

    @Test
    fun capsPromptAndResponseLengths() {
        val longText = "가".repeat(2000)
        val fakeTransport = FakeTransport(
            responseStatus = 200,
            responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "$longText" }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = longText,
            apiKey = "ValidKey",
        )

        assertTrue(result is GeminiResult.Success)
        val text = (result as GeminiResult.Success).text
        assertEquals(GeminiConfig.MAX_RESPONSE_CHARS, text.length)

        val bodyString = String(fakeTransport.capturedRequest!!.bodyBytes, Charsets.UTF_8)
        assertTrue(bodyString.contains("가".repeat(GeminiConfig.MAX_PROMPT_CHARS)))
        assertFalse(bodyString.contains("가".repeat(GeminiConfig.MAX_PROMPT_CHARS + 1)))
    }

    @Test
    fun redactsApiKeyInErrorDetailsAndToString() {
        val secretKey = "AIzaSySecretApiKey123456789012345"
        val fakeTransport = FakeTransport(
            responseStatus = 400,
            responseBody = """{"error":{"message":"Invalid API key: $secretKey","status":"INVALID_ARGUMENT"}}""",
        )

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "테스트 질문",
            apiKey = secretKey,
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertFalse("Error detail must not expose raw API key", error.detail.contains(secretKey))
        assertTrue("Error detail must redact API key", error.detail.contains("[REDACTED_KEY]"))

        // Verify GeminiHttpRequest.toString() redacts key header
        val req = fakeTransport.capturedRequest!!
        val reqString = req.toString()
        assertFalse("GeminiHttpRequest.toString() must not expose secret key", reqString.contains(secretKey))
        assertTrue("GeminiHttpRequest.toString() must contain redacted marker", reqString.contains("[REDACTED_KEY]"))

        // Verify GeminiHttpResponse.toString() redacts key pattern
        val respWithKey = GeminiHttpResponse(500, "", "Failure connecting with key $secretKey")
        val respString = respWithKey.toString()
        assertFalse("GeminiHttpResponse.toString() must not expose secret key", respString.contains(secretKey))
        assertTrue("GeminiHttpResponse.toString() must contain redacted marker", respString.contains("[REDACTED_KEY]"))
    }

    @Test
    fun verifiesOfficialModelConfigAgainstGoogleSpecifications() {
        assertEquals("gemini-2.5-flash", GeminiConfig.DEFAULT_MODEL)
        assertTrue(GeminiConfig.SUPPORTED_MODELS.contains("gemini-2.5-flash"))
        assertTrue(GeminiConfig.SUPPORTED_MODELS.contains("gemini-3.5-flash"))
        assertTrue(GeminiConfig.SUPPORTED_MODELS.contains("gemini-3.8-flash"))

        // Obsolete / retired models must NOT be configured
        assertFalse(GeminiConfig.SUPPORTED_MODELS.contains("gemini-1.5-flash"))
        assertFalse(GeminiConfig.SUPPORTED_MODELS.contains("gemini-2.0-flash"))
    }

    @Test
    fun cancellationTokenCallbacksExecuteAtMostOnceUnderConcurrentRace() {
        val token = GeminiCancellationToken()
        val callbackCount = 50
        val executionCounts = Array(callbackCount) { AtomicInteger(0) }
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(callbackCount + 1)

        val executor = Executors.newFixedThreadPool(callbackCount + 1)
        try {
            for (i in 0 until callbackCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        token.onCancel {
                            executionCounts[i].incrementAndGet()
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            executor.submit {
                try {
                    startLatch.await()
                    token.cancel()
                } finally {
                    doneLatch.countDown()
                }
            }

            startLatch.countDown()
            assertTrue("Concurrency test timed out", doneLatch.await(5, TimeUnit.SECONDS))

            // Ensure cancel is finalized
            token.cancel()

            for (i in 0 until callbackCount) {
                val count = executionCounts[i].get()
                assertEquals("Callback $i must execute exactly once under concurrent race", 1, count)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
