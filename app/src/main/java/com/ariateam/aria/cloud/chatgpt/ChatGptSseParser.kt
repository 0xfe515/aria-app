package com.ariateam.aria.cloud.chatgpt

import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader

sealed interface SseParseOutcome {
    data class Completed(val text: String) : SseParseOutcome
    data class Failed(val code: String?, val message: String?, val param: String?) : SseParseOutcome
    data class Incomplete(val reason: String?) : SseParseOutcome
    data class Refused(val reason: String?) : SseParseOutcome
    data class Interrupted(val detail: String) : SseParseOutcome
}

class ChatGptSseParser {

    private val textBuilder = StringBuilder()
    private var totalBytesRead = 0L

    val accumulatedText: String
        get() = textBuilder.toString()

    class LineTooLongException(message: String) : Exception(message)

    /**
     * Parses an SSE stream from a Reader line by line with strict line-length capping.
     * Invokes [onDelta] for each streaming text delta.
     * Returns immediately upon reaching a terminal event (Completed, Failed, Incomplete, Refused).
     */
    fun parseStream(
        reader: BufferedReader,
        isCancelled: () -> Boolean = { false },
        onDelta: ((String) -> Unit)? = null,
    ): SseParseOutcome {
        var currentEventType: String? = null
        val currentDataLines = mutableListOf<String>()

        try {
            while (true) {
                if (isCancelled()) {
                    return SseParseOutcome.Interrupted("Stream reading cancelled")
                }

                val line = readBoundedLine(reader, ChatGptConfig.MAX_EVENT_LINE_CHARS) ?: break
                totalBytesRead += line.length + 1
                if (totalBytesRead > ChatGptConfig.MAX_TOTAL_STREAM_CHARS) {
                    return SseParseOutcome.Interrupted("Stream exceeded maximum allowed length")
                }

                if (line.isEmpty()) {
                    // Blank line dispatches the accumulated event
                    if (currentDataLines.isNotEmpty()) {
                        val eventData = currentDataLines.joinToString("\n")
                        val outcome = processEvent(currentEventType, eventData, onDelta)
                        currentDataLines.clear()
                        currentEventType = null

                        if (outcome != null) {
                            return outcome
                        }
                    }
                } else if (line.startsWith(":")) {
                    // Comment line in SSE, ignore
                    continue
                } else if (line.startsWith("event:")) {
                    currentEventType = line.substring(6).trim()
                } else if (line.startsWith("data:")) {
                    var dataContent = line.substring(5)
                    if (dataContent.startsWith(" ")) {
                        dataContent = dataContent.substring(1)
                    }
                    currentDataLines.add(dataContent)
                } else if (line.startsWith("id:") || line.startsWith("retry:")) {
                    continue
                }
            }

            // Flush trailing event if present
            if (currentDataLines.isNotEmpty()) {
                val eventData = currentDataLines.joinToString("\n")
                val outcome = processEvent(currentEventType, eventData, onDelta)
                currentDataLines.clear()
                if (outcome != null) {
                    return outcome
                }
            }
        } catch (e: LineTooLongException) {
            return SseParseOutcome.Interrupted("SSE line length exceeded maximum limit: ${e.message}")
        } catch (e: Throwable) {
            if (isCancelled()) {
                return SseParseOutcome.Interrupted("Stream cancelled: ${e.message}")
            }
            return SseParseOutcome.Interrupted("Stream read error: ${e.message}")
        }

        if (isCancelled()) {
            return SseParseOutcome.Interrupted("Stream cancelled")
        }

        return SseParseOutcome.Interrupted("Stream ended without response.completed")
    }

    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (ch == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(ch.toChar())
            if (sb.length > maxChars) {
                throw LineTooLongException("Line exceeded $maxChars characters")
            }
        }
    }

    internal fun processEvent(
        explicitEventType: String?,
        data: String,
        onDelta: ((String) -> Unit)? = null,
    ): SseParseOutcome? {
        val trimmedData = data.trim()
        if (trimmedData.isEmpty() || trimmedData == "[DONE]") {
            return null
        }

        val json: JSONObject = try {
            validateStrictJsonObject(trimmedData)
            JSONObject(trimmedData)
        } catch (e: JSONException) {
            // Recognized events that are malformed fail closed
            if (explicitEventType != null && explicitEventType != "ping") {
                return SseParseOutcome.Interrupted("Malformed event payload for $explicitEventType: ${e.message}")
            }
            return null
        }

        val jsonType = json.optString("type", null)
        val effectiveType = explicitEventType ?: jsonType ?: return null

        return when (effectiveType) {
            "response.output_text.delta" -> {
                if (!json.has("delta")) {
                    return SseParseOutcome.Interrupted("Malformed response.output_text.delta: missing delta field")
                }
                val delta = json.optString("delta", "")
                if (delta.isNotEmpty()) {
                    if (textBuilder.length + delta.length > ChatGptConfig.MAX_RESPONSE_CHARS) {
                        val remaining = ChatGptConfig.MAX_RESPONSE_CHARS - textBuilder.length
                        if (remaining > 0) {
                            textBuilder.append(delta.take(remaining))
                        }
                    } else {
                        textBuilder.append(delta)
                    }
                    onDelta?.invoke(delta)
                }
                null
            }
            "response.completed" -> {
                val responseObj = json.optJSONObject("response")
                val refusal = responseObj?.optString("refusal", null)?.ifEmpty { null }
                    ?: json.optString("refusal", null)?.ifEmpty { null }

                if (refusal != null) {
                    return SseParseOutcome.Refused(refusal)
                }

                val status = responseObj?.optString("status", null)
                if (status == "refused") {
                    return SseParseOutcome.Refused("Refused by ChatGPT policy")
                }

                if (textBuilder.isEmpty()) {
                    val directOutputText = responseObj?.optString("output_text", null)?.ifEmpty { null }
                    if (directOutputText != null) {
                        textBuilder.append(directOutputText.take(ChatGptConfig.MAX_RESPONSE_CHARS))
                    } else if (responseObj != null) {
                        val outputArray = responseObj.optJSONArray("output")
                        if (outputArray != null) {
                            val extracted = StringBuilder()
                            for (i in 0 until outputArray.length()) {
                                val item = outputArray.optJSONObject(i) ?: continue
                                if (item.optString("type") == "message") {
                                    val contentArray = item.optJSONArray("content") ?: continue
                                    for (j in 0 until contentArray.length()) {
                                        val part = contentArray.optJSONObject(j) ?: continue
                                        if (part.optString("type") == "output_text") {
                                            extracted.append(part.optString("text"))
                                        }
                                    }
                                }
                            }
                            if (extracted.isNotEmpty()) {
                                textBuilder.append(extracted.toString().take(ChatGptConfig.MAX_RESPONSE_CHARS))
                            }
                        }
                    }
                }
                SseParseOutcome.Completed(textBuilder.toString())
            }
            "response.failed", "error" -> {
                val errorObj = json.optJSONObject("error")
                    ?: json.optJSONObject("response")?.optJSONObject("error")

                val code = errorObj?.optString("code", null)?.ifEmpty { null }
                    ?: json.optString("code", null)?.ifEmpty { null }
                    ?: json.optString("error", null)?.ifEmpty { null }

                val message = errorObj?.optString("message", null)?.ifEmpty { null }
                    ?: json.optString("message", null)?.ifEmpty { null }
                    ?: json.optString("detail", null)?.ifEmpty { null }

                val param = errorObj?.optString("param", null)?.ifEmpty { null }
                    ?: json.optString("param", null)?.ifEmpty { null }

                SseParseOutcome.Failed(code = code, message = message, param = param)
            }
            "response.incomplete" -> {
                val reason = json.optString("reason", null)?.ifEmpty { null }
                    ?: json.optJSONObject("response")?.optString("status_details", null)?.ifEmpty { null }
                    ?: json.optString("message", null)?.ifEmpty { null }

                SseParseOutcome.Incomplete(reason)
            }
            else -> {
                null
            }
        }
    }

    private fun validateStrictJsonObject(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            var inQuote = false
            var escape = false
            var firstColon = -1
            for (i in 1 until trimmed.length) {
                val c = trimmed[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inQuote = !inQuote
                    continue
                }
                if (!inQuote) {
                    if (c == ':') {
                        firstColon = i
                        break
                    }
                    if (c == '}' || c == '{') break
                }
            }
            if (firstColon != -1) {
                val keyCandidate = trimmed.substring(1, firstColon).trim()
                if (keyCandidate.isNotEmpty() && (!keyCandidate.startsWith("\"") || !keyCandidate.endsWith("\""))) {
                    throw JSONException("Malformed event payload: unquoted key '$keyCandidate'")
                }
            }
        }
    }
}
