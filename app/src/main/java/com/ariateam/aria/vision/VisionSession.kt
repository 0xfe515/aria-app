package com.ariateam.aria.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.Preview
import com.ariateam.aria.distance.BodyMountProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VisionSessionStartRequest(
    val surfaceProvider: Preview.SurfaceProvider?,
    val mode: VisionPipelineMode,
    val targetRotation: Int,
)

internal object VisionSession {
    private val lock = Any()
    private val mutableUiState = MutableStateFlow(VisionUiState())
    private val mutableModelState = MutableStateFlow<VisionModelState>(VisionModelState.Missing)
    private val mutableInitialRestoreCompleted = MutableStateFlow(false)

    val uiState: StateFlow<VisionUiState> = mutableUiState.asStateFlow()
    val modelState: StateFlow<VisionModelState> = mutableModelState.asStateFlow()
    val isInitialRestoreCompleted: StateFlow<Boolean> = mutableInitialRestoreCompleted.asStateFlow()

    private const val SESSION_PREFS = "aria_vision_session"
    private const val KEY_USER_STOPPED = "user_stopped"
    @Volatile
    private var userStopRequested = false

    @Volatile
    private var controller: VisionPipelineController? = null

    val isInitialized: Boolean
        get() = controller != null

    fun captureSingleFrame(callback: (Bitmap?) -> Unit) {
        val ctrl = controller
        if (ctrl == null) {
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

    private var pendingStartRequest: VisionSessionStartRequest? = null

    fun ensureController(context: Context): VisionPipelineController {
        controller?.let { return it }
        return synchronized(lock) {
            controller ?: createController(context.applicationContext).also { controller = it }
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
