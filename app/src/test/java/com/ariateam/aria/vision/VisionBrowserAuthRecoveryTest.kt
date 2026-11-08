package com.ariateam.aria.vision

import android.content.Context
import android.graphics.Bitmap
import com.ariateam.aria.DebugDestination
import com.ariateam.aria.PendingCameraStartGate
import com.ariateam.aria.cloud.CloudQuestionCoordinator
import com.ariateam.aria.cloud.GeminiCancellationToken
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

/**
 * JVM regression tests for:
 * 1. VisionSurfaceReconciliationPolicy evaluating ON_STOP detach, in-flight binding coalescing, and ON_RESUME rebind.
 * 2. VisionCameraResumePolicy fencing explicit stop, admission, and permission loss across browser auth.
 * 3. Ongoing vision concurrency during ordinary GPT text questions (zero frame captures, uninterrupted pipeline).
 * 4. Photo questions capturing exactly one snapshot while vision pipeline continues concurrently.
 */
class VisionBrowserAuthRecoveryTest {

    private class FakeAuthRepository(
        var token: String? = "valid_test_token_456",
        var status: ChatGptAuthStatus = ChatGptAuthStatus.AUTHENTICATED,
        var generation: Long = 1L,
    ) : ChatGptAuthRepository {
        val stateFlow = MutableStateFlow(
            ChatGptAuthState(
                sessionGeneration = generation,
                status = status,
            )
        )
        override val sessionState: StateFlow<ChatGptAuthState> = stateFlow

        override suspend fun validAccessToken(): String? = token
        override fun startLogin(context: Context, targetClientId: String?, forceReconsent: Boolean) {}
        override fun cancelLogin() {}
        override suspend fun signOut() {}
        override suspend fun switchAccount(clientId: String) {}
        override fun getSavedAccounts(): List<ChatGptAccountSummary> = emptyList()
    }

