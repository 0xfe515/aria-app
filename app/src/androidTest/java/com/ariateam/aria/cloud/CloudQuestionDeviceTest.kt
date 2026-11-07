package com.ariateam.aria.cloud

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudQuestionDeviceTest {

    private lateinit var keyStorage: AndroidKeyStoreAriaKeyStorage
    private lateinit var fakeAuth: FakeDeviceAuthRepository

    private class FakeDeviceAuthRepository(
        var token: String? = "test_device_token",
    ) : ChatGptAuthRepository {
        val _state = MutableStateFlow(
            ChatGptAuthState(
                sessionGeneration = 1L,
                status = ChatGptAuthStatus.AUTHENTICATED,
            )
        )
        override val sessionState: StateFlow<ChatGptAuthState> = _state
        override suspend fun validAccessToken(): String? = token
        override fun startLogin(context: android.content.Context, targetClientId: String?, forceReconsent: Boolean) {}
        override fun cancelLogin() {}
        override suspend fun signOut() {}
        override suspend fun switchAccount(clientId: String) {}
        override fun getSavedAccounts(): List<ChatGptAccountSummary> = emptyList()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        keyStorage = AndroidKeyStoreAriaKeyStorage(
            context = context,
            prefsName = TEST_PREFS_NAME,
            keyAlias = TEST_KEY_ALIAS,
        )
        fakeAuth = FakeDeviceAuthRepository()
        // Ensure isolated test alias and test prefs start clean without touching production
        keyStorage.clearApiKey()
    }

    @After
    fun tearDown() {
        if (::keyStorage.isInitialized) {
            keyStorage.clearApiKey()
        }
    }

    @Test
    fun androidKeyStoreEncryptsAndDecryptsApiKey() {
        assertTrue(
            "Test alias must never equal production alias",
            TEST_KEY_ALIAS != AndroidKeyStoreAriaKeyStorage.KEY_ALIAS,
        )

        val testKey = "AIzaSyDeviceTestEncryptionKey1234567890"
        val saved = keyStorage.saveApiKey(testKey)
        assertTrue("Saving API key to AndroidKeyStore must succeed", saved)

        val retrieved = keyStorage.getApiKey()
        assertNotNull("Retrieved API key must not be null", retrieved)
        assertEquals("Decrypted API key must match the original plaintext exactly", testKey, retrieved)

        keyStorage.clearApiKey()
        assertNull("After clearing, API key must be null", keyStorage.getApiKey())
    }

    @Test
    fun truthfulReportingWhenNoKeyConfigured() {
        val client = GeminiClient()
        val result = client.generateContent(
            prompt = "키 없는 질문",
            apiKey = "",
        )

        assertTrue(result is GeminiResult.Error)
        val error = result as GeminiResult.Error
        assertEquals(GeminiErrorKind.NO_KEY, error.kind)
        assertTrue(error.userMessageKorean.contains("Gemini API 키가 설정되지 않았어요"))
    }

    @Test
    fun cancelledPhotoQuestionImmediatelyRecyclesRealBitmap() {
        var pendingCallback: ((Bitmap?) -> Unit)? = null

        val coordinator = CloudQuestionCoordinator(
            authRepository = fakeAuth,
            ioDispatcher = Dispatchers.Main,
            mainDispatcher = Dispatchers.Main,
            frameCapturer = { cb -> pendingCallback = cb },
        )

        var responseDelivered: String? = null
        var errorDelivered: String? = null

        coordinator.askPhotoQuestion(
            questionId = 501L,
            prompt = "앞에 뭐 있어",
            onResponse = { responseDelivered = it },
            onError = { errorDelivered = it },
        )

        assertNotNull("Frame capture callback should be captured", pendingCallback)

        // Cancel before frame arrives
        coordinator.cancel(501L)

        // Provide real Android bitmap
        val realBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertFalse("Bitmap should not be recycled before delivery", realBitmap.isRecycled)

        pendingCallback?.invoke(realBitmap)

        assertTrue("Bitmap must be recycled immediately when question was cancelled", realBitmap.isRecycled)
        assertNull("Cancelled question must not receive response", responseDelivered)
        assertNull("Cancelled question must not receive error", errorDelivered)
    }

    @Test
    fun disposedCoordinatorImmediatelyRecyclesRealBitmap() {
        var pendingCallback: ((Bitmap?) -> Unit)? = null

        val coordinator = CloudQuestionCoordinator(
            authRepository = fakeAuth,
            ioDispatcher = Dispatchers.Main,
            mainDispatcher = Dispatchers.Main,
            frameCapturer = { cb -> pendingCallback = cb },
        )

        coordinator.askPhotoQuestion(
            questionId = 502L,
            prompt = "앞에 뭐 있어",
            onResponse = {},
            onError = {},
        )

        assertNotNull(pendingCallback)

        // Dispose coordinator
        coordinator.dispose()
        assertTrue(coordinator.isDisposed)

        val realBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        pendingCallback?.invoke(realBitmap)

        assertTrue("Bitmap must be recycled immediately on disposed coordinator", realBitmap.isRecycled)
    }

    @Test
    fun cancelledScopeBeforeCoroutineBodyStartsRecyclesRealBitmap() {
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedDispatcher = QueuedTestDispatcher()

        val coordinator = CloudQuestionCoordinator(
            authRepository = fakeAuth,
            ioDispatcher = queuedDispatcher,
            mainDispatcher = queuedDispatcher,
            frameCapturer = { cb -> pendingCallback = cb },
        )

        coordinator.askPhotoQuestion(
            questionId = 601L,
            prompt = "앞에 뭐 있어",
            onResponse = {},
            onError = {},
        )

        val realBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertFalse("Bitmap should not be recycled before delivery", realBitmap.isRecycled)

        // Deliver frame to coordinator; coroutine is queued on queuedDispatcher and has not started body
        pendingCallback?.invoke(realBitmap)

        // Cancel coordinator while coroutine body has not run
        coordinator.cancel(601L)

        // Advance dispatcher
        queuedDispatcher.advance()

        assertTrue("Real Android Bitmap must be recycled even if scope was cancelled before coroutine body executed", realBitmap.isRecycled)
    }

    @Test
    fun disposedCoordinatorBeforeCoroutineBodyStartsRecyclesRealBitmap() {
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedDispatcher = QueuedTestDispatcher()

        val coordinator = CloudQuestionCoordinator(
            authRepository = fakeAuth,
            ioDispatcher = queuedDispatcher,
            mainDispatcher = queuedDispatcher,
            frameCapturer = { cb -> pendingCallback = cb },
        )

        coordinator.askPhotoQuestion(
            questionId = 602L,
            prompt = "앞에 뭐 있어",
            onResponse = {},
            onError = {},
        )

        val realBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertFalse(realBitmap.isRecycled)

        pendingCallback?.invoke(realBitmap)

        // Dispose coordinator while coroutine body has not run
        coordinator.dispose()

        queuedDispatcher.advance()

        assertTrue("Real Android Bitmap must be recycled when coordinator disposed before body execution", realBitmap.isRecycled)
    }

    @Test
    fun cancelledQuestionWhenMainRunsLaunchButIoQueuedRecyclesRealBitmapAndEmitsNoCallback() {
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedMain = QueuedTestDispatcher()
        val queuedIo = QueuedTestDispatcher()
        var transportInvocations = 0

        val fakeTransport = object : ChatGptTransport {
            override fun execute(request: ChatGptHttpRequest, cancellationToken: GeminiCancellationToken?): ChatGptHttpResponse {
                return ChatGptHttpResponse(200, """{"models":[{"slug":"gpt-6.1-sol","display_name":"Sol","visibility":"list"}]}""")
            }

            override fun executeStream(
                request: ChatGptHttpRequest,
                cancellationToken: GeminiCancellationToken?,
                onDelta: ((String) -> Unit)?,
            ): ChatGptStreamResponse {
                transportInvocations++
                return ChatGptStreamResponse(200, SseParseOutcome.Completed("성공"))
            }
        }

        val coordinator = CloudQuestionCoordinator(
            authRepository = fakeAuth,
            chatGptClient = ChatGptClient(transport = fakeTransport),
            ioDispatcher = queuedIo,
            mainDispatcher = queuedMain,
            frameCapturer = { cb -> pendingCallback = cb },
        )

        var responseDelivered: String? = null
        var errorDelivered: String? = null

        coordinator.askPhotoQuestion(
            questionId = 701L,
            prompt = "앞에 뭐 있어",
            onResponse = { responseDelivered = it },
            onError = { errorDelivered = it },
        )

        val realBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertFalse(realBitmap.isRecycled)

        pendingCallback?.invoke(realBitmap)

        // Advance main: launch block runs, transfers capturedBitmap into workingBitmap,
        // and enters withContext(ioDispatcher), queueing the IO block onto queuedIo.
        queuedMain.advance()

        // Cancel while IO block is queued and has not executed
        coordinator.cancel(701L)

        // Advance IO dispatcher and main dispatcher
        queuedIo.advance()
        queuedMain.advance()

        assertTrue(
            "Real Android Bitmap must be recycled across the withContext(IO) dispatch boundary when cancelled",
            realBitmap.isRecycled,
        )
        assertNull("Cancelled question must not receive response", responseDelivered)
        assertNull("Cancelled question must not receive error", errorDelivered)
        assertEquals("API transport must never be invoked for cancelled request", 0, transportInvocations)
    }

    @Test
    fun disposedCoordinatorWhenMainRunsLaunchButIoQueuedRecyclesRealBitmapAndEmitsNoCallback() {
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedMain = QueuedTestDispatcher()
        val queuedIo = QueuedTestDispatcher()
        var transportInvocations = 0

        val fakeTransport = object : ChatGptTransport {
            override fun execute(request: ChatGptHttpRequest, cancellationToken: GeminiCancellationToken?): ChatGptHttpResponse {
                return ChatGptHttpResponse(200, """{"models":[{"slug":"gpt-6.1-sol","display_name":"Sol","visibility":"list"}]}""")
            }

            override fun executeStream(
                request: ChatGptHttpRequest,
                cancellationToken: GeminiCancellationToken?,
                onDelta: ((String) -> Unit)?,
            ): ChatGptStreamResponse {
                transportInvocations++
                return ChatGptStreamResponse(200, SseParseOutcome.Completed("성공"))
            }
        }

        val coordinator = CloudQuestionCoordinator(
            authRepository = fakeAuth,
            chatGptClient = ChatGptClient(transport = fakeTransport),
            ioDispatcher = queuedIo,
            mainDispatcher = queuedMain,
            frameCapturer = { cb -> pendingCallback = cb },
        )

        var responseDelivered: String? = null
        var errorDelivered: String? = null

        coordinator.askPhotoQuestion(
            questionId = 702L,
            prompt = "앞에 뭐 있어",
            onResponse = { responseDelivered = it },
            onError = { errorDelivered = it },
        )

        val realBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        assertFalse(realBitmap.isRecycled)

        pendingCallback?.invoke(realBitmap)

        // Advance main to reach withContext(ioDispatcher)
        queuedMain.advance()

        // Dispose coordinator while IO block is queued
        coordinator.dispose()

        // Advance remaining queued work
        queuedIo.advance()
        queuedMain.advance()

        assertTrue(
            "Real Android Bitmap must be recycled across the withContext(IO) dispatch boundary on dispose",
            realBitmap.isRecycled,
        )
        assertNull("Disposed coordinator must not deliver response", responseDelivered)
        assertNull("Disposed coordinator must not deliver error", errorDelivered)
        assertEquals("API transport must never be invoked for disposed coordinator", 0, transportInvocations)
    }

    private class QueuedTestDispatcher : CoroutineDispatcher() {
        private val queue = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }
        fun advance() {
            val list = queue.toList()
            queue.clear()
            for (r in list) r.run()
        }
    }

    companion object {
        private const val TEST_PREFS_NAME = "aria_cloud_secure_prefs_device_test"
        private const val TEST_KEY_ALIAS = "aria_gemini_api_key_device_test_v1"
    }
}
