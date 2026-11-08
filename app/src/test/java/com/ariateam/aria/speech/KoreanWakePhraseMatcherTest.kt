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

    @Test fun englishAndMixedCanonicalVariantsMatchCaseInsensitively() {
        listOf(
            "Hey Aria",
            "hey aria",
            "HEY ARIA",
            "Hey aria",
            "  Hey Aria! ",
            "헤이 Aria",
            "헤이 aria",
            "Hey 아리아",
            "Hey 아리야",
            "hey아리아",
            "hey아리야",
            "해이 Aria",
            "해이 aria",
        ).forEach {
            assertTrue(it, matcher.containsWakePhrase(it))
            assertEquals(it, "", matcher.afterWakePhrase(it))
        }
        listOf(
            "Hey Aria 설정 열어" to "설정 열어",
            "hey aria, 오늘 날씨" to "오늘 날씨",
            "헤이 Aria 설정 열어" to "설정 열어",
            "Hey 아리아 홈으로 가" to "홈으로 가",
            "Hey 아리야 소리 켜" to "소리 켜",
            "hey아리아 설정 열어" to "설정 열어",
            "Hey Aria, what time is it?" to "what time is it?",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, matcher.afterWakePhrase(input))
        }
    }

    @Test fun observedFullPhoneticVariantsMatchAtStartWithSafeBoundaryAndExtractCommand() {
        listOf(
            "헤뤼이아",
            "헤뤼이야",
            "해이 아리아",
            "해이 아리야",
            "헤이 알리아",
            "헤이 알리야",
            "해이 알리아",
            "해이 알리야",
            "  헤뤼이아! ",
            "  헤이 알리아! ",
        ).forEach {
            assertTrue(it, matcher.containsWakePhrase(it))
            assertEquals(it, "", matcher.afterWakePhrase(it))
        }
        listOf(
            "헤뤼이아 설정 열어",
            "헤뤼이야, 설정 열어",
            "해이 아리아 설정 열어",
            "해이아리야, 설정 열어",
            "헤이 알리아 설정 열어",
            "헤이 알리야, 홈으로 가",
            "해이 알리아 설정 열어",
            "해이 알리야, 안내 소리 켜",
        ).forEach {
            assertEquals(it, "설정 열어".takeIf { expected -> it.contains("설정 열어") }
                ?: if (it.contains("홈으로 가")) "홈으로 가" else "안내 소리 켜", matcher.afterWakePhrase(it))
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
            "헤이 알리아는 누구야",
            "헤이 알리아가 뭐야",
            "헤이 알리야라고 했어",
            "헤이 아리아는 누구야",
            "헤이 아리아가 뭐야",
            "헤이 아리아라고 했어",
            "Hey Aria는 누구야",
            "Hey Aria라고 했어",
            "알리야 어디가",
            "우리 알리야는",
            "mariachi band",
            "variable declaration",
            "scenario analysis",
            "bavaria trip",
            "Hey Ariana",
            "Hey Arianna",
            "hey arias",
            "heyariax",
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
            "헤이 알리",
            "헤이 알리 설정 열어",
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
            "알리아",
            "알리야",
            "오늘 헤이 아리아라고 불렀어",
            "방금 헤뤼이아라고 인식됐어",
            "지금 헤뤼이아 설정 열어",
            "yesterday Hey Aria said",
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
            "",
            matcher.selectWakeAfterPhrase(listOf("헤이 알리", "헤이 알리아")),
        )
        assertEquals(
            "",
            matcher.selectWakeAfterPhrase(listOf("heyari", "Hey Aria")),
        )
        assertEquals(
            "설정 열어",
            matcher.selectWakeAfterPhrase(listOf("헤이 아리 설정 열어", "헤이 아리아 설정 열어")),
        )
        assertEquals(
            "설정 열어",
            matcher.selectWakeAfterPhrase(listOf("헤이 알리 설정 열어", "헤이 알리아 설정 열어")),
        )
        assertEquals(
            "설정 열어",
            matcher.selectWakeAfterPhrase(listOf("아리아 설정 열어", "헤이 아리아 설정 열어")),
        )
        assertEquals(
            "설정 열어",
            matcher.selectWakeAfterPhrase(listOf("알리아 설정 열어", "헤이 알리아 설정 열어")),
        )
        // Rank 0 already has a valid full wake match; lower canonical candidate must not override its command.
        assertEquals(
            "홈으로 가",
            matcher.selectWakeAfterPhrase(listOf("헤뤼이아 홈으로 가", "헤이 아리아 설정 열어")),
        )
        assertEquals(
            "홈으로 가",
            matcher.selectWakeAfterPhrase(listOf("Hey Aria 홈으로 가", "헤이 아리아 설정 열어")),
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

    @Test fun latinAndMixedAliasesRequireActualBoundaryAndRejectSuffixContinuations() {
        listOf(
            "Hey Ariana",
            "Hey Arianna",
            "hey arias",
            "heyariax",
            "Hey Ariana 설정 열어",
            "Hey Arianna 홈으로 가",
            "hey arias 소리 켜",
            "heyariax 설정 열어",
            "heyariana",
            "heyariannas",
        ).forEach {
            assertFalse("Suffix continuation $it must be rejected as false wake", matcher.containsWakePhrase(it))
            assertNull("afterWakePhrase($it) must be null", matcher.afterWakePhrase(it))
            assertNull("selectWakeAfterPhrase(listOf($it)) must be null", matcher.selectWakeAfterPhrase(listOf(it)))
        }

        // Canonical with valid boundaries or compact Korean without Latin suffix
        assertEquals("설정 열어", matcher.afterWakePhrase("Hey Aria 설정 열어"))
        assertEquals("오늘 날씨", matcher.afterWakePhrase("hey aria, 오늘 날씨"))
        assertEquals("설정열어", matcher.afterWakePhrase("헤이아리아설정열어"))
        assertEquals("설정 열어", matcher.afterWakePhrase("헤이 아리아 설정 열어"))
    }
}
