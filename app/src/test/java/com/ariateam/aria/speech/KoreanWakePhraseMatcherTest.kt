package com.ariateam.aria.speech

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanWakePhraseMatcherTest {
    private val matcher = KoreanWakePhraseMatcher()

    @Test fun canonicalEnglishMixedAndObservedPronunciationsRemainSupported() {
        listOf(
            "헤이 아리아", "헤이 아리야", "헤이아리아", "헤이아리야",
            "Hey Aria", "hey aria", "Hey 아리아", "헤이 Aria",
            "헤뤼이아", "헤뤼이야", "헤이알리아", "헤이알리야",
            "해이아리아", "해이알리야", "에이 알리아",
        ).forEach { assertTrue("$it should match", matcher.containsWakePhrase(it)) }
    }

    @Test fun punctuationSpacingNfkcAndCompactCommandsAreExtracted() {
        assertEquals("설정 열어", matcher.afterWakePhrase("해이, 아리야! 설정 열어"))
        assertEquals("홈으로 가", matcher.afterWakePhrase("에이 알리아 홈으로 가"))
        assertEquals("설정열어", matcher.afterWakePhrase("헤이아리아설정열어"))
        assertEquals("소리 켜", matcher.afterWakePhrase("Hey 아리아, 소리 켜"))
        assertEquals("설정 열어", matcher.afterWakePhrase(Normalizer.normalize("헤이 아리아 설정 열어", Normalizer.Form.NFD)))
        assertEquals("설정 열어", matcher.afterWakePhrase("Ｈｅｙ　Ａｒｉａ 설정 열어"))
        assertEquals("", matcher.afterWakePhrase("헤이 아리아!  \u200B"))
    }

    @Test fun standaloneFullAndShortNamesAreAcceptedButOneSyllableIsNot() {
        listOf("아리아", "아리야", "알리아", "알리야", "아리", "알리").forEach {
            assertEquals(it, "", matcher.afterWakePhrase(it))
        }
        listOf("아", "알", "리", "헤", "헤이", "설정 열어", "오늘 날씨 어때", "방금 회의했어").forEach {
            assertNull(it, matcher.afterWakePhrase(it))
        }
    }

    @Test fun nBestUsesFirstWakeCandidateAndDoesNotRequireCommandAgreement() {
        assertEquals("설정 열어", matcher.selectWakeAfterPhrase(listOf("오늘 날씨", "아리아 설정 열어")))
        assertEquals("는 누구야", matcher.selectWakeAfterPhrase(listOf("알리아는 누구야", "헤이 아리아 홈으로 가")))
        assertNull(matcher.selectWakeAfterPhrase(listOf("오늘 날씨", "설정 열어")))
    }

    @Test fun nBestCandidateCountIsBounded() {
        val candidates = List(VoiceLimits.MAX_RECOGNITION_CANDIDATES) { "오늘 날씨 $it" } + "아리아 설정 열어"
        assertNull(matcher.selectWakeAfterPhrase(candidates))
        val wakeAtLastAllowedIndex =
            List(VoiceLimits.MAX_RECOGNITION_CANDIDATES - 1) { "오늘 날씨 $it" } + "알리 설정 열어"
        assertEquals("설정 열어", matcher.selectWakeAfterPhrase(wakeAtLastAllowedIndex))
    }
}
