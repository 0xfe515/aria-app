package com.ariateam.aria.cloud

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class GeminiErrorKind {
    NO_KEY,
    AUTH_FAILED,
    RATE_LIMIT,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    SERVER_ERROR,
    CANCELLED,
    EMPTY_RESPONSE,
    CAMERA_UNAVAILABLE,
    PAYLOAD_TOO_LARGE,
    MODEL_UNAVAILABLE,
}

sealed interface GeminiResult {
    data class Success(val text: String) : GeminiResult
    data class Error(
        val kind: GeminiErrorKind,
        val detail: String,
        val userMessageKorean: String,
    ) : GeminiResult
}

/**
 * Thread-safe cancellation signal for in-flight Gemini requests.
 * Allows transport implementations to attach active abort callbacks (e.g. HttpURLConnection.disconnect())
 * so that blocking network I/O is aborted immediately when cancelled from any thread.
 */
class GeminiCancellationToken {
    @Volatile
    private var isCancelledInternal = false
    private val lock = Any()
    private val callbacks = mutableListOf<CancellationRegistration>()

    val isCancelled: Boolean
        get() = isCancelledInternal

    fun cancel() {
        val toExecute = synchronized(lock) {
            if (isCancelledInternal) return
            isCancelledInternal = true
            val list = callbacks.toList()
            callbacks.clear()
            list
        }
        for (callback in toExecute) {
            callback.invoke()
        }
    }

    /**
     * Registers a callback to be executed immediately if already cancelled,
     * or upon cancel(). Execution is guaranteed to be at-most-once under any concurrency.
     * Returns a cleanup handle to unregister the callback upon completion.
     */
    fun onCancel(callback: () -> Unit): () -> Unit {
        val registration = CancellationRegistration(callback)
        val shouldExecuteImmediately = synchronized(lock) {
            if (isCancelledInternal) {
                true
            } else {
                callbacks.add(registration)
                false
            }
        }
        if (shouldExecuteImmediately) {
            registration.invoke()
            return {}
        }
        return {
            synchronized(lock) {
                callbacks.remove(registration)
            }
        }
    }

    private class CancellationRegistration(private val action: () -> Unit) {
        private val executed = AtomicBoolean(false)

        fun invoke() {
            if (executed.compareAndSet(false, true)) {
                try {
                    action()
                } catch (t: Throwable) {
                    try {
                        Log.w(TAG, "Error executing cancellation callback: ${t.message}")
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    companion object {
        private const val TAG = "GeminiCancelToken"
    }
}

data class GeminiHttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
    val connectTimeoutMs: Int = GeminiConfig.CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = GeminiConfig.READ_TIMEOUT_MS,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GeminiHttpRequest
        if (url != other.url) return false
        if (method != other.method) return false
        if (headers != other.headers) return false
        if (!bodyBytes.contentEquals(other.bodyBytes)) return false
        if (connectTimeoutMs != other.connectTimeoutMs) return false
        if (readTimeoutMs != other.readTimeoutMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        result = 31 * result + connectTimeoutMs
        result = 31 * result + readTimeoutMs
        return result
    }

    override fun toString(): String {
        val safeHeaders = headers.mapValues { (k, v) ->
            if (k.equals(GeminiConfig.HEADER_API_KEY, ignoreCase = true)) "[REDACTED_KEY]" else v
        }
        return "GeminiHttpRequest(url=$url, method=$method, headers=$safeHeaders, bodyBytesLength=${bodyBytes.size})"
    }
}

data class GeminiHttpResponse(
    val statusCode: Int,
    val body: String,
    val errorMessage: String? = null,
) {
    override fun toString(): String {
        val safeErrorMessage = errorMessage?.replace(Regex("""AIzaSy[A-Za-z0-9_-]{20,}"""), "[REDACTED_KEY]")
        return "GeminiHttpResponse(statusCode=$statusCode, bodyLength=${body.length}, errorMessage=$safeErrorMessage)"
    }
}
