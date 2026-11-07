package com.ariateam.aria.cloud.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

sealed class LoopbackCallbackResult {
    data class Success(
        val code: String,
        val clientId: String,
        val scope: String?,
    ) : LoopbackCallbackResult() {
        override fun toString(): String {
            return "LoopbackCallbackResult.Success(clientId=$clientId, hasCode=${code.isNotEmpty()}, hasScope=${scope != null})"
        }
    }

    data class Error(
        val errorCode: String,
        val errorDescription: String?,
    ) : LoopbackCallbackResult() {
        override fun toString(): String {
            return "LoopbackCallbackResult.Error(errorCode=$errorCode, hasDescription=${!errorDescription.isNullOrEmpty()})"
        }
    }
}

interface ChatGptAuthLoopbackServer {
    fun start(
        attemptId: Long = 0L,
        expectedState: String,
        savedClientId: String?,
        onResult: (LoopbackCallbackResult) -> Unit,
    ): Int

    fun stop(attemptId: Long? = null)
}

class DefaultChatGptAuthLoopbackServer : ChatGptAuthLoopbackServer {

    private var activeServerSocket: ServerSocket? = null
    private var activeClientSocket: Socket? = null
    private var listenerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeAttemptId: Long = 0L

    @Synchronized
    override fun start(
        attemptId: Long,
        expectedState: String,
        savedClientId: String?,
        onResult: (LoopbackCallbackResult) -> Unit,
    ): Int {
        stopInternal()

        val currentAttempt = if (attemptId != 0L) attemptId else ++activeAttemptId
        activeAttemptId = currentAttempt

        val localAddress = InetAddress.getByName(ChatGptAuthConstants.LOOPBACK_HOST)
        val socket = ServerSocket(0, 1, localAddress)
        activeServerSocket = socket
        val port = socket.localPort

        val isResultDelivered = AtomicBoolean(false)

        listenerJob = scope.launch {
            try {
                while (!socket.isClosed && synchronized(this@DefaultChatGptAuthLoopbackServer) { activeAttemptId == currentAttempt }) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    synchronized(this@DefaultChatGptAuthLoopbackServer) {
                        if (activeAttemptId != currentAttempt) {
                            try { clientSocket.close() } catch (_: Throwable) {}
                            return@launch
                        }
                        activeClientSocket = clientSocket
                    }

                    clientSocket.soTimeout = 15_000
                    handleClientConnection(clientSocket, port, expectedState, savedClientId, isResultDelivered, onResult)
                    synchronized(this@DefaultChatGptAuthLoopbackServer) {
                        activeClientSocket = null
                    }
                    if (isResultDelivered.get()) {
                        break
                    }
                }
            } finally {
                synchronized(this@DefaultChatGptAuthLoopbackServer) {
                    if (activeAttemptId == currentAttempt) {
                        stopInternal()
                    }
                }
            }
        }

