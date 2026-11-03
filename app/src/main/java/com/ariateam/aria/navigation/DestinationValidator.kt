package com.ariateam.aria.navigation

/**
 * Validates and sanitizes destination queries for external navigation deep links.
 */
object DestinationValidator {
    private val RESERVED_KEYWORDS = setOf(
        "길안내", "길안내해줘", "길찾기", "길찾아줘", "안내", "안내해줘", "지도",
        "목적지", "도착지", "어디", "그쪽", "거기", "여기", "가자", "가줘", "이동", "검색",
        "찾아줘", "찾아봐", "찾아봐줘", "알려줘", "가는길", "가는방법",
    )

    fun sanitize(raw: String): String {
        return raw.trim()
            .replace(Regex("""[\r\n\t]"""), " ")
            .replace(Regex("""\s+"""), " ")
    }

    fun isValid(raw: String): Boolean {
        val cleaned = sanitize(raw)
        if (cleaned.length < 2 || cleaned.length > 100) return false
        val normalized = cleaned.replace(" ", "")
        if (normalized in RESERVED_KEYWORDS) return false
        return cleaned.any { it.isLetterOrDigit() }
    }
}
