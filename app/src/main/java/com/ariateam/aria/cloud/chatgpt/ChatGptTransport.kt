package com.ariateam.aria.cloud.chatgpt

import com.ariateam.aria.cloud.GeminiCancellationToken
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class ChatGptStreamResponse(
    val statusCode: Int,
    val outcome: SseParseOutcome? = null,
    val errorMessage: String? = null,
    val rawErrorBody: String? = null,
)

interface ChatGptTransport {
    fun execute(
        request: ChatGptHttpRequest,
        cancellationToken: GeminiCancellationToken? = null,
    ): ChatGptHttpResponse

    fun executeStream(
        request: ChatGptHttpRequest,
        cancellationToken: GeminiCancellationToken? = null,
        onDelta: ((String) -> Unit)? = null,
    ): ChatGptStreamResponse
}

class DefaultChatGptTransport : ChatGptTransport {

    override fun execute(
        request: ChatGptHttpRequest,
        cancellationToken: GeminiCancellationToken?,
    ): ChatGptHttpResponse {
        if (cancellationToken?.isCancelled == true) {
            return ChatGptHttpResponse(
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
                instanceFollowRedirects = false
            }
            connection = conn

            unregisterCancel = cancellationToken?.onCancel {
                try {
                    conn.disconnect()
                } catch (_: Throwable) {}
            }

            if (cancellationToken?.isCancelled == true) {
                conn.disconnect()
                return ChatGptHttpResponse(
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
                return ChatGptHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled during write",
                )
            }

            val statusCode = conn.responseCode

            if (cancellationToken?.isCancelled == true) {
                conn.disconnect()
                return ChatGptHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled before read",
                )
            }

            val responseBody = if (statusCode in 200..299) {
                readStreamToString(conn.inputStream, cancellationToken)
            } else {
                val errorStream = conn.errorStream ?: conn.inputStream
                readStreamToString(errorStream, cancellationToken)
            }

            ChatGptHttpResponse(
                statusCode = statusCode,
                body = responseBody,
                headers = conn.headerFields.filterKeys { it != null },
            )
        } catch (e: SocketTimeoutException) {
            ChatGptHttpResponse(
                statusCode = 408,
                body = "",
                errorMessage = "Timeout: ${e.message}",
            )
        } catch (e: UnknownHostException) {
            ChatGptHttpResponse(
                statusCode = 503,
                body = "",
                errorMessage = "Network unavailable: ${e.message}",
            )
        } catch (e: Throwable) {
            if (cancellationToken?.isCancelled == true) {
                ChatGptHttpResponse(
                    statusCode = 499,
                    body = "",
                    errorMessage = "Cancelled: ${e.message}",
                )
            } else {
                ChatGptHttpResponse(
                    statusCode = 500,
                    body = "",
                    errorMessage = "Transport error: ${e.message}",
                )
            }
        } finally {
            unregisterCancel?.invoke()
            try {
                connection?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    override fun executeStream(
        request: ChatGptHttpRequest,
        cancellationToken: GeminiCancellationToken?,
        onDelta: ((String) -> Unit)?,
    ): ChatGptStreamResponse {
        if (cancellationToken?.isCancelled == true) {
            return ChatGptStreamResponse(
                statusCode = 499,
                outcome = SseParseOutcome.Interrupted("Cancelled before connection"),
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
                instanceFollowRedirects = false
            }
            connection = conn

            unregisterCancel = cancellationToken?.onCancel {
                try {
                    conn.disconnect()
                } catch (_: Throwable) {}
            }

            if (cancellationToken?.isCancelled == true) {
                conn.disconnect()
                return ChatGptStreamResponse(
                    statusCode = 499,
                    outcome = SseParseOutcome.Interrupted("Cancelled before write"),
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
                return ChatGptStreamResponse(
                    statusCode = 499,
                    outcome = SseParseOutcome.Interrupted("Cancelled during write"),
                    errorMessage = "Cancelled during write",
                )
            }

            val statusCode = conn.responseCode

            if (cancellationToken?.isCancelled == true) {
                conn.disconnect()
                return ChatGptStreamResponse(
                    statusCode = 499,
                    outcome = SseParseOutcome.Interrupted("Cancelled before read"),
                    errorMessage = "Cancelled before read",
                )
            }

            if (statusCode !in 200..299) {
                val errorStream = conn.errorStream ?: conn.inputStream
                val errorBody = readStreamToString(errorStream, cancellationToken)
                return ChatGptStreamResponse(
                    statusCode = statusCode,
                    rawErrorBody = errorBody,
                    errorMessage = "HTTP $statusCode",
                )
            }

            val parser = ChatGptSseParser()
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val outcome = reader.use { br ->
                parser.parseStream(
                    reader = br,
                    isCancelled = { cancellationToken?.isCancelled == true },
                    onDelta = onDelta,
                )
            }

            ChatGptStreamResponse(
                statusCode = statusCode,
                outcome = outcome,
            )
        } catch (e: SocketTimeoutException) {
            ChatGptStreamResponse(
                statusCode = 408,
                outcome = SseParseOutcome.Interrupted("Socket timeout"),
                errorMessage = "Timeout: ${e.message}",
            )
        } catch (e: UnknownHostException) {
            ChatGptStreamResponse(
                statusCode = 503,
                outcome = SseParseOutcome.Interrupted("Network unavailable"),
                errorMessage = "Network unavailable: ${e.message}",
            )
        } catch (e: Throwable) {
            if (cancellationToken?.isCancelled == true) {
                ChatGptStreamResponse(
                    statusCode = 499,
                    outcome = SseParseOutcome.Interrupted("Cancelled"),
                    errorMessage = "Cancelled: ${e.message}",
                )
            } else {
                ChatGptStreamResponse(
                    statusCode = 500,
                    outcome = SseParseOutcome.Interrupted("Transport error"),
                    errorMessage = "Transport error: ${e.message}",
                )
            }
        } finally {
            unregisterCancel?.invoke()
            try {
                connection?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun readStreamToString(
        stream: InputStream?,
        cancellationToken: GeminiCancellationToken?,
    ): String {
        if (stream == null) return ""
        val buffer = ByteArray(4096)
        val out = ByteArrayOutputStream()
        stream.use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (cancellationToken?.isCancelled == true) {
                    break
                }
                out.write(buffer, 0, bytesRead)
                if (out.size() > ChatGptConfig.MAX_TOTAL_STREAM_CHARS) {
                    break
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
