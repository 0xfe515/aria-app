package com.ariateam.aria.cloud.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class AuthHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccessful: Boolean get() = statusCode in 200..299

    override fun toString(): String {
        return "AuthHttpResponse(statusCode=$statusCode, bodyBytes=${body.length})"
    }
}

interface ChatGptAuthHttpTransport {
    suspend fun postForm(
        url: String,
        formParams: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): AuthHttpResponse

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): AuthHttpResponse
}

class DefaultChatGptAuthHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxResponseBytes: Int = 262_144, // 256 KB
) : ChatGptAuthHttpTransport {

    override suspend fun postForm(
        url: String,
        formParams: Map<String, String>,
        headers: Map<String, String>,
    ): AuthHttpResponse = withContext(Dispatchers.IO) {
        val targetUrl = URL(url)
        validateAuthority(targetUrl)

        val connection = (targetUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            val encodedBody = formParams.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }

            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(encodedBody)
                writer.flush()
            }

            val statusCode = connection.responseCode
            val inputStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = inputStream?.use { stream ->
                readBoundedBody(stream, maxResponseBytes)
            } ?: ""

            AuthHttpResponse(
                statusCode = statusCode,
                body = body,
                headers = connection.headerFields ?: emptyMap(),
            )
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun getJson(
        url: String,
        headers: Map<String, String>,
    ): AuthHttpResponse = withContext(Dispatchers.IO) {
        val targetUrl = URL(url)
        validateAuthority(targetUrl)

        val connection = (targetUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            doInput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            val statusCode = connection.responseCode
            val inputStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = inputStream?.use { stream ->
                readBoundedBody(stream, maxResponseBytes)
            } ?: ""

            AuthHttpResponse(
                statusCode = statusCode,
                body = body,
                headers = connection.headerFields ?: emptyMap(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedBody(inputStream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream()
        var totalRead = 0
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > maxBytes) {
                throw IllegalStateException("Response body exceeded maximum allowed limit of $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }

    private fun validateAuthority(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("HTTPS protocol required for OpenAI authentication endpoints: $url")
        }
        val host = url.host.lowercase()
        if (host != ChatGptAuthConstants.AUTHORITY_HOST) {
            throw SecurityException("Untrusted authority host '$host'. Pinned authority is '${ChatGptAuthConstants.AUTHORITY_HOST}'")
        }
        if (url.port != -1 && url.port != 443) {
            throw SecurityException("Non-standard HTTPS port rejected: ${url.port}")
        }
        if (url.userInfo != null) {
            throw SecurityException("UserInfo component forbidden in auth URL")
        }
        if (url.ref != null) {
            throw SecurityException("Fragment component forbidden in auth URL")
        }
    }
}
