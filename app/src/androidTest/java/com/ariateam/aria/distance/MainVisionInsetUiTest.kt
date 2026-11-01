package com.ariateam.aria.distance

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.ui.screen.MainVisionInset
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelMetadata
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionUiState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Deterministic instrumentation test for [MainVisionInset].
 *
 * Covers:
 * - Expanded bounds (width >= 200dp, height >= 130dp) and "추정" indicator tag.
 * - Unconfigured profile prompt and click callback.
 * - Fresh numeric distance display and expiry when stale (>1.5s).
 * - Immediate invalidation upon profile change until newer frame arrives.
 * - Camera stopped status rendering.
 * - Hardware independence (no-op camera callbacks) and store cleanup.
 */
class MainVisionInsetUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var store: BodyMountProfileStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = BodyMountProfileStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
        store.close()
    }

    @Test
    fun insetExpandedBoundsDisplayed() {
        val validProfile = BodyMountProfile(175f, BodyMountPosition.SHOULDER)
        assertTrue(store.save(validProfile))

        compose.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Running,
                    ),
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                    onRequestCameraPermission = {},
                    profile = validProfile,
                )
            }
        }

        // Expanded bounds: width >= 200dp, height >= 130dp
        val insetNode = compose.onNodeWithTag("mainVisionInset").assertIsDisplayed()
        val bounds = insetNode.getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        assertTrue("Inset width ($width) must be >= 200dp", width >= 200.dp)
        assertTrue("Inset height ($height) must be >= 130dp", height >= 130.dp)

        // "추정" indicator tag displayed
        compose.onNodeWithTag("mainVisionEstimateTag").assertIsDisplayed()
    }

    @Test
    fun unconfiguredProfileClickInvokesSetupCallback() {
        var setupOpened = false

        compose.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Running,
                    ),
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                    onRequestCameraPermission = {},
                    onOpenProfileSetup = { setupOpened = true },
                    profile = null,
                )
            }
        }

        // Badge shows unconfigured prompt and clicking it triggers setup callback
        compose.onNodeWithTag("mainVisionDistanceBadge")
            .assertIsDisplayed()
            .assertTextContains("키·착용 위치 설정")
            .performClick()

        compose.runOnIdle {
            assertTrue(setupOpened)
        }
    }

    @Test
    fun freshDistanceDisplaysEstimatedBadgeAndExpiresWhenStale() {
        val validProfile = BodyMountProfile(170f, BodyMountPosition.SHOULDER)
        assertTrue(store.save(validProfile))

        val gcdState = mutableStateOf<GroundContactDistance?>(null)

        compose.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Running,
                    ),
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                    onRequestCameraPermission = {},
                    groundContactDistance = gcdState.value,
                    profile = validProfile,
                )
            }
        }

        // Initialize fresh distance timestamp after compose setup using runOnIdle
        compose.runOnIdle {
            gcdState.value = GroundContactDistance(
                distanceMeters = 2.34f,
                timestampNanos = SystemClock.elapsedRealtimeNanos(),
            )
        }

        // Fresh distance displayed: "추정 약 2.3m"
        compose.onNodeWithTag("mainVisionDistanceBadge").assertIsDisplayed()
        compose.onNodeWithText("추정 약 2.3m").assertIsDisplayed()

        // Periodic ticker inside MainVisionInset checks freshness every 200ms
        // Wait deterministically for stale distance (>1.5s) to expire to "거리 확인 중"
        compose.waitUntil(timeoutMillis = 4000L) {
            try {
                compose.onNodeWithText("거리 확인 중").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        compose.onNodeWithText("거리 확인 중").assertIsDisplayed()
    }

    @Test
    fun profileChangeImmediatelyInvalidatesNumericDisplayUntilNewFrame() {
        val initialProfile = BodyMountProfile(170f, BodyMountPosition.SHOULDER)
        val profileState = mutableStateOf(initialProfile)
        val gcdState = mutableStateOf<GroundContactDistance?>(null)

        compose.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Running,
                    ),
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                    onRequestCameraPermission = {},
                    groundContactDistance = gcdState.value,
                    profile = profileState.value,
                )
            }
        }

        // Initialize fresh distance timestamp after compose setup using runOnIdle
        compose.runOnIdle {
            gcdState.value = GroundContactDistance(
                distanceMeters = 2.3f,
                timestampNanos = SystemClock.elapsedRealtimeNanos(),
            )
        }

        // Initial state: fresh numeric distance displayed
        compose.onNodeWithTag("mainVisionDistanceBadge").assertIsDisplayed()
        compose.onNodeWithText("추정 약 2.3m").assertIsDisplayed()

        // Profile changes from SHOULDER to CHEST via runOnIdle
        compose.runOnIdle {
            profileState.value = BodyMountProfile(170f, BodyMountPosition.CHEST)
        }

        // Immediately invalidated to "거리 확인 중" because frame was captured before profile change
        compose.onNodeWithText("거리 확인 중").assertIsDisplayed()

        // When a new frame captured AFTER the change arrives, numeric display returns
        compose.runOnIdle {
            gcdState.value = GroundContactDistance(
                distanceMeters = 2.1f,
                timestampNanos = SystemClock.elapsedRealtimeNanos(),
            )
        }

        compose.onNodeWithText("추정 약 2.1m").assertIsDisplayed()
    }

    @Test
    fun cameraStoppedDisplaysStoppedStatus() {
        val validProfile = BodyMountProfile(175f, BodyMountPosition.SHOULDER)

        compose.setContent {
            AriaTheme {
                MainVisionInset(
                    visionUiState = VisionUiState(
                        cameraStatus = CameraSessionStatus.Stopped,
                    ),
                    visionModelState = VisionModelState.Ready(
                        VisionModelMetadata("test.tflite", 100L, "sha", 1000L)
                    ),
                    hasCameraPermission = true,
                    isHomeVisible = true,
                    onStartCamera = { _, _ -> },
                    onStopCamera = {},
                    onSurfaceProviderUpdated = { _, _ -> },
                    onRequestCameraPermission = {},
                    profile = validProfile,
                )
            }
        }

        // Camera stopped renders "비전 중지됨"
        compose.onNodeWithText("비전 중지됨").assertIsDisplayed()
    }
}
