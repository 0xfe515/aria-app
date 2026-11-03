package com.ariateam.aria.navigation

import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCommandParserTest {

    private fun parse(text: String, context: CommandContext = CommandContext(), now: Long = 0) =
        NavigationCommandParser.parse(text, context, now)

    @Test
    fun explicitProviderAndDestinationResolvesImmediately() {
        val cases = mapOf(
            "네이버 지도로 강남역 안내해줘" to Pair(NavigationProvider.Naver, "강남역"),
            "네이버지도 강남역 가자" to Pair(NavigationProvider.Naver, "강남역"),
            "네이버로 서울역 길안내" to Pair(NavigationProvider.Naver, "서울역"),
            "카카오맵으로 판교역 길안내해줘" to Pair(NavigationProvider.Kakao, "판교역"),
            "카카오맵 홍대입구 가줘" to Pair(NavigationProvider.Kakao, "홍대입구"),
            "카카오 지도로 부산역 안내" to Pair(NavigationProvider.Kakao, "부산역"),
            "구글 지도로 서울시청 안내해줘" to Pair(NavigationProvider.Google, "서울시청"),
            "구글맵 인천공항 가자" to Pair(NavigationProvider.Google, "인천공항"),
            "구글로 여의도역 길안내" to Pair(NavigationProvider.Google, "여의도역"),
        )

        for ((utterance, expected) in cases) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance'", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals(expected.first, nav.provider)
            assertEquals(expected.second, nav.destination)
        }
    }

    @Test
    fun missingDestinationWithProviderRequestsClarification() {
        val result = parse("네이버 지도로 길안내해줘")
        assertTrue(result is Interpretation.Clarify)
        val clarify = result as Interpretation.Clarify
        assertTrue(clarify.question.contains("네이버 지도"))
        assertTrue(clarify.question.contains("목적지"))

        val target = NavigationCommandParser.extractPendingTarget("네이버 지도로 길안내해줘")
        assertEquals("nav_prov:naver", target)
    }

    @Test
    fun missingProviderWithDestinationRequestsProviderChoice() {
        val result = parse("강남역 길안내해줘")
        assertTrue(result is Interpretation.Clarify)
        val clarify = result as Interpretation.Clarify
        assertTrue(clarify.question.contains("네이버 지도"))
        assertTrue(clarify.question.contains("카카오맵"))
        assertTrue(clarify.question.contains("구글 지도"))

        val target = NavigationCommandParser.extractPendingTarget("강남역 길안내해줘")
        assertEquals("nav_dest:강남역", target)
    }

    @Test
    fun twoStepConversationPendingDestinationFollowedByProviderResolves() {
        val context = CommandContext("nav_dest:강남역", expiresAt = 30_000)
        val result = parse("카카오맵", context = context, now = 10_000)
        assertTrue(result is Interpretation.NavigateExternal)
        val nav = result as Interpretation.NavigateExternal
        assertEquals(NavigationProvider.Kakao, nav.provider)
        assertEquals("강남역", nav.destination)
    }

    @Test
    fun twoStepConversationPendingProviderFollowedByDestinationResolves() {
        val context = CommandContext("nav_prov:naver", expiresAt = 30_000)
        val result = parse("서울역", context = context, now = 10_000)
        assertTrue(result is Interpretation.NavigateExternal)
        val nav = result as Interpretation.NavigateExternal
        assertEquals(NavigationProvider.Naver, nav.provider)
        assertEquals("서울역", nav.destination)
    }

    @Test
    fun expiredContextFallsBackToNormalUtteranceParsing() {
        val context = CommandContext("nav_dest:강남역", expiresAt = 30_000)
        // At 30_001 the context has expired
        val result = parse("카카오맵", context = context, now = 30_001)
        // With no destination in "카카오맵", it asks for destination
        assertTrue(result is Interpretation.Clarify)
        val clarify = result as Interpretation.Clarify
        assertTrue(clarify.question.contains("목적지"))
    }

    @Test
    fun multipleConflictingProvidersClarifiesToChooseOne() {
        val result = parse("네이버 지도 카카오맵으로 강남역 가줘")
        assertTrue(result is Interpretation.Clarify)
        val clarify = result as Interpretation.Clarify
        assertTrue(clarify.question.contains("하나만 선택"))
    }

    @Test
    fun negationPhrasesInterceptBeforeNavigation() {
        val negations = listOf(
            "네이버 지도 길안내 하지마",
            "강남역 가지마",
            "카카오맵 열지마",
            "길찾지마",
        )
        for (utterance in negations) {
            val result = parse(utterance)
            assertTrue("Expected Clarify for negation '$utterance'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertEquals("원하는 명령을 하나만 다시 말해 주세요.", clarify.question)
        }
    }

    @Test
    fun compoundPhrasesInterceptBeforeNavigation() {
        val compounds = listOf(
            "네이버 지도로 강남역 가고 그리고 소리 꺼",
            "카카오맵으로 판교역 안내 다음에 홈으로 가",
        )
        for (utterance in compounds) {
            val result = parse(utterance)
            assertTrue("Expected Clarify for compound '$utterance'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertEquals("명령을 하나씩 말해 주세요.", clarify.question)
        }
    }

    @Test
    fun nonNavigationCommandsReturnNullToAllowAppCommandMatching() {
        val nonNav = listOf(
            "홈", "홈으로 가", "설정", "설정 열어", "소리 켜", "소리 꺼",
            "화면 숨겨", "화면 보여줘", "현재 상태", "도움말", "취소", "듣기 중지",
            "", "   ", "날씨 알려줘",
        )
        for (utterance in nonNav) {
            val result = parse(utterance)
            assertNull("Expected null for '$utterance'", result)
        }
    }

    @Test
    fun koreanRoadNamesPreserveEndingRo() {
        val roadCases = mapOf(
            "네이버 지도로 대학로 가자" to Pair(NavigationProvider.Naver, "대학로"),
            "카카오맵으로 을지로 안내해줘" to Pair(NavigationProvider.Kakao, "을지로"),
            "구글 지도로 충무로 길안내" to Pair(NavigationProvider.Google, "충무로"),
            "네이버 지도로 테헤란로 가줘" to Pair(NavigationProvider.Naver, "테헤란로"),
            "카카오맵으로 세종대로 안내" to Pair(NavigationProvider.Kakao, "세종대로"),
            "구글맵으로 마포대로 가자" to Pair(NavigationProvider.Google, "마포대로"),
            "네이버 지도로 종로 가자" to Pair(NavigationProvider.Naver, "종로"),
            "카카오 지도로 구로 가자" to Pair(NavigationProvider.Kakao, "구로"),
            "네이버 지도로 강변북로 안내해줘" to Pair(NavigationProvider.Naver, "강변북로"),
        )

        for ((utterance, expected) in roadCases) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance'", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals(expected.first, nav.provider)
            assertEquals(expected.second, nav.destination)
        }
    }

    @Test
    fun redundantParticleRoRoStripsOnlyOneRo() {
        val cases = mapOf(
            "네이버 지도로 대학로로 가자" to Pair(NavigationProvider.Naver, "대학로"),
            "카카오맵으로 을지로로 가줘" to Pair(NavigationProvider.Kakao, "을지로"),
        )

        for ((utterance, expected) in cases) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance'", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals(expected.first, nav.provider)
            assertEquals(expected.second, nav.destination)
        }
    }

    @Test
    fun searchAndWaySuffixesAreStripped() {
        val cases = mapOf(
            "카카오맵으로 강남역 찾아줘" to Pair(NavigationProvider.Kakao, "강남역"),
            "네이버 지도로 홍대입구 가는길 알려줘" to Pair(NavigationProvider.Naver, "홍대입구"),
            "구글맵으로 테헤란로 찾아봐" to Pair(NavigationProvider.Google, "테헤란로"),
        )

        for ((utterance, expected) in cases) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance'", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals(expected.first, nav.provider)
            assertEquals(expected.second, nav.destination)
        }
    }
}
