package com.ariateam.aria.navigation.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
}