        return port
    }

    private fun handleClientConnection(
        clientSocket: Socket,
        port: Int,
        expectedState: String,
        savedClientId: String?,
        isResultDelivered: AtomicBoolean,
        onResult: (LoopbackCallbackResult) -> Unit,
    ) {
        try {
            clientSocket.use { client ->
                val inputStream = client.getInputStream()
                val writer = OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)

                var totalBytesRead = 0
                val maxTotalBytes = 16_384
                val maxLineBytes = 4_096

                // Read request line strictly bounded
                val requestLine = try {
                    readBoundedLine(inputStream, maxLineBytes)
                } catch (_: Throwable) {
                    writeResponse(writer, 400, "Request line too long")
                    return
                } ?: return

                totalBytesRead += requestLine.toByteArray(StandardCharsets.UTF_8).size

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val headerLine = try {
                        readBoundedLine(inputStream, maxLineBytes)
                    } catch (_: Throwable) {
                        writeResponse(writer, 400, "Header line too long")
                        return
                    } ?: break

                    if (headerLine.isEmpty()) break
                    totalBytesRead += headerLine.toByteArray(StandardCharsets.UTF_8).size
                    if (totalBytesRead > maxTotalBytes) {
                        writeResponse(writer, 400, "Request headers exceeded 16KB limit")
                        return
                    }

                    val colonIdx = headerLine.indexOf(':')
                    if (colonIdx > 0) {
                        val headerName = headerLine.substring(0, colonIdx).trim().lowercase()
                        val headerVal = headerLine.substring(colonIdx + 1).trim()
                        headers[headerName] = headerVal
                    }
                }

                // Verify request line: GET /auth/callback?... HTTP/1.1
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    writeResponse(writer, 405, "Method Not Allowed")
                    return
                }

                val fullPath = parts[1]
                val questionIdx = fullPath.indexOf('?')
                val path = if (questionIdx >= 0) fullPath.substring(0, questionIdx) else fullPath

                if (path != ChatGptAuthConstants.CALLBACK_PATH) {
                    writeResponse(writer, 404, "Not Found")
                    return
                }

                val hostHeader = headers["host"] ?: ""
                val expectedHost = "${ChatGptAuthConstants.LOOPBACK_HOST}:$port"
                if (hostHeader.isNotEmpty() && hostHeader != expectedHost && hostHeader != ChatGptAuthConstants.LOOPBACK_HOST) {
                    writeResponse(writer, 400, "Invalid Host header")
                    return
                }

                val query = if (questionIdx >= 0) fullPath.substring(questionIdx + 1) else ""
                val params = parseQueryParams(query)

                // Reject duplicate keys that must appear at most once
                if ((params["client_id"]?.size ?: 0) > 1 || (params["error"]?.size ?: 0) > 1 || (params["code"]?.size ?: 0) > 1) {
                    writeResponse(writer, 400, "Duplicate query parameters forbidden")
                    return
                }

                val stateList = params["state"] ?: emptyList()
                if (stateList.size != 1 || !ChatGptCrypto.timingSafeEquals(stateList[0], expectedState)) {
                    writeResponse(
                        writer,
                        400,
                        "Invalid sign-in state. Please return to the browser tab that initiated sign-in.",
                    )
                    return
                }

                if (params.containsKey("error")) {
                    val errorCode = params["error"]?.firstOrNull() ?: "access_denied"
                    val errorDesc = params["error_description"]?.firstOrNull()
                    writeHtmlResponse(
                        writer,
                        title = "Aria - Sign in Declined",
                        heading = "Sign in was not completed",
                        message = "ChatGPT sign-in was declined or cancelled. You can return to Aria.",
                    )
                    if (isResultDelivered.compareAndSet(false, true)) {
                        onResult(LoopbackCallbackResult.Error(errorCode, errorDesc))
                    }
                    return
                }

                val codeList = params["code"] ?: emptyList()
                val code = codeList.firstOrNull()
                val returnedClientId = params["client_id"]?.firstOrNull()
                val finalClientId = returnedClientId ?: savedClientId

                if (codeList.size != 1 || code.isNullOrBlank() ||
                    finalClientId.isNullOrBlank() ||
                    finalClientId == ChatGptAuthConstants.DYNAMIC_CLIENT_ID ||
                    (savedClientId != null && returnedClientId != null && savedClientId != returnedClientId)
                ) {
                    writeHtmlResponse(
                        writer,
                        title = "Aria - Registration Incomplete",
                        heading = "Registration incomplete",
                        message = "ChatGPT did not return a valid client registration or authorization code. Please try signing in again.",
                    )
                    if (isResultDelivered.compareAndSet(false, true)) {
                        onResult(
                            LoopbackCallbackResult.Error(
                                "registration_incomplete",
                                "Missing or invalid client_id / authorization code in callback",
                            ),
                        )
                    }
                    return
                }

                val scope = params["scope"]?.firstOrNull()
                writeHtmlResponse(
                    writer,
                    title = "Aria - Sign in Complete",
                    heading = "Sign in complete",
                    message = "Aria has received authorization. You can close this tab and return to Aria.",
                )

                if (isResultDelivered.compareAndSet(false, true)) {
                    onResult(LoopbackCallbackResult.Success(code = code, clientId = finalClientId, scope = scope))
                }
            }
        } catch (_: Throwable) {
            // Ignore socket disconnect / client close
        }
    }

    private fun readBoundedLine(inputStream: InputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream()
        var total = 0
        var prevCr = false

        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                return if (total == 0) null else out.toString("UTF-8")
            }
            if (b == '\n'.code) {
                return out.toString("UTF-8")
            }
            if (prevCr) {
                out.write('\r'.code)
                total++
                prevCr = false
            }
            if (b == '\r'.code) {
                prevCr = true
                continue
            }
            out.write(b)
            total++
            if (total > maxBytes) {
                throw IllegalStateException("Line length exceeded $maxBytes bytes")
            }
        }
    }

    private fun parseQueryParams(queryString: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        if (queryString.isEmpty()) return result
        val pairs = queryString.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            val key = if (idx > 0) URLDecoder.decode(pair.substring(0, idx), "UTF-8") else URLDecoder.decode(pair, "UTF-8")
            val value = if (idx > 0 && pair.length > idx + 1) URLDecoder.decode(pair.substring(idx + 1), "UTF-8") else ""
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun writeResponse(writer: OutputStreamWriter, code: Int, text: String) {
        val statusText = if (code == 400) "Bad Request" else if (code == 404) "Not Found" else if (code == 405) "Method Not Allowed" else "Error"
        writer.write("HTTP/1.1 $code $statusText\r\n")
        writer.write("Content-Type: text/plain; charset=utf-8\r\n")
        writer.write("Content-Length: ${text.toByteArray(StandardCharsets.UTF_8).size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(text)
        writer.flush()
    }

    private fun writeHtmlResponse(
        writer: OutputStreamWriter,
        title: String,
        heading: String,
        message: String,
    ) {
        val html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<title>$title</title>" +
            "<style>body{font-family:sans-serif;max-width:32rem;margin:15vh auto;padding:24px;text-align:center;color:#202123;}" +
            "h1{font-size:24px;margin-bottom:12px;}p{font-size:16px;color:#555;}</style></head>" +
            "<body><h1>$heading</h1><p>$message</p></body></html>"
        val bytes = html.toByteArray(StandardCharsets.UTF_8)

        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/html; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Referrer-Policy: no-referrer\r\n")
        writer.write("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(html)
        writer.flush()
    }

    @Synchronized
    override fun stop(attemptId: Long?) {
        if (attemptId != null && attemptId != 0L && activeAttemptId != attemptId) {
            // Attempt mismatch: do not stop a newer listener
            return
        }
        ++activeAttemptId
        stopInternal()
    }

    private fun stopInternal() {
        listenerJob?.cancel()
        listenerJob = null

        try {
            activeClientSocket?.close()
        } catch (_: Throwable) {}
        activeClientSocket = null

        try {
            activeServerSocket?.close()
        } catch (_: Throwable) {}
        activeServerSocket = null
    }
}
