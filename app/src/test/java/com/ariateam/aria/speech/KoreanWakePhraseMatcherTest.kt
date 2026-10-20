package com.ariateam.aria.speech

import java.text.Normalizer
import org.junit.Assert.*
import org.junit.Test

class KoreanWakePhraseMatcherTest {
    private val matcher = KoreanWakePhraseMatcher()

    @Test fun officialPhrasesIgnoreSpacingPunctuationNormalizationAndFormatCharsAtTheStart() {
        listOf(
            "헤이 아리아 설정 열어",
            "헤이아리야, 설정 열어",
            "  헤이, 아리아! 설정 열어",
            "\uFEFF헤이\u200B 아리아 설정 열어",
            Normalizer.normalize("헤이 아리아 설정 열어", Normalizer.Form.NFD),
        ).forEach {
            assertEquals(it, "설정 열어", matcher.afterWakePhrase(it))
        }
        assertEquals("", matcher.afterWakePhrase("헤이 아리아"))
        assertEquals("", matcher.afterWakePhrase("헤이 아리아!  \u200B"))
    }

    @Test fun observedFullPhoneticVariantsMatchAtStartWithSafeBoundaryAndExtractCommand() {
        listOf(
            "헤뤼이아",
            "헤뤼이야",
            "해이 아리아",
            "해이 아리야",
            "  헤뤼이아! ",
        ).forEach {
            assertTrue(it, matcher.containsWakePhrase(it))
            assertEquals(it, "", matcher.afterWakePhrase(it))
        }
        listOf(
            "헤뤼이아 설정 열어",
            "헤뤼이야, 설정 열어",
            "해이 아리아 설정 열어",
            "해이아리야, 설정 열어",
        ).forEach {
            assertEquals(it, "설정 열어", matcher.afterWakePhrase(it))
        }
    }

    @Test fun phoneticAliasesEmbeddedInWordsOrFollowedByParticlesAreRejected() {
        listOf(
            "헤뤼이아파트",
            "헤뤼이아는 누구야",
            "헤뤼이아가 뭐야",
            "헤뤼이아를 불렀어",
            "헤뤼이아라고 했어",
            "헤뤼이아 는 누구야",
            "해이아리아파트",
            "해이 아리아는 누구야",
            "해이아리야라고 불렀어",
        ).forEach {
            assertFalse(it, matcher.containsWakePhrase(it))
            assertNull(it, matcher.afterWakePhrase(it))
            assertNull(it, matcher.selectWakeAfterPhrase(listOf(it)))
        }
    }

    @Test fun truncatedPrefixesStandaloneNamesAndMidSentenceMentionsAreRejected() {
        listOf(
            "헤",
            "헤 ",
            "헤!",
            "헤이",
            "헤이,",
            "헤이 아",
            "헤이 아리",
            "헤이 아리 설정 열어",
            "헤뤼",
            "헤뤼이",
            "해",
            "해이",
            "해이 아리",
            "히어리야",
            "헤아리다",
            "헤어지자",
            "아리아 설정 열어",
            "아리야",
            "오늘 헤이 아리아라고 불렀어",
            "방금 헤뤼이아라고 인식됐어",
            "지금 헤뤼이아 설정 열어",
            "",
            "   ",
        ).forEach {
            assertFalse(it, matcher.containsWakePhrase(it))
            assertNull(it, matcher.afterWakePhrase(it))
            assertNull(it, matcher.selectWakeAfterPhrase(listOf(it)))
        }
    }

    @Test fun nBestCandidateSelectionValidatesWakeFragmentsAndRequiresCommandAgreement() {
        assertEquals(
            "",
            matcher.selectWakeAfterPhrase(listOf("헤", "헤이 아리아")),
        )
        assertEquals(
            "",
            matcher.selectWakeAfterPhrase(listOf("헤이 아리", "헤이 아리아")),
        )
        assertEquals(
            "설정 열어",
            matcher.selectWakeAfterPhrase(listOf("헤이 아리 설정 열어", "헤이 아리아 설정 열어")),
        )
        assertEquals(
            "설정 열어",
            matcher.selectWakeAfterPhrase(listOf("아리아 설정 열어", "헤이 아리아 설정 열어")),
        )
        // Rank 0 already has a valid full wake match; lower canonical candidate must not override its command.
        assertEquals(
            "홈으로 가",
            matcher.selectWakeAfterPhrase(listOf("헤뤼이아 홈으로 가", "헤이 아리아 설정 열어")),
        )
        // Contradictory or unconfirmed command suffixes in lower candidates must be rejected.
        assertNull(
            matcher.selectWakeAfterPhrase(listOf("헤이 아리 홈으로 가", "헤이 아리아 설정 열어")),
        )
        assertNull(
            matcher.selectWakeAfterPhrase(listOf("헤이 아리", "헤이 아리아 설정 열어")),
        )
        assertNull(
            matcher.selectWakeAfterPhrase(listOf("설정 열어", "헤이 아리아 설정 열어")),
        )
        assertNull(
            matcher.selectWakeAfterPhrase(
                listOf("헤", "헤이", "헤이 아", "헤이 아리", "헤뤼이", "헤이 아리아"),
            ),
        )
    }

    @Test fun onlyTheFirstPrefixIsStripped() {
        assertEquals("헤이 아리아라고 말해", matcher.afterWakePhrase("헤이 아리아 헤이 아리아라고 말해"))
    }
}
