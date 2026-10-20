package com.ariateam.aria.speech

import org.junit.Assert.*
import org.junit.Test

class KoreanWakePhraseMatcherTest {
    private val matcher = KoreanWakePhraseMatcher()

    @Test fun officialPhrasesIgnoreSpacingAndPunctuationAtTheStart() {
        listOf("헤이 아리아 설정 열어", "헤이아리야, 설정 열어", "  헤이, 아리아! 설정 열어").forEach {
            assertEquals(it, "설정 열어", matcher.afterWakePhrase(it))
        }
        assertEquals("", matcher.afterWakePhrase("헤이 아리아"))
    }

    @Test fun aliasesStandaloneNamesAndMidSentenceMentionsAreRejected() {
        listOf("히어리야", "아리아 설정 열어", "아리야", "오늘 헤이 아리아라고 불렀어", "헤이 아리", "").forEach {
            assertFalse(it, matcher.containsWakePhrase(it))
            assertNull(matcher.afterWakePhrase(it))
        }
    }

    @Test fun onlyTheFirstPrefixIsStripped() {
        assertEquals("헤이 아리아라고 말해", matcher.afterWakePhrase("헤이 아리아 헤이 아리아라고 말해"))
    }
}
