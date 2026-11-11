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
    fun genericMissingDestinationRemembersDefaultNaverProvider() {
        val result = parse("길안내해줘")
        assertTrue(result is Interpretation.Clarify)
        assertTrue((result as Interpretation.Clarify).question.contains("어디로 갈까요"))
        assertEquals("nav_prov:naver", NavigationCommandParser.extractPendingTarget("길안내해줘"))
        assertNull(NavigationCommandParser.extractPendingTarget("날씨 알려줘"))
        assertNull(NavigationCommandParser.extractPendingTarget("강남역"))
        assertNull(NavigationCommandParser.extractPendingTarget("안내해줘"))
        assertNull(NavigationCommandParser.extractPendingTarget("길안내해줘 그리고 홈으로 가"))
        assertNull(NavigationCommandParser.extractPendingTarget("길안내 하지마"))
        assertNull(NavigationCommandParser.extractPendingTarget("네이버 지도로 길안내 하지마"))
    }

    @Test
    fun genericDestinationDefaultsToNaver() {
        val result = parse("강남역 길안내해줘")
        assertTrue(result is Interpretation.NavigateExternal)
        assertEquals(NavigationProvider.Naver, (result as Interpretation.NavigateExternal).provider)

        val target = NavigationCommandParser.extractPendingTarget("강남역 길안내해줘")
        assertEquals("nav_dest:강남역", target)
    }

    @Test
    fun genericNavigationUsesPendingDestinationWithNaver() {
        val result = parse("길안내해줘", context = CommandContext("nav_dest:강남역", expiresAt = 30_000), now = 10_000)
        assertTrue(result is Interpretation.NavigateExternal)
        assertEquals(NavigationProvider.Naver, (result as Interpretation.NavigateExternal).provider)
        assertEquals("강남역", result.destination)
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
    fun expiredPendingProviderDoesNotRouteBareDestination() {
        val context = CommandContext("nav_prov:naver", expiresAt = 30_000)
        assertNull(parse("강남역", context = context, now = 30_001))
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

    @Test
    fun flexibleKoreanNavigationRequestsExtractExactDestination() {
        val cases = mapOf(
            "충주시청까지 안내해줘" to "충주시청",
            "길 안내해 주세요, 충주시청" to "충주시청",
            "길 안내 해 주세요 충주시청" to "충주시청",
            "충주시청 길 안내 해 줘" to "충주시청",
            "길안내 충주시청" to "충주시청",
            "충주시청 가고 싶어" to "충주시청",
            "충주시청으로 가고 싶어" to "충주시청",
            "서울로 안내해줘" to "서울",
            "충주시청 가는 길 좀 알려줘" to "충주시청",
            "걸어서 충주시청까지" to "충주시청",
            "  충주시청 까지 길안내 해주세요! " to "충주시청",
            "충주시청 안내해 줘" to "충주시청",
            "충주시청 안내 해 줘" to "충주시청",
            "충주시청 까지 안내해 줘" to "충주시청",
            "충주시청 까지 안내 해 줘" to "충주시청",
        )

        for ((utterance, destination) in cases) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance', got $result", result is Interpretation.NavigateExternal)
            assertEquals(destination, (result as Interpretation.NavigateExternal).destination)
            assertEquals(NavigationProvider.Naver, result.provider)
        }
    }

    @Test
    fun navigationKeepsRoadNamesAndDoesNotMatchPlaceInformationQuestions() {
        val cases = mapOf(
            "테헤란로까지 안내해줘" to "테헤란로",
            "을지로 가는 길 좀 알려줘" to "을지로",
            "퇴계로까지 안내해줘" to "퇴계로",
            "동호로까지 길안내" to "동호로",
            "안내동까지 길안내" to "안내동",
            "안내면사무소까지 안내해줘" to "안내면사무소",
        )
        for ((utterance, destination) in cases) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance'", result is Interpretation.NavigateExternal)
            assertEquals(destination, (result as Interpretation.NavigateExternal).destination)
        }

        assertNull(parse("충주시청에 대해 알려줘"))
    }

    @Test
    fun desireAndSingleHagoWorkflowAreNotMisreadAsCompoundCommands() {
        assertTrue(parse("충주시청 가고 싶어요") is Interpretation.NavigateExternal)
        assertTrue(parse("충주시청 가고 길안내 해줘") is Interpretation.NavigateExternal)
        assertTrue(parse("충주시청 가고 소리 꺼") is Interpretation.Clarify)
    }

    @Test
    fun naturalWalkingRequestsExtractTargetBeforeOrAfterRequest() {
        val cases = mapOf(
            "충주시청으로 데려다줘" to "충주시청",
            "충주시청까지 길 좀 찾아줘" to "충주시청",
            "충주시청 가는 방법 알려줘" to "충주시청",
            "충주시청 어떻게 가" to "충주시청",
            "충주시청까지 걸어갈래" to "충주시청",
            "충주시청까지 도보로 안내해 주세요" to "충주시청",
            "충주시청 가는 경로 찾아줘" to "충주시청",
            "길 좀 찾아줘 충주시청" to "충주시청",
            "안내면사무소까지 길 좀 찾아줘" to "안내면사무소",
            "백범로까지 도보로 안내해 주세요" to "백범로",
        )
        for ((utterance, expected) in cases) {
            val result = parse(utterance)
            assertTrue("Expected navigation for '$utterance', got $result", result is Interpretation.NavigateExternal)
            assertEquals(expected, (result as Interpretation.NavigateExternal).destination)
        }
    }

    @Test
    fun unsupportedTransportClarifiesAndInformationOrNegationDoesNotNavigate() {
        val unsupported = listOf(
            "자동차로 충주시청 가줘",
            "버스로 충주시청까지 안내해줘",
            "택시 타고 충주시청 가줘",
            "자전거로 충주시청 안내해줘",
            "대중교통으로 충주시청까지 안내해줘",
            "자동차를 타고 충주시청 가줘",
        )
        for (utterance in unsupported) {
            val result = parse(utterance)
            assertTrue("Expected transport clarification for '$utterance', got $result", result is Interpretation.Clarify)
            assertTrue((result as Interpretation.Clarify).question.contains("목적지와 함께 도보로"))
        }
        assertNull(parse("충주시청 역사 알려줘"))
        assertTrue(parse("충주시청까지 가지 마") is Interpretation.Clarify)
    }

    @Test
    fun transportWordsInsideDestinationOrInformationQuestionAreNotModes() {
        val cases = mapOf(
            "버스터미널까지 안내해줘" to "버스터미널",
            "자동차박물관 안내해줘" to "자동차박물관",
            "택시승강장 길안내" to "택시승강장",
        )
        for ((utterance, destination) in cases) {
            val result = parse(utterance)
            assertTrue("Expected destination navigation for '$utterance', got $result", result is Interpretation.NavigateExternal)
            assertEquals(destination, (result as Interpretation.NavigateExternal).destination)
        }
        assertNull(parse("버스가 뭐야"))
    }

    @Test
    fun unsupportedTransportClarifiesEvenWithPendingDestination() {
        val context = CommandContext("nav_dest:충주시청", expiresAt = 30_000)
        val result = parse("버스로 안내해줘", context = context, now = 10_000)
        assertTrue(result is Interpretation.Clarify)
        assertTrue((result as Interpretation.Clarify).question.contains("목적지와 함께 도보로"))
    }

    @Test
    fun koreaTransportationUniversityChungjuCampusUtteranceAndAsrVariantsExtractExactDestination() {
        val expectedDest = "한국교통대학교 충주캠퍼스"

        // 1. Spacing and particle variants without explicit provider (defaults to Naver)
        val variants = listOf(
            // User report exact text (preexisting baseline pass)
            "한국교통대학교 충주캠퍼스 까지 안내해줘",
            // ASR spacing variations where previous implementation failed by not stripping action or particle
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
            "한국교통대학교 충주캠퍼스 까지 안내 해 줘요",
            "한국교통대학교 충주캠퍼스 까지 길안내해줘",
            "한국교통대학교 충주캠퍼스 까지 길안내 해 줘",
            "한국교통대학교 충주캠퍼스 까지 길 안내해 줘",
            "한국교통대학교 충주캠퍼스 까지 길 안내 해 줘",
            "한국교통대학교 충주캠퍼스까지 길 안내 해 줘",
            "한국교통대학교 충주캠퍼스 까지 도보로 안내해 줘",
            "한국교통대학교 충주캠퍼스까지 걸어서 안내해 줘",
            // Punctuation and whitespace formatting
            "한국교통대학교 충주캠퍼스 까지 안내해줘.",
            "한국교통대학교 충주캠퍼스 까지 안내해 줘!",
            "한국교통대학교 충주캠퍼스, 까지 안내해 줘",
            "  한국교통대학교 충주캠퍼스 까지 안내해 줘  ",
        )

        for (utterance in variants) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance', got $result", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals(expectedDest, nav.destination)
            assertEquals(NavigationProvider.Naver, nav.provider)
        }

        // 2. Explicit providers
        val providerCases = mapOf(
            "네이버 지도로 한국교통대학교 충주캠퍼스 까지 안내해 줘" to NavigationProvider.Naver,
            "카카오맵으로 한국교통대학교 충주캠퍼스 까지 안내해 줘" to NavigationProvider.Kakao,
            "구글 지도로 한국교통대학교 충주캠퍼스 까지 안내해 줘" to NavigationProvider.Google,
        )
        for ((utterance, provider) in providerCases) {
            val result = parse(utterance)
            assertTrue("Expected NavigateExternal for '$utterance'", result is Interpretation.NavigateExternal)
            val nav = result as Interpretation.NavigateExternal
            assertEquals(expectedDest, nav.destination)
            assertEquals(provider, nav.provider)
        }

        // 3. Pending context target extraction
        assertEquals("nav_dest:$expectedDest", NavigationCommandParser.extractPendingTarget("한국교통대학교 충주캠퍼스 까지 안내해 줘"))

        // 4. Two-turn conversation
        val pendingContext = CommandContext("nav_prov:naver", expiresAt = 30_000)
        val twoTurnResult = parse("한국교통대학교 충주캠퍼스 까지", context = pendingContext, now = 10_000)
        assertTrue(twoTurnResult is Interpretation.NavigateExternal)
        assertEquals(expectedDest, (twoTurnResult as Interpretation.NavigateExternal).destination)

        // 5. Negation and compound safety preserved
        assertTrue(parse("한국교통대학교 충주캠퍼스 까지 안내하지 마") is Interpretation.Clarify)
        assertTrue(parse("한국교통대학교 충주캠퍼스 가지 마") is Interpretation.Clarify)
        assertTrue(parse("한국교통대학교 충주캠퍼스 까지 안내해 줘 그리고 홈으로 가") is Interpretation.Clarify)
    }
}
