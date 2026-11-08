package com.ariateam.aria.ui.screen

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ariateam.aria.DebugDestination
import com.ariateam.aria.command.LanguageStatus
import com.ariateam.aria.navigation.ExternalNavigationLauncher
import com.ariateam.aria.navigation.NavigationLaunchResult
import com.ariateam.aria.navigation.NavigationProvider
import com.ariateam.aria.PendingCameraStartGate
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.distance.DistanceActivity
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
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionCameraResumePolicy
import com.ariateam.aria.vision.VisionForegroundService
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionPipelineMode
import com.ariateam.aria.vision.VisionSession
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
    var isDistanceModeForeground by rememberSaveable { mutableStateOf(false) }
    var showBodyMountProfileDialog by rememberSaveable { mutableStateOf(false) }
    var hasAttemptedStartupVoiceStart by rememberSaveable { mutableStateOf(false) }
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
    val invalidatePendingAndDetachPreview = {
        pendingCameraGate.invalidate()
        visionModel.ensureController().updateSurfaceProvider(null)
    }

    // Release camera resources and invalidate pending permission requests on configuration change.
    var lastOrientation by remember { mutableIntStateOf(configuration.orientation) }
    LaunchedEffect(configuration.orientation) {
        if (configuration.orientation != lastOrientation) {
            lastOrientation = configuration.orientation
            invalidatePendingAndDetachPreview()
        }
    }


    LaunchedEffect(
        selectedTab,
        debugDestination,
        showLicenses,
    ) {
        if (selectedTab == 1) {
            if (!showLicenses && PendingCameraStartGate.isCameraAllowedDestination(debugDestination)) {
                visionModel.ensureController()
            } else {
                invalidatePendingAndStopCamera()
            }
            model.setUiScreen("settings")
        } else {
            // Home tab: previous settings camera is already synchronously invalidated and stopped
            // by navigation callbacks (FloatingTabBar, BackHandler, voice navigation).
            // Do not call invalidatePendingAndStopCamera() here to avoid racing Home inset camera start.
            visionModel.ensureController()
        }
    }

    var screenVisible by remember { mutableStateOf(false) }
    var isBlackScreenVisible by rememberSaveable { mutableStateOf(false) }
    var lastBlackScreenActivityAt by rememberSaveable {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    var isTouchActive by remember { mutableStateOf(false) }
    var hasScreenVisibilityBaseline by rememberSaveable { mutableStateOf(false) }
    var lastScreenVisibilityEventId by rememberSaveable {
        mutableLongStateOf(state.command.screenVisibilityId)
    }
    val toastFeedbackPolicy = rememberSaveable(saver = ToastFeedbackPolicy.Saver) {
        ToastFeedbackPolicy()
    }
    val isServiceSynced by model.isServiceSynced.collectAsStateWithLifecycle()

    val blackScreenEligibility = BlackScreenEligibility(
        isHome = selectedTab == 0,
        hasBeenAdmitted = startupModel.hasBeenAdmitted,
        isForeground = screenVisible,
        isStartupSettingsVisible = startupModel.isStartupSettingsVisible,
        isExcludedScreen = selectedTab != 0 || debugDestination != DebugDestination.None || showLicenses,
        isDialogVisible = showBodyMountProfileDialog,
        hasActionRequiredFailure = permissionLossDetected ||
            state.phase == VoicePhase.Error ||
            StartupPermissionInventory.CAMERA in missingRuntimePermissions ||
            visionModelState !is VisionModelState.Ready ||
            visionUiState.cameraStatus in setOf(
                CameraSessionStatus.PermissionDenied,
                CameraSessionStatus.CameraUnavailable,
                CameraSessionStatus.Error,
            ),
        voicePhase = state.phase,
        cameraStatus = visionUiState.cameraStatus,
        visionReady = visionModelState is VisionModelState.Ready,
    )
    val canHideBlackScreenManually = BlackScreenPolicy.canHideManually(blackScreenEligibility)
    val canAutoHideBlackScreen = BlackScreenPolicy.canAutoHide(blackScreenEligibility)
    val recordActivity by rememberUpdatedState {
        lastBlackScreenActivityAt = SystemClock.elapsedRealtime()
    }
    val startTouchActivity by rememberUpdatedState {
        recordActivity()
        isTouchActive = true
    }
    val finishTouchActivity by rememberUpdatedState {
        recordActivity()
        isTouchActive = false
    }

    LaunchedEffect(selectedTab, canHideBlackScreenManually) {
        model.setUiScreen(
            when {
                selectedTab == 1 -> "settings"
                canHideBlackScreenManually -> "home"
                else -> "blocked"
            },
        )
    }

    LaunchedEffect(canHideBlackScreenManually) {
        if (!canHideBlackScreenManually) isBlackScreenVisible = false
    }

    LaunchedEffect(canAutoHideBlackScreen) {
        if (canAutoHideBlackScreen) {
            lastBlackScreenActivityAt = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(isServiceSynced, state.command.screenVisibilityId, state.command.screenHidden) {
        if (!isServiceSynced) return@LaunchedEffect
        if (!hasScreenVisibilityBaseline) {
            lastScreenVisibilityEventId = state.command.screenVisibilityId
            hasScreenVisibilityBaseline = true
            return@LaunchedEffect
        }
        val eventId = state.command.screenVisibilityId
        if (!BlackScreenPolicy.isFreshVisibilityEvent(eventId, lastScreenVisibilityEventId)) return@LaunchedEffect
        lastScreenVisibilityEventId = eventId
        if (state.command.screenHidden) {
            if (canHideBlackScreenManually) {
                lastBlackScreenActivityAt = SystemClock.elapsedRealtime()
                isBlackScreenVisible = true
            }
        } else {
            lastBlackScreenActivityAt = SystemClock.elapsedRealtime()
            isBlackScreenVisible = false
        }
    }

    LaunchedEffect(screenVisible) {
        if (screenVisible) {
            isBlackScreenVisible = false
            lastBlackScreenActivityAt = SystemClock.elapsedRealtime()
        } else {
            isBlackScreenVisible = false
        }
    }

    LaunchedEffect(blackScreenEligibility, lastBlackScreenActivityAt, isBlackScreenVisible, isTouchActive) {
        if (!screenVisible || !canAutoHideBlackScreen || isBlackScreenVisible || isTouchActive) {
            return@LaunchedEffect
        }
        val remaining = BlackScreenPolicy.remainingMillis(
            lastActivityAt = lastBlackScreenActivityAt,
            now = SystemClock.elapsedRealtime(),
        )
        if (remaining > 0) delay(remaining)
        if (!isTouchActive && BlackScreenPolicy.shouldAutoHide(
                value = blackScreenEligibility,
                lastActivityAt = lastBlackScreenActivityAt,
                now = SystemClock.elapsedRealtime(),
            )
        ) {
            isBlackScreenVisible = true
        }
    }

    LaunchedEffect(state.phase, state.command.eventId) {
        if (state.phase == VoicePhase.Dictating || state.command.eventId > 0L) {
            lastBlackScreenActivityAt = SystemClock.elapsedRealtime()
        }
    }

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
                invalidatePendingAndDetachPreview()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                isDistanceModeForeground = false
                val missingPermissions = refreshRuntimePermissions()
                val lostPermissions = startupModel.observeGrantedPermissions(
                    requiredRuntimePermissions.filterNot(missingPermissions::contains).toSet(),
                )
                if (startupModel.hasBeenAdmitted && lostPermissions.isNotEmpty()) {
                    startupModel.enterPermissionRecovery()
                    if (StartupPermissionInventory.RECORD_AUDIO in lostPermissions) model.stop()
                    invalidatePendingAndStopCamera()
                } else {
                    val canResume = VisionCameraResumePolicy.canResumeOnForeground(
                        isScreenEligible = PendingCameraStartGate.isCameraEligibleScreen(
                            selectedTab = selectedTab,
                            debugDestination = debugDestination,
                            showLicenses = showLicenses,
                            hasBeenAdmitted = startupModel.hasBeenAdmitted,
                            isStartupSettingsVisible = startupModel.isStartupSettingsVisible,
                            permissionLossDetected = permissionLossDetected,
                        ),
                        isDistanceModeForeground = isDistanceModeForeground,
                        hasCameraPermission = StartupPermissionInventory.CAMERA !in missingPermissions,
                        requiresExplicitRestart = VisionSession.requiresExplicitRestart(context),
                        hasBeenAdmitted = startupModel.hasBeenAdmitted,
                    )
                    if (canResume) {
                        visionModel.ensureController().rebindCoalescedIfPending()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        screenVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        model.setUiVisible(screenVisible)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.setUiVisible(false)
            invalidatePendingAndDetachPreview()
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

    val externalLauncher = remember(context) { ExternalNavigationLauncher(context) }
    var lastExternalNavId by rememberSaveable { mutableLongStateOf(state.command.externalNavId) }
    var hasExternalNavBaseline by rememberSaveable { mutableStateOf(false) }

    val authRepository = remember(context) {
        com.ariateam.aria.cloud.auth.DefaultChatGptAuthRepository.getInstance(context)
    }
    val chatGptClient = remember { com.ariateam.aria.cloud.chatgpt.ChatGptClient() }
    val cloudCoordinator = remember(authRepository, chatGptClient) {
        com.ariateam.aria.cloud.CloudQuestionCoordinator(
            authRepository = authRepository,
            chatGptClient = chatGptClient,
            onAuthInvalidated = { qId ->
                model.cancelCloudQuestion(qId)
            },
        )
    }

    DisposableEffect(cloudCoordinator) {
        onDispose {
            cloudCoordinator.dispose()
        }
    }

    var lastCloudQuestionId by rememberSaveable { mutableLongStateOf(state.command.cloudQuestionId) }
    var hasCloudQuestionBaseline by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.command.cloudQuestionId, screenVisible, startupModel.hasBeenAdmitted, isServiceSynced) {
        if (!isServiceSynced) return@LaunchedEffect
        if (!hasCloudQuestionBaseline) {
            lastCloudQuestionId = state.command.cloudQuestionId
            hasCloudQuestionBaseline = true
            return@LaunchedEffect
        }
        val qId = state.command.cloudQuestionId
        if (qId == 0L) {
            cloudCoordinator.cancel()
            return@LaunchedEffect
        }
        if (qId <= lastCloudQuestionId) return@LaunchedEffect
        if (!screenVisible || !startupModel.hasBeenAdmitted) return@LaunchedEffect

        lastCloudQuestionId = qId
        val qType = state.command.cloudQuestionType
        val qPrompt = state.command.cloudQuestionPrompt

        model.consumeCloudQuestion(qId)

        if (qType == "photo") {
            cloudCoordinator.askPhotoQuestion(
                questionId = qId,
                prompt = qPrompt,
                onResponse = { responseText ->
                    model.speakCloudResponse(qId, responseText)
                },
                onError = { errorMessage ->
                    model.reportCloudError(qId, errorMessage)
                },
            )
        } else {
            cloudCoordinator.askTextQuestion(
                questionId = qId,
                prompt = qPrompt,
                onResponse = { responseText ->
                    model.speakCloudResponse(qId, responseText)
                },
                onError = { errorMessage ->
                    model.reportCloudError(qId, errorMessage)
                },
            )
        }
    }

    LaunchedEffect(state.command.externalNavId, screenVisible, startupModel.hasBeenAdmitted, isServiceSynced) {
        if (!isServiceSynced) return@LaunchedEffect
        if (!hasExternalNavBaseline) {
            lastExternalNavId = state.command.externalNavId
            hasExternalNavBaseline = true
            return@LaunchedEffect
        }
        val eventId = state.command.externalNavId
        if (eventId == 0L || eventId <= lastExternalNavId) return@LaunchedEffect
        if (!screenVisible || !startupModel.hasBeenAdmitted) return@LaunchedEffect

        lastExternalNavId = eventId
        val provider = NavigationProvider.fromId(state.command.externalNavProvider)
        val destination = state.command.externalNavDestination

        model.consumeNavigation(eventId)

        if (provider != null && destination.isNotBlank()) {
            when (externalLauncher.launch(provider, destination)) {
                is NavigationLaunchResult.AppLaunched -> {
                    Toast.makeText(
                        context,
                        "${provider.displayName} 앱으로 ${destination} 검색을 엽니다.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is NavigationLaunchResult.WebFallbackLaunched -> {
                    Toast.makeText(
                        context,
                        "${provider.displayName} 앱이 설치되어 있지 않아 웹 검색으로 연결합니다.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is NavigationLaunchResult.Failed -> {
                    Toast.makeText(
                        context,
                        com.ariateam.aria.R.string.nav_not_found,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
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
    LaunchedEffect(startupModel.hasBeenAdmitted, hasMicPermission, isServiceSynced, state.phase, state.isActive) {
        if (StartupPolicy.shouldLatchStartupVoiceHandled(state.isActive, state.phase)) {
            hasAttemptedStartupVoiceStart = true
        }
        if (isServiceSynced && StartupPolicy.shouldStartVoiceDuringStartup(
                hasBeenAdmitted = startupModel.hasBeenAdmitted,
                hasMicPermission = hasMicPermission,
                voicePhase = state.phase,
                isVoiceActive = state.isActive,
                hasAttemptedStartupVoiceStart = hasAttemptedStartupVoiceStart,
            )
        ) {
            hasAttemptedStartupVoiceStart = true
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

    val requestCameraStart: (Preview.SurfaceProvider?, VisionPipelineMode, Int, Boolean) -> Unit =
        { provider, mode, rotation, explicitRestart ->
        val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val isScreenEligible = PendingCameraStartGate.isCameraEligibleScreen(
            selectedTab = selectedTab,
            debugDestination = debugDestination,
            showLicenses = showLicenses,
            hasBeenAdmitted = startupModel.hasBeenAdmitted,
            isStartupSettingsVisible = startupModel.isStartupSettingsVisible,
            permissionLossDetected = permissionLossDetected,
        )
        if (!isScreenEligible || isDistanceModeForeground) {
            invalidatePendingAndStopCamera()
        } else if (!isResumed) {
            invalidatePendingAndDetachPreview()
        } else if (StartupPermissionInventory.CAMERA !in refreshRuntimePermissions()) {
            pendingCameraGate.invalidate()
            VisionForegroundService.requestStart(context, provider, mode, rotation, explicitRestart)
        } else {
            pendingCameraGate.invalidate()
            visionModel.ensureController().permissionDenied()
            if (selectedTab != 0) {
                startupModel.enterPermissionRecovery()
            }
        }
    }
    val requestStartCamera: (Preview.SurfaceProvider?, VisionPipelineMode, Int) -> Unit =
        { provider, mode, rotation -> requestCameraStart(provider, mode, rotation, false) }
    val requestExplicitCameraStart: (Preview.SurfaceProvider?, VisionPipelineMode, Int) -> Unit =
        { provider, mode, rotation -> requestCameraStart(provider, mode, rotation, true) }

    val openSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }
    val openDistanceMode = {
        isDistanceModeForeground = true
        invalidatePendingAndStopCamera()
        context.startActivity(DistanceActivity.createIntent(context))
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
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(canHideBlackScreenManually, isBlackScreenVisible) {
                if (canHideBlackScreenManually && !isBlackScreenVisible) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        startTouchActivity()
                        try {
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        } finally {
                            finishTouchActivity()
                        }
                    }
                }
            },
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
                        selectedTab == 0 -> {
                            VoiceDemoScreen(
                                state = state,
                                onStart = { requestAction(1) },
                                onFinish = model::finishDictation,
                                onOpenSettings = openSettings,
                                onOpenDistanceMode = openDistanceMode,
                                onStartWaiting = { requestAction(0) },
                                onStop = model::stop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        bottom = navSafeBottomPadding + transcriptContentClearance,
                                    ),
                            )
                            MainVisionInset(
                                visionUiState = visionUiState,
                                visionModelState = visionModelState,
                                hasCameraPermission = StartupPermissionInventory.CAMERA !in missingRuntimePermissions,
                                isHomeVisible = screenVisible && selectedTab == 0 && startupModel.hasBeenAdmitted && !permissionLossDetected && !isDistanceModeForeground,
                                onStartCamera = { provider, rotation ->
                                    requestStartCamera(provider, VisionPipelineMode.PREPROCESS_OR_INFERENCE, rotation)
                                },
                                onStopCamera = visionModel::stopCamera,
                                onSurfaceProviderUpdated = { provider, rotation ->
                                    visionModel.ensureController().updateSurfaceProvider(provider, rotation)
                                },
                                onRequestCameraPermission = {
                                    val missing = refreshRuntimePermissions()
                                    if (StartupPermissionInventory.CAMERA in missing) {
                                        startupPermissions.launch(arrayOf(StartupPermissionInventory.CAMERA))
                                    }
                                },
                                requiresExplicitRestart = visionModel.requiresExplicitRestart,
                                onExplicitRestartCamera = { provider, rotation ->
                                    requestCameraStart(
                                        provider,
                                        VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                                        rotation,
                                        true,
                                    )
                                },
                                onOpenAppSettings = openSettings,
                                onOpenProfileSetup = { showBodyMountProfileDialog = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 8.dp, end = 12.dp),
                            )
                        }
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
                            onStartCamera = requestExplicitCameraStart,
                            onStopCamera = invalidatePendingAndStopCamera,
                            onSelectPipelineMode = visionModel::setPipelineMode,
                            onResetMetrics = visionModel::resetMetrics,
                            onOpenAppSettings = openSettings,
                            onSurfaceProviderUpdated = { provider, rotation ->
                                pendingCameraGate.updateSurfaceProvider(provider, rotation)
                                visionModel.ensureController().updateSurfaceProvider(provider, rotation)
                            },
                            onCalibrateForward = { visionModel.calibrateForward() },
                            onToggleHazardAlert = { visionModel.setHazardAlertEnabled(it) },
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
                            onStartCamera = requestExplicitCameraStart,
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
                                authRepository = authRepository,
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
                                onOpenDistanceMode = openDistanceMode,
                                onOpenProfileSetup = { showBodyMountProfileDialog = true },
                                onOpenAppSettings = openSettings,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = navSafeBottomPadding + transcriptContentClearance),
                            )
                        }
                    }
                }

                if (showBodyMountProfileDialog) {
                    BodyMountProfileDialog(
                        onDismiss = { showBodyMountProfileDialog = false },
                    )
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

                if (isBlackScreenVisible) {
                    BlackScreenOverlay(
                        onReveal = {
                            lastBlackScreenActivityAt = SystemClock.elapsedRealtime()
                            isBlackScreenVisible = false
                        },
                    )
                }
            }
        }
    }
}
