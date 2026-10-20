package com.ariateam.aria.speech

import java.text.Normalizer

/** Only official phrases at the beginning of a recognition result. No fuzzy ASR aliases. */
internal class KoreanWakePhraseMatcher {
    fun containsWakePhrase(text: String): Boolean = afterWakePhrase(text) != null

    fun afterWakePhrase(text: String): String? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val match = PHRASE_PATTERN.find(normalized) ?: return null
        return normalized.substring(match.range.last + 1)
            .trimStart { it.isWhitespace() || !it.isLetterOrDigit() }
    }

    companion object {
        private const val SEPARATOR = "[\\s\\p{P}\\p{Z}]*"
        private val PHRASE_PATTERN = Regex(
            "^$SEPARATOR" + listOf("헤이아리아", "헤이아리야").joinToString("|", "(?:", ")") { phrase ->
                phrase.map { Regex.escape(it.toString()) }.joinToString(SEPARATOR)
            },
        )
    }
}
