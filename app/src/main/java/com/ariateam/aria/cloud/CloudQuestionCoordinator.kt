package com.ariateam.aria.cloud

import android.graphics.Bitmap
import android.util.Log
import com.ariateam.aria.vision.VisionSession
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
    private val keyStorage: AriaKeyStorage,
    private val geminiClient: GeminiClient = GeminiClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val frameCapturer: ((Bitmap?) -> Unit) -> Unit = VisionSession::captureSingleFrame,
) : Closeable, AutoCloseable {

    private val coordinatorJob = SupervisorJob()
    private val scope = CoroutineScope(coordinatorJob + mainDispatcher)
    private val disposed = AtomicBoolean(false)

    private val lock = Any()
    private var activeRequest: ActiveRequest? = null

    val isDisposed: Boolean
        get() = disposed.get()

    private class ActiveRequest(
        val questionId: Long,
        val cancellationToken: GeminiCancellationToken,
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

        val request = synchronized(lock) {
            if (disposed.get()) return
            cancelActiveRequestLocked()
            val req = ActiveRequest(
                questionId = questionId,
                cancellationToken = GeminiCancellationToken(),
            )
            activeRequest = req
            req
        }

        val apiKey = keyStorage.getApiKey()
        if (apiKey.isNullOrBlank()) {
            val shouldNotify = synchronized(lock) {
                if (!disposed.get() && !request.cancellationToken.isCancelled && activeRequest === request) {
                    activeRequest = null
                    true
                } else {
                    false
                }
            }
            if (shouldNotify) {
                onError("Gemini API 키가 설정되지 않았어요. 설정에서 API 키를 등록해 주세요.")
            }
            return
        }

        val job = scope.launch {
            val result = withContext(ioDispatcher) {
                geminiClient.generateContent(
                    prompt = prompt,
                    imageBase64 = null,
                    apiKey = apiKey,
                    cancellationToken = request.cancellationToken,
                )
            }

            synchronized(lock) {
                if (disposed.get() || request.cancellationToken.isCancelled || activeRequest !== request) {
                    logD(TAG, "Ignoring stale or cancelled text question result (id=$questionId)")
                    return@launch
                }
                activeRequest = null
            }

            when (result) {
                is GeminiResult.Success -> onResponse(result.text)
                is GeminiResult.Error -> {
                    if (result.kind != GeminiErrorKind.CANCELLED) {
                        onError(result.userMessageKorean)
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

        val request = synchronized(lock) {
            if (disposed.get()) return
            cancelActiveRequestLocked()
            val req = ActiveRequest(
                questionId = questionId,
                cancellationToken = GeminiCancellationToken(),
                isFramePending = true,
            )
            activeRequest = req
            req
        }

        val apiKey = keyStorage.getApiKey()
        if (apiKey.isNullOrBlank()) {
            val shouldNotify = synchronized(lock) {
                if (!disposed.get() && !request.cancellationToken.isCancelled && activeRequest === request) {
                    activeRequest = null
                    true
                } else {
                    false
                }
            }
            if (shouldNotify) {
                onError("Gemini API 키가 설정되지 않았어요. 설정에서 API 키를 등록해 주세요.")
            }
            return
        }

        frameCapturer { bitmap ->
            val shouldProceed = synchronized(lock) {
                request.isFramePending = false
                if (disposed.get() || request.cancellationToken.isCancelled || activeRequest !== request) {
                    logD(TAG, "Recycling frame from cancelled, disposed, or superseded photo question (id=$questionId)")
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
                    if (!disposed.get() && !request.cancellationToken.isCancelled && activeRequest === request) {
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

            val job = try {
                scope.launch {
                    val workingBitmap = synchronized(lock) {
                        if (disposed.get() || request.cancellationToken.isCancelled || activeRequest !== request) {
                            null
                        } else {
                            val b = request.capturedBitmap
                            request.capturedBitmap = null
                            b
                        }
                    }

                    if (workingBitmap == null) {
                        logD(TAG, "Working bitmap unavailable or request cancelled before coroutine start (id=$questionId)")
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
                            } else if (request.cancellationToken.isCancelled) {
                                GeminiResult.Error(
                                    kind = GeminiErrorKind.CANCELLED,
                                    detail = "Cancelled during image encoding",
                                    userMessageKorean = "질문이 취소되었어요.",
                                )
                            } else {
                                geminiClient.generateContent(
                                    prompt = prompt,
                                    imageBase64 = base64,
                                    apiKey = apiKey,
                                    cancellationToken = request.cancellationToken,
                                )
                            }
                        }
                    } finally {
                        safeRecycle(workingBitmap)
                    }

                    synchronized(lock) {
                        if (disposed.get() || request.cancellationToken.isCancelled || activeRequest !== request) {
                            logD(TAG, "Ignoring stale or cancelled photo question result (id=$questionId)")
                            return@launch
                        }
                        activeRequest = null
                    }

                    when (result) {
                        is GeminiResult.Success -> onResponse(result.text)
                        is GeminiResult.Error -> {
                            if (result.kind != GeminiErrorKind.CANCELLED) {
                                onError(result.userMessageKorean)
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

            job.invokeOnCompletion { cause ->
                if (cause != null) {
                    synchronized(lock) {
                        safeRecycle(request.capturedBitmap)
                        request.capturedBitmap = null
                    }
                }
            }

            synchronized(lock) {
                if (activeRequest === request && !request.cancellationToken.isCancelled && !disposed.get()) {
                    request.job = job
                } else {
                    job.cancel()
                    safeRecycle(request.capturedBitmap)
                    request.capturedBitmap = null
                }
            }
        }
    }

    fun cancel(questionId: Long? = null) {
        synchronized(lock) {
            val req = activeRequest ?: return
            if (questionId == null || req.questionId == questionId) {
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
