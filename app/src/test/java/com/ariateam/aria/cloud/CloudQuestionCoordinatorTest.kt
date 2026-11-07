package com.ariateam.aria.cloud

import android.content.Context
import android.graphics.Bitmap
import com.ariateam.aria.cloud.auth.ChatGptAccountSummary
import com.ariateam.aria.cloud.auth.ChatGptAuthRepository
import com.ariateam.aria.cloud.auth.ChatGptAuthState
import com.ariateam.aria.cloud.auth.ChatGptAuthStatus
import com.ariateam.aria.cloud.chatgpt.ChatGptClient
import com.ariateam.aria.cloud.chatgpt.ChatGptHttpRequest
import com.ariateam.aria.cloud.chatgpt.ChatGptHttpResponse
import com.ariateam.aria.cloud.chatgpt.ChatGptStreamResponse
import com.ariateam.aria.cloud.chatgpt.ChatGptTransport
import com.ariateam.aria.cloud.chatgpt.SseParseOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private class FakeAuthRepository(
        var token: String? = "test_token_123",
        var status: ChatGptAuthStatus = ChatGptAuthStatus.AUTHENTICATED,
        var generation: Long = 1L,
    ) : ChatGptAuthRepository {
        var throwOnValidToken: Throwable? = null
        val stateFlow = MutableStateFlow(
            ChatGptAuthState(
                sessionGeneration = generation,
                status = status,
            )
        )
        override val sessionState: StateFlow<ChatGptAuthState> = stateFlow

        fun setSession(newGen: Long, newStatus: ChatGptAuthStatus) {
            generation = newGen
            status = newStatus
            stateFlow.value = ChatGptAuthState(
                sessionGeneration = newGen,
                status = newStatus,
            )
        }

        override suspend fun validAccessToken(): String? {
            throwOnValidToken?.let { throw it }
            return token
        }
        override fun startLogin(context: Context, targetClientId: String?, forceReconsent: Boolean) {}
        override fun cancelLogin() {}
        override suspend fun signOut() {
            setSession(generation + 1, ChatGptAuthStatus.UNAUTHENTICATED)
        }
        override suspend fun switchAccount(clientId: String) {
            setSession(generation + 1, ChatGptAuthStatus.AUTHENTICATED)
        }
        override fun getSavedAccounts(): List<ChatGptAccountSummary> = emptyList()
    }

    private class StubChatGptTransport(
        private val responseText: String = "응답 성공",
    ) : ChatGptTransport {
        override fun execute(
            request: ChatGptHttpRequest,
            cancellationToken: GeminiCancellationToken?,
        ): ChatGptHttpResponse {
            return ChatGptHttpResponse(
                200,
                """{"models":[{"slug":"gpt-6.1-sol","display_name":"ChatGPT 6.1 Sol","visibility":"list"}]}""",
            )
        }

        override fun executeStream(
            request: ChatGptHttpRequest,
            cancellationToken: GeminiCancellationToken?,
            onDelta: ((String) -> Unit)?,
        ): ChatGptStreamResponse {
            if (cancellationToken?.isCancelled == true) {
                return ChatGptStreamResponse(499, SseParseOutcome.Interrupted("Cancelled"), "Cancelled")
            }
            return ChatGptStreamResponse(200, SseParseOutcome.Completed(responseText))
        }
    }

    private class BlockingChatGptTransport : ChatGptTransport {
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val cancelCalled = AtomicBoolean(false)
        val executionCount = AtomicInteger(0)

        override fun execute(
            request: ChatGptHttpRequest,
            cancellationToken: GeminiCancellationToken?,
        ): ChatGptHttpResponse {
            return ChatGptHttpResponse(
                200,
                """{"models":[{"slug":"gpt-6.1-sol","display_name":"ChatGPT 6.1 Sol","visibility":"list"}]}""",
            )
        }

        override fun executeStream(
            request: ChatGptHttpRequest,
            cancellationToken: GeminiCancellationToken?,
            onDelta: ((String) -> Unit)?,
        ): ChatGptStreamResponse {
            executionCount.incrementAndGet()
            cancellationToken?.onCancel {
                cancelCalled.set(true)
                releaseLatch.countDown()
            }
            startedLatch.countDown()

            val released = releaseLatch.await(5, TimeUnit.SECONDS)
            if (cancellationToken?.isCancelled == true) {
                return ChatGptStreamResponse(499, SseParseOutcome.Interrupted("Cancelled: connection closed"), "Cancelled")
            }
            return if (released) {
                ChatGptStreamResponse(200, SseParseOutcome.Completed("정상 응답"))
            } else {
                ChatGptStreamResponse(408, SseParseOutcome.Interrupted("Timed out"), "Timed out")
            }
        }
    }

    @Test
    fun askTextQuestionFailsGracefullyWhenUnauthenticated() {
        val auth = FakeAuthRepository(token = null, status = ChatGptAuthStatus.UNAUTHENTICATED)
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = StubChatGptTransport()),
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
        assertTrue(errorReported!!.contains("ChatGPT 로그인이 필요해요"))
    }

    @Test
    fun askTextQuestionReturnsResponseWhenAuthenticated() {
        val auth = FakeAuthRepository(token = "valid_access_token")
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = StubChatGptTransport("서울 날씨는 맑습니다.")),
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
    fun planUseNotEnabledReportsSpecificKoreanMessage() {
        val auth = FakeAuthRepository(token = null, status = ChatGptAuthStatus.PLAN_USE_NOT_ENABLED)
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = StubChatGptTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        var errorReported: String? = null

        coordinator.askTextQuestion(
            questionId = 104L,
            prompt = "질문",
            onResponse = {},
            onError = { errorReported = it },
        )

        assertNotNull(errorReported)
        assertTrue(errorReported!!.contains("ChatGPT 플랜 사용 권한이 필요해요"))
    }

    @Test
    fun activeCancellationAbortsBlockedConnectionImmediately() {
        val auth = FakeAuthRepository(token = "valid_access_token")
        val blockingTransport = BlockingChatGptTransport()
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = blockingTransport),
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

        // Wait until transport begins execution
        assertTrue("Transport should have started", blockingTransport.startedLatch.await(3, TimeUnit.SECONDS))

        // Trigger active cancellation
        val cancelStart = System.currentTimeMillis()
        coordinator.cancel(102L)

        // Verify the transport's cancellation hook was immediately called
        assertTrue("Cancellation hook should be executed immediately", blockingTransport.releaseLatch.await(1, TimeUnit.SECONDS))
        val elapsed = System.currentTimeMillis() - cancelStart
        assertTrue("Cancellation should abort in <1000ms, was ${elapsed}ms", elapsed < 1000)
        assertTrue("Transport cancellation flag must be set", blockingTransport.cancelCalled.get())

        Thread.sleep(100)
        assertNull("Cancelled request must not report response", responseReported)
        assertNull("Cancelled request must not report error", errorReported)
    }

    @Test
    fun disposeCancelsActiveJobScopeAndAbortsTransport() {
        val auth = FakeAuthRepository(token = "valid_access_token")
        val blockingTransport = BlockingChatGptTransport()
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = blockingTransport),
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

        coordinator.dispose()
        assertTrue("Coordinator must be marked disposed", coordinator.isDisposed)
        assertTrue("Transport cancellation must trigger on dispose", blockingTransport.releaseLatch.await(1, TimeUnit.SECONDS))
        assertTrue(blockingTransport.cancelCalled.get())
    }

    @Test
    fun staleQuestionResultIgnoredWhenNewQuestionArrives() {
        val auth = FakeAuthRepository(token = "valid_token")
        var question1Delivery: String? = null
        var question2Delivery: String? = null

        val transport = object : ChatGptTransport {
            override fun execute(request: ChatGptHttpRequest, cancellationToken: GeminiCancellationToken?): ChatGptHttpResponse {
                return ChatGptHttpResponse(200, """{"models":[{"slug":"gpt-6.1-sol","display_name":"Sol","visibility":"list"}]}""")
            }

            override fun executeStream(
                request: ChatGptHttpRequest,
                cancellationToken: GeminiCancellationToken?,
                onDelta: ((String) -> Unit)?,
            ): ChatGptStreamResponse {
                val body = String(request.bodyBytes, Charsets.UTF_8)
                return if (body.contains("질문1")) {
                    ChatGptStreamResponse(200, SseParseOutcome.Completed("응답 1"))
                } else {
                    ChatGptStreamResponse(200, SseParseOutcome.Completed("응답 2"))
                }
            }
        }

        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = transport),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        // Fire question 1
        coordinator.askTextQuestion(
            questionId = 201L,
            prompt = "질문1",
            onResponse = { question1Delivery = it },
            onError = {},
        )

        // Fire question 2
        coordinator.askTextQuestion(
            questionId = 202L,
            prompt = "질문2",
            onResponse = { question2Delivery = it },
            onError = {},
        )

        assertEquals("응답 2", question2Delivery)
    }

    @Test
    fun noCameraCaptureWhenUnauthenticatedForPhotoQuestion() {
        val auth = FakeAuthRepository(token = null, status = ChatGptAuthStatus.UNAUTHENTICATED)
        var cameraFrameRequested = false

        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = StubChatGptTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            frameCapturer = { cameraFrameRequested = true },
        )

        var errorReported: String? = null

        coordinator.askPhotoQuestion(
            questionId = 301L,
            prompt = "앞에 뭐 있어",
            onResponse = {},
            onError = { errorReported = it },
        )

        assertFalse("Camera frame MUST NEVER be captured when user is unauthenticated", cameraFrameRequested)
        assertNotNull(errorReported)
        assertTrue(errorReported!!.contains("ChatGPT 로그인이 필요해요"))
    }

    @Test
    fun sessionGenerationChangeCancelsInFlightImmediately() {
        val auth = FakeAuthRepository(token = "valid_token", generation = 1L)
        val blockingTransport = BlockingChatGptTransport()
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = blockingTransport),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.IO,
        )

        var responseReported: String? = null
        var errorReported: String? = null

        coordinator.askTextQuestion(
            questionId = 401L,
            prompt = "세션 전환 중단 질문",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertTrue(blockingTransport.startedLatch.await(3, TimeUnit.SECONDS))

        // User switches account: generation advances from 1 to 2
        auth.setSession(newGen = 2L, newStatus = ChatGptAuthStatus.AUTHENTICATED)

        assertTrue("In-flight request should be cancelled immediately on session change", blockingTransport.releaseLatch.await(1, TimeUnit.SECONDS))
        assertTrue(blockingTransport.cancelCalled.get())

        Thread.sleep(100)
        assertNull("Old session response must not reach callback", responseReported)
        assertNull("Old session error must not reach callback", errorReported)
    }

    @Test
    fun logoutCancelsInFlightImmediately() {
        val auth = FakeAuthRepository(token = "valid_token", generation = 1L)
        val blockingTransport = BlockingChatGptTransport()
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = blockingTransport),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.IO,
        )

        var responseReported: String? = null

        coordinator.askTextQuestion(
            questionId = 402L,
            prompt = "로그아웃 중단 질문",
            onResponse = { responseReported = it },
            onError = {},
        )

        assertTrue(blockingTransport.startedLatch.await(3, TimeUnit.SECONDS))

        // User logs out
        auth.setSession(newGen = 2L, newStatus = ChatGptAuthStatus.UNAUTHENTICATED)

        assertTrue("In-flight request should be cancelled immediately on logout", blockingTransport.releaseLatch.await(1, TimeUnit.SECONDS))
        assertTrue(blockingTransport.cancelCalled.get())
        assertNull("Logged-out session must not deliver response", responseReported)
    }

    @Test
    fun initialUnauthenticatedAskDeterministicallyReportsLoginGuidanceWithoutTimeoutOrCancellation() {
        val auth = FakeAuthRepository(token = null, status = ChatGptAuthStatus.UNAUTHENTICATED)
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = StubChatGptTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        var errorReported: String? = null
        var responseReported: String? = null

        coordinator.askTextQuestion(
            questionId = 501L,
            prompt = "로그인 전 질문",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertNull(responseReported)
        assertNotNull(errorReported)
        assertTrue(errorReported!!.contains("ChatGPT 로그인이 필요해요"))
    }

    @Test
    fun throwingAuthRepositoryReportsSanitizedErrorAndClearsStateWithoutCrashing() {
        val auth = FakeAuthRepository(token = "valid_token")
        auth.throwOnValidToken = RuntimeException("Auth token refresh network failed")
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = StubChatGptTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        var errorReported: String? = null
        var responseReported: String? = null

        coordinator.askTextQuestion(
            questionId = 502L,
            prompt = "토큰 오류 테스트",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertNull(responseReported)
        assertNotNull(errorReported)
        assertTrue(errorReported!!.contains("로그인 상태를 확인할 수 없어요"))
    }

    @Test
    fun throwingAuthRepositoryWithSecretMessageSanitizesErrorWithoutLeakingSecret() {
        val auth = FakeAuthRepository(token = "valid_token")
        val secretHeader = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sensitive_payload"
        auth.throwOnValidToken = RuntimeException("Fatal network crash: Authorization failed for header: $secretHeader")
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = StubChatGptTransport()),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        var errorReported: String? = null
        var responseReported: String? = null

        coordinator.askTextQuestion(
            questionId = 503L,
            prompt = "시크릿 유출 방지 테스트",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertNull(responseReported)
        assertNotNull(errorReported)
        assertFalse("Error message must not contain raw secret token", errorReported!!.contains(secretHeader))
        assertFalse("Error message must not contain bearer substring", errorReported!!.contains("Bearer"))
        assertTrue(errorReported!!.contains("로그인 상태를 확인할 수 없어요"))
    }

    @Test
    fun authInvalidationInvokesCallbackWithQuestionId() {
        val auth = FakeAuthRepository(token = "valid_token", status = ChatGptAuthStatus.AUTHENTICATED)
        val blockingTransport = BlockingChatGptTransport()
        val invalidatedQIds = mutableListOf<Long>()
        val coordinator = CloudQuestionCoordinator(
            authRepository = auth,
            chatGptClient = ChatGptClient(transport = blockingTransport),
            ioDispatcher = Dispatchers.Default,
            mainDispatcher = Dispatchers.Default,
            onAuthInvalidated = { qId -> synchronized(invalidatedQIds) { invalidatedQIds.add(qId) } },
        )

        var responseReported: String? = null
        var errorReported: String? = null

        coordinator.askTextQuestion(
            questionId = 777L,
            prompt = "인증 취소 콜백 테스트",
            onResponse = { responseReported = it },
            onError = { errorReported = it },
        )

        assertTrue(blockingTransport.startedLatch.await(3, TimeUnit.SECONDS))

        // Session invalidated while request is active
        auth.setSession(newGen = 2L, newStatus = ChatGptAuthStatus.UNAUTHENTICATED)

        assertTrue("In-flight request should be cancelled", blockingTransport.releaseLatch.await(2, TimeUnit.SECONDS))
        var waited = 0
        while (waited < 20 && synchronized(invalidatedQIds) { invalidatedQIds.isEmpty() }) {
            Thread.sleep(50)
            waited++
        }
        val recorded = synchronized(invalidatedQIds) { invalidatedQIds.toList() }
        assertEquals(listOf(777L), recorded)
        assertNull(responseReported)
    }
}
