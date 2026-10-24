package com.ariateam.aria.ui.screen

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ariateam.aria.DebugDestination
import com.ariateam.aria.PendingCameraStartGate
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceViewModel
import com.ariateam.aria.startup.DefaultStartupSoundPlayer
import com.ariateam.aria.startup.StartupCuePolicy
import com.ariateam.aria.startup.StartupDecision
import com.ariateam.aria.startup.StartupIssue
import com.ariateam.aria.startup.StartupPolicy
import com.ariateam.aria.startup.StartupPermissionInventory
import com.ariateam.aria.startup.StartupSoundPlayer
import com.ariateam.aria.startup.StartupStage
import com.ariateam.aria.startup.StartupViewModel
import com.ariateam.aria.ui.feedback.FeedbackToast
import com.ariateam.aria.ui.feedback.ToastFeedbackPolicy
import com.ariateam.aria.ui.navigation.FloatingTabBar
import com.ariateam.aria.ui.navigation.FloatingTabBarBottomMargin
import com.ariateam.aria.ui.navigation.FloatingTabBarHeight
import com.ariateam.aria.ui.navigation.calculateFloatingNavSafeBottomPadding
import com.ariateam.aria.vision.VisionPipelineMode
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun MainScreen(
    model: VoiceViewModel = viewModel(),
    visionModel: VisionViewModel = viewModel(),
    startupModel: StartupViewModel = viewModel(),
    startupSoundPlayer: StartupSoundPlayer? = null,
) {
    val context = LocalContext.current
    val effectiveStartupPlayer = remember(context, startupSoundPlayer) {
        startupSoundPlayer ?: DefaultStartupSoundPlayer(context)
    }
    val requiredRuntimePermissions = remember(Build.VERSION.SDK_INT) {
        StartupPermissionInventory.requiredRuntimePermissions(Build.VERSION.SDK_INT)
    }
    fun readMissingRuntimePermissions(): Set<String> =
        requiredRuntimePermissions.filterNot { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }.toSet()
    var missingRuntimePermissions by remember(context) {
        mutableStateOf(readMissingRuntimePermissions())
    }
    val refreshRuntimePermissions: () -> Set<String> = {
        readMissingRuntimePermissions().also { missingRuntimePermissions = it }
    }
    val hasMicPermission = StartupPermissionInventory.RECORD_AUDIO !in missingRuntimePermissions
    val grantedRuntimePermissions = requiredRuntimePermissions.filterNot(missingRuntimePermissions::contains).toSet()
    val lostGrantedPermissions = startupModel.grantedPermissionsLostSinceAdmission(grantedRuntimePermissions)
    val permissionLossDetected = lostGrantedPermissions.isNotEmpty()
    val state by model.state.collectAsStateWithLifecycle()
    val audibleFeedback by model.audibleFeedback.collectAsStateWithLifecycle()
    val visionUiState by visionModel.uiState.collectAsStateWithLifecycle()
    val visionModelState by visionModel.modelState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

    LaunchedEffect(model, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                model.refreshState()
                delay(2_000)
            }
        }
    }
    LaunchedEffect(state.phase) {
        Log.i("AriaVoiceUI", "Screen phase: ${state.phase}")
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var debugDestination by rememberSaveable { mutableStateOf(DebugDestination.None) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val pendingCameraGate = remember { PendingCameraStartGate() }

    val isVisionRestoreCompleted by visionModel.isInitialRestoreCompleted.collectAsStateWithLifecycle()
    val saveableStateHolder = rememberSaveableStateHolder()

    // Eagerly prepare vision on startup off UI thread using existing executor
    LaunchedEffect(Unit) {
        visionModel.ensureController()
    }

    val invalidatePendingAndStopCamera = {
        pendingCameraGate.invalidate()
        visionModel.stopCamera()
    }

    // Release camera resources and invalidate pending permission requests on configuration change.
    var lastOrientation by remember { mutableIntStateOf(configuration.orientation) }
    LaunchedEffect(configuration.orientation) {
        if (configuration.orientation != lastOrientation) {
            lastOrientation = configuration.orientation
            invalidatePendingAndStopCamera()
        }
    }

    LaunchedEffect(selectedTab, debugDestination, showLicenses) {
        if (selectedTab == 1 && !showLicenses && PendingCameraStartGate.isCameraAllowedDestination(debugDestination)) {
            visionModel.ensureController()
        } else {
            invalidatePendingAndStopCamera()
        }
        model.setUiScreen(if (selectedTab == 1) "settings" else "home")
    }

    var screenVisible by remember { mutableStateOf(false) }
    val toastFeedbackPolicy = rememberSaveable(saver = ToastFeedbackPolicy.Saver) {
        ToastFeedbackPolicy()
    }
    val isServiceSynced by model.isServiceSynced.collectAsStateWithLifecycle()

    val currentLatestState by rememberUpdatedState(state)
    val currentLatestVisible by rememberUpdatedState(screenVisible)

    DisposableEffect(effectiveStartupPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                effectiveStartupPlayer.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (startupSoundPlayer == null) {
                effectiveStartupPlayer.release()
            }
        }
    }

    DisposableEffect(lifecycleOwner, model, visionModel) {
        val observer = LifecycleEventObserver { _, event ->
            val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            val wasVisible = screenVisible
            screenVisible = isResumed
            model.setUiVisible(isResumed)
            if (isResumed != wasVisible) {
                // Use model.state.value for accurate latest state at transition
                toastFeedbackPolicy.onAppVisibilityChanged(isResumed, model.state.value)
            }
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                invalidatePendingAndStopCamera()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                val missingPermissions = refreshRuntimePermissions()
                val lostPermissions = startupModel.observeGrantedPermissions(
                    requiredRuntimePermissions.filterNot(missingPermissions::contains).toSet(),
                )
                if (startupModel.hasBeenAdmitted && lostPermissions.isNotEmpty()) {
                    startupModel.enterPermissionRecovery()
                    if (StartupPermissionInventory.RECORD_AUDIO in lostPermissions) model.stop()
                    invalidatePendingAndStopCamera()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        screenVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        model.setUiVisible(screenVisible)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.setUiVisible(false)
            invalidatePendingAndStopCamera()
        }
    }

    // Unified feedback effect with readiness gating handled by ToastFeedbackPolicy
    LaunchedEffect(state, screenVisible, isServiceSynced) {
        when (val feedback = toastFeedbackPolicy.evaluate(state, isAppVisible = screenVisible, isServiceSynced = isServiceSynced)) {
            is FeedbackToast.CommandOutcome -> {
                Toast.makeText(context, feedback.message, Toast.LENGTH_SHORT).show()
            }
            is FeedbackToast.VoiceErrorNotification -> {
                val msgRes = com.ariateam.aria.speech.voiceErrorMessage(feedback.errorCode)
                Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
            }
            null -> Unit
        }
    }

    LaunchedEffect(state.command.navigationId, screenVisible) {
        if (screenVisible && state.command.navigationId != 0L) {
            invalidatePendingAndStopCamera()
            debugDestination = DebugDestination.None
            showLicenses = false
            selectedTab = if (state.command.destination == "settings") 1 else 0
            model.consumeNavigation(state.command.navigationId)
        }
    }

    // Back handling: returns to Settings from sub-screens or to Home from Settings
    BackHandler(
        enabled = startupModel.isStartupSettingsVisible ||
            (startupModel.hasBeenAdmitted && (debugDestination != DebugDestination.None || showLicenses || selectedTab != 0)),
    ) {
        when {
            startupModel.isStartupSettingsVisible && debugDestination == DebugDestination.None &&
                !showLicenses -> startupModel.returnToStartup()
            debugDestination != DebugDestination.None -> {
                invalidatePendingAndStopCamera()
                debugDestination = DebugDestination.None
            }
            showLicenses -> showLicenses = false
            selectedTab != 0 -> selectedTab = 0
        }
    }

    val dispatch: (Int) -> Unit = { action ->
        when (action) {
            1 -> model.beginDictation()
            else -> model.startWaiting()
        }
    }
    val startupPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        startupModel.finishRuntimePermissionRequest()
        val missingPermissions = refreshRuntimePermissions()
        if (StartupPermissionInventory.RECORD_AUDIO in missingPermissions) {
            model.permissionDenied()
        }
    }
    val requestAction: (Int) -> Unit = { action ->
        val missingPermissions = refreshRuntimePermissions()
        if (StartupPermissionInventory.RECORD_AUDIO !in missingPermissions) {
            dispatch(action)
        } else {
            model.permissionDenied()
            startupModel.enterPermissionRecovery()
        }
    }

    LaunchedEffect(
        startupModel.hasBeenAdmitted,
        startupModel.isStartupSettingsVisible,
        startupModel.hasCompletedRuntimePermissionCheck,
        startupModel.isRequestingRuntimePermissions,
        startupModel.shouldRetryRuntimePermissionRequest,
        missingRuntimePermissions,
        screenVisible,
    ) {
        if (!startupModel.hasBeenAdmitted && !startupModel.isStartupSettingsVisible && screenVisible &&
            !startupModel.isRequestingRuntimePermissions
        ) {
            val actualMissingPermissions = refreshRuntimePermissions()
            if (actualMissingPermissions.isEmpty()) {
                startupModel.completeRuntimePermissionCheck()
            } else if (!startupModel.hasCompletedRuntimePermissionCheck ||
                startupModel.shouldRetryRuntimePermissionRequest
            ) {
                startupModel.beginRuntimePermissionRequest()
                startupPermissions.launch(
                    requiredRuntimePermissions.filter(actualMissingPermissions::contains).toTypedArray(),
                )
            }
        }
    }

    // Start wake-word listening only after the microphone is granted, independently of optional permissions.
    LaunchedEffect(startupModel.hasBeenAdmitted, hasMicPermission, isServiceSynced) {
        if (!startupModel.hasBeenAdmitted && hasMicPermission && isServiceSynced &&
            !state.isActive && state.phase != VoicePhase.Initializing
        ) {
            model.startWaiting()
        }
    }

    LaunchedEffect(lostGrantedPermissions) {
        if (lostGrantedPermissions.isNotEmpty() && startupModel.hasBeenAdmitted) {
            val lostPermissions = startupModel.observeGrantedPermissions(grantedRuntimePermissions)
            startupModel.enterPermissionRecovery()
            if (StartupPermissionInventory.RECORD_AUDIO in lostPermissions) model.stop()
            invalidatePendingAndStopCamera()
        }
    }

    val startupDecision = remember(
        isVisionRestoreCompleted,
        visionModelState,
        isServiceSynced,
        state,
        missingRuntimePermissions,
        startupModel.hasCompletedRuntimePermissionCheck,
        startupModel.userAcceptedDegraded,
        startupModel.userAcceptedPermissionDegraded,
        startupModel.timedOutStage,
    ) {
        StartupPolicy.evaluate(
            isVisionRestoreCompleted = isVisionRestoreCompleted,
            visionModelState = visionModelState,
            isVoiceServiceSynced = isServiceSynced,
            voiceState = state,
            missingRuntimePermissions = missingRuntimePermissions,
            hasCompletedPermissionCheck = startupModel.hasCompletedRuntimePermissionCheck,
            userAcceptedDegraded = startupModel.userAcceptedDegraded,
            userAcceptedPermissionDegraded = startupModel.userAcceptedPermissionDegraded,
            timedOutStage = startupModel.timedOutStage,
        )
    }

    LaunchedEffect(startupDecision, startupModel.hasBeenAdmitted) {
        if (startupDecision is StartupDecision.Loading && !startupModel.hasBeenAdmitted &&
            startupDecision.stage != StartupStage.RuntimePermissions
        ) {
            startupModel.observePendingStage(startupDecision.stage)
        } else if (!startupModel.hasBeenAdmitted && startupDecision is StartupDecision.Ready) {
            startupModel.admit(requiredRuntimePermissions.filterNot(missingRuntimePermissions::contains).toSet())
        }
    }

    LaunchedEffect(startupDecision, screenVisible, startupModel.hasPlayedStartupCue, audibleFeedback) {
        if (StartupCuePolicy.shouldTriggerAdmissionCue(
                hasPlayed = startupModel.hasPlayedStartupCue,
                decision = startupDecision,
                isForeground = screenVisible,
            )
        ) {
            startupModel.markStartupCuePlayed()
            if (audibleFeedback) {
                effectiveStartupPlayer.playCue(audible = true, isForeground = true)
            }
        }
    }

    val onStartupRetry: () -> Unit = {
        startupModel.retry()
        val missingPermissions = refreshRuntimePermissions()
        if (missingPermissions.isNotEmpty()) {
            startupModel.retryRuntimePermissionRequest()
        } else {
            when {
                !isVisionRestoreCompleted || visionModelState is VisionModelState.Loading ->
                    visionModel.retryModelRestore()
                visionModelState is VisionModelState.Error || visionModelState is VisionModelState.Missing ->
                    visionModel.restoreDefaultModel()
            }
            val restartListening = startupDecision is StartupDecision.ActionRequired &&
                (startupDecision.issue == StartupIssue.VoiceServiceError ||
                    startupDecision.stage == StartupStage.VoiceSync)
            model.retry(restartListening = restartListening)
        }
    }

    val requestStartCamera: (Preview.SurfaceProvider?, VisionPipelineMode, Int) -> Unit = { provider, mode, rotation ->
        val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (selectedTab != 1 || !PendingCameraStartGate.isCameraAllowedDestination(debugDestination) || !isResumed) {
            invalidatePendingAndStopCamera()
        } else if (StartupPermissionInventory.CAMERA !in refreshRuntimePermissions()) {
            pendingCameraGate.invalidate()
            visionModel.ensureController().startCamera(
                lifecycleOwner = lifecycleOwner,
                surfaceProvider = provider,
                mode = mode,
                targetRotation = rotation,
            )
        } else {
            pendingCameraGate.invalidate()
            visionModel.ensureController().permissionDenied()
            startupModel.enterPermissionRecovery()
        }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            visionModel.importModel(uri)
        }
    }

    val openSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }
    val openStartupSettings = {
        when {
            startupDecision is StartupDecision.ActionRequired &&
                (startupDecision.issue == StartupIssue.RuntimePermissionsDenied ||
                    startupDecision.stage == StartupStage.RuntimePermissions) -> openSettings()
            else -> {
                invalidatePendingAndStopCamera()
                selectedTab = 1
                debugDestination = DebugDestination.None
                showLicenses = false
                startupModel.showSettingsFromStartup()
            }
        }
    }

    var floatingTabBarHeightDp by remember { mutableStateOf(FloatingTabBarHeight) }
    var liveTranscriptHeightDp by remember { mutableStateOf(0.dp) }
    val isTranscriptVisible = state.phase == VoicePhase.Dictating && state.transcript.isNotBlank()
    val safeDrawingBottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val transcriptContentClearance = if (isTranscriptVisible) liveTranscriptHeightDp + 8.dp else 0.dp
    val hiddenNavTranscriptBottomPadding = if (isTranscriptVisible) {
        safeDrawingBottomPadding + 24.dp + transcriptContentClearance
    } else {
        0.dp
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if ((!startupModel.hasBeenAdmitted || permissionLossDetected) && !startupModel.isStartupSettingsVisible) {
            StartupScreen(
                decision = startupDecision,
                onRetry = onStartupRetry,
                onContinueDegraded = {
                    if (startupDecision is StartupDecision.ActionRequired &&
                        startupDecision.issue == StartupIssue.RuntimePermissionsDenied
                    ) {
                        startupModel.acceptPermissionDegraded()
                    } else {
                        startupModel.acceptDegraded()
                    }
                },
                onOpenSettings = openStartupSettings,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // Main content area with safe drawing horizontal/top insets
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                            )
                        )
                ) {
                    val navSafeBottomPadding = calculateFloatingNavSafeBottomPadding(floatingTabBarHeightDp)
                    when {
                        selectedTab == 0 -> VoiceDemoScreen(
                            state = state,
                            onStart = { requestAction(1) },
                            onFinish = model::finishDictation,
                            onOpenSettings = openSettings,
                            onStartWaiting = { requestAction(0) },
                            onStop = model::stop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = navSafeBottomPadding + transcriptContentClearance),
                        )
                        showLicenses -> LicensesScreen(
                            onBack = { showLicenses = false },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = hiddenNavTranscriptBottomPadding),
                        )
                        debugDestination == DebugDestination.VisionDiagnostics -> VisionDiagnosticsScreen(
                            uiState = visionUiState,
                            modelState = visionModelState,
                            onBack = {
                                invalidatePendingAndStopCamera()
                                debugDestination = DebugDestination.None
                            },
                            onStartCamera = requestStartCamera,
                            onStopCamera = invalidatePendingAndStopCamera,
                            onSelectPipelineMode = visionModel::setPipelineMode,
                            onResetMetrics = visionModel::resetMetrics,
                            onImportModelClick = {
                                modelPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                            },
                            onClearModelClick = visionModel::clearModel,
                            onRestoreDefaultModelClick = visionModel::restoreDefaultModel,
                            onApplyOptimizedModelClick = visionModel::applyOptimizedModel,
                            onOpenAppSettings = openSettings,
                            onSurfaceProviderUpdated = { provider, rotation ->
                                pendingCameraGate.updateSurfaceProvider(provider, rotation)
                                visionModel.ensureController().updateSurfaceProvider(provider, rotation)
                            },
                            onCalibrateForward = { visionModel.calibrateForward() },
                            onToggleHazardAlert = { visionModel.setHazardAlertEnabled(it) },
                            onConfigureBackend = { backend, threads -> visionModel.configureBackend(backend, threads) },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = hiddenNavTranscriptBottomPadding),
                        )
                        debugDestination == DebugDestination.VoiceDiagnostics -> VoiceDiagnosticsScreen(
                            state = state,
                            audibleFeedback = audibleFeedback,
                            onBack = {
                                invalidatePendingAndStopCamera()
                                debugDestination = DebugDestination.None
                            },
                            onStartWaiting = { requestAction(0) },
                            onStop = model::stop,
                            onStartDictation = { requestAction(1) },
                            onFinishDictation = model::finishDictation,
                            onAudibleFeedbackChanged = model::setAudibleFeedback,
                            onOpenSettings = openSettings,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = hiddenNavTranscriptBottomPadding),
                        )
                        debugDestination == DebugDestination.PresentationDemo -> PresentationDemoScreen(
                            voiceState = state,
                            visionUiState = visionUiState,
                            visionModelState = visionModelState,
                            onBack = {
                                invalidatePendingAndStopCamera()
                                debugDestination = DebugDestination.None
                            },
                            onStartCamera = requestStartCamera,
                            onStopCamera = invalidatePendingAndStopCamera,
                            onStartVoiceWaiting = { requestAction(0) },
                            onStopVoice = model::stop,
                            onOpenAppSettings = openSettings,
                            onSurfaceProviderUpdated = { provider, rotation ->
                                pendingCameraGate.updateSurfaceProvider(provider, rotation)
                                visionModel.ensureController().updateSurfaceProvider(provider, rotation)
                            },
                            onCalibrateForward = { visionModel.calibrateForward() },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = hiddenNavTranscriptBottomPadding),
                        )
                        else -> saveableStateHolder.SaveableStateProvider("settings") {
                            SettingsScreen(
                                state = state,
                                onStartWaiting = { requestAction(0) },
                                onStop = model::stop,
                                audibleFeedback = audibleFeedback,
                                onAudibleFeedbackChanged = model::setAudibleFeedback,
                                onDownloadModel = model::downloadModel,
                                onCancelDownload = model::cancelModelDownload,
                                onDeleteModel = model::deleteModel,
                                onNotificationSettings = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    )
                                },
                                onOpenVisionDiagnostics = {
                                    invalidatePendingAndStopCamera()
                                    visionModel.ensureController()
                                    debugDestination = DebugDestination.VisionDiagnostics
                                },
                                onOpenVoiceDiagnostics = {
                                    invalidatePendingAndStopCamera()
                                    debugDestination = DebugDestination.VoiceDiagnostics
                                },
                                onOpenPresentationDemo = {
                                    invalidatePendingAndStopCamera()
                                    visionModel.ensureController()
                                    debugDestination = DebugDestination.PresentationDemo
                                },
                                onOpenLicenses = { showLicenses = true },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = navSafeBottomPadding + transcriptContentClearance),
                            )
                        }
                    }
                }

                LiveTranscriptOverlay(
                    state = state,
                    floatingTabBarVisible = startupModel.hasBeenAdmitted &&
                        debugDestination == DebugDestination.None && !showLicenses,
                    floatingTabBarHeight = floatingTabBarHeightDp,
                    onHeightChanged = { liveTranscriptHeightDp = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // Real floating navigation capsule overlay:
                // Sits at BottomCenter with transparent background outside bounds
                // and respects safe system navigation bar bottom insets.
                if (startupModel.hasBeenAdmitted && debugDestination == DebugDestination.None && !showLicenses) {
                    FloatingTabBar(
                        selectedTab = selectedTab,
                        onSelected = { nextTab ->
                            invalidatePendingAndStopCamera()
                            debugDestination = DebugDestination.None
                            showLicenses = false
                            selectedTab = nextTab
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            .padding(bottom = FloatingTabBarBottomMargin),
                        onHeightChanged = { measuredHeight ->
                            floatingTabBarHeightDp = measuredHeight
                        },
                    )
                }
            }
        }
    }
}
