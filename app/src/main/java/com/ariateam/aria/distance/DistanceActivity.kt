package com.ariateam.aria.distance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.ui.screen.DistanceScreen
import com.ariateam.aria.ui.theme.AriaTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

/**
 * Dedicated internal non-exported Activity hosting the ARCore Raw Depth obstacle distance mode.
 *
 * Provides exclusive camera lifecycle ownership, stopping existing CameraX when entered and releasing
 * ARCore sessions cleanly before returning to MainActivity.
 */
class DistanceActivity : ComponentActivity() {

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, DistanceActivity::class.java)
        }
    }

    private val viewModel: DistanceViewModel by viewModels()

    private var session: Session? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: DistanceRenderer? = null
    private var installRequested = false
    private var isActivityResumed = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initOrResumeArSession()
        } else {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_PERMISSION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
        }
        val distanceRenderer = DistanceRenderer(
            frameListener = { frame, currentSession ->
                viewModel.onFrameAvailable(frame, currentSession)
            },
            errorListener = { throwable ->
                viewModel.onRendererError(throwable)
            },
        )
        surfaceView.setRenderer(distanceRenderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        this.glSurfaceView = surfaceView
        this.renderer = distanceRenderer

        setContent {
            AriaTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { surfaceView },
                        modifier = Modifier.fillMaxSize(),
                    )

                    DistanceScreen(
                        uiState = uiState,
                        onBack = { finish() },
                        onRecalibrate = { viewModel.recalibrate() },
                        onDismissOnboarding = { dontShowAgain ->
                            viewModel.dismissOnboarding(dontShowAgain)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        viewModel.onResumeSession(hasActiveSession = session != null)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_PERMISSION)
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        initOrResumeArSession()
    }

    private fun initOrResumeArSession() {
        if (!isActivityResumed || isFinishing || isDestroyed) {
            return
        }

        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        viewModel.setSetupStage(DistanceSetupStage.ERROR_INSTALL_REQUIRED)
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                val newSession = Session(this)

                // Conservative requirement: raw depth support must be present on device
                if (!newSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    newSession.close()
                    viewModel.setSetupStage(DistanceSetupStage.ERROR_DEPTH_UNSUPPORTED)
                    return
                }

                val config = Config(newSession).apply {
                    depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    focusMode = Config.FocusMode.AUTO
                }
                newSession.configure(config)

                this.session = newSession
                renderer?.attachSession(newSession)
                viewModel.setRawDepthActive(true)
                viewModel.setSetupStage(DistanceSetupStage.FLOOR_SEARCH)
            }

            session?.resume()
            glSurfaceView?.onResume()
            renderer?.updateDisplayRotation(display?.rotation ?: 0)
        } catch (e: UnavailableUserDeclinedInstallationException) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_INSTALL_REQUIRED, e.message)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_AR_UNAVAILABLE, e.message)
        } catch (e: UnavailableApkTooOldException) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_INSTALL_REQUIRED, e.message)
        } catch (e: UnavailableSdkTooOldException) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_INSTALL_REQUIRED, e.message)
        } catch (e: CameraNotAvailableException) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_AR_UNAVAILABLE, e.message)
        } catch (e: Throwable) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_AR_UNAVAILABLE, e.message)
        }
    }

    override fun onPause() {
        isActivityResumed = false
        glSurfaceView?.onPause()
        try {
            session?.pause()
        } catch (_: Throwable) {
        }
        viewModel.onPauseSession()
        super.onPause()
    }

    override fun onDestroy() {
        isActivityResumed = false
        renderer?.detachSession()
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        glSurfaceView = null
        renderer = null
        super.onDestroy()
    }
}
