package com.ariateam.aria.distance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.ui.screen.BodyMountProfileDialog
import com.ariateam.aria.ui.screen.DistanceScreen
import com.ariateam.aria.ui.theme.AriaTheme

/**
 * Dedicated internal non-exported Activity hosting the normal monocular ground-contact distance mode.
 *
 * Provides exclusive camera lifecycle ownership, using CameraX and [com.ariateam.aria.vision.VisionPipelineController]
 * to estimate ground contact distance without ARCore or Raw Depth hardware requirements.
 */
class DistanceActivity : ComponentActivity() {

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, DistanceActivity::class.java)
        }
    }

    private val viewModel: GroundContactDistanceViewModel by viewModels()

    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var isActivityResumed = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setSetupStage(DistanceSetupStage.TRACKING)
            startCameraIfReady()
        } else {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_PERMISSION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            AriaTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var showProfileDialog by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                keepScreenOn = true
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                previewSurfaceProvider = surfaceProvider
                                startCameraIfReady()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    DistanceScreen(
                        uiState = uiState,
                        onBack = { finish() },
                        onRecalibrate = { viewModel.recalibrate() },
                        onOpenProfileSetup = { showProfileDialog = true },
                        onDismissOnboarding = { dontShowAgain ->
                            viewModel.dismissOnboarding(dontShowAgain)
                        },
                    )

                    if (showProfileDialog) {
                        BodyMountProfileDialog(
                            onDismiss = { showProfileDialog = false }
                        )
                    }
                }
            }
        }
    }

    private fun startCameraIfReady() {
        if (!isActivityResumed || isFinishing || isDestroyed) return
        val provider = previewSurfaceProvider ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startCamera(
                lifecycleOwner = this,
                surfaceProvider = provider,
                targetRotation = display?.rotation ?: Surface.ROTATION_0,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            viewModel.setSetupStage(DistanceSetupStage.ERROR_PERMISSION)
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        viewModel.setSetupStage(DistanceSetupStage.TRACKING)
        startCameraIfReady()
    }

    override fun onPause() {
        isActivityResumed = false
        viewModel.stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        isActivityResumed = false
        viewModel.stopCamera()
        previewSurfaceProvider = null
        super.onDestroy()
    }
}
