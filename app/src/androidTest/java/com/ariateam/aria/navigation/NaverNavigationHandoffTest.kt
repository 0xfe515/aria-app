package com.ariateam.aria.navigation

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaverNavigationHandoffTest {

    private class FakeLauncher : NavigationLauncher {
        var lastProvider: NavigationProvider? = null
        var lastDestination: String? = null
        var lastResolvedDestination: ResolvedDestination? = null
        var walkingRouteCalled = false
        var launchCalled = false

        override fun launch(provider: NavigationProvider, destination: String): NavigationLaunchResult {
            launchCalled = true
            lastProvider = provider
            lastDestination = destination
            return NavigationLaunchResult.AppLaunched(provider, Uri.EMPTY)
        }

        override fun launchWalkingRoute(destination: ResolvedDestination): NavigationLaunchResult {
            walkingRouteCalled = true
            lastResolvedDestination = destination
            return NavigationLaunchResult.WalkingRouteLaunched(
                Uri.EMPTY,
                destination,
            )
        }
    }

    @Test
    fun resolvedDestinationArmsWalkingRouteDirectly() = runBlocking {
        val launcher = FakeLauncher()
        var armedToken: String? = null
        var armedDest: String? = null

        val handoff = NaverNavigationHandoff(
            launcher = launcher,
            destinationResolver = {
                DestinationResolution.Resolved(ResolvedDestination("서울역", 37.5559, 126.9723))
            },
            isAccessibilityEnabled = { true },
            armAssist = { _, token, dest ->
                armedToken = token
                armedDest = dest
                true
            },
        )

        val outcome = handoff.execute(
            eventId = 101L,
            provider = NavigationProvider.Naver,
            rawDestination = "서울역",
            screenVisible = true,
        )

        assertTrue("Outcome should be WalkingRouteArmed", outcome is NaverHandoffOutcome.WalkingRouteArmed)
        assertTrue(launcher.walkingRouteCalled)
        assertEquals("서울역", armedDest)
        assertTrue(armedToken?.startsWith("101-") == true)
    }

    @Test
    fun notFoundDestinationArmsSearchFallbackWithoutCoordinates() = runBlocking {
        val launcher = FakeLauncher()
        var armedToken: String? = null
        var armedDest: String? = null

        val handoff = NaverNavigationHandoff(
            launcher = launcher,
            destinationResolver = {
                DestinationResolution.NotFound
            },
            isAccessibilityEnabled = { true },
            armAssist = { _, token, dest ->
                armedToken = token
                armedDest = dest
                true
            },
        )

        val outcome = handoff.execute(
            eventId = 102L,
            provider = NavigationProvider.Naver,
            rawDestination = "한국교통대학교 충주캠퍼스",
            screenVisible = true,
        )

        assertTrue("Outcome should be SearchFallbackArmed", outcome is NaverHandoffOutcome.SearchFallbackArmed)
        assertTrue(launcher.launchCalled)
        assertEquals("한국교통대학교 충주캠퍼스", launcher.lastDestination)
        assertEquals("한국교통대학교 충주캠퍼스", armedDest)
        assertTrue(armedToken?.startsWith("102-") == true)
        val msg = NaverNavigationHandoff.feedbackMessage(outcome)
        assertTrue("Feedback message should announce checking guidance start", msg.contains("도보 경로를 찾고 있어요"))
    }

    @Test
    fun unavailableGeocoderArmsSearchFallbackWhenServiceEnabled() = runBlocking {
        val launcher = FakeLauncher()
        var armedDest: String? = null

        val handoff = NaverNavigationHandoff(
            launcher = launcher,
            destinationResolver = {
                DestinationResolution.Unavailable
            },
            isAccessibilityEnabled = { true },
            armAssist = { _, _, dest ->
                armedDest = dest
                true
            },
        )

        val outcome = handoff.execute(
            eventId = 103L,
            provider = NavigationProvider.Naver,
            rawDestination = "한국교통대학교 충주캠퍼스",
            screenVisible = true,
        )

        assertTrue(outcome is NaverHandoffOutcome.SearchFallbackArmed)
        assertEquals("한국교통대학교 충주캠퍼스", armedDest)
    }

    @Test
    fun ambiguousDestinationArmsSearchFallbackWithoutCoordinates() = runBlocking {
        val launcher = FakeLauncher()
        var armedToken: String? = null
        var armedDest: String? = null

        val handoff = NaverNavigationHandoff(
            launcher = launcher,
            destinationResolver = {
                DestinationResolution.Ambiguous
            },
            isAccessibilityEnabled = { true },
            armAssist = { _, token, dest ->
                armedToken = token
                armedDest = dest
                true
            },
        )

        val outcome = handoff.execute(
            eventId = 104L,
            provider = NavigationProvider.Naver,
            rawDestination = "스타벅스",
            screenVisible = true,
        )

        assertTrue("Outcome should be SearchFallbackArmed", outcome is NaverHandoffOutcome.SearchFallbackArmed)
        assertTrue(launcher.launchCalled)
        assertEquals("스타벅스", launcher.lastDestination)
        assertEquals("스타벅스", armedDest)
        assertTrue(armedToken?.startsWith("104-") == true)
        val msg = NaverNavigationHandoff.feedbackMessage(outcome)
        assertTrue("Feedback message should announce checking guidance start", msg.contains("도보 경로를 찾고 있어요"))
    }

    @Test
    fun genericProviderDoesNotInvokeNaverHandoff() = runBlocking {
        val launcher = FakeLauncher()
        var assistArmed = false

        val handoff = NaverNavigationHandoff(
            launcher = launcher,
            destinationResolver = { DestinationResolution.NotFound },
            isAccessibilityEnabled = { true },
            armAssist = { _, _, _ -> assistArmed = true; true },
        )

        val outcome = handoff.execute(
            eventId = 105L,
            provider = NavigationProvider.Google,
            rawDestination = "서울역",
            screenVisible = true,
        )

        assertTrue(outcome is NaverHandoffOutcome.GenericProviderLaunched)
        assertFalse(assistArmed)
        assertEquals(NavigationProvider.Google, launcher.lastProvider)
    }

    @Test
    fun invalidatedRequestDuringResolutionReturnsIgnoredWithoutArmingOrLaunching() = runBlocking {
        val launcher = FakeLauncher()
        var assistArmed = false

        val handoff = NaverNavigationHandoff(
            launcher = launcher,
            destinationResolver = {
                DestinationResolution.Resolved(ResolvedDestination("서울역", 37.5559, 126.9723))
            },
            isAccessibilityEnabled = { true },
            armAssist = { _, _, _ -> assistArmed = true; true },
        )

        val outcome = handoff.execute(
            eventId = 106L,
            provider = NavigationProvider.Naver,
            rawDestination = "서울역",
            screenVisible = true,
            isRequestStillValid = { false },
        )

        assertTrue("Outcome should be Ignored", outcome is NaverHandoffOutcome.Ignored)
        assertEquals("Request invalidated during resolution", (outcome as NaverHandoffOutcome.Ignored).reason)
        assertFalse("Launcher should not be called", launcher.launchCalled)
        assertFalse("Launcher walking route should not be called", launcher.walkingRouteCalled)
        assertFalse("Assist should not be armed", assistArmed)
    }
}
