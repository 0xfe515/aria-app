package com.ariateam.aria.speech

import java.text.Normalizer
import java.util.Locale

/**
 * Matches full Korean wake phrases at the beginning of a recognition result.
 *
 * Recognizes canonical "헤이 아리아" / "헤이 아리야", verified English/mixed variants ("Hey Aria", "헤이 Aria",
 * "Hey 아리아"), and observed full-utterance phonetic variants ("헤뤼이아", "헤이알리아", "헤이알리야")
 * only when wake-position anchored and followed by a safe boundary or valid command.
 * N-best alternatives can confirm wake identity only when the top candidate is a wake-prefix fragment
 * and agrees on any trailing command text.
 */
internal class KoreanWakePhraseMatcher {
    fun containsWakePhrase(text: String): Boolean = afterWakePhrase(text) != null

    fun afterWakePhrase(text: String): String? =
        afterCanonicalPhrase(text) ?: afterPhoneticVariant(text)

    fun selectWakeAfterPhrase(candidates: List<String>): String? {
        val bounded = candidates.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(VoiceLimits.MAX_RECOGNITION_CANDIDATES)
            .toList()
        if (bounded.isEmpty()) return null

        val top = bounded.first()
        afterWakePhrase(top)?.let { return it }

        val topAfterFragment = afterWakeFragment(top) ?: return null
        val lowerCandidates = bounded.drop(1)
        val lowerAfter = lowerCandidates.firstNotNullOfOrNull { afterCanonicalPhrase(it) }
            ?: lowerCandidates.firstNotNullOfOrNull { afterPhoneticVariant(it) }
            ?: return null

        return if (normalizeCommandForAgreement(topAfterFragment) == normalizeCommandForAgreement(lowerAfter)) {
            lowerAfter
        } else {
            null
        }
    }

    private fun afterCanonicalPhrase(text: String): String? {
        val remainder = extractAfterMatch(text, KOREAN_CANONICAL_PATTERN)
            ?: extractAfterMatch(text, LATIN_CANONICAL_PATTERN)
            ?: return null
        if (remainder.isNotEmpty() && LEADING_PARTICLE_PATTERN.containsMatchIn(remainder)) {
            return null
        }
        return remainder
    }

    private fun afterPhoneticVariant(text: String): String? {
        val remainder = extractAfterMatch(text, PHONETIC_VARIANT_PATTERN) ?: return null
        if (remainder.isNotEmpty() && LEADING_PARTICLE_PATTERN.containsMatchIn(remainder)) {
            return null
        }
        return remainder
    }

    private fun afterWakeFragment(text: String): String? =
        extractAfterMatch(text, WAKE_FRAGMENT_PATTERN)

    private fun extractAfterMatch(text: String, pattern: Regex): String? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val match = pattern.find(normalized) ?: return null
        return normalized.substring(match.range.last + 1)
            .trimStart { it.isWhitespace() || !it.isLetterOrDigit() }
            .trimEnd { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }
    }

    private fun normalizeCommandForAgreement(command: String): String =
        Normalizer.normalize(command, Normalizer.Form.NFKC)
            .replace(SEPARATOR_REGEX, "")
            .lowercase(Locale.ROOT)

    companion object {
        internal val CANONICAL_WAKE_PHRASES = listOf(
            "헤이 아리아", "헤이 아리야",
            "Hey Aria", "hey aria", "헤이 Aria", "Hey 아리아",
            "헤이 알리아", "헤이 알리야",
        )
        private val COMPACT_KOREAN_CANONICAL_PHRASES = listOf(
            "헤이아리아", "헤이아리야",
        )
        private val COMPACT_LATIN_CANONICAL_PHRASES = listOf(
            "heyaria", "헤이aria", "hey아리아", "hey아리야",
        )
        private val COMPACT_PHONETIC_VARIANTS = listOf(
            "헤뤼이아", "헤뤼이야", "해이아리아", "해이아리야",
            "헤이알리아", "헤이알리야", "해이알리아", "해이알리야",
            "해이aria", "hey알리아", "hey알리야",
        )
        // Longer fragments first so the longest prefix fragment is consumed before checking command agreement.
        private val COMPACT_WAKE_FRAGMENTS = listOf(
            "헤이아리", "해이아리", "헤이알리", "해이알리", "헤뤼이", "헤이아", "해이아",
            "heyaria", "heyari", "알리아", "알리야", "아리아", "아리야",
            "헤이", "해이", "헤뤼", "aria", "hey", "헤", "해",
        )
        private const val SEPARATOR = "[\\s\\p{P}\\p{Z}\\p{Cf}]*"
        private const val REQUIRED_SEPARATOR = "[\\s\\p{P}\\p{Z}\\p{Cf}]+"
        private const val LATIN_BOUNDARY = "(?=$|\\b|$REQUIRED_SEPARATOR)"

        private val SEPARATOR_REGEX = Regex(REQUIRED_SEPARATOR)
        private val KOREAN_CANONICAL_PATTERN = buildPrefixPattern(COMPACT_KOREAN_CANONICAL_PHRASES, requireBoundary = false)
        private val LATIN_CANONICAL_PATTERN = buildPrefixPattern(COMPACT_LATIN_CANONICAL_PHRASES, requireBoundary = true)
        private val PHONETIC_VARIANT_PATTERN = buildPrefixPattern(COMPACT_PHONETIC_VARIANTS, requireBoundary = true)
        private val WAKE_FRAGMENT_PATTERN = buildPrefixPattern(COMPACT_WAKE_FRAGMENTS, requireBoundary = true)
        private val LEADING_PARTICLE_PATTERN = Regex(
            "^(?:는|은|가|이|를|을|도|만|의|라고|란)(?:$|$REQUIRED_SEPARATOR)",
        )

        private fun buildPrefixPattern(phrases: List<String>, requireBoundary: Boolean): Regex {
            val boundary = if (requireBoundary) LATIN_BOUNDARY else ""
            return Regex(
                "^$SEPARATOR" + phrases.joinToString("|", "(?:", ")") { phrase ->
                    phrase.map { Regex.escape(it.toString()) }.joinToString(SEPARATOR)
                } + boundary,
                RegexOption.IGNORE_CASE,
            )
        }
    }
}
