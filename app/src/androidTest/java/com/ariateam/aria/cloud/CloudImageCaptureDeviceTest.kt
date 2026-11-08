package com.ariateam.aria.cloud

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.MainActivity
import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import com.ariateam.aria.command.RuleCommandInterpreter
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
import com.ariateam.aria.distance.BodyMountPosition
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.startup.StartupPermissionInventory
import com.ariateam.aria.startup.StartupViewModel
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionForegroundService
import com.ariateam.aria.vision.VisionSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises VisionSession's real CameraX snapshot path and the image bytes sent to a fake API transport. */
@RunWith(AndroidJUnit4::class)
class CloudImageCaptureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var coordinator: CloudQuestionCoordinator? = null
    private var bodyProfileStore: BodyMountProfileStore? = null
    private var originalBodyProfile: BodyMountProfile? = null
    private var profileFixtureInstalled = false
    private var originallyStoppedByUser = false

    private class FakeAuthenticatedRepository : ChatGptAuthRepository {
        override val sessionState = MutableStateFlow(
            ChatGptAuthState(sessionGeneration = 5L, status = ChatGptAuthStatus.AUTHENTICATED),
        )
        override suspend fun validAccessToken(): String = "device_test_only_token"
        override fun startLogin(context: Context, targetClientId: String?, forceReconsent: Boolean) = Unit
        override fun cancelLogin() = Unit
        override suspend fun signOut() = Unit
        override suspend fun switchAccount(clientId: String) = Unit
        override fun getSavedAccounts(): List<ChatGptAccountSummary> = emptyList()
    }

    @Before
    fun setUpHomeCamera() {
        originallyStoppedByUser = VisionSession.requiresExplicitRestart(context)
        bodyProfileStore = BodyMountProfileStore(context).also { store ->
            originalBodyProfile = store.profile.value
            profileFixtureInstalled = true
            check(store.save(BodyMountProfile(statureCm = 170f, position = BodyMountPosition.CHEST)))
        }
        StartupPermissionInventory.requiredRuntimePermissions(Build.VERSION.SDK_INT).forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        instrumentation.runOnMainSync {
            context.stopService(Intent(context, VisionForegroundService::class.java))
            VisionSession.closeController()
            VisionSession.clearExplicitStop(context)
        }
        val activeScenario = ActivityScenario.launch(MainActivity::class.java)
        scenario = activeScenario
        activeScenario.onActivity { activity ->
            val granted = StartupPermissionInventory.requiredRuntimePermissions(Build.VERSION.SDK_INT)
                .filter { activity.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
                .toSet()
            ViewModelProvider(activity)[StartupViewModel::class.java].admit(granted)
            VisionSession.ensureController(activity)
        }
    }

    @After
    fun tearDown() {
        coordinator?.dispose()
        coordinator = null
        try {
            instrumentation.runOnMainSync {
                context.stopService(Intent(context, VisionForegroundService::class.java))
                VisionSession.closeController()
            }
            scenario?.close()
            scenario = null
        } finally {
            try {
                if (profileFixtureInstalled) {
                    val store = requireNotNull(bodyProfileStore)
                    if (originalBodyProfile == null) store.clear()
                    else check(store.save(requireNotNull(originalBodyProfile)))
                }
            } finally {
                bodyProfileStore?.close()
                bodyProfileStore = null
                originalBodyProfile = null
                profileFixtureInstalled = false
                if (originallyStoppedByUser) VisionSession.markExplicitStop(context)
                else VisionSession.clearExplicitStop(context)
            }
        }
    }

    @Test
    fun looseVisualQuestionCapturesRealCameraFrameAndAttachesDecodableJpeg() = runBlocking {
        val running = withTimeout(30_000L) {
            VisionSession.uiState.first { it.cameraStatus == CameraSessionStatus.Running }
        }
        assertEquals(CameraSessionStatus.Running, running.cameraStatus)

        val interpretation = RuleCommandInterpreter().interpret("전방 표지판 글씨 읽어 주세요", CommandContext(), 0L)
        assertTrue("Loose visual cue should select photo flow: $interpretation", interpretation is Interpretation.CloudPhotoQuestion)
        val prompt = (interpretation as Interpretation.CloudPhotoQuestion).prompt
        val transport = CapturingImageTransport()
        val responseLatch = CountDownLatch(1)
        val deliveredResponse = AtomicReference<String?>()
        val deliveredError = AtomicReference<String?>()
        coordinator = CloudQuestionCoordinator(
            authRepository = FakeAuthenticatedRepository(),
            chatGptClient = ChatGptClient(transport),
            ioDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Main,
            frameCapturer = VisionSession::captureSingleFrame,
        )

        coordinator?.askPhotoQuestion(
            questionId = 9001L,
            prompt = prompt,
            onResponse = { deliveredResponse.set(it); responseLatch.countDown() },
            onError = { deliveredError.set(it); responseLatch.countDown() },
        )

        assertTrue("Fake transport should receive the captured image request", transport.requestLatch.await(30, TimeUnit.SECONDS))
        assertTrue("Question result should return only after image request", responseLatch.await(15, TimeUnit.SECONDS))
        assertEquals("기기 카메라 이미지 응답", deliveredResponse.get())
        assertEquals(null, deliveredError.get())

        val body = requireNotNull(transport.requestBody.get())
        val input = JSONObject(body).getJSONArray("input")
        val content = input.getJSONObject(0).getJSONArray("content")
        var imageData: String? = null
        var retainedPrompt: String? = null
        for (index in 0 until content.length()) {
            val part = content.getJSONObject(index)
            when (part.optString("type")) {
                "input_image" -> imageData = part.optString("image_url")
                "input_text" -> retainedPrompt = part.optString("text")
            }
        }
        assertNotNull("Photo request must include image part", imageData)
        assertTrue("Image part must be JPEG data URL", requireNotNull(imageData).startsWith("data:image/jpeg;base64,"))
        assertTrue("Substantive spoken request must be retained", requireNotNull(retainedPrompt).contains("전방 표지판 글씨 읽어 주세요"))
        val jpeg = Base64.decode(requireNotNull(imageData).substringAfter(','), Base64.DEFAULT)
        assertTrue("Captured JPEG bytes must be nonempty", jpeg.isNotEmpty())
        assertTrue("JPEG start marker missing", jpeg.size > 3 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull("Attached bytes must decode as a real JPEG image", decoded)
        assertTrue("Decoded capture must have dimensions", requireNotNull(decoded).width > 0 && decoded.height > 0)
        decoded.recycle()
    }

    private class CapturingImageTransport : ChatGptTransport {
        val requestLatch = CountDownLatch(1)
        val requestBody = AtomicReference<String?>()

        override fun execute(request: ChatGptHttpRequest, cancellationToken: GeminiCancellationToken?): ChatGptHttpResponse =
            ChatGptHttpResponse(
                200,
                """{"models":[{"slug":"gpt-6.1-sol","display_name":"Test model","visibility":"list"}]}""",
            )

        override fun executeStream(
            request: ChatGptHttpRequest,
            cancellationToken: GeminiCancellationToken?,
            onDelta: ((String) -> Unit)?,
        ): ChatGptStreamResponse {
            requestBody.set(String(request.bodyBytes, Charsets.UTF_8))
            requestLatch.countDown()
            return ChatGptStreamResponse(200, SseParseOutcome.Completed("기기 카메라 이미지 응답"))
        }
    }
}
