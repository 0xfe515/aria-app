package com.ariateam.aria

import androidx.camera.core.Preview
import com.ariateam.aria.vision.VisionPipelineMode

enum class DebugDestination {
    None,
    VisionDiagnostics,
    VoiceDiagnostics,
    PresentationDemo,
}

data class PendingCameraStartRequest(
    val token: Long,
    val destination: DebugDestination,
    val surfaceProvider: Preview.SurfaceProvider?,
    val mode: VisionPipelineMode,
    val targetRotation: Int,
    val permissionGranted: Boolean = false,
)

/**
 * Guards camera start requests across asynchronous permission dialogs and navigation/lifecycle changes.
 *
 * Ensures that granting a pending camera permission after navigating away from `VisionDiagnostics`
 * or `PresentationDemo` (via Back, tab switch, voice navigation, configuration change, or backgrounding)
 * can never start a hidden camera session on Home or Settings.
 */
class PendingCameraStartGate {
    private var nextToken: Long = 0L

    var pendingRequest: PendingCameraStartRequest? = null
        private set

    val activeToken: Long
        get() = nextToken

    fun issueRequest(
        destination: DebugDestination,
        surfaceProvider: Preview.SurfaceProvider?,
        mode: VisionPipelineMode,
        targetRotation: Int,
    ): PendingCameraStartRequest? {
        if (!isCameraAllowedDestination(destination)) {
            invalidate()
            return null
        }
        val token = ++nextToken
        return PendingCameraStartRequest(
            token = token,
            destination = destination,
            surfaceProvider = surfaceProvider,
            mode = mode,
            targetRotation = targetRotation,
            permissionGranted = false,
        ).also {
            pendingRequest = it
        }
    }

    fun updateSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?, targetRotation: Int) {
        val current = pendingRequest ?: return
        pendingRequest = current.copy(
            surfaceProvider = surfaceProvider,
            targetRotation = targetRotation,
        )
    }

    fun invalidate() {
        nextToken++
        pendingRequest = null
    }

    sealed interface PermissionDecision {
        data object Ignored : PermissionDecision
        data object Denied : PermissionDecision
        data object DeferredUntilResumed : PermissionDecision
        data class StartNow(val request: PendingCameraStartRequest) : PermissionDecision
    }

    fun onPermissionCallback(
        granted: Boolean,
        selectedTab: Int,
        currentDestination: DebugDestination,
        isLifecycleResumed: Boolean,
    ): PermissionDecision {
        val current = pendingRequest
        if (current == null || current.token != nextToken) {
            invalidate()
            return PermissionDecision.Ignored
        }
        if (selectedTab != 1 || !isCameraAllowedDestination(currentDestination) || currentDestination != current.destination) {
            invalidate()
            return PermissionDecision.Ignored
        }
        if (!granted) {
            invalidate()
            return PermissionDecision.Denied
        }
        if (isLifecycleResumed) {
            invalidate()
            return PermissionDecision.StartNow(current.copy(permissionGranted = true))
        }
        pendingRequest = current.copy(permissionGranted = true)
        return PermissionDecision.DeferredUntilResumed
    }

    fun consumeGrantedOnResumeIfEligible(
        selectedTab: Int,
        currentDestination: DebugDestination,
        isLifecycleResumed: Boolean,
    ): PendingCameraStartRequest? {
        val current = pendingRequest ?: return null
        if (!current.permissionGranted) return null
        if (current.token != nextToken ||
            selectedTab != 1 ||
            !isCameraAllowedDestination(currentDestination) ||
            currentDestination != current.destination ||
            !isLifecycleResumed
        ) {
            invalidate()
            return null
        }
        invalidate()
        return current
    }

    companion object {
        fun isCameraAllowedDestination(destination: DebugDestination): Boolean =
            destination == DebugDestination.VisionDiagnostics ||
                destination == DebugDestination.PresentationDemo

        fun isHomeCameraAllowed(
            selectedTab: Int,
            hasBeenAdmitted: Boolean,
            isStartupSettingsVisible: Boolean = false,
            permissionLossDetected: Boolean = false,
        ): Boolean =
            selectedTab == 0 && hasBeenAdmitted && !isStartupSettingsVisible && !permissionLossDetected

        fun isCameraEligibleScreen(
            selectedTab: Int,
            debugDestination: DebugDestination,
            showLicenses: Boolean,
            hasBeenAdmitted: Boolean,
            isStartupSettingsVisible: Boolean = false,
            permissionLossDetected: Boolean = false,
        ): Boolean =
            if (selectedTab == 0) {
                isHomeCameraAllowed(selectedTab, hasBeenAdmitted, isStartupSettingsVisible, permissionLossDetected)
            } else {
                selectedTab == 1 && !showLicenses && isCameraAllowedDestination(debugDestination)
            }
    }
}