    private class StubChatGptTransport(
        private val responseText: String = "응답 성공",
    ) : ChatGptTransport {
        val callCount = AtomicInteger(0)
        var lastCapturedPrompt: String? = null

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
            callCount.incrementAndGet()
            lastCapturedPrompt = request.bodyBytes.toString(Charsets.UTF_8)
            onDelta?.invoke(responseText)
            return ChatGptStreamResponse(200, SseParseOutcome.Completed(responseText))
        }
    }

    @Test
    fun surfaceReconciliationPolicy_evaluatesBrowserLifecycleAndCoalescedBinding() {
        // Step 1: Normal steady-state with active preview bound
        val steadyAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = true,
            hasSurfaceProvider = true,
            isSameProvider = true,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.NONE, steadyAction)

        // Step 2: ON_STOP occurs when launching system browser -> preview detached
        val onStopAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = true,
            hasSurfaceProvider = false, // updateSurfaceProvider(null)
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.TRIGGER_REBIND, onStopAction)

        // Step 3: Call updateSurfaceProvider while rebind is already in flight -> coalesced (NONE)
        val inFlightAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = true,
            isRebindInProgress = false,
            isSessionLive = true,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.NONE, inFlightAction)

        // Step 4: In-flight rebind finishes, reconciler checks target vs bound state -> detects discrepancy
        val postBindReconciledAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.TRIGGER_REBIND, postBindReconciledAction)

        // Step 5: Stale session ticket fencing -> rejected
        val staleAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = false,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.NONE, staleAction)
    }

    @Test
    fun surfaceReconciliationPolicy_failedBindsDoNotTriggerReconciliationAndObeyBackoffOwner() {
        // When bind fails (e.g. CameraState.ERROR_CAMERA_IN_USE), preview is NOT bound (isPreviewBound = false)
        // while the UI still desires a preview (hasSurfaceProvider = true).
        // With bindSucceeded = false, reconciliation MUST return NONE so that handleCameraFailure's
        // scheduleTransientRetry bounded backoff is strictly obeyed without triggering an immediate rebind.
        val failedBindAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
            bindSucceeded = false,
        )
        assertEquals(
            "Failed bind must NOT trigger surface reconciliation (must obey backoff owner)",
            VisionSurfaceReconciliationPolicy.Action.NONE,
            failedBindAction,
        )

        // Conversely, when bind succeeds with the same state discrepancy, reconciliation triggers rebind
        val successfulBindAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
            bindSucceeded = true,
        )
        assertEquals(
            "Successful bind with pending discrepancy must trigger rebind",
            VisionSurfaceReconciliationPolicy.Action.TRIGGER_REBIND,
            successfulBindAction,
        )
    }

    @Test
    fun surfaceReconciliationPolicy_fencesRetryResurrectionAfterExplicitStopOrStaleTicket() {
        // Session dead (e.g. user clicked Stop notification -> requiresExplicitRestart or closed)
        val deadSessionAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = false,
            bindSucceeded = true,
        )
        assertEquals(
            "Dead session must strictly fence rebind trigger",
            VisionSurfaceReconciliationPolicy.Action.NONE,
            deadSessionAction,
        )
    }

    @Test
    fun surfaceReconciliationPolicy_reconcilesOnlyWhenDifferenceActuallyPending() {
        // Both bound and wanted with same provider: NONE
        val matchingBoundAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = true,
            hasSurfaceProvider = true,
            isSameProvider = true,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
            bindSucceeded = true,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.NONE, matchingBoundAction)

        // Both unbound and not wanted: NONE
        val matchingUnboundAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = false,
            isSameProvider = true,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
            bindSucceeded = true,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.NONE, matchingUnboundAction)

        // Bound but new surface provider attached: UPDATE_BOUND_SURFACE
        val updateSurfaceAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = true,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = false,
            isSessionLive = true,
            bindSucceeded = true,
        )
        assertEquals(VisionSurfaceReconciliationPolicy.Action.UPDATE_BOUND_SURFACE, updateSurfaceAction)

        // In-flight rebind or binding flags prevent dual/nested enqueueing
        val nestedRebindAction = VisionSurfaceReconciliationPolicy.evaluate(
            isPreviewBound = false,
            hasSurfaceProvider = true,
            isSameProvider = false,
            isBindingInFlight = false,
            isRebindInProgress = true,
            isSessionLive = true,
            bindSucceeded = true,
        )
        assertEquals(
            "Nested rebind in progress must coalesce and not enqueue duplicate",
            VisionSurfaceReconciliationPolicy.Action.NONE,
            nestedRebindAction,
        )
    }

    @Test
    fun cameraResumePolicy_fencesExplicitStopAdmissionAndPermissionsAcrossBrowserReturn() {
        val eligibleHome = PendingCameraStartGate.isCameraEligibleScreen(
            selectedTab = 0,
            debugDestination = DebugDestination.None,
            showLicenses = false,
            hasBeenAdmitted = true,
            isStartupSettingsVisible = false,
            permissionLossDetected = false,
        )
        assertTrue("Home screen must be eligible for camera start", eligibleHome)

        // 1. Normal return from browser: admitted, permissions OK, no explicit Stop -> Can resume
        assertTrue(
            "Wanted camera must resume upon returning from browser",
            VisionCameraResumePolicy.canResumeOnForeground(
                isScreenEligible = eligibleHome,
                isDistanceModeForeground = false,
                hasCameraPermission = true,
                requiresExplicitRestart = false,
                hasBeenAdmitted = true,
            ),
        )

        // 2. User clicked Stop notification while browser was open -> Explicit stop latch set -> CANNOT resume
        assertFalse(
            "Explicit stop latch must strictly prevent camera resurrection on return",
            VisionCameraResumePolicy.canResumeOnForeground(
                isScreenEligible = eligibleHome,
                isDistanceModeForeground = false,
                hasCameraPermission = true,
                requiresExplicitRestart = true,
                hasBeenAdmitted = true,
            ),
        )

        // 3. User revoked CAMERA permission in OS settings while in browser -> CANNOT resume
        assertFalse(
            "Revoked camera permission must fence camera resume",
            VisionCameraResumePolicy.canResumeOnForeground(
                isScreenEligible = eligibleHome,
                isDistanceModeForeground = false,
                hasCameraPermission = false,
                requiresExplicitRestart = false,
                hasBeenAdmitted = true,
            ),
        )

        // 4. User not yet admitted through startup gate -> CANNOT resume
        assertFalse(
            "Unadmitted user must not resume camera",
            VisionCameraResumePolicy.canResumeOnForeground(
                isScreenEligible = eligibleHome,
                isDistanceModeForeground = false,
                hasCameraPermission = true,
                requiresExplicitRestart = false,
                hasBeenAdmitted = false,
            ),
        )

        // 5. Distance mode foregrounded -> CANNOT resume home camera
        assertFalse(
            "Distance mode foreground must fence home camera resume",
            VisionCameraResumePolicy.canResumeOnForeground(
                isScreenEligible = eligibleHome,
                isDistanceModeForeground = true,
                hasCameraPermission = true,
                requiresExplicitRestart = false,
                hasBeenAdmitted = true,
            ),
        )
    }

    @Test
    fun gptTextQuestion_runsConcurrentlyWithoutFrameCaptureOrCameraInterruption() {
        val authRepo = FakeAuthRepository()
        val transport = StubChatGptTransport("서울입니다.")
        val client = ChatGptClient(transport = transport)
        val frameCaptureInvoked = AtomicBoolean(false)

        val coordinator = CloudQuestionCoordinator(
            authRepository = authRepo,
            chatGptClient = client,
            frameCapturer = { callback ->
                frameCaptureInvoked.set(true)
                callback(null)
            },
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        val responseLatch = CountDownLatch(1)
        var deliveredResponse: String? = null
        var deliveredError: String? = null

        coordinator.askTextQuestion(
            questionId = 301L,
            prompt = "수도가 어디야?",
            onResponse = { response ->
                deliveredResponse = response
                responseLatch.countDown()
            },
            onError = { error ->
                deliveredError = error
                responseLatch.countDown()
            },
        )

        assertTrue("Response latch must count down", responseLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(deliveredResponse)
        assertNull(deliveredError)
        assertEquals("서울입니다.", deliveredResponse)

        // Ordinary text question must never trigger frame capture
        assertFalse("Text questions must never invoke frameCapturer", frameCaptureInvoked.get())
        assertEquals(1, transport.callCount.get())

        coordinator.dispose()
    }

    @Test
    fun photoQuestion_capturesSingleFrameWhileVisionContinues() {
        val authRepo = FakeAuthRepository()
        val transport = StubChatGptTransport("자전거가 보입니다.")
        val client = ChatGptClient(transport = transport)
        val frameCaptureCount = AtomicInteger(0)

        val coordinator = CloudQuestionCoordinator(
            authRepository = authRepo,
            chatGptClient = client,
            frameCapturer = { callback ->
                frameCaptureCount.incrementAndGet()
                callback(null)
            },
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )

        val responseLatch = CountDownLatch(1)
        var deliveredResponse: String? = null
        var deliveredError: String? = null

        coordinator.askPhotoQuestion(
            questionId = 402L,
            prompt = "뭐가 보여?",
            onResponse = { response ->
                deliveredResponse = response
                responseLatch.countDown()
            },
            onError = { error ->
                deliveredError = error
                responseLatch.countDown()
            },
        )

        assertTrue("Response latch must count down", responseLatch.await(5, TimeUnit.SECONDS))
        assertNull(deliveredResponse)
        assertNotNull("Null frame from frameCapturer reports actionable camera unavailable error", deliveredError)
        assertTrue(deliveredError!!.contains("카메라가 실행 중이지 않아"))

        // Exactly one frame snapshot capture attempted
        assertEquals(1, frameCaptureCount.get())

        coordinator.dispose()
    }
}
