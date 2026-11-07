package com.ariateam.aria.cloud.chatgpt

object ChatGptConfig {
    const val BASE_URL = "https://api.openai.com/v1"
    const val MODELS_URL = "$BASE_URL/models"
    const val RESPONSES_URL = "$BASE_URL/responses"

    const val DEFAULT_INSTRUCTIONS =
        "Aria 음성 비서로서 사용자의 질문에 친절하고 간결하게 한국어 구어로 2~3문장 이내로 핵심만 답변하세요."

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 25_000

    const val MAX_PROMPT_CHARS = 1000
    const val MAX_RESPONSE_CHARS = 1000

    const val MAX_EVENT_LINE_CHARS = 65_536
    const val MAX_TOTAL_STREAM_CHARS = 1_048_576

    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_ACCEPT = "Accept"

    const val MIME_APPLICATION_JSON = "application/json"
    const val MIME_EVENT_STREAM = "text/event-stream"
}
