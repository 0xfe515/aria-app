package com.ariateam.aria.speech

import java.text.Normalizer

/** Bounded pronunciation and common ASR spelling aliases for the Aria wake name. */
internal class KoreanWakePhraseMatcher {
    fun containsWakePhrase(text: String): Boolean = afterWakePhrase(text) != null

    fun afterWakePhrase(text: String): String? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val match = WAKE_PATTERN.find(normalized) ?: return null
        return normalized.substring(match.range.last + 1)
            .trimStart { it.isWhitespace() || !it.isLetterOrDigit() }
            .trimEnd { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }
    }

    /** Prefer the highest-ranked candidate containing a recognized wake prefix. */
    fun selectWakeAfterPhrase(candidates: List<String>): String? = candidates.asSequence()
        .take(VoiceLimits.MAX_RECOGNITION_CANDIDATES)
        .mapNotNull(::afterWakePhrase)
        .firstOrNull()

    companion object {
        internal val CANONICAL_WAKE_PHRASES = listOf(
            "헤이 아리아", "헤이 아리야", "해이 아리아", "해이 아리야", "에이 아리아", "에이 아리야",
            "Hey Aria", "Hey Ariya", "Hey 아리아", "Hey 아리야", "헤이 Aria", "헤이 Ariya",
            "헤이 알리아", "헤이 알리야", "아리아", "아리야", "알리아", "알리야", "아리", "알리",
        )
        private val NAME_VARIANTS = listOf(
            "아리아", "아리야", "알리아", "알리야", "아리", "알리",
            "aria", "ariya", "ariah", "alia", "aliya", "ari",
        )
        private val PREFIX_VARIANTS = listOf("헤이", "해이", "헤", "에이", "hey", "hai", "hay")
        private const val SEPARATOR = "[\\s\\p{P}\\p{Z}\\p{Cf}]*"
        private val WAKE_PATTERN: Regex = buildList {
            PREFIX_VARIANTS.forEach { prefix ->
                NAME_VARIANTS.forEach { name -> add(prefix + name) }
            }
            addAll(listOf("헤뤼이아", "헤뤼이야", "헤이알리아", "헤이알리야", "해이알리아", "해이알리야"))
            addAll(NAME_VARIANTS)
        }.distinct().sortedByDescending(String::length).joinToString("|") { literal ->
            literal.map { Regex.escape(it.toString()) }.joinToString(SEPARATOR)
        }.let { body -> Regex("^$SEPARATOR(?:$body)", RegexOption.IGNORE_CASE) }
    }
}
