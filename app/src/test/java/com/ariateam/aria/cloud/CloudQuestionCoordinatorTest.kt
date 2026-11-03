package com.ariateam.aria.cloud

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CloudQuestionCoordinatorTest {

    private class StubTransport(
        private val responseText: String = "응답 성공",
    ) : GeminiTransport {
        override fun execute(
            request: GeminiHttpRequest,
            cancellationToken: GeminiCancellationToken?,
        ): GeminiHttpResponse {
            if (cancellationToken?.isCancelled == true) {
                return GeminiHttpResponse(499, "", "Cancelled")
            }
            val json = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "$responseText" }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent()
            return GeminiHttpResponse(200, json)
        }
    }

    private class BlockingTransport : GeminiTransport {
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val cancelCalled = AtomicBoolean(false)
        val executionCount = AtomicInteger(0)

        override fun execute(
            request: GeminiHttpRequest,
            cancellationToken: GeminiCancellationToken?,
        ): GeminiHttpResponse {
            executionCount.incrementAndGet()
            cancellationToken?.onCancel {
                cancelCalled.set(true)
                releaseLatch.countDown()
            }
            startedLatch.countDown()

            val released = releaseLatch.await(5, TimeUnit.SECONDS)
            if (cancellationToken?.isCancelled == true) {
                return GeminiHttpResponse(499, "", "Cancelled: connection closed")
            }
            return if (released) {
                GeminiHttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"정상 응답"}]}}]}""")
            } else {
                GeminiHttpResponse(408, "", "Timed out")
            }
        }
    }

    @Test
    fun askTextQuestionFailsGracefullyWhenKeyIsMissing() {
        val storage = InMemoryAriaKeyStorage(key = null)
        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = StubTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        var errorReported: String? = null
        var responseReported: String? = null

        coordinator.askTextQuestion(
            questionId = 100L,
            prompt = "오늘 날씨 어때",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertNull(responseReported)
        assertNotNull(errorReported)
        assertTrue(errorReported!!.contains("Gemini API 키가 설정되지 않았어요"))
    }

    @Test
    fun askTextQuestionReturnsResponseWhenKeyIsConfigured() {
        val storage = InMemoryAriaKeyStorage(key = "AIzaSyTestKey")
        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = StubTransport("서울 날씨는 맑습니다.")),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        var errorReported: String? = null
        var responseReported: String? = null

        coordinator.askTextQuestion(
            questionId = 101L,
            prompt = "서울 날씨 어때",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertNull(errorReported)
        assertEquals("서울 날씨는 맑습니다.", responseReported)
    }

    @Test
    fun activeCancellationAbortsBlockedConnectionImmediately() {
        val storage = InMemoryAriaKeyStorage(key = "AIzaSyTestKey")
        val blockingTransport = BlockingTransport()
        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = blockingTransport),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.IO,
        )

        var responseReported: String? = null
        var errorReported: String? = null

        coordinator.askTextQuestion(
            questionId = 102L,
            prompt = "차단될 질문",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        // Wait until transport actually begins network I/O
        assertTrue("Transport should have started", blockingTransport.startedLatch.await(3, TimeUnit.SECONDS))

        // Trigger active cancellation
        val cancelStart = System.currentTimeMillis()
        coordinator.cancel(102L)

        // Verify the transport's cancellation hook was immediately called
        assertTrue("Cancellation hook should be executed immediately", blockingTransport.releaseLatch.await(1, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - cancelStart
        assertTrue("Cancellation should abort in <1000ms, was ${elapsed}ms", elapsed < 1000)
        assertTrue("Transport cancellation flag must be set", blockingTransport.cancelCalled.get())

        // Wait a moment to ensure no callbacks were dispatched to user
        Thread.sleep(100)
        assertNull("Cancelled request must not report response", responseReported)
        assertNull("Cancelled request must not report error", errorReported)
    }

    @Test
    fun disposeCancelsActiveJobScopeAndAbortsTransport() {
        val storage = InMemoryAriaKeyStorage(key = "AIzaSyTestKey")
        val blockingTransport = BlockingTransport()
        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = blockingTransport),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.IO,
        )

        coordinator.askTextQuestion(
            questionId = 103L,
            prompt = "처분될 질문",
            onResponse = {},
            onError = {},
        )

        assertTrue(blockingTransport.startedLatch.await(3, TimeUnit.SECONDS))

        // Dispose the coordinator
        coordinator.dispose()
        assertTrue(coordinator.isDisposed)
        assertTrue(blockingTransport.releaseLatch.await(1, TimeUnit.SECONDS))
        assertTrue(blockingTransport.cancelCalled.get())

        // Subsequent requests on disposed coordinator must be rejected
        val previousCount = blockingTransport.executionCount.get()
        coordinator.askTextQuestion(
            questionId = 104L,
            prompt = "새 질문",
            onResponse = {},
            onError = {},
        )
        assertEquals("No new execution on disposed coordinator", previousCount, blockingTransport.executionCount.get())
    }

    @Test
    fun newQuestionCancelsPriorActiveRequestWithoutImpactingNewRequest() {
        val storage = InMemoryAriaKeyStorage(key = "AIzaSyTestKey")
        val blockingTransport1 = BlockingTransport()
        val stubTransport2 = StubTransport("두 번째 질문 응답")

        var activeTransport: GeminiTransport = blockingTransport1
        val delegatingTransport = object : GeminiTransport {
            override fun execute(
                request: GeminiHttpRequest,
                cancellationToken: GeminiCancellationToken?,
            ): GeminiHttpResponse {
                return activeTransport.execute(request, cancellationToken)
            }
        }

        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = delegatingTransport),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.IO,
        )

        var response1: String? = null
        var response2: String? = null

        // Launch Request 1
        coordinator.askTextQuestion(
            questionId = 201L,
            prompt = "첫 번째 질문",
            onResponse = { response1 = it },
            onError = {},
        )

        assertTrue(blockingTransport1.startedLatch.await(3, TimeUnit.SECONDS))

        // Switch to fast stub and launch Request 2
        activeTransport = stubTransport2
        val latch2 = CountDownLatch(1)
        coordinator.askTextQuestion(
            questionId = 202L,
            prompt = "두 번째 질문",
            onResponse = {
                response2 = it
                latch2.countDown()
            },
            onError = {},
        )

        // Request 1 must have been actively aborted
        assertTrue("Request 1 connection must be aborted", blockingTransport1.releaseLatch.await(1, TimeUnit.SECONDS))
        assertTrue(blockingTransport1.cancelCalled.get())

        // Request 2 completes successfully
        assertTrue("Request 2 must complete", latch2.await(3, TimeUnit.SECONDS))
        assertEquals("두 번째 질문 응답", response2)
        assertNull("Stale Request 1 response must not be delivered", response1)
    }

    @Test
    fun cancelledPhotoQuestionWithNullBitmapEmitsNoCallback() {
        val storage = InMemoryAriaKeyStorage(key = "AIzaSyTestKey")
        var frameCallback: ((android.graphics.Bitmap?) -> Unit)? = null
        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = StubTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            frameCapturer = { cb -> frameCallback = cb },
        )

        var errorReported: String? = null
        var responseReported: String? = null

        coordinator.askPhotoQuestion(
            questionId = 301L,
            prompt = "사진 질문",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertNotNull("Frame capture callback should be registered", frameCallback)

        // Cancel before frame arrives
        coordinator.cancel(301L)

        // Now frame arrives (null because camera not active)
        frameCallback?.invoke(null)

        // Because it was cancelled, no error should be reported
        assertNull("Cancelled photo question must not report error", errorReported)
        assertNull("Cancelled photo question must not report response", responseReported)
    }

    @Test
    fun photoQuestionWithNullBitmapWhenActiveReportsCameraError() {
        val storage = InMemoryAriaKeyStorage(key = "AIzaSyTestKey")
        var frameCallback: ((android.graphics.Bitmap?) -> Unit)? = null
        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = StubTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            frameCapturer = { cb -> frameCallback = cb },
        )

        var errorReported: String? = null

        coordinator.askPhotoQuestion(
            questionId = 302L,
            prompt = "사진 질문",
            onResponse = {},
            onError = { errorReported = it },
        )

        assertNotNull(frameCallback)
        frameCallback?.invoke(null)

        assertNotNull("Error should be reported when camera is not running", errorReported)
        assertTrue(errorReported!!.contains("카메라가 실행 중이지 않아"))
    }

    @Test
    fun disposedCoordinatorRejectsNewRequestsImmediately() {
        val storage = InMemoryAriaKeyStorage(key = "AIzaSyTestKey")
        val coordinator = CloudQuestionCoordinator(
            keyStorage = storage,
            geminiClient = GeminiClient(transport = StubTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        coordinator.dispose()
        assertTrue(coordinator.isDisposed)

        var invoked = false
        coordinator.askTextQuestion(
            questionId = 400L,
            prompt = "테스트 질문",
            onResponse = { invoked = true },
            onError = { invoked = true },
        )
        assertFalse("Disposed coordinator must reject text request without callback", invoked)

        coordinator.askPhotoQuestion(
            questionId = 401L,
            prompt = "사진 질문",
            onResponse = { invoked = true },
            onError = { invoked = true },
        )
        assertFalse("Disposed coordinator must reject photo request without callback", invoked)
    }
}
