package com.ariateam.aria.cloud

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        keyStorage = AndroidKeyStoreAriaKeyStorage(context)
        keyStorage.clearApiKey()
    }

    @Test
    fun androidKeyStoreEncryptsAndDecryptsApiKey() {
        assertFalse(keyStorage.hasApiKey())
        assertNull(keyStorage.getApiKey())
        assertNull(keyStorage.getMaskedApiKey())

        val testKey = "AIzaSyDeviceTestKey123456789"
        val saved = keyStorage.saveApiKey(testKey)
        assertTrue("Key must save successfully to AndroidKeyStore", saved)
        assertTrue(keyStorage.hasApiKey())

        val retrieved = keyStorage.getApiKey()
        assertEquals(testKey, retrieved)

        val masked = keyStorage.getMaskedApiKey()
        assertNotNull(masked)
        assertEquals("AIza...6789", masked)
        assertFalse("Masked key must not expose middle of secret", masked!!.contains("DeviceTestKey"))

        val cleared = keyStorage.clearApiKey()
        assertTrue("Key must clear successfully", cleared)
        assertFalse(keyStorage.hasApiKey())
        assertNull(keyStorage.getApiKey())
    }

    @Test
    fun cloudImageProcessorCompressesBitmapWithinBounds() {
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

        try {
            val base64 = CloudImageProcessor.compressBitmapToBase64(bitmap)
            assertNotNull(base64)
            assertTrue("Base64 string must not be empty", base64!!.isNotBlank())
            assertTrue(
                "Encoded size must be bounded",
                base64.length < GeminiConfig.MAX_IMAGE_BYTES * 2,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun geminiClientHandlesDeterministicFakeTransportOnDevice() {
        val fakeTransport = object : GeminiTransport {
            override fun execute(
                request: GeminiHttpRequest,
                cancellationToken: GeminiCancellationToken?,
            ): GeminiHttpResponse {
                val json = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "text": "디바이스 테스트 응답입니다." }
                            ]
                          }
                        }
                      ]
                    }
                """.trimIndent()
                return GeminiHttpResponse(200, json)
            }
        }

        val client = GeminiClient(transport = fakeTransport)
        val result = client.generateContent(
            prompt = "디바이스 질문",
            apiKey = "AIzaSyDeviceKey",
        )

        assertTrue(result is GeminiResult.Success)
        assertEquals("디바이스 테스트 응답입니다.", (result as GeminiResult.Success).text)
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
        keyStorage.saveApiKey("AIzaSyDeviceTestKey")
        var pendingCallback: ((Bitmap?) -> Unit)? = null

        val coordinator = CloudQuestionCoordinator(
            keyStorage = keyStorage,
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
        keyStorage.saveApiKey("AIzaSyDeviceTestKey")
        var pendingCallback: ((Bitmap?) -> Unit)? = null

        val coordinator = CloudQuestionCoordinator(
            keyStorage = keyStorage,
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
        keyStorage.saveApiKey("AIzaSyDeviceTestKey")
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedDispatcher = QueuedTestDispatcher()

        val coordinator = CloudQuestionCoordinator(
            keyStorage = keyStorage,
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
        keyStorage.saveApiKey("AIzaSyDeviceTestKey")
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedDispatcher = QueuedTestDispatcher()

        val coordinator = CloudQuestionCoordinator(
            keyStorage = keyStorage,
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
        keyStorage.saveApiKey("AIzaSyDeviceTestKey")
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedMain = QueuedTestDispatcher()
        val queuedIo = QueuedTestDispatcher()
        var transportInvocations = 0

        val fakeTransport = object : GeminiTransport {
            override fun execute(
                request: GeminiHttpRequest,
                cancellationToken: GeminiCancellationToken?,
            ): GeminiHttpResponse {
                transportInvocations++
                return GeminiHttpResponse(200, "{}")
            }
        }

        val coordinator = CloudQuestionCoordinator(
            keyStorage = keyStorage,
            geminiClient = GeminiClient(transport = fakeTransport),
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
        keyStorage.saveApiKey("AIzaSyDeviceTestKey")
        var pendingCallback: ((Bitmap?) -> Unit)? = null
        val queuedMain = QueuedTestDispatcher()
        val queuedIo = QueuedTestDispatcher()
        var transportInvocations = 0

        val fakeTransport = object : GeminiTransport {
            override fun execute(
                request: GeminiHttpRequest,
                cancellationToken: GeminiCancellationToken?,
            ): GeminiHttpResponse {
                transportInvocations++
                return GeminiHttpResponse(200, "{}")
            }
        }

        val coordinator = CloudQuestionCoordinator(
            keyStorage = keyStorage,
            geminiClient = GeminiClient(transport = fakeTransport),
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
}
