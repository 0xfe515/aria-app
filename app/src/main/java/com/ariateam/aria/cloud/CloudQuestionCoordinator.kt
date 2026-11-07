package com.ariateam.aria.cloud

import android.graphics.Bitmap
import android.util.Log
import com.ariateam.aria.cloud.auth.ChatGptAuthRepository
import com.ariateam.aria.cloud.auth.ChatGptAuthStatus
import com.ariateam.aria.cloud.chatgpt.ChatGptClient
import com.ariateam.aria.vision.VisionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class CloudQuestionCoordinator(
    private val authRepository: ChatGptAuthRepository,
    private val chatGptClient: ChatGptClient = ChatGptClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val frameCapturer: ((Bitmap?) -> Unit) -> Unit = VisionSession::captureSingleFrame,
    private val onAuthInvalidated: ((Long) -> Unit)? = null,
) : Closeable, AutoCloseable {

    private val coordinatorJob = SupervisorJob()
    private val scope = CoroutineScope(coordinatorJob + mainDispatcher)
    private val disposed = AtomicBoolean(false)

    private val lock = Any()
    private var activeRequest: ActiveRequest? = null

    val isDisposed: Boolean
        get() = disposed.get()

    init {
        // Observe session generation and status changes: cancel in-flight requests immediately on logout or account switch
        scope.launch {
            authRepository.sessionState.collect { state ->
                val cancelledQId: Long? = synchronized(lock) {
                    val active = activeRequest ?: return@collect
                    if (state.sessionGeneration != active.sessionGeneration ||
                        (active.startedWithAuth && state.status != ChatGptAuthStatus.AUTHENTICATED)
                    ) {
                        logD(TAG, "Cancelling active request ${active.questionId} due to auth state change (gen=${state.sessionGeneration}, status=${state.status})")
                        val qId = active.questionId
                        cancelActiveRequestLocked()
                        qId
                    } else {
                        null
                    }
                }
                if (cancelledQId != null) {
                    try {
                        onAuthInvalidated?.invoke(cancelledQId)
                    } catch (t: Throwable) {
                        logW(TAG, "Error in onAuthInvalidated callback: ${t.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private class ActiveRequest(
        val questionId: Long,
        val cancellationToken: GeminiCancellationToken,
        val sessionGeneration: Long,
        val startedWithAuth: Boolean,
        var job: Job? = null,
        var isFramePending: Boolean = false,
        var capturedBitmap: Bitmap? = null,
    )

    fun askTextQuestion(
        questionId: Long,
        prompt: String,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (disposed.get()) {
            logW(TAG, "askTextQuestion ignored: coordinator is disposed")
            return
        }

        val currentStatus = authRepository.sessionState.value.status
        val currentGen = authRepository.sessionState.value.sessionGeneration
        val request = synchronized(lock) {
            if (disposed.get()) return
            cancelActiveRequestLocked()
            val req = ActiveRequest(
                questionId = questionId,
                cancellationToken = GeminiCancellationToken(),
                sessionGeneration = currentGen,
                startedWithAuth = currentStatus == ChatGptAuthStatus.AUTHENTICATED,
            )
            activeRequest = req
            req
        }

        val job = scope.launch {
            val token = try {
                withContext(ioDispatcher) {
                    if (request.cancellationToken.isCancelled || isDisposed) return@withContext null
                    authRepository.validAccessToken()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logW(TAG, "Error obtaining access token: ${t.javaClass.simpleName}")
                val shouldNotify = synchronized(lock) {
                    if (!disposed.get() && !request.cancellationToken.isCancelled &&
                        activeRequest === request && authRepository.sessionState.value.sessionGeneration == request.sessionGeneration
                    ) {
                        activeRequest = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldNotify) {
                    onError("로그인 상태를 확인할 수 없어요. 네트워크 연결을 확인해 주세요.")
                }
                return@launch
            }

            if (token.isNullOrBlank()) {
                val shouldNotify = synchronized(lock) {
                    if (!disposed.get() && !request.cancellationToken.isCancelled &&
                        activeRequest === request && authRepository.sessionState.value.sessionGeneration == request.sessionGeneration
                    ) {
                        activeRequest = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldNotify) {
                    val errMessage = resolveUnauthenticatedMessage(authRepository.sessionState.value.status)
                    onError(errMessage)
                }
                return@launch
            }

            val result = withContext(ioDispatcher) {
                if (request.cancellationToken.isCancelled || isDisposed ||
                    authRepository.sessionState.value.sessionGeneration != request.sessionGeneration
                ) {
                    GeminiResult.Error(
                        kind = GeminiErrorKind.CANCELLED,
                        detail = "Cancelled or session changed before execution",
                        userMessageKorean = "질문이 취소되었어요.",
                    )
                } else {
                    chatGptClient.generateContent(
                        prompt = prompt,
                        imageBase64 = null,
                        accessToken = token,
                        cancellationToken = request.cancellationToken,
                    )
                }
            }

            val shouldDeliver = synchronized(lock) {
                if (disposed.get() || request.cancellationToken.isCancelled ||
                    activeRequest !== request || authRepository.sessionState.value.sessionGeneration != request.sessionGeneration
                ) {
                    logD(TAG, "Ignoring stale or cancelled text question result (id=$questionId)")
                    false
                } else {
                    activeRequest = null
                    true
                }
            }

            if (shouldDeliver) {
                when (result) {
                    is GeminiResult.Success -> onResponse(result.text)
                    is GeminiResult.Error -> {
                        if (result.kind != GeminiErrorKind.CANCELLED) {
                            onError(result.userMessageKorean)
                        }
                    }
                }
            }
        }

        synchronized(lock) {
            if (activeRequest === request && !request.cancellationToken.isCancelled) {
                request.job = job
            } else {
                job.cancel()
            }
        }
    }

    fun askPhotoQuestion(
        questionId: Long,
        prompt: String,
        onResponse: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (disposed.get()) {
            logW(TAG, "askPhotoQuestion ignored: coordinator is disposed")
            return
        }

        val currentStatus = authRepository.sessionState.value.status
        val currentGen = authRepository.sessionState.value.sessionGeneration
        val request = synchronized(lock) {
            if (disposed.get()) return
            cancelActiveRequestLocked()
            val req = ActiveRequest(
                questionId = questionId,
                cancellationToken = GeminiCancellationToken(),
                sessionGeneration = currentGen,
                startedWithAuth = currentStatus == ChatGptAuthStatus.AUTHENTICATED,
                isFramePending = false,
            )
            activeRequest = req
            req
        }

        // Obtain token BEFORE camera frame capture: no camera capture when not authenticated
        val authJob = scope.launch {
            val token = try {
                withContext(ioDispatcher) {
                    if (request.cancellationToken.isCancelled || isDisposed) return@withContext null
                    authRepository.validAccessToken()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logW(TAG, "Error obtaining access token for photo request: ${t.javaClass.simpleName}")
                val shouldNotify = synchronized(lock) {
                    if (!disposed.get() && !request.cancellationToken.isCancelled &&
                        activeRequest === request && authRepository.sessionState.value.sessionGeneration == request.sessionGeneration
                    ) {
                        request.isFramePending = false
                        safeRecycle(request.capturedBitmap)
                        request.capturedBitmap = null
                        activeRequest = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldNotify) {
                    onError("로그인 상태를 확인할 수 없어요. 네트워크 연결을 확인해 주세요.")
                }
                return@launch
            }

            if (token.isNullOrBlank()) {
                val shouldNotify = synchronized(lock) {
                    if (!disposed.get() && !request.cancellationToken.isCancelled &&
                        activeRequest === request && authRepository.sessionState.value.sessionGeneration == request.sessionGeneration
                    ) {
                        activeRequest = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldNotify) {
                    val errMessage = resolveUnauthenticatedMessage(authRepository.sessionState.value.status)
                    onError(errMessage)
                }
                return@launch
            }

            val shouldCaptureFrame = synchronized(lock) {
                if (disposed.get() || request.cancellationToken.isCancelled ||
                    activeRequest !== request || authRepository.sessionState.value.sessionGeneration != request.sessionGeneration
                ) {
                    false
                } else {
                    request.isFramePending = true
                    true
                }
            }
            if (!shouldCaptureFrame) return@launch

            // Explicit photo capture only after successful authentication
            withContext(mainDispatcher) {
                frameCapturer { bitmap ->
                    val shouldProceed = synchronized(lock) {
                        request.isFramePending = false
                        if (disposed.get() || request.cancellationToken.isCancelled ||
                            activeRequest !== request || authRepository.sessionState.value.sessionGeneration != request.sessionGeneration
                        ) {
                            logD(TAG, "Recycling frame from cancelled or superseded photo question (id=$questionId)")
                            safeRecycle(bitmap)
                            false
                        } else {
                            request.capturedBitmap = bitmap
                            true
                        }
                    }
                    if (!shouldProceed) return@frameCapturer

                    if (bitmap == null) {
                        val shouldNotify = synchronized(lock) {
                            if (!disposed.get() && !request.cancellationToken.isCancelled &&
                                activeRequest === request && authRepository.sessionState.value.sessionGeneration == request.sessionGeneration
                            ) {
                                activeRequest = null
                                true
                            } else {
                                false
                            }
                        }
                        if (shouldNotify) {
                            onError("카메라가 실행 중이지 않아 사진을 찍을 수 없어요. 카메라를 켠 후 다시 시도해 주세요.")
                        }
                        return@frameCapturer
                    }

                    val processJob = try {
                        scope.launch {
                            val workingBitmap = synchronized(lock) {
                                if (disposed.get() || request.cancellationToken.isCancelled ||
                                    activeRequest !== request || authRepository.sessionState.value.sessionGeneration != request.sessionGeneration
                                ) {
                                    null
                                } else {
                                    val b = request.capturedBitmap
                                    request.capturedBitmap = null
                                    b
                                }
                            }

                            if (workingBitmap == null) {
                                logD(TAG, "Working bitmap unavailable or request cancelled before coroutine start (id=$questionId)")
                                safeRecycle(bitmap)
                                return@launch
                            }

                            val result = try {
                                withContext(ioDispatcher) {
                                    val base64 = try {
                                        CloudImageProcessor.compressBitmapToBase64(workingBitmap)
                                    } finally {
                                        safeRecycle(workingBitmap)
                                    }

                                    if (base64 == null) {
                                        GeminiResult.Error(
                                            kind = GeminiErrorKind.PAYLOAD_TOO_LARGE,
                                            detail = "Failed to encode photo to base64",
                                            userMessageKorean = "사진 데이터를 처리하지 못했어요. 다시 시도해 주세요.",
                                        )
                                    } else if (request.cancellationToken.isCancelled ||
                                        authRepository.sessionState.value.sessionGeneration != request.sessionGeneration
                                    ) {
                                        GeminiResult.Error(
                                            kind = GeminiErrorKind.CANCELLED,
                                            detail = "Cancelled or session changed during image encoding",
                                            userMessageKorean = "질문이 취소되었어요.",
                                        )
                                    } else {
                                        chatGptClient.generateContent(
                                            prompt = prompt,
                                            imageBase64 = base64,
                                            accessToken = token,
                                            cancellationToken = request.cancellationToken,
                                        )
                                    }
                                }
                            } finally {
                                safeRecycle(workingBitmap)
                            }

                            val shouldDeliver = synchronized(lock) {
                                if (disposed.get() || request.cancellationToken.isCancelled ||
                                    activeRequest !== request || authRepository.sessionState.value.sessionGeneration != request.sessionGeneration
                                ) {
                                    logD(TAG, "Ignoring stale or cancelled photo question result (id=$questionId)")
                                    false
                                } else {
                                    activeRequest = null
                                    true
                                }
                            }

                            if (shouldDeliver) {
                                when (result) {
                                    is GeminiResult.Success -> onResponse(result.text)
                                    is GeminiResult.Error -> {
                                        if (result.kind != GeminiErrorKind.CANCELLED) {
                                            onError(result.userMessageKorean)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        synchronized(lock) {
                            safeRecycle(request.capturedBitmap)
                            request.capturedBitmap = null
                        }
                        return@frameCapturer
                    }

                    processJob.invokeOnCompletion { cause ->
                        if (cause != null) {
                            synchronized(lock) {
                                safeRecycle(request.capturedBitmap)
                                request.capturedBitmap = null
                            }
                        }
                    }

                    synchronized(lock) {
                        if (activeRequest === request && !request.cancellationToken.isCancelled && !disposed.get()) {
                            request.job = processJob
                        } else {
                            processJob.cancel()
                            safeRecycle(request.capturedBitmap)
                            request.capturedBitmap = null
                        }
                    }
                }
            }
        }

        synchronized(lock) {
            if (activeRequest === request && !request.cancellationToken.isCancelled) {
                request.job = authJob
            } else {
                authJob.cancel()
            }
        }
    }

    fun cancel(questionId: Long? = null) {
        synchronized(lock) {
            val req = activeRequest ?: return
            if (questionId == null || questionId == 0L || req.questionId == questionId) {
                cancelActiveRequestLocked()
            }
        }
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed.compareAndSet(false, true)) {
                cancelActiveRequestLocked()
                coordinatorJob.cancel()
            }
        }
    }

    override fun close() {
        dispose()
    }

    private fun cancelActiveRequestLocked() {
        val req = activeRequest ?: return
        req.cancellationToken.cancel()
        req.job?.cancel()
        req.isFramePending = false
        safeRecycle(req.capturedBitmap)
        req.capturedBitmap = null
        activeRequest = null
    }

    companion object {
        private const val TAG = "CloudQuestionCoord"

        fun resolveUnauthenticatedMessage(status: ChatGptAuthStatus): String {
            return when (status) {
                ChatGptAuthStatus.PLAN_USE_NOT_ENABLED ->
                    "ChatGPT 플랜 사용 권한이 필요해요. 설정의 계정 메뉴에서 권한을 확인해 주세요."
                ChatGptAuthStatus.REFRESH_FAILED_RECONNECT ->
                    "ChatGPT 계정 연결이 만료되었어요. 설정의 계정 메뉴에서 다시 로그인해 주세요."
                else ->
                    "ChatGPT 로그인이 필요해요. 설정의 계정 메뉴에서 로그인해 주세요."
            }
        }

        private fun safeRecycle(bitmap: Bitmap?) {
            try {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (_: Throwable) {}
        }

        private fun logD(tag: String, msg: String) {
            try {
                Log.d(tag, msg)
            } catch (_: Throwable) {}
        }

        private fun logW(tag: String, msg: String) {
            try {
                Log.w(tag, msg)
            } catch (_: Throwable) {}
        }
    }
}
