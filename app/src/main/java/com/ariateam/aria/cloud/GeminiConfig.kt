package com.ariateam.aria.cloud

/**
 * Configuration constants and limits for Google Gemini API integration.
 *
 * Consults official Google Gemini REST API specifications:
 * - Default Model: gemini-2.5-flash (multimodal low-latency).
 * - Supported Active Models: gemini-2.5-flash, gemini-3.5-flash, gemini-3.8-flash.
 *   Note: Older models such as gemini-1.5-flash and gemini-2.0-flash have reached end-of-life / shutdown
 *   and are not supported or configured as fallbacks.
 * - Endpoint: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * - Auth: x-goog-api-key header (avoids API key in URL logs).
 */
object GeminiConfig {
    const val DEFAULT_MODEL = "gemini-2.5-flash"
    val SUPPORTED_MODELS: List<String> = listOf(
        "gemini-2.5-flash",
        "gemini-3.5-flash",
        "gemini-3.8-flash",
    )

    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val HEADER_API_KEY = "x-goog-api-key"

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000

    const val MAX_PROMPT_CHARS = 500
    const val MAX_RESPONSE_CHARS = 1_000
    const val MAX_IMAGE_DIMENSION = 1024
    const val JPEG_QUALITY = 80
    const val MAX_IMAGE_BYTES = 512 * 1024 // 512 KB

    const val SYSTEM_INSTRUCTION =
        "시각 장애인을 위한 음성 비서 Aria입니다. 질문에 대해 간결하고 명확한 한국어 1~3문장 구어로 답변해 주세요."
}
