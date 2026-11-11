package com.ariateam.aria.command

import com.ariateam.aria.navigation.NavigationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandInterpreterTest {
    private val interpreter = RuleCommandInterpreter()

    private fun interpret(text: String, context: CommandContext = CommandContext(), now: Long = 0) =
        interpreter.interpret(text, context, now)

    @Test
    fun koreaTransportationUniversityChungjuCampusFullInterpreterFlow() {
        val expectedDest = "한국교통대학교 충주캠퍼스"

        // Exact user phrase passes (preexisting baseline)
        val baseline = interpret("한국교통대학교 충주캠퍼스 까지 안내해줘")
        assertTrue("Expected NavigateExternal for baseline, got $baseline", baseline is Interpretation.NavigateExternal)
        val navBaseline = baseline as Interpretation.NavigateExternal
        assertEquals(expectedDest, navBaseline.destination)
        assertEquals(NavigationProvider.Naver, navBaseline.provider)

        // Real ASR spacing and punctuation variants that previously failed by not stripping action or particle
        val asrVariants = listOf(
            "한국교통대학교 충주캠퍼스 까지 안내해 줘",
            "한국교통대학교 충주캠퍼스 까지 안내 해 줘",
            "한국교통대학교 충주캠퍼스 까지 안내 해줘",
            "한국교통대학교 충주캠퍼스까지 안내해줘",
            "한국교통대학교 충주캠퍼스까지 안내해 줘",
            "한국교통대학교 충주캠퍼스까지 안내 해 줘",
            "한국교통대학교 충주캠퍼스까지 안내 해줘",
            "한국교통대학교 충주캠퍼스 안내해줘",
            "한국교통대학교 충주캠퍼스 안내해 줘",
            "한국교통대학교 충주캠퍼스 안내 해 줘",
            "한국교통대학교 충주캠퍼스 안내 해줘",
            "한국교통대학교 충주캠퍼스 까지 안내해 주세요",
            "한국교통대학교 충주캠퍼스 까지 안내해줘요",
            "한국교통대학교 충주캠퍼스 까지 안내해 줘요",
            "한국교통대학교 충주캠퍼스 까지 길안내해줘",
            "한국교통대학교 충주캠퍼스 까지 길안내 해 줘",
            "한국교통대학교 충주캠퍼스 까지 길 안내해 줘",
            "한국교통대학교 충주캠퍼스 까지 길 안내 해 줘",
            "한국교통대학교 충주캠퍼스까지 길 안내 해 줘",
            "한국교통대학교 충주캠퍼스 까지 안내해줘.",
            "한국교통대학교 충주캠퍼스 까지 안내해 줘!",
            "한국교통대학교 충주캠퍼스, 까지 안내해 줘",
            "  한국교통대학교 충주캠퍼스 까지 안내해 줘  ",
        )

        for (utterance in asrVariants) {
            val result = interpret(utterance)
            assertTrue("Expected NavigateExternal for '$utterance', got $result", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals("Destination must match expected without request language for '$utterance'", expectedDest, nav.destination)
            assertEquals(NavigationProvider.Naver, nav.provider)
        }
    }

    @Test
    fun explicitProviderRoutingThroughRuleCommandInterpreter() {
        val expectedDest = "한국교통대학교 충주캠퍼스"
        val providers = mapOf(
            "네이버 지도로 한국교통대학교 충주캠퍼스 까지 안내해 줘" to NavigationProvider.Naver,
            "카카오맵으로 한국교통대학교 충주캠퍼스 까지 안내해 줘" to NavigationProvider.Kakao,
            "구글 지도로 한국교통대학교 충주캠퍼스 까지 안내해 줘" to NavigationProvider.Google,
        )
        for ((utterance, provider) in providers) {
            val result = interpret(utterance)
            assertTrue("Expected NavigateExternal for '$utterance'", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals(expectedDest, nav.destination)
            assertEquals(provider, nav.provider)
        }
    }

    @Test
    fun negationAndCompoundSafetyPreservedThroughRuleCommandInterpreter() {
        val negations = listOf(
            "한국교통대학교 충주캠퍼스 까지 안내하지 마",
            "한국교통대학교 충주캠퍼스 가지 마",
            "한국교통대학교 충주캠퍼스 길찾지 마",
        )
        for (utterance in negations) {
            val result = interpret(utterance)
            assertTrue("Expected Clarify for negation '$utterance', got $result", result is Interpretation.Clarify)
            assertEquals("원하는 명령을 하나만 다시 말해 주세요.", (result as Interpretation.Clarify).question)
        }

        val compounds = listOf(
            "한국교통대학교 충주캠퍼스 까지 안내해 줘 그리고 홈으로 가",
            "한국교통대학교 충주캠퍼스까지 안내해줘 다음에 소리 꺼",
        )
        for (utterance in compounds) {
            val result = interpret(utterance)
            assertTrue("Expected Clarify for compound '$utterance', got $result", result is Interpretation.Clarify)
            assertEquals("명령을 하나씩 말해 주세요.", (result as Interpretation.Clarify).question)
        }
    }

    @Test
    fun poiNamesContainingActionWordsPreservedThroughRuleCommandInterpreter() {
        val poiCases = mapOf(
            "안내면사무소까지 안내해 줘" to "안내면사무소",
            "안내동까지 안내해 줘" to "안내동",
            "대학로까지 안내해 줘" to "대학로",
            "을지로까지 안내해 줘" to "을지로",
            "자동차박물관까지 안내해 줘" to "자동차박물관",
            "버스터미널까지 안내해 줘" to "버스터미널",
        )
        for ((utterance, expected) in poiCases) {
            val result = interpret(utterance)
            assertTrue("Expected NavigateExternal for '$utterance', got $result", result is Interpretation.NavigateExternal)
            assertEquals(expected, (result as Interpretation.NavigateExternal).destination)
        }
    }
}
