package com.ariateam.aria.navigation

import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import com.ariateam.aria.command.RuleCommandInterpreter
import com.ariateam.aria.navigation.accessibility.NaverAssistPolicy
import com.ariateam.aria.navigation.accessibility.NaverWalkingAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NaverNavigationRealFlowAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // Bring test-owned host activity to foreground before arming to mimic
        // fresh Aria -> Naver window state, without starting actual MainActivity (preserves user prefs/startup).
        composeRule.setContent {
            Text("Aria Nav Test Host")
        }
        composeRule.waitForIdle()
        NaverWalkingAccessibilityService.cancel(context)
    }

    @After
    fun tearDown() {
        NaverWalkingAccessibilityService.cancel(context)
    }

    @Test
    fun realFlowUtteranceToSanitizedDestinationAndPercentEncodedUri() {
        val utterance = "한국교통대학교 충주캠퍼스 까지 안내해줘"
        val interpreter = RuleCommandInterpreter()
        val result = interpreter.interpret(utterance, CommandContext(), SystemClock.elapsedRealtime())

        assertTrue("Expected NavigateExternal for navigation utterance, got: $result", result is Interpretation.NavigateExternal)
        val nav = result as Interpretation.NavigateExternal
        assertEquals(NavigationProvider.Naver, nav.provider)
        assertEquals("한국교통대학교 충주캠퍼스", nav.destination)

        // Validate percent-encoded spaces in URI
        val appUri = nav.provider.buildAppUri(nav.destination)
        val uriString = appUri.toString()
        assertFalse("URI should not contain literal '+' bug for spaces in custom scheme", uriString.contains("+"))
        assertTrue("URI should contain '%20' encoded space", uriString.contains("%20"))
        assertTrue("URI should target Naver custom scheme", uriString.startsWith("nmap://search?query="))
        assertFalse("URI should not retain command suffixes like '까지' or '안내해줘'", uriString.contains("안내") || uriString.contains("까지"))
    }

    @Test
    fun productionHandoffForcedNotFoundFallbackReachesGuidance() = runBlocking {
        val utterance = "한국교통대학교 충주캠퍼스 까지 안내해줘"
        val interpreter = RuleCommandInterpreter()
        val interpretation = interpreter.interpret(utterance, CommandContext(), SystemClock.elapsedRealtime())
            as Interpretation.NavigateExternal

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Initialize FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES before handoff so the production service remains active
        val uiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertNotNull("UiAutomation must be initialized with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES", uiAutomation)

        val naverInstalled = runCatching {
            context.packageManager.getPackageInfo(NaverAssistPolicy.NAVER_PACKAGE, 0)
        }.isSuccess
        assertTrue("Naver Map (${NaverAssistPolicy.NAVER_PACKAGE}) must be installed on test device", naverInstalled)

        val serviceEnabled = NaverWalkingAccessibilityService.isEnabled(context)
        assertTrue("NaverWalkingAccessibilityService must be enabled on test device", serviceEnabled)

        val forcedNotFoundHandoff = NaverNavigationHandoff(
            context = context,
            destinationResolver = { DestinationResolution.NotFound },
        )

        val eventId = 9101L
        val outcome = forcedNotFoundHandoff.execute(
            eventId = eventId,
            provider = interpretation.provider,
            rawDestination = interpretation.destination,
            screenVisible = true,
        )

        assertTrue(
            "Forced NotFound handoff must arm search fallback, got: $outcome",
            outcome is NaverHandoffOutcome.SearchFallbackArmed,
        )

        // Observe Naver UI without clicking any map buttons from test; accessibility service must perform all actions
        assertGuidanceReached(uiAutomation, interpretation.destination)
    }

    @Test
    fun productionHandoffDefaultResolverReachesGuidance() = runBlocking {
        val utterance = "한국교통대학교 충주캠퍼스 까지 안내해줘"
        val interpreter = RuleCommandInterpreter()
        val interpretation = interpreter.interpret(utterance, CommandContext(), SystemClock.elapsedRealtime())
            as Interpretation.NavigateExternal

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Initialize FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES before handoff so the production service remains active
        val uiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertNotNull("UiAutomation must be initialized with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES", uiAutomation)

        val naverInstalled = runCatching {
            context.packageManager.getPackageInfo(NaverAssistPolicy.NAVER_PACKAGE, 0)
        }.isSuccess
        assertTrue("Naver Map (${NaverAssistPolicy.NAVER_PACKAGE}) must be installed on test device", naverInstalled)

        val serviceEnabled = NaverWalkingAccessibilityService.isEnabled(context)
        assertTrue("NaverWalkingAccessibilityService must be enabled on test device", serviceEnabled)

        val defaultHandoff = NaverNavigationHandoff(context = context)
        val eventId = 9102L
        val outcome = defaultHandoff.execute(
            eventId = eventId,
            provider = interpretation.provider,
            rawDestination = interpretation.destination,
            screenVisible = true,
        )

        assertTrue(
            "Default resolver handoff must arm assist (SearchFallbackArmed or WalkingRouteArmed), got: $outcome",
            outcome is NaverHandoffOutcome.SearchFallbackArmed || outcome is NaverHandoffOutcome.WalkingRouteArmed,
        )

        // Observe Naver UI without clicking any map buttons from test; accessibility service must perform all actions
        assertGuidanceReached(uiAutomation, interpretation.destination)
    }

    private fun assertGuidanceReached(uiAutomation: UiAutomation, destination: String) {
        val deadline = SystemClock.elapsedRealtime() + 45_000L
        var guidanceReached = false
        var lastCollected = emptySet<String>()

        while (SystemClock.elapsedRealtime() < deadline) {
            val root = runCatching { uiAutomation.rootInActiveWindow }.getOrNull()
            if (root != null) {
                try {
                    val pkg = root.packageName?.toString()
                    if (pkg == NaverAssistPolicy.NAVER_PACKAGE) {
                        val allTexts = mutableSetOf<String>()
                        collectTextsAndDescriptions(root, allTexts)
                        lastCollected = allTexts

                        val hasDestination = allTexts.any { NaverAssistPolicy.isDestinationMatch(it, destination) }
                        val hasRetarget = allTexts.contains("재탐색")
                        val hasOverview = allTexts.contains("전체경로")
                        val hasStartButton = allTexts.any { it.contains("안내시작") }

                        if (hasDestination && hasRetarget && hasOverview && !hasStartButton) {
                            guidanceReached = true
                            break
                        }
                    }
                } finally {
                    runCatching { root.recycle() }
                }
            }
            SystemClock.sleep(500L)
        }

        assertTrue(
            "Expected fresh named campus GUIDANCE (destination '$destination', '재탐색', '전체경로', and no '안내시작') within bounded timeout. Last observed texts: $lastCollected",
            guidanceReached,
        )
    }

    private fun collectTextsAndDescriptions(node: AccessibilityNodeInfo, texts: MutableSet<String>) {
        val text = node.text?.toString().orEmpty().trim()
        val cd = node.contentDescription?.toString().orEmpty().trim()
        if (text.isNotBlank()) texts.add(text)
        if (cd.isNotBlank()) texts.add(cd)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectTextsAndDescriptions(child, texts)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }
}
