package com.ariateam.aria.command

/**
 * Evaluates whether an unparsed Korean voice utterance should be rejected locally
 * (as a cancellation, negation, or incomplete local command intent)
 * or allowed to fall back to conversational cloud processing (ChatGPT).
 */
object LocalFallbackPolicy {

    private val CANCELLATION_EXACT = setOf(
        "취소", "취소해", "취소해줘", "취소하기", "취소할래",
        "그만", "그만해", "그만해줘", "그만들어", "그만들어줘",
        "멈춰", "멈춰줘", "중지", "중지해", "중지해줘", "종료", "종료해", "종료해줘",
        "안해", "안할래", "안할래줘", "싫어", "됐어", "아니", "아니요",
        "듣기중지", "듣기중지해", "음성인식꺼", "전체중지",
    )

    private val NEGATION_PATTERNS = listOf(
        "하지마", "하지말", "하지 마", "하지 말",
        "켜지마", "켜지 마", "끄지마", "끄지 마",
        "열지마", "열지 마", "가지마", "가지 마",
        "숨기지마", "숨기지 마", "보여주지마", "보여주지 마",
        "안내하지마", "안내하지 마", "길찾지마", "길찾지 마",
        "검색하지마", "검색하지 마",
        "안돼", "안 돼", "아냐",
    )

    private val INCOMPLETE_LOCAL_COMMAND_WORDS = setOf(
        "홈", "설정", "화면", "소리", "안내", "지도", "길안내", "내비", "내비게이션",
        "명령", "상태", "도움말",
    )

    fun isCancellation(text: String): Boolean {
        val normalized = RuleCommandInterpreter.normalize(text)
        return normalized in CANCELLATION_EXACT
    }

    fun isNegation(text: String): Boolean {
        val trimmed = text.trim()
        val normalized = RuleCommandInterpreter.normalize(text)
        return NEGATION_PATTERNS.any {
            trimmed.contains(it) || normalized.contains(RuleCommandInterpreter.normalize(it))
        }
    }

    fun isIncompleteLocalIntent(text: String): Boolean {
        val normalized = RuleCommandInterpreter.normalize(text)
        return normalized in INCOMPLETE_LOCAL_COMMAND_WORDS ||
            normalized in setOf("볼륨", "음량", "소리크기", "경고빈도", "알림빈도", "주의단계", "주의알림")
    }

    /**
     * Returns true if this utterance represents a cancellation, negation,
     * or incomplete local command intent that must NOT be sent to cloud LLM.
     */
    fun shouldRejectBeforeCloudFallback(text: String): Boolean {
        if (text.isBlank()) return true
        if (isCancellation(text)) return true
        if (isNegation(text)) return true
        if (isIncompleteLocalIntent(text)) return true
        return false
    }

    fun rejectionMessage(text: String): String {
        return when {
            isCancellation(text) -> "취소했어요."
            isNegation(text) -> "원하는 명령을 하나만 다시 말해 주세요."
            isIncompleteLocalIntent(text) -> "명령을 구체적으로 말씀해 주세요. 홈으로 가, 설정 열어, 앱 안내 음량이나 경고 빈도를 말할 수 있어요."
            else -> "명령을 이해하지 못했어요. 도움말이라고 말해 주세요."
        }
    }
}
