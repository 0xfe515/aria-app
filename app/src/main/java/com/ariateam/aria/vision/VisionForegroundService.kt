package com.ariateam.aria.vision

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ariateam.aria.runtime.AriaRuntimeStateBridge
import com.ariateam.aria.runtime.AriaUnifiedNotificationCoordinator
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class VisionForegroundService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionWatchJob: Job? = null
    private var foreground = false
    private var sessionStarted = false
    private var activeMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE
    private var currentSessionId: String? = null
    private val serviceLifetimeToken = Binder()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        AriaUnifiedNotificationCoordinator.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(startId)
            ACTION_STOP -> {
                VisionSession.markExplicitStop(this)
                VisionSession.cancelPendingStartRequest()
                stopForegroundSession()
                stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startSession(startId: Int) {
        val request = VisionSession.takePendingStartRequest()
        if (request == null) {
            if (!sessionStarted) stopSelf(startId)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val controller = VisionSession.ensureController(applicationContext)
            controller.permissionDenied()
            AriaUnifiedNotificationCoordinator.postErrorNotification(
                context = this,
                title = "비전 모니터링 중지",
                detail = controller.uiState.value.cameraErrorDetail.orEmpty().ifBlank {
                    "카메라 권한이 필요합니다. 설정에서 권한을 허용해 주세요."
                },
            )
            stopForegroundSession()
            stopSelf(startId)
            return
        }

        try {
            val sessionToken = "vision_${System.currentTimeMillis()}_${startId}_${UUID.randomUUID().toString().take(8)}"
            currentSessionId = sessionToken
            AriaRuntimeStateBridge.setVisionActive(this, true, sessionId = sessionToken, token = serviceLifetimeToken)
            if (!foreground) {
                val voiceActive = AriaRuntimeStateBridge.isVoiceActive(this)
                startForeground(
                    AriaUnifiedNotificationCoordinator.NOTIFICATION_ID,
                    AriaUnifiedNotificationCoordinator.buildUnifiedNotification(
                        context = this,
                        voiceActive = voiceActive,
                        visionActive = true,
                    ),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
                foreground = true
                AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                    context = this,
                    voiceActive = voiceActive,
                    visionActive = true,
                    force = true,
                )
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }

            val controller = VisionSession.ensureController(applicationContext)
            if (sessionStarted) {
                controller.updateSurfaceProvider(request.surfaceProvider, request.targetRotation)
                if (activeMode != request.mode) controller.setPipelineMode(request.mode)
            } else {
                controller.startCamera(
                    lifecycleOwner = this,
                    surfaceProvider = request.surfaceProvider,
                    mode = request.mode,
                    targetRotation = request.targetRotation,
                )
                sessionStarted = true
            }
            activeMode = request.mode
            watchSession(controller)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start camera foreground session", error)
            VisionSession.ensureController(applicationContext).reportCameraServiceFailure(
                "카메라 모니터링을 시작하지 못했습니다 (${error.javaClass.simpleName}). 앱 화면에서 다시 시작해 주세요.",
            )
            stopForegroundSession()
            stopSelf(startId)
        }
    }

    private fun watchSession(controller: VisionPipelineController) {
        sessionWatchJob?.cancel()
        sessionWatchJob = serviceScope.launch {
            controller.uiState.collect { state ->
                when (state.cameraStatus) {
                    CameraSessionStatus.CameraUnavailable -> {
                        val voiceActive = AriaRuntimeStateBridge.isVoiceActive(this@VisionForegroundService)
                        AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                            context = this@VisionForegroundService,
                            voiceActive = voiceActive,
                            visionActive = true,
                            cameraUnavailable = true,
                        )
                    }
                    CameraSessionStatus.Running -> {
                        AriaUnifiedNotificationCoordinator.cancelErrorNotification(this@VisionForegroundService)
                        val voiceActive = AriaRuntimeStateBridge.isVoiceActive(this@VisionForegroundService)
                        AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                            context = this@VisionForegroundService,
                            voiceActive = voiceActive,
                            visionActive = true,
                            cameraUnavailable = false,
                        )
                    }
                    CameraSessionStatus.Error, CameraSessionStatus.PermissionDenied -> {
                        Log.w(TAG, "Camera session ended: ${state.cameraErrorDetail.orEmpty()}")
                        AriaUnifiedNotificationCoordinator.postErrorNotification(
                            context = this@VisionForegroundService,
                            title = "비전 모니터링 중지",
                            detail = state.cameraErrorDetail.orEmpty().ifBlank {
                                "카메라 세션이 중지되었습니다. 앱을 열어 상태를 확인해 주세요."
                            },
                        )
                        stopForegroundSession()
                        stopSelf()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun stopForegroundSession() {
        AriaRuntimeStateBridge.setVisionActive(this, false, sessionId = currentSessionId, token = serviceLifetimeToken)
        if (foreground) {
            val voiceActive = AriaRuntimeStateBridge.isVoiceActive(this)
            if (voiceActive) {
                stopForeground(STOP_FOREGROUND_DETACH)
                AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                    context = this,
                    voiceActive = true,
                    visionActive = false,
                )
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                AriaUnifiedNotificationCoordinator.updateUnifiedNotification(
                    context = this,
                    voiceActive = false,
                    visionActive = false,
                )
            }
            foreground = false
        }
    }

    override fun onDestroy() {
        sessionWatchJob?.cancel()
        serviceScope.cancel()
        stopForegroundSession()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        VisionSession.closeController()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ariateam.aria.vision.START"
        const val ACTION_STOP = "com.ariateam.aria.vision.STOP"
        private const val TAG = "AriaVisionService"

        fun requestStart(
            context: Context,
            surfaceProvider: Preview.SurfaceProvider?,
            mode: VisionPipelineMode,
            targetRotation: Int,
            explicitRestart: Boolean = false,
        ): Boolean {
            if (VisionSession.requiresExplicitRestart(context)) {
                if (!explicitRestart) return false
                VisionSession.clearExplicitStop(context)
            }
            VisionSession.setPendingStartRequest(
                VisionSessionStartRequest(surfaceProvider, mode, targetRotation),
            )
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VisionForegroundService::class.java).setAction(ACTION_START),
                )
                true
            } catch (error: Exception) {
                Log.e(TAG, "Camera foreground service start was rejected", error)
                VisionSession.cancelPendingStartRequest()
                VisionSession.ensureController(context).reportCameraServiceFailure(
                    "카메라 모니터링 서비스를 시작하지 못했습니다 (${error.javaClass.simpleName}). 앱 화면에서 다시 시작해 주세요.",
                )
                false
            }
        }

        fun requestStop(context: Context) {
            VisionSession.cancelPendingStartRequest()
            context.stopService(Intent(context, VisionForegroundService::class.java))
        }
    }
}
