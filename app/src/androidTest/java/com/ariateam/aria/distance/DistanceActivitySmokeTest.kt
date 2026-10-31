package com.ariateam.aria.distance

import android.Manifest
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.viewModels
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.ui.screen.DistanceScreen
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test for [DistanceActivity] registration, lifecycle launch/pause/resume,
 * and [DistanceScreen] Compose rendering.
 */
@RunWith(AndroidJUnit4::class)
class DistanceActivitySmokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDistanceActivityManifestDeclaration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val componentName = ComponentName(context, DistanceActivity::class.java)

        val activityInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getActivityInfo(
                componentName,
                PackageManager.ComponentInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(componentName, 0)
        }

        assertNotNull("DistanceActivity must be registered in AndroidManifest.xml", activityInfo)
        assertFalse("DistanceActivity must NOT be exported", activityInfo.exported)
        assertEquals(
            "DistanceActivity must declare SCREEN_ORIENTATION_PORTRAIT",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activityInfo.screenOrientation,
        )
    }

    @Test
    fun testDistanceActivityLaunchAndPauseResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.CAMERA,
            )
        } catch (_: Throwable) {
        }

        val intent = DistanceActivity.createIntent(context)
        ActivityScenario.launch<DistanceActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertNotNull("DistanceActivity must instantiate without constructor crash", activity)
                assertFalse("DistanceActivity must not be finishing", activity.isFinishing)
            }

            // Validate pause and resume lifecycle transitions without crashing
            scenario.moveToState(Lifecycle.State.STARTED) // onPause
            scenario.moveToState(Lifecycle.State.RESUMED) // onResume
            scenario.onActivity { activity ->
                assertFalse("DistanceActivity must survive pause/resume cycle", activity.isFinishing)
            }
        }
    }

    @Test
    fun testDistanceScreenRendersTrackingState() {
        val state = DistanceUiState(
            status = DistanceStatus.TRACKING,
            risk = DistanceRisk.CAUTION,
            distanceMeters = 2.5f,
            validSampleCount = 64,
            groundOffsetMeters = -1.30f,
            floorDetected = true,
            setupStage = DistanceSetupStage.TRACKING,
            rawDepthActive = true,
            showOnboarding = false,
        )

        composeTestRule.setContent {
            AriaTheme {
                DistanceScreen(
                    uiState = state,
                    onBack = {},
                    onRecalibrate = {},
                    onDismissOnboarding = {},
                )
            }
        }

        // Verify distance readout and header
        composeTestRule.onNodeWithText("보행 장애물 거리 감지").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.5 m").assertIsDisplayed()
        composeTestRule.onNodeWithText("주의: 전방 장애물 접근").assertIsDisplayed()
    }

    @Test
    fun testDistanceScreenRendersErrorState() {
        val state = DistanceUiState(
            status = DistanceStatus.UNAVAILABLE,
            setupStage = DistanceSetupStage.ERROR_DEPTH_UNSUPPORTED,
            showOnboarding = false,
        )

        composeTestRule.setContent {
            AriaTheme {
                DistanceScreen(
                    uiState = state,
                    onBack = {},
                    onRecalibrate = {},
                    onDismissOnboarding = {},
                )
            }
        }

        composeTestRule.onNodeWithText("이 기기는 ARCore Raw Depth 모드를 지원하지 않습니다.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Aria 홈으로 돌아가기").assertIsDisplayed()
    }
}
