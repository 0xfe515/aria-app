package com.ariateam.aria.cloud

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

interface GeminiTransport {
    fun execute(
        request: GeminiHttpRequest,
        cancellationToken: GeminiCancellationToken? = null,
    ): GeminiHttpResponse

    fun execute(
        request: GeminiHttpRequest,
        cancelFlag: AtomicBoolean,
    ): GeminiHttpResponse {
        val token = GeminiCancellationToken()
        if (cancelFlag.get()) {
            token.cancel()
        }
        return execute(request, token)
    }
}

class DefaultGeminiTransport : GeminiTransport {
    override fun execute(
        request: GeminiHttpRequest,
        cancellationToken: GeminiCancellationToken?,
    ): GeminiHttpResponse {
        if (cancellationToken?.isCancelled == true) {
            return GeminiHttpResponse(
                statusCode = 499,
                body = "",
                errorMessage = "Cancelled before connection",
            )
        }

        var connection: HttpURLConnection? = null
        var unregisterCancel: (() -> Unit)? = null

        return try {
            val url = URL(request.url)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = request.connectTimeoutMs
                readTimeout = request.readTimeoutMs
                requestMethod = request.method
                doInput = true
                doOutput = request.bodyBytes.isNotEmpty()
                instanceFollowRedirects = true
            }
            connection = conn

            // Actively disconnect the socket immediately when cancellation is signaled from another thread.
            unregisterCancel = cancellationToken?.onCancel {
                try {
                    conn.disconnect()
                } catch (_: Throwable) {}
            }

            if (cancellationToken?.isCancelled == true) {
                conn.disconnect()
                return GeminiHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled before write",
                )
            }

            for ((headerName, headerVal) in request.headers) {
                conn.setRequestProperty(headerName, headerVal)
            }

            if (request.bodyBytes.isNotEmpty()) {
                conn.outputStream.use { os ->
                    os.write(request.bodyBytes)
                    os.flush()
                }
            }

            if (cancellationToken?.isCancelled == true) {
                conn.disconnect()
                return GeminiHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled during write",
                )
            }

            val statusCode = conn.responseCode

            if (cancellationToken?.isCancelled == true) {
                conn.disconnect()
                return GeminiHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled before read",
                )
            }

            val inputStream: InputStream? = if (statusCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }

            val responseBody = inputStream?.use { stream ->
                readStreamLimited(stream, maxBytes = 256 * 1024, cancellationToken = cancellationToken)
            } ?: ""

            if (cancellationToken?.isCancelled == true) {
                return GeminiHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled during read",
                )
            }

            GeminiHttpResponse(
                statusCode = statusCode,
                body = responseBody,
            )
        } catch (e: SocketTimeoutException) {
            if (cancellationToken?.isCancelled == true) {
                GeminiHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled: socket disconnected",
                )
            } else {
                GeminiHttpResponse(
                    statusCode = 408,
                    body = "",
                    errorMessage = "Timeout: ${e.message}",
                )
            }
        } catch (e: UnknownHostException) {
            GeminiHttpResponse(
                statusCode = 503,
                body = "",
                errorMessage = "Network unavailable: ${e.message}",
            )
        } catch (e: Throwable) {
            if (cancellationToken?.isCancelled == true) {
                GeminiHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled: connection closed (${e.message})",
                )
            } else {
                GeminiHttpResponse(
                    statusCode = 500,
                    body = "",
                    errorMessage = "Connection error: ${e.message}",
                )
            }
        } finally {
            try {
                unregisterCancel?.invoke()
            } catch (_: Throwable) {}
            try {
                connection?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun readStreamLimited(
        stream: InputStream,
        maxBytes: Int,
        cancellationToken: GeminiCancellationToken?,
    ): String {
        val buffer = ByteArray(4096)
        val baos = ByteArrayOutputStream()
        var total = 0
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            if (cancellationToken?.isCancelled == true) break
            val toWrite = minOf(read, maxBytes - total)
            if (toWrite > 0) {
                baos.write(buffer, 0, toWrite)
                total += toWrite
            }
            if (total >= maxBytes) break
        }
        return baos.toString(Charsets.UTF_8.name())
    }
}
