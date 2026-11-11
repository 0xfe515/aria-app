package com.ariateam.aria.navigation

/**
 * Validates and sanitizes destination queries for external navigation deep links.
 */
object DestinationValidator {
    private val RESERVED_KEYWORDS = setOf(
        "길안내", "길안내해", "길안내해줘", "길안내해줘요", "길안내해주세요",
        "길찾기", "길찾기해", "길찾기해줘", "길찾기해줘요", "길찾기해주세요",
        "길찾아", "길찾아줘", "길찾아줘요", "길찾아주세요",
        "안내", "안내해", "안내해줘", "안내해줘요", "안내해주세요", "안내해줄래",
        "도보안내", "도보안내해줘", "도보로안내", "도보로안내해줘",
        "지도", "목적지", "도착지", "어디", "그쪽", "거기", "여기",
        "가자", "가줘", "가줘요", "가주세요", "가고싶어", "가고싶어요",
        "이동", "검색",
        "찾아줘", "찾아봐", "찾아봐줘", "찾아주세요",
        "알려줘", "알려줘요", "알려주세요",
        "가는길", "가는길알려줘", "가는방법", "가는방법알려줘", "가는법", "가는법알려줘",
        "경로", "경로찾아줘",
        "데려다줘", "데려다줘요", "데려다주세요",
        "모셔다줘", "모셔다줘요", "모셔다주세요",
        "어떻게가", "어떻게가요", "어떻게가줘", "어떻게가주세요",
        "걸어가", "걸어가줘", "걸어가요", "걸어갈래",
        "까지", "으로", "에서", "에", "로",
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
