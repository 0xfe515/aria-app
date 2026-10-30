package com.ariateam.aria.ui.screen

import com.ariateam.aria.DebugDestination
import com.ariateam.aria.PendingCameraStartGate
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
}
