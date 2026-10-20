package com.ariateam.aria.speech

import java.text.Normalizer

/**
 * Matches full Korean wake phrases at the beginning of a recognition result.
 *
 * Recognizes canonical "헤이 아리아" / "헤이 아리야" and narrowly justified full-utterance
 * phonetic variants (such as the observed ASR decoding "헤뤼이아") only when followed by a safe
 * boundary. N-best alternatives can confirm wake identity only when the top candidate is a
 * wake-prefix fragment and agrees on any trailing command text.
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

    private fun afterCanonicalPhrase(text: String): String? =
        extractAfterMatch(text, CANONICAL_PATTERN)

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

    companion object {
        internal val CANONICAL_WAKE_PHRASES = listOf("헤이 아리아", "헤이 아리야")
        private val COMPACT_CANONICAL_PHRASES = listOf("헤이아리아", "헤이아리야")
        private val COMPACT_PHONETIC_VARIANTS = listOf("헤뤼이아", "헤뤼이야", "해이아리아", "해이아리야")
        // Longer fragments first so the longest prefix fragment is consumed before checking command agreement.
        private val COMPACT_WAKE_FRAGMENTS = listOf(
            "헤이아리", "해이아리", "헤뤼이", "헤이아", "해이아",
            "아리아", "아리야", "헤이", "해이", "헤뤼", "헤", "해",
        )
        private const val SEPARATOR = "[\\s\\p{P}\\p{Z}\\p{Cf}]*"
        private const val REQUIRED_SEPARATOR = "[\\s\\p{P}\\p{Z}\\p{Cf}]+"

        private val SEPARATOR_REGEX = Regex(REQUIRED_SEPARATOR)
        private val CANONICAL_PATTERN = buildPrefixPattern(COMPACT_CANONICAL_PHRASES, requireBoundary = false)
        private val PHONETIC_VARIANT_PATTERN = buildPrefixPattern(COMPACT_PHONETIC_VARIANTS, requireBoundary = true)
        private val WAKE_FRAGMENT_PATTERN = buildPrefixPattern(COMPACT_WAKE_FRAGMENTS, requireBoundary = true)
        private val LEADING_PARTICLE_PATTERN = Regex(
            "^(?:는|은|가|이|를|을|도|만|의|라고|란)(?:$|$REQUIRED_SEPARATOR)",
        )

        private fun buildPrefixPattern(phrases: List<String>, requireBoundary: Boolean): Regex {
            val boundary = if (requireBoundary) "(?=$|$REQUIRED_SEPARATOR)" else ""
            return Regex(
                "^$SEPARATOR" + phrases.joinToString("|", "(?:", ")") { phrase ->
                    phrase.map { Regex.escape(it.toString()) }.joinToString(SEPARATOR)
                } + boundary,
            )
        }
    }
}
