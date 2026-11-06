package com.ariateam.aria.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import com.ariateam.aria.distance.BodyMountProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal data class VisionSessionStartRequest(
    val surfaceProvider: Preview.SurfaceProvider?,
    val mode: VisionPipelineMode,
    val targetRotation: Int,
)

internal object VisionSession {
    private val lock = Any()
    private val walkingAssistGeneration = AtomicLong(0L)
    private val mutableUiState = MutableStateFlow(VisionUiState())
    private val mutableModelState = MutableStateFlow<VisionModelState>(VisionModelState.Missing)
    private val mutableInitialRestoreCompleted = MutableStateFlow(false)

    val uiState: StateFlow<VisionUiState> = mutableUiState.asStateFlow()
    val modelState: StateFlow<VisionModelState> = mutableModelState.asStateFlow()
    val isInitialRestoreCompleted: StateFlow<Boolean> = mutableInitialRestoreCompleted.asStateFlow()

    internal fun nextWalkingAssistGeneration(): Long = walkingAssistGeneration.updateAndGet { previous ->
        maxOf(SystemClock.elapsedRealtimeNanos(), previous + 1L)
    }

    private const val SESSION_PREFS = "aria_vision_session"
    private const val KEY_USER_STOPPED = "user_stopped"
    @Volatile
    private var userStopRequested = false

    internal fun setUserStopRequestedForTesting(value: Boolean) {
        userStopRequested = value
    }

    internal val isUserStopRequestedForTesting: Boolean
        get() = userStopRequested

    @Volatile
    private var controller: VisionPipelineController? = null

    val isInitialized: Boolean
        get() = controller?.isClosed == false

    fun captureSingleFrame(callback: (Bitmap?) -> Unit) {
        val ctrl = controller
        if (ctrl == null || ctrl.isClosed) {
            callback(null)
            return
        }
        ctrl.captureSingleFrame(callback)
    }

    fun requiresExplicitRestart(context: Context): Boolean =
        userStopRequested ||
            context.applicationContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_STOPPED, false)

    fun clearExplicitStop(context: Context) {
        userStopRequested = false
        context.applicationContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_STOPPED, false)
            .commit()
    }

    fun markExplicitStop(context: Context) {
        userStopRequested = true
        val persisted = context.applicationContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_STOPPED, true)
            .commit()
        if (!persisted) Log.e("AriaVisionSession", "Could not persist explicit camera stop state")
    }

    fun onExplicitAppStart(context: Context) {
        clearExplicitStop(context)
        synchronized(lock) {
            if (controller?.isClosed == true) {
                controller = null
                mutableModelState.value = VisionModelState.Missing
                mutableInitialRestoreCompleted.value = false
            }
            if (mutableUiState.value.cameraStatus == CameraSessionStatus.Error) {
                mutableUiState.value = mutableUiState.value.copy(
                    cameraStatus = CameraSessionStatus.Stopped,
                    cameraErrorDetail = null,
                )
            }
        }
    }

    private var pendingStartRequest: VisionSessionStartRequest? = null

    fun ensureController(context: Context): VisionPipelineController {
        val current = controller
        if (current != null && !current.isClosed) return current
        return synchronized(lock) {
            val lockedCurrent = controller
            if (lockedCurrent != null && !lockedCurrent.isClosed) {
                lockedCurrent
            } else {
                mutableModelState.value = VisionModelState.Missing
                mutableInitialRestoreCompleted.value = false
                createController(context.applicationContext).also { controller = it }
            }
        }
    }

    fun setPendingStartRequest(request: VisionSessionStartRequest) {
        synchronized(lock) {
            pendingStartRequest = request
        }
    }

    fun takePendingStartRequest(): VisionSessionStartRequest? = synchronized(lock) {
        pendingStartRequest.also { pendingStartRequest = null }
    }

    fun cancelPendingStartRequest() {
        synchronized(lock) {
            pendingStartRequest = null
        }
    }

    fun closeController() {
        synchronized(lock) {
            val toClose = controller ?: return
            toClose.close()
            controller = null
            mutableModelState.value = VisionModelState.Missing
            mutableInitialRestoreCompleted.value = false
        }
    }

    fun closeExpectedController(expected: VisionPipelineController?): Boolean {
        if (expected == null) return false
        synchronized(lock) {
            val current = controller
            return if (current === expected) {
                current.close()
                controller = null
                mutableModelState.value = VisionModelState.Missing
                mutableInitialRestoreCompleted.value = false
                true
            } else {
                if (!expected.isClosed) {
                    expected.close()
                }
                false
            }
        }
    }

    fun diagnosticSummary(context: Context): String {
        val ctrl = controller
        return "VisionSession(hasController=${ctrl != null}, controllerId=${if (ctrl != null) Integer.toHexString(System.identityHashCode(ctrl)) else "null"}, isRunning=${ctrl?.isRunning}, isClosed=${ctrl?.isClosed}, userStopLatch=${requiresExplicitRestart(context)}, pendingRequest=${pendingStartRequest != null})"
    }

    private fun createController(context: Context): VisionPipelineController {
        val modelStore = VisionModelStore(
            context = context,
            mutableState = mutableModelState,
            mutableInitialRestoreCompleted = mutableInitialRestoreCompleted,
        )
        return VisionPipelineController(
            context = context,
            modelStore = modelStore,
            bodyMountProfileStore = BodyMountProfileStore(context),
            mutableUiState = mutableUiState,
        )
    }
}
