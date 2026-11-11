package com.ariateam.aria.navigation.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverAssistPolicyTest {
    private val req = NaverAssistRequest("fresh", "서울역", 2_000L, 500L, 500L)
    private val button = listOf(AssistButton("안내 시작", visible = true, enabled = true))

    private fun allowed(
        request: NaverAssistRequest? = req, token: String? = "fresh", pkg: String? = NaverAssistPolicy.NAVER_PACKAGE,
        fresh: Boolean = true, now: Long = 1_000L, root: Boolean = true, destination: String? = "서울역",
        walking: Boolean = true, buttons: List<AssistButton> = button, consumed: Boolean = false,
    ) = NaverAssistPolicy.shouldClick(request, token, pkg, if (fresh) 700L else 400L, now, root, 1, 1, destination, walking, false, buttons, consumed)

    private fun tree(vararg nodes: AssistTreeNode) = NaverAssistTargetSelector.select(nodes.toList(), NaverAssistPolicy.NAVER_PACKAGE, 7)

    @Test fun composeLabeledChildrenResolveToOneSharedClickableParent() {
        val targets = tree(
            AssistTreeNode(0, null, "", true, true, false, NaverAssistPolicy.NAVER_PACKAGE, 7),
            AssistTreeNode(1, 0, "", true, true, true, NaverAssistPolicy.NAVER_PACKAGE, 7),
            AssistTreeNode(2, 1, "안내시작", true, true, false, NaverAssistPolicy.NAVER_PACKAGE, 7),
            AssistTreeNode(3, 1, "안내시작", true, true, false, NaverAssistPolicy.NAVER_PACKAGE, 7),
        )
        assertEquals(setOf(1), targets)
    }

    @Test fun distinctParentsAreAmbiguousAndRejectedByOneTargetGuard() {
        val targets = tree(
            AssistTreeNode(0, null, "", true, true, false, NaverAssistPolicy.NAVER_PACKAGE, 7),
            AssistTreeNode(1, 0, "", true, true, true, NaverAssistPolicy.NAVER_PACKAGE, 7),
            AssistTreeNode(2, 1, "안내시작", true, true, false, NaverAssistPolicy.NAVER_PACKAGE, 7),
            AssistTreeNode(3, 0, "", true, true, true, NaverAssistPolicy.NAVER_PACKAGE, 7),
            AssistTreeNode(4, 3, "안내시작", true, true, false, NaverAssistPolicy.NAVER_PACKAGE, 7),
        )
        assertEquals(setOf(1, 3), targets)
        assertFalse(allowed(buttons = targets.map { AssistButton("안내시작", true, true) }))
    }

    @Test fun rejectsDistantDisabledInvisibleAndMismatchedTargetsAndAcceptsDirectClick() {
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        assertTrue(tree(AssistTreeNode(0, null, "안내시작", true, true, true, pkg, 7)).contains(0))
        assertTrue(tree(AssistTreeNode(0, null, "", true, true, false, pkg, 7), AssistTreeNode(1, 0, "", true, true, true, pkg, 7), AssistTreeNode(2, 1, "", true, true, false, pkg, 7), AssistTreeNode(3, 2, "안내시작", true, true, false, pkg, 7)).isEmpty())
        assertTrue(tree(AssistTreeNode(0, null, "", true, false, true, pkg, 7), AssistTreeNode(1, 0, "안내시작", true, true, false, pkg, 7)).isEmpty())
        assertTrue(tree(AssistTreeNode(0, null, "", false, true, true, pkg, 7), AssistTreeNode(1, 0, "안내시작", true, true, false, pkg, 7)).isEmpty())
        assertTrue(tree(AssistTreeNode(0, null, "", true, true, true, "other", 7), AssistTreeNode(1, 0, "안내시작", true, true, false, pkg, 7)).isEmpty())
        assertTrue(tree(AssistTreeNode(0, null, "", true, true, true, pkg, 8), AssistTreeNode(1, 0, "안내시작", true, true, false, pkg, 7)).isEmpty())
        assertTrue(tree(AssistTreeNode(0, null, "", true, true, true, pkg, 7), AssistTreeNode(1, 0, "시작", true, true, false, pkg, 7)).isEmpty())
    }

    @Test fun rejectsDisabledStartLabelEvenWhenParentIsEnabled() {
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        assertTrue(tree(
            AssistTreeNode(0, null, "", true, true, true, pkg, 7),
            AssistTreeNode(1, 0, "안내시작", true, false, false, pkg, 7),
        ).isEmpty())
    }

    @Test fun rejectsImmediateClickableParentWithUnrelatedLabelOrControlChild() {
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        assertTrue(tree(
            AssistTreeNode(0, null, "", true, true, false, pkg, 7),
            AssistTreeNode(1, 0, "", true, true, true, pkg, 7),
            AssistTreeNode(2, 1, "안내시작", true, true, false, pkg, 7),
            AssistTreeNode(3, 1, "미리보기", true, true, false, pkg, 7),
        ).isEmpty())
        assertTrue(tree(
            AssistTreeNode(0, null, "", true, true, false, pkg, 7),
            AssistTreeNode(1, 0, "", true, true, true, pkg, 7),
            AssistTreeNode(2, 1, "안내시작", true, true, false, pkg, 7),
            AssistTreeNode(3, 1, "", true, true, true, pkg, 7),
        ).isEmpty())
    }

    @Test fun matchingFreshWalkingRouteClicksOnce() { assertTrue(allowed()); assertFalse(allowed(consumed = true)) }
    @Test fun rejectsWrongPackageAndStaleRootOrEvent() {
        assertFalse(allowed(pkg = "com.other")); assertFalse(allowed(root = false)); assertFalse(allowed(fresh = false))
    }
    @Test fun rejectsDrivingDestinationMismatchAndExpiredRequest() {
        assertFalse(allowed(walking = false)); assertFalse(allowed(destination = "강남역")); assertFalse(allowed(now = 2_000L))
    }
    @Test fun rejectsDuplicateMissingInvisibleOrDisabledButtons() {
        assertFalse(allowed(buttons = button + button)); assertFalse(allowed(buttons = emptyList()))
        assertFalse(allowed(buttons = listOf(AssistButton("안내 시작", false, true))))
        assertFalse(allowed(buttons = listOf(AssistButton("안내 시작", true, false))))
    }
    @Test fun replayCancelAndWrongTokenCannotClick() {
        assertFalse(allowed(request = null)); assertFalse(allowed(token = "old")); assertFalse(allowed(consumed = true))
    }

    @Test fun rejectsOldEventsAndMismatchedWindows() {
        assertFalse(allowed(fresh = false))
        assertFalse(NaverAssistPolicy.shouldClick(req, "fresh", NaverAssistPolicy.NAVER_PACKAGE, 700L, 1_000L, true, 1, 2, "서울역", true, false, button, false))
    }

    @Test fun walkingModeMustBeSelectedAndOtherModeRejected() {
        val walking = NaverAssistPolicy.modeEvidence(listOf("도보" to true, "자동차" to false))
        assertTrue(walking.walkingSelected)
        assertFalse(walking.drivingOrTransitSelected)
        val driving = NaverAssistPolicy.modeEvidence(listOf("도보" to false, "자동차" to true))
        assertFalse(driving.walkingSelected)
        assertTrue(driving.drivingOrTransitSelected)
        assertFalse(allowed(walking = false))
    }

    @Test fun requestStoreFencesCancelReplacementExpiryAndSingleShot() {
        val store = NaverAssistRequestStore()
        var clicks = 0
        store.arm(req)
        assertTrue(store.consume("fresh", 1_999L) { clicks++; true })
        assertFalse(store.consume("fresh", 1_999L) { clicks++; true })
        assertEquals(1, clicks)
        store.arm(req)
        val snapshotBeforeCancel = store.current(1_000L)!!
        store.cancel()
        assertFalse(store.consume(snapshotBeforeCancel.token, 1_000L) { clicks++; true })
        store.arm(req)
        val snapshotBeforeReplacement = store.current(1_000L)!!
        val replacement = req.copy(token = "replacement")
        store.arm(replacement)
        assertFalse(store.consume(snapshotBeforeReplacement.token, 1_000L) { clicks++; true })
        assertFalse(store.consume("replacement", replacement.expiresAt) { clicks++; true })
        assertEquals(1, clicks)
    }

    @Test fun handoffRequiresFreshNaverWindowStateAndRejectsUnrelatedApp() {
        val state = NaverAssistHandoffState("com.ariateam.aria")
        state.arm(req)
        assertFalse(state.observe("com.ariateam.aria", true, 600L, "com.ariateam.aria", 1, 1))
        assertFalse(state.observe("com.nhn.android.nmap", false, 700L, "com.nhn.android.nmap", 2, 2))
        assertTrue(state.observe("com.nhn.android.nmap", true, 700L, "com.nhn.android.nmap", 2, 2))
        assertTrue(state.observe("com.nhn.android.nmap", false, 800L, "com.nhn.android.nmap", 2, 2))
        state.arm(req)
        assertFalse(state.observe("com.other.app", true, 700L, "com.other.app", 3, 3))
        assertFalse(state.observe("com.nhn.android.nmap", true, 800L, "com.nhn.android.nmap", 2, 2))
    }

    @Test fun destinationMatchRequiresStrictExactMatchingWhileRejectingSuffixesAndSubBuildings() {
        val dest = "한국교통대학교 충주캠퍼스"
        assertTrue(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스", dest))
        assertTrue(NaverAssistPolicy.isDestinationMatch("한국교통대학교충주캠퍼스", dest))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스대학교", dest))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교충주캠퍼스박물관", dest))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스종합강의관", dest))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스휴스퀘어관", dest))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 증평캠퍼스", dest))
        assertFalse(NaverAssistPolicy.isDestinationMatch("", dest))
    }

    @Test fun searchResultUnambiguousCardSelectsDirectionsButton() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        // Tree representing search list with 2 cards:
        // Card 1: 한국교통대학교 충주캠퍼스
        // Card 2: 한국교통대학교충주캠퍼스박물관
        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            // Card 1
            AssistTreeNode(1, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(2, 1, "한국교통대학교 충주캠퍼스", true, true, true, pkg, winId),
            AssistTreeNode(3, 1, "길찾기", true, true, true, pkg, winId),
            // Card 2
            AssistTreeNode(4, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(5, 4, "한국교통대학교충주캠퍼스박물관", true, true, true, pkg, winId),
            AssistTreeNode(6, 4, "길찾기", true, true, true, pkg, winId),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertTrue("Decision should be ClickSearchDirections", decision is AssistDecision.ClickSearchDirections)
        assertEquals(3, (decision as AssistDecision.ClickSearchDirections).targetId)
    }

    @Test fun searchResultAmbiguousCardsRejectAutoClick() {
        val dest = "스타벅스 강남"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        // 2 cards both matching "스타벅스 강남"
        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(2, 1, "스타벅스 강남", true, true, true, pkg, winId),
            AssistTreeNode(3, 1, "길찾기", true, true, true, pkg, winId),
            AssistTreeNode(4, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(5, 4, "스타벅스 강남", true, true, true, pkg, winId),
            AssistTreeNode(6, 4, "길찾기", true, true, true, pkg, winId),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertEquals(AssistDecision.None, decision)
    }

    @Test fun queryHeaderCampusAndOneWrongMuseumCardReturnsNone() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        // Search screen with top search bar containing requested campus query,
        // and a ListView containing only ONE wrong result card (museum).
        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            // Top query header
            AssistTreeNode(1, 0, dest, true, true, false, pkg, winId),
            // ListView container
            AssistTreeNode(2, 0, "", true, true, false, pkg, winId, className = "android.widget.ListView"),
            // Card 1: Museum (wrong result)
            AssistTreeNode(3, 2, "", true, true, false, pkg, winId, className = "android.view.View"),
            AssistTreeNode(4, 3, "한국교통대학교 충주캠퍼스 박물관", true, true, true, pkg, winId),
            AssistTreeNode(5, 3, "길찾기", true, true, true, pkg, winId),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        // Must NOT ascend to top query header; museum card does not match requested destination
        assertEquals(AssistDecision.None, decision)
    }

    @Test fun routePlanningOriginCampusDestMuseumReturnsNone() {
        val campus = "한국교통대학교 충주캠퍼스"
        val museum = "한국교통대학교 충주캠퍼스 박물관"
        val request = NaverAssistRequest("token1", campus, 3_000L, 500L, 500L, stage = AssistStage.START_GUIDANCE)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        // Route planning screen where origin is campus and destination is museum
        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "도보", true, true, true, pkg, winId, selected = true),
            // Route header containing switch button, origin field, and destination field
            AssistTreeNode(2, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(3, 2, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(4, 2, campus, true, true, true, pkg, winId), // Origin = campus
            AssistTreeNode(5, 2, museum, true, true, true, pkg, winId), // Destination = museum
            AssistTreeNode(6, 0, "", true, true, true, pkg, winId), // start button container
            AssistTreeNode(7, 6, "안내시작", true, true, false, pkg, winId),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        // Destination is museum, so campus request must NOT proceed to guidance
        assertEquals(AssistDecision.None, decision)
    }

    @Test fun routePlanningHistoryCampusDestMuseumReturnsNone() {
        val campus = "한국교통대학교 충주캠퍼스"
        val museum = "한국교통대학교 충주캠퍼스 박물관"
        val request = NaverAssistRequest("token1", campus, 3_000L, 500L, 500L, stage = AssistStage.START_GUIDANCE)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        // Route planning screen where destination is museum, and campus only appears in history
        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "도보", true, true, true, pkg, winId, selected = true),
            // Route header
            AssistTreeNode(2, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(3, 2, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(4, 2, "충주시 대소원면", true, true, true, pkg, winId), // Origin
            AssistTreeNode(5, 2, museum, true, true, true, pkg, winId), // Destination = museum
            // Search history list below header containing campus
            AssistTreeNode(6, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(7, 6, campus, true, true, false, pkg, winId), // Recent search history
            AssistTreeNode(8, 0, "", true, true, true, pkg, winId), // start button container
            AssistTreeNode(9, 8, "안내시작", true, true, false, pkg, winId),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        // Campus in history must NOT satisfy destination matching
        assertEquals(AssistDecision.None, decision)
    }

    @Test fun routePlanningWithEmptyOriginSelectsOriginEntry() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L, stage = AssistStage.SEARCH_RESULTS)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "대중교통", true, true, true, pkg, winId),
            AssistTreeNode(2, 0, "자동차", true, true, true, pkg, winId),
            AssistTreeNode(3, 0, "도보", true, true, false, pkg, winId, selected = true),
            // Route header
            AssistTreeNode(4, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(5, 4, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(6, 4, "", true, true, true, pkg, winId), // Origin input container
            AssistTreeNode(7, 6, "출발지 입력", true, true, false, pkg, winId),
            AssistTreeNode(8, 4, dest, true, true, true, pkg, winId), // Destination
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertTrue("Decision should be ClickOriginEntry", decision is AssistDecision.ClickOriginEntry)
        assertEquals(6, (decision as AssistDecision.ClickOriginEntry).targetId)
    }

    @Test fun originPickerScreenSelectsMyLocationButton() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L, stage = AssistStage.ORIGIN_PICKER)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "뒤로 가기", true, true, true, pkg, winId, viewIdResourceName = "com.nhn.android.nmap:id/btn_back"),
            AssistTreeNode(2, 0, "출발지 입력", true, true, true, pkg, winId, viewIdResourceName = "com.nhn.android.nmap:id/edit_text_search"),
            AssistTreeNode(3, 0, "", true, true, true, pkg, winId, viewIdResourceName = "com.nhn.android.nmap:id/btn_my_location"),
            AssistTreeNode(4, 3, "내위치", true, true, false, pkg, winId),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertTrue("Decision should be ClickMyLocation", decision is AssistDecision.ClickMyLocation)
        assertEquals(3, (decision as AssistDecision.ClickMyLocation).targetId)
    }

    @Test fun routePlanningCalculatedSelectsStartGuidance() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L, stage = AssistStage.START_GUIDANCE)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "대중교통", true, true, true, pkg, winId),
            AssistTreeNode(2, 0, "도보", true, true, false, pkg, winId, selected = true),
            // Route header
            AssistTreeNode(3, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(4, 3, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(5, 3, "내 위치", true, true, true, pkg, winId),
            AssistTreeNode(6, 3, dest, true, true, true, pkg, winId),
            AssistTreeNode(7, 0, "안내시작", true, true, true, pkg, winId),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertTrue("Decision should be ClickStartGuidance", decision is AssistDecision.ClickStartGuidance)
        assertEquals(7, (decision as AssistDecision.ClickStartGuidance).targetId)
    }

    @Test fun actualCapturedUnlabeledClickableParentWithTwoNonClickableLabelChildrenEvaluatesToClickStartGuidance() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L, stage = AssistStage.START_GUIDANCE)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        // Exact captured tree structure from Naver 6.12.1.1 (location-now.xml):
        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "대중교통", true, true, true, pkg, winId),
            AssistTreeNode(2, 0, "도보", true, true, false, pkg, winId, selected = true),
            // Route header
            AssistTreeNode(3, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(4, 3, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(5, 3, "내 위치", true, true, true, pkg, winId),
            AssistTreeNode(6, 3, dest, true, true, true, pkg, winId),
            // Unlabeled clickable parent with 2 non-clickable label children
            AssistTreeNode(7, 0, "", true, true, true, pkg, winId, className = "android.view.View"),
            AssistTreeNode(8, 7, "안내시작", true, true, false, pkg, winId, className = "android.view.View"),
            AssistTreeNode(9, 7, "안내시작", true, true, false, pkg, winId, className = "android.widget.TextView"),
        )

        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertTrue("Decision should be ClickStartGuidance", decision is AssistDecision.ClickStartGuidance)
        assertEquals("Target must be the clickable parent (id=7)", 7, (decision as AssistDecision.ClickStartGuidance).targetId)
    }

    @Test fun stepCounterBoundLimitsTotalAssistActions() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest(
            token = "token1",
            destination = dest,
            expiresAt = 3_000L,
            armedAtElapsed = 500L,
            armedAtUptime = 500L,
            stepCount = NaverAssistPolicy.MAX_ASSIST_STEPS,
            stage = AssistStage.START_GUIDANCE,
        )
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "도보", true, true, false, pkg, winId, selected = true),
            // Route header
            AssistTreeNode(2, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(3, 2, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(4, 2, "내 위치", true, true, true, pkg, winId),
            AssistTreeNode(5, 2, dest, true, true, true, pkg, winId),
            AssistTreeNode(6, 0, "안내시작", true, true, true, pkg, winId),
        )

        // When step count reaches MAX_ASSIST_STEPS (5), no further actions are allowed
        val decision = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertEquals(AssistDecision.None, decision)
    }

    @Test fun exactDestinationMatchesWhileDistinctPlacesAndSubBuildingsAreRejected() {
        val target = "한국교통대학교 충주캠퍼스"
        assertTrue(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스", target))
        assertTrue(NaverAssistPolicy.isDestinationMatch("한국교통대학교충주캠퍼스", target))

        // Distinct places must NOT match (exact normalized equality)
        assertFalse("서울 must not match 서울역", NaverAssistPolicy.isDestinationMatch("서울", "서울역"))
        assertFalse("서울역 must not match 서울", NaverAssistPolicy.isDestinationMatch("서울역", "서울"))
        assertFalse("서울역 must not match 서울역공원", NaverAssistPolicy.isDestinationMatch("서울역", "서울역공원"))
        assertFalse("서울역공원 must not match 서울역", NaverAssistPolicy.isDestinationMatch("서울역공원", "서울역"))

        // Sub-buildings and suffix variations must NOT match campus destination
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스대학교", target))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스 대학교", target))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스 박물관", target))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스도서관", target))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스 종합강의관", target))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 충주캠퍼스 휴스퀘어관", target))

        // Different campuses must NOT match
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 증평캠퍼스", target))
        assertFalse(NaverAssistPolicy.isDestinationMatch("한국교통대학교 의왕캠퍼스", target))
    }

    @Test fun tryStepRejectsMismatchedOrCanceledRequestWithoutExecutingAction() {
        val store = NaverAssistRequestStore()
        var executed = false
        val ok1 = store.tryStep("tok1", AssistStage.SEARCH_RESULTS, AssistStage.ORIGIN_INPUT, 600L) {
            executed = true
            true
        }
        assertFalse("Unarmed store should reject step", ok1)
        assertFalse("Action should not execute", executed)

        store.arm(NaverAssistRequest("tok1", "한국교통대학교 충주캠퍼스", 3_000L, 500L, 500L, stage = AssistStage.SEARCH_RESULTS))
        val ok2 = store.tryStep("tok2", AssistStage.SEARCH_RESULTS, AssistStage.ORIGIN_INPUT, 600L) {
            executed = true
            true
        }
        assertFalse("Wrong token should reject step", ok2)
        assertFalse("Action should not execute", executed)

        store.cancel("tok1")
        val ok3 = store.tryStep("tok1", AssistStage.SEARCH_RESULTS, AssistStage.ORIGIN_INPUT, 600L) {
            executed = true
            true
        }
        assertFalse("Canceled store should reject step", ok3)
        assertFalse("Action should not execute", executed)
    }

    @Test fun walkingTabClickedOnceAndNotReplayedWhileAwaitingWalkingSelection() {
        val dest = "한국교통대학교 충주캠퍼스"
        val request = NaverAssistRequest("token1", dest, 3_000L, 500L, 500L, stage = AssistStage.START_GUIDANCE)
        val pkg = NaverAssistPolicy.NAVER_PACKAGE
        val winId = 10

        // Driving is currently selected; walking is not selected
        val tree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "자동차", true, true, true, pkg, winId, selected = true),
            AssistTreeNode(2, 0, "도보", true, true, true, pkg, winId, selected = false),
            // Route header
            AssistTreeNode(3, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(4, 3, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(5, 3, "내 위치", true, true, true, pkg, winId),
            AssistTreeNode(6, 3, dest, true, true, true, pkg, winId),
            AssistTreeNode(7, 0, "안내시작", true, true, true, pkg, winId),
        )

        // First event: yields ClickWalkingTab
        val decision1 = NaverAssistPolicy.evaluate(
            request, "token1", pkg, 600L, 1_000L, true, winId, winId, tree, false,
        )
        assertTrue("Decision should be ClickWalkingTab", decision1 is AssistDecision.ClickWalkingTab)
        assertEquals(2, (decision1 as AssistDecision.ClickWalkingTab).targetId)

        // Transition request to AWAITING_WALKING_SELECTION (as done by tryStep)
        val awaitingRequest = request.copy(stage = AssistStage.AWAITING_WALKING_SELECTION, stepCount = 2)

        // Duplicate/stale event on unchanged tree must NOT click walking tab again
        val decision2 = NaverAssistPolicy.evaluate(
            awaitingRequest, "token1", pkg, 650L, 1_050L, true, winId, winId, tree, false,
        )
        assertEquals("Duplicate unchanged tab tree must not click twice while awaiting selection", AssistDecision.None, decision2)

        // Once walking mode updates to selected = true
        val updatedTree = listOf(
            AssistTreeNode(0, null, "", true, true, false, pkg, winId),
            AssistTreeNode(1, 0, "자동차", true, true, true, pkg, winId, selected = false),
            AssistTreeNode(2, 0, "도보", true, true, true, pkg, winId, selected = true),
            // Route header
            AssistTreeNode(3, 0, "", true, true, false, pkg, winId),
            AssistTreeNode(4, 3, "출발지 도착지 전환", true, true, true, pkg, winId),
            AssistTreeNode(5, 3, "내 위치", true, true, true, pkg, winId),
            AssistTreeNode(6, 3, dest, true, true, true, pkg, winId),
            AssistTreeNode(7, 0, "안내시작", true, true, true, pkg, winId),
        )
        val decision3 = NaverAssistPolicy.evaluate(
            awaitingRequest, "token1", pkg, 700L, 1_100L, true, winId, winId, updatedTree, false,
        )
        assertTrue("After walking selection evidence, guidance can start", decision3 is AssistDecision.ClickStartGuidance)
        assertEquals(7, (decision3 as AssistDecision.ClickStartGuidance).targetId)
    }
}
