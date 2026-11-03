package com.ariateam.aria.vision

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ariateam.aria.MainActivity
import com.ariateam.aria.R
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

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "비전 모니터링", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(startId)
            ACTION_STOP -> {
                VisionSession.markExplicitStop(this)
                VisionSession.cancelPendingStartRequest()
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
            postCameraFailureNotification(controller.uiState.value.cameraErrorDetail.orEmpty())
            stopForegroundSession()
            stopSelf(startId)
            return
        }

        try {
            if (!foreground) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
                foreground = true
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
                if (state.cameraStatus == CameraSessionStatus.Error ||
                    state.cameraStatus == CameraSessionStatus.PermissionDenied
                ) {
                    Log.w(TAG, "Camera session ended: ${state.cameraErrorDetail.orEmpty()}")
                    postCameraFailureNotification(state.cameraErrorDetail.orEmpty())
                    stopForegroundSession()
                    stopSelf()
                }
            }
        }
    }

    private fun stopForegroundSession() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    private fun postCameraFailureNotification(detail: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            ERROR_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("비전 모니터링 중지")
            .setContentText(detail.ifBlank { "카메라 세션이 중지되었습니다. 앱을 열어 상태를 확인해 주세요." })
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, VisionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("카메라로 주변 위험을 모니터링 중입니다.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(null, "모니터링 중지", stopIntent).build(),
            )
            .setOngoing(true)
            .build()
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
        private const val CHANNEL_ID = "aria_vision_camera"
        private const val NOTIFICATION_ID = 3
        private const val ERROR_NOTIFICATION_ID = 5
        private const val STOP_REQUEST_CODE = 4
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
