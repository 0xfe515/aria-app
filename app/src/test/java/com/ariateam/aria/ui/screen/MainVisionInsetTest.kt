package com.ariateam.aria.ui.screen

import com.ariateam.aria.DebugDestination
import com.ariateam.aria.PendingCameraStartGate
import com.ariateam.aria.distance.BodyMountPosition
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.GroundContactDistance
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.StagePercentiles
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionTimingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainVisionInsetTest {

    private fun createDummyMetadata() = com.ariateam.aria.vision.VisionModelMetadata(
        displayName = "test.tflite",
        sizeBytes = 100L,
        sha256 = "hash",
        importedAtMillis = 1000L,
    )

    @Test
    fun computeMainVisionStatus_truthfulPermissionDenied() {
        val timing = VisionTimingSnapshot(modelLoaded = true)

        // Missing camera permission yields PermissionDenied
        val statusNoPermission = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Stopped,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = timing,
            hasFreshOverlay = false,
            hasCameraPermission = false,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.PermissionDenied, statusNoPermission)

        // CameraSessionStatus.PermissionDenied yields PermissionDenied
        val statusDeniedStatus = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.PermissionDenied,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = timing,
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.PermissionDenied, statusDeniedStatus)
    }

    @Test
    fun computeMainVisionStatus_truthfulError() {
        val timing = VisionTimingSnapshot(modelLoaded = true)

        // CameraSessionStatus.Error yields Error
        val statusCameraError = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Error,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = timing,
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Error, statusCameraError)

        // CameraSessionStatus.CameraUnavailable yields Error
        val statusUnavailable = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.CameraUnavailable,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = timing,
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Error, statusUnavailable)

        // VisionModelState.Error yields Error
        val statusModelError = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Running,
            visionModelState = VisionModelState.Error("Corrupt model", "Invalid tensor format"),
            timing = timing,
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Error, statusModelError)
    }

    @Test
    fun computeMainVisionStatus_stoppedAndDisabled() {
        val timing = VisionTimingSnapshot(modelLoaded = true)

        // User disabled inset camera yields Stopped
        val statusDisabled = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Running,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = timing,
            hasFreshOverlay = true,
            hasCameraPermission = true,
            isCameraEnabled = false,
        )
        assertEquals(MainVisionStatus.Stopped, statusDisabled)

        // Camera stopped yields Stopped
        val statusStopped = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Stopped,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = timing,
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Stopped, statusStopped)
    }

    @Test
    fun computeMainVisionStatus_initializingAndModelAbsent() {
        // Camera starting yields Initializing
        val statusStarting = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Starting,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = VisionTimingSnapshot(),
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Initializing, statusStarting)

        // Model loading yields Initializing
        val statusLoading = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Running,
            visionModelState = VisionModelState.Loading("loading.tflite"),
            timing = VisionTimingSnapshot(modelLoaded = false),
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Initializing, statusLoading)

        // Camera running without model loaded yields ModelAbsent
        val statusModelAbsent = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Running,
            visionModelState = VisionModelState.Missing,
            timing = VisionTimingSnapshot(modelLoaded = false),
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.ModelAbsent, statusModelAbsent)

        // Camera running with model ready but before first fresh inference frame arrives yields Initializing
        val statusAwaitingFirstFrame = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Running,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = VisionTimingSnapshot(modelLoaded = true),
            hasFreshOverlay = false,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Initializing, statusAwaitingFirstFrame)
    }

    @Test
    fun computeMainVisionStatus_inferencingOnlyWhenFreshFramesActive() {
        val timing = VisionTimingSnapshot(
            modelLoaded = true,
            steadyFrames = 15,
            steadyProcessedFps = 18.5,
        )

        // Real inference running with fresh overlay yields Inferencing
        val statusActiveInference = computeMainVisionStatus(
            cameraStatus = CameraSessionStatus.Running,
            visionModelState = VisionModelState.Ready(metadata = createDummyMetadata()),
            timing = timing,
            hasFreshOverlay = true,
            hasCameraPermission = true,
            isCameraEnabled = true,
        )
        assertEquals(MainVisionStatus.Inferencing, statusActiveInference)
    }

    @Test
    fun formatCadenceOrLatency_formatsCorrectly() {
        val dummyPercentile = StagePercentiles(10, 10, 32.4, 45.0, 34.0, 28.0, 50.0)

        // Both FPS and latency available
        val timingBoth = VisionTimingSnapshot(
            steadyProcessedFps = 19.8,
            totalAnalyzerMs = dummyPercentile,
        )
        assertEquals("20 FPS · 32ms", formatCadenceOrLatency(timingBoth))

        // Only FPS available
        val timingFpsOnly = VisionTimingSnapshot(steadyProcessedFps = 15.2)
        assertEquals("15 FPS", formatCadenceOrLatency(timingFpsOnly))

        // Only latency available (warmup before steady FPS calculated)
        val timingLatencyOnly = VisionTimingSnapshot(
            steadyProcessedFps = 0.0,
            modelInvokeMs = dummyPercentile,
        )
        assertEquals("32ms", formatCadenceOrLatency(timingLatencyOnly))

        // Neither available
        val timingEmpty = VisionTimingSnapshot()
        assertNull(formatCadenceOrLatency(timingEmpty))
    }

    @Test
    fun pendingCameraStartGate_screenEligibilityGuards() {
        // Home tab is eligible when admitted, no startup settings, and no permission loss
        assertTrue(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 0,
                debugDestination = DebugDestination.None,
                showLicenses = false,
                hasBeenAdmitted = true,
                isStartupSettingsVisible = false,
                permissionLossDetected = false,
            )
        )

        // Home tab is NOT eligible if not admitted yet
        assertFalse(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 0,
                debugDestination = DebugDestination.None,
                showLicenses = false,
                hasBeenAdmitted = false,
                isStartupSettingsVisible = false,
                permissionLossDetected = false,
            )
        )

        // Home tab is NOT eligible if startup settings is visible
        assertFalse(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 0,
                debugDestination = DebugDestination.None,
                showLicenses = false,
                hasBeenAdmitted = true,
                isStartupSettingsVisible = true,
                permissionLossDetected = false,
            )
        )

        // Home tab is NOT eligible if permission loss is detected
        assertFalse(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 0,
                debugDestination = DebugDestination.None,
                showLicenses = false,
                hasBeenAdmitted = true,
                isStartupSettingsVisible = false,
                permissionLossDetected = true,
            )
        )

        // Settings tab: VisionDiagnostics is eligible
        assertTrue(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 1,
                debugDestination = DebugDestination.VisionDiagnostics,
                showLicenses = false,
                hasBeenAdmitted = true,
            )
        )

        // Settings tab: PresentationDemo is eligible
        assertTrue(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 1,
                debugDestination = DebugDestination.PresentationDemo,
                showLicenses = false,
                hasBeenAdmitted = true,
            )
        )

        // Settings tab: root (DebugDestination.None) is NOT eligible
        assertFalse(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 1,
                debugDestination = DebugDestination.None,
                showLicenses = false,
                hasBeenAdmitted = true,
            )
        )

        // Settings tab: VoiceDiagnostics is NOT eligible
        assertFalse(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 1,
                debugDestination = DebugDestination.VoiceDiagnostics,
                showLicenses = false,
                hasBeenAdmitted = true,
            )
        )

        // Settings tab: showLicenses = true is NOT eligible even if debugDestination was set
        assertFalse(
            PendingCameraStartGate.isCameraEligibleScreen(
                selectedTab = 1,
                debugDestination = DebugDestination.VisionDiagnostics,
                showLicenses = true,
                hasBeenAdmitted = true,
            )
        )
    }

    @Test
    fun isDistanceMeasurementValid_strictFreshnessAndNumericGuards() {
        val nowNanos = 10_000_000_000L

        // Valid case: 2.3m, fresh within 1.0s, camera running, profile valid, home visible
        assertTrue(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 2.3f,
                timestampNanos = nowNanos - 1_000_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // Stale measurement (> 1.5s old) must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 2.3f,
                timestampNanos = nowNanos - 1_600_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // Future timestamp (clock skew or sensor anomaly where age < 0) must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 2.3f,
                timestampNanos = nowNanos + 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // Timestamp zero (uninitialized) must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 2.3f,
                timestampNanos = 0L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // Negative or zero distance must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = -0.5f,
                timestampNanos = nowNanos - 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 0.0f,
                timestampNanos = nowNanos - 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // NaN and Infinite distance must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = Float.NaN,
                timestampNanos = nowNanos - 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = Float.POSITIVE_INFINITY,
                timestampNanos = nowNanos - 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // Home hidden must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = false,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 2.3f,
                timestampNanos = nowNanos - 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // Camera stopped must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = false,
                isProfileValid = true,
                distanceMeters = 2.3f,
                timestampNanos = nowNanos - 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )

        // Profile invalid must be rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = false,
                distanceMeters = 2.3f,
                timestampNanos = nowNanos - 500_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
            )
        )
    }

    @Test
    fun evaluateDistancePresentation_truthfulKoreanBadges() {
        val nowNanos = 10_000_000_000L
        val validProfile = BodyMountProfile(175f, BodyMountPosition.SHOULDER)

        // 1. Unconfigured profile prompts setup hint
        val unconfigured = evaluateDistancePresentation(
            isHomeVisible = true,
            cameraStatus = CameraSessionStatus.Running,
            profile = null,
            groundContactDistance = GroundContactDistance(),
            currentElapsedRealtimeNanos = nowNanos,
        )
        assertEquals("키·착용 위치 설정", unconfigured.badgeText)
        assertTrue(unconfigured.isActionableSetupHint)
        assertFalse(unconfigured.isFreshNumeric)

        // 2. Camera stopped shows stopped text
        val stopped = evaluateDistancePresentation(
            isHomeVisible = true,
            cameraStatus = CameraSessionStatus.Stopped,
            profile = validProfile,
            groundContactDistance = GroundContactDistance(distanceMeters = 2.3f, timestampNanos = nowNanos),
            currentElapsedRealtimeNanos = nowNanos,
            stoppedText = "비전 중지됨",
        )
        assertEquals("비전 중지됨", stopped.badgeText)
        assertFalse(stopped.isActionableSetupHint)
        assertFalse(stopped.isFreshNumeric)

        // 3. Valid fresh numeric distance shows estimated distance with '추정' and '약'
        val fresh = evaluateDistancePresentation(
            isHomeVisible = true,
            cameraStatus = CameraSessionStatus.Running,
            profile = validProfile,
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.34f,
                timestampNanos = nowNanos - 300_000_000L,
            ),
            currentElapsedRealtimeNanos = nowNanos,
        )
        assertEquals("추정 약 2.3m", fresh.badgeText)
        assertTrue(fresh.isFreshNumeric)
        assertFalse(fresh.isActionableSetupHint)
        assertTrue(fresh.distanceDescription.contains("추정 약 2.3미터"))

        // 4. Stale distance (>1.5s old) falls back to measuring
        val stale = evaluateDistancePresentation(
            isHomeVisible = true,
            cameraStatus = CameraSessionStatus.Running,
            profile = validProfile,
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.34f,
                timestampNanos = nowNanos - 2_000_000_000L,
            ),
            currentElapsedRealtimeNanos = nowNanos,
            measuringText = "거리 확인 중",
        )
        assertEquals("거리 확인 중", stale.badgeText)
        assertFalse(stale.isFreshNumeric)

        // 5. Fresh custom message from core
        val customMsg = evaluateDistancePresentation(
            isHomeVisible = true,
            cameraStatus = CameraSessionStatus.Running,
            profile = validProfile,
            groundContactDistance = GroundContactDistance(
                distanceMeters = null,
                message = "전방 장애물 미감지",
                timestampNanos = nowNanos - 400_000_000L,
            ),
            currentElapsedRealtimeNanos = nowNanos,
        )
        assertEquals("전방 장애물 미감지", customMsg.badgeText)
        assertFalse(customMsg.isFreshNumeric)
    }

    @Test
    fun isDistanceMeasurementValid_rejectsTimestampsOlderThanMinValidTimestamp() {
        val nowNanos = 10_000_000_000L
        val minValid = 9_500_000_000L

        // Timestamp captured before minValid (e.g. before profile change) is rejected
        assertFalse(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 2.5f,
                timestampNanos = 9_400_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
                minValidTimestampNanos = minValid,
            )
        )

        // Timestamp captured at or after minValid is accepted
        assertTrue(
            isDistanceMeasurementValid(
                isHomeVisible = true,
                isCameraRunning = true,
                isProfileValid = true,
                distanceMeters = 2.5f,
                timestampNanos = 9_600_000_000L,
                currentElapsedRealtimeNanos = nowNanos,
                minValidTimestampNanos = minValid,
            )
        )
    }

    @Test
    fun evaluateDistancePresentation_invalidatesNumericUntilNewerFrameThanMinValid() {
        val nowNanos = 10_000_000_000L
        val minValid = 9_500_000_000L
        val validProfile = BodyMountProfile(175f, BodyMountPosition.SHOULDER)

        // Old frame captured at 9.4s (< minValid 9.5s) must be invalidated to "거리 확인 중"
        val invalidated = evaluateDistancePresentation(
            isHomeVisible = true,
            cameraStatus = CameraSessionStatus.Running,
            profile = validProfile,
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.3f,
                timestampNanos = 9_400_000_000L,
            ),
            currentElapsedRealtimeNanos = nowNanos,
            minValidTimestampNanos = minValid,
        )
        assertEquals("거리 확인 중", invalidated.badgeText)
        assertFalse(invalidated.isFreshNumeric)

        // New frame captured at 9.6s (>= minValid 9.5s) is displayed as numeric
        val newFrame = evaluateDistancePresentation(
            isHomeVisible = true,
            cameraStatus = CameraSessionStatus.Running,
            profile = validProfile,
            groundContactDistance = GroundContactDistance(
                distanceMeters = 2.3f,
                timestampNanos = 9_600_000_000L,
            ),
            currentElapsedRealtimeNanos = nowNanos,
            minValidTimestampNanos = minValid,
        )
        assertEquals("추정 약 2.3m", newFrame.badgeText)
        assertTrue(newFrame.isFreshNumeric)
    }
}
