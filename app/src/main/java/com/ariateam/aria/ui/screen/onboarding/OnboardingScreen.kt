// LocalContext.current + getString is the legitimate escape hatch for string resources in this
// first-run screen (some lookups occur in recognition callbacks outside composition, where
// Compose's stringResource is not applicable). Suppress the noisy check for this file.
@file:Suppress("LocalContextGetResourceValueCall")

package com.ariateam.aria.ui.screen.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.R
import com.ariateam.aria.distance.BodyMountPosition
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.speech.RecognitionFailure
import com.ariateam.aria.ui.screen.onboarding.OnboardingStateMachine.Step

private const val MAX_REPROMPTS = 2

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    store: BodyMountProfileStore,
    onCompleted: (BodyMountProfile) -> Unit,
) {
    val context: Context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // applicationContext is safe to retain in the composition-owned state machine; the Activity
    // context is not.
    val applicationContext = context.applicationContext

    // Composition-owned state machine (not registered with the Activity ViewModelStore). Rotation
    // safely restarts confirmations; this is accepted. It is deliberately NOT created via viewModel()
    // so it is scoped to this composition and cleared when the gate completes.
    val stateMachine = remember(store, applicationContext) {
        OnboardingStateMachine(store) { key ->
            when (key) {
                "onboarding_error_stature" -> applicationContext.getString(R.string.onboarding_error_stature)
                "onboarding_error_mount" -> applicationContext.getString(R.string.onboarding_error_mount)
                "onboarding_error_confirmation" ->
                    applicationContext.getString(R.string.onboarding_error_confirmation)
                "onboarding_error_save" -> applicationContext.getString(R.string.onboarding_error_save)
                "onboarding_error_tts" -> applicationContext.getString(R.string.onboarding_error_tts)
                else -> key
            }
        }
    }

    val uiState by stateMachine.uiState.collectAsStateWithLifecycle()

    fun isInputStep(step: Step) =
        step == Step.AskHeight || step == Step.AskMount ||
            step == Step.ConfirmHeight || step == Step.ConfirmMount

    fun isPromptStep(step: Step) =
        step != Step.Saving && step != Step.Done

    fun errorStringForStep(step: Step): String = when (step) {
        Step.AskHeight, Step.ConfirmHeight -> applicationContext.getString(R.string.onboarding_error_stature)
        Step.AskMount, Step.ConfirmMount -> applicationContext.getString(R.string.onboarding_error_mount)
        else -> applicationContext.getString(R.string.onboarding_error_confirmation)
    }

    fun ttsErrorString(): String = applicationContext.getString(R.string.onboarding_error_tts)

    var isResumed by remember { mutableStateOf(true) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    // Bounded consecutive recognition failures on the current step; reset on a valid advance or retry.
    var repromptCount by remember { mutableStateOf(0) }
    // Prompt revision: incrementing forces the speak effect to re-run (re-prompt) after a non-advancing
    // voice result, on resume, or when permission is granted.
    var promptRevision by remember { mutableStateOf(0) }
    var currentPrompt by remember { mutableStateOf("") }
    var permissionGranted by remember {
        mutableStateOf(controllerHasMicrophonePermission(context))
    }
    // Idempotent completion guard: onCompleted must be invoked exactly once (auto or touch).
    var completed by remember { mutableStateOf(false) }

    val hasPermission = permissionGranted

    // Microphone permission: auto-request once on first composition.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            voiceError = context.getString(R.string.error_permission)
        } else if (isResumed && isPromptStep(uiState.step)) {
            // Permission granted while on a prompt step: re-prompt and listen.
            promptRevision++
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val controller = remember {
        OnboardingController(
            applicationContext = applicationContext,
            onPromptFinished = { stateMachine.onPromptFinished() },
            onRecognitionResult = { text ->
                voiceError = null
                val before = stateMachine.uiState.value.step
                stateMachine.submit(text)
                if (stateMachine.uiState.value.step == before) {
                    // Recognized but did not advance: re-prompt, bounded.
                    repromptCount++
                    if (repromptCount < MAX_REPROMPTS) {
                        promptRevision++
                    } else {
                        voiceError = errorStringForStep(before)
                    }
                } else {
                    repromptCount = 0
                }
            },
            onRecognitionFailure = { failure ->
                val isNoMatchOrSilence =
                    failure == RecognitionFailure.NoMatch || failure == RecognitionFailure.NoSpeech
                if (isNoMatchOrSilence && repromptCount < MAX_REPROMPTS) {
                    repromptCount++
                    voiceError = null
                    promptRevision++
                } else {
                    repromptCount++
                    voiceError = when (failure) {
                        RecognitionFailure.Permission -> context.getString(R.string.error_permission)
                        RecognitionFailure.Unavailable ->
                            context.getString(R.string.error_recognition_unavailable)
                        RecognitionFailure.Network -> context.getString(R.string.error_recognition_network)
                        RecognitionFailure.Language -> context.getString(R.string.error_recognition_language)
                        RecognitionFailure.Audio -> context.getString(R.string.error_microphone)
                        else -> context.getString(R.string.error_recognition_failed)
                    }
                }
            },
            onTtsError = {
                // TTS init/speak/onError failure: surface a resource-backed manual/retry error, never
                // treat as a successful prompt (state machine does not advance).
                repromptCount = 0
                voiceError = ttsErrorString()
            },
        )
    }

    // Lifecycle observer: pause on stop; do not permanently release until disposal; resume re-prompts
    // every non-terminal step (Welcome included), not only input steps.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    controller.onResume()
                    if (isPromptStep(uiState.step)) {
                        promptRevision++
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    controller.onPause()
                }
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> controller.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }

    fun repromptCurrent() {
        if (currentPrompt.isBlank()) return
        voiceError = null
        controller.speak(currentPrompt) {
            if (isResumed && permissionGranted) {
                controller.startListening()
            }
        }
    }

    fun retryCurrent() {
        voiceError = null
        repromptCount = 0
        promptRevision++
        if (permissionGranted) {
            repromptCurrent()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Idempotent completion: release the controller (before onCompleted) and hand the confirmed
     *  profile to MainActivity once. The state is not reset to Welcome; composition-owned state goes
     *  away on completion. */
    fun finishOnboarding(statureCm: Float, position: BodyMountPosition) {
        if (completed) return
        completed = true
        controller.release()
        onCompleted(BodyMountProfile(statureCm = statureCm, position = position))
    }

    // Speak the prompt for the current step. Re-runs when the step, prompt revision, resumption or
    // permission state changes. On real TTS completion (onDone on the main handler) advance the state
    // machine and, for input/confirm steps, start listening only when permitted and resumed.
    LaunchedEffect(uiState.step, promptRevision, isResumed, permissionGranted) {
        val step = uiState.step
        val prompt: String? = when (step) {
            Step.Welcome -> applicationContext.getString(R.string.onboarding_welcome)
            Step.AskHeight -> applicationContext.getString(R.string.onboarding_ask_stature)
            Step.ConfirmHeight ->
                applicationContext.getString(R.string.onboarding_confirm_stature, uiState.statureCm ?: 0f)
            Step.AskMount -> applicationContext.getString(R.string.onboarding_ask_mount)
            Step.ConfirmMount -> {
                val positionText = uiState.mountPosition?.let { when (it) {
                    BodyMountPosition.SHOULDER -> applicationContext.getString(R.string.mount_shoulder)
                    BodyMountPosition.CHEST -> applicationContext.getString(R.string.mount_chest)
                }} ?: ""
                applicationContext.getString(R.string.onboarding_confirm_mount, positionText)
            }
            else -> null
        }
        currentPrompt = prompt.orEmpty()
        voiceError = null
        if (prompt != null) {
            controller.speak(prompt) {
                // Real TTS completion callback: Welcome -> AskHeight; no-op otherwise.
                stateMachine.onPromptFinished()
                if (step != Step.Welcome && isResumed && permissionGranted) {
                    controller.startListening()
                }
            }
        }
    }

    // Auto-complete on Done (voice-only path): speak the completion label, then on real TTS
    // completion release the controller and invoke onCompleted exactly once. Keys on (step, isResumed)
    // so the completion label is re-spoken after pause/resume instead of hanging. The touch button
    // shares the same idempotent completion.
    LaunchedEffect(uiState.step, isResumed) {
        if (uiState.step == Step.Done && !completed) {
            val stature = uiState.statureCm
            val position = uiState.mountPosition
            if (stature != null && position != null) {
                controller.speak(applicationContext.getString(R.string.onboarding_completed)) {
                    finishOnboarding(statureCm = stature, position = position)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (uiState.step) {
            Step.Welcome -> {
                WelcomeMessage(
                    context = context,
                    // Touch Next advances directly without requiring a functioning TTS engine.
                    onNext = { stateMachine.onPromptFinished() },
                )
            }
            Step.AskHeight -> {
                val heightTextState = remember { mutableStateOf(uiState.heightInput) }
                AskHeight(
                    context = context,
                    heightText = heightTextState.value,
                    onHeightChanged = { heightTextState.value = it },
                    onSubmit = { stateMachine.submit(heightTextState.value) },
                    error = uiState.error,
                    voiceError = voiceError,
                    hasPermission = hasPermission,
                    onRepeat = { repromptCount = 0; voiceError = null; repromptCurrent() },
                    onRetry = { retryCurrent() },
                )
            }
            Step.ConfirmHeight -> {
                ConfirmHeight(
                    context = context,
                    statureCm = uiState.statureCm ?: 0f,
                    onConfirmYes = { stateMachine.submit("예") },
                    onConfirmNo = { stateMachine.submit("아니오") },
                    voiceError = voiceError,
                    onRepeat = { repromptCount = 0; voiceError = null; repromptCurrent() },
                    onRetry = { retryCurrent() },
                )
            }
            Step.AskMount -> {
                val mountTextState = remember { mutableStateOf(uiState.mountInput) }
                AskMount(
                    context = context,
                    mountText = mountTextState.value,
                    onMountChanged = { mountTextState.value = it },
                    onSubmit = { stateMachine.submit(mountTextState.value) },
                    onMountSelected = { stateMachine.submit(it) },
                    error = uiState.error,
                    voiceError = voiceError,
                    hasPermission = hasPermission,
                    onRepeat = { repromptCount = 0; voiceError = null; repromptCurrent() },
                    onRetry = { retryCurrent() },
                )
            }
            Step.ConfirmMount -> {
                ConfirmMount(
                    context = context,
                    mountPosition = uiState.mountPosition,
                    onConfirmYes = { stateMachine.submit("예") },
                    onConfirmNo = { stateMachine.submit("아니오") },
                    voiceError = voiceError,
                    onRepeat = { repromptCount = 0; voiceError = null; repromptCurrent() },
                    onRetry = { retryCurrent() },
                )
            }
            Step.Saving -> {
                SavingIndicator(context = context)
            }
            Step.Done -> {
                CompletedMessage(
                    context = context,
                    onCompleted = {
                        finishOnboarding(
                            statureCm = uiState.statureCm ?: 0f,
                            position = uiState.mountPosition ?: BodyMountPosition.SHOULDER,
                        )
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeMessage(
    context: Context,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = context.getString(R.string.onboarding_welcome),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Button(onClick = onNext) {
            Text("다음")
        }
    }
}

@Composable
private fun AskHeight(
    context: Context,
    heightText: String,
    onHeightChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    error: String?,
    voiceError: String?,
    hasPermission: Boolean,
    onRepeat: () -> Unit,
    onRetry: () -> Unit,
) {
    val displayError = error ?: voiceError
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = context.getString(R.string.onboarding_ask_stature),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (displayError != null) {
            Text(
                text = displayError,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
        OutlinedTextField(
            value = heightText,
            onValueChange = onHeightChanged,
            label = { Text("키 (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardActions = KeyboardActions(onNext = { onSubmit() })
        )
        Button(
            onClick = onSubmit,
            enabled = heightText.isNotBlank()
        ) {
            Text("다음")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onRepeat) {
                Text("다시 듣기")
            }
            Button(
                onClick = onRetry,
                enabled = !hasPermission || voiceError != null
            ) {
                Text("다시 시도")
            }
        }
        if (!hasPermission) {
            Text(
                text = context.getString(R.string.error_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun ConfirmHeight(
    context: Context,
    statureCm: Float,
    onConfirmYes: () -> Unit,
    onConfirmNo: () -> Unit,
    voiceError: String?,
    onRepeat: () -> Unit,
    onRetry: () -> Unit,
) {
    val displayError = voiceError
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = context.getString(R.string.onboarding_confirm_stature, statureCm),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (displayError != null) {
            Text(
                text = displayError,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onConfirmYes) {
                Text("예")
            }
            Button(onClick = onConfirmNo) {
                Text("아니오")
            }
        }
        Button(onClick = onRepeat) {
            Text("다시 듣기")
        }
        Button(onClick = onRetry) {
            Text("다시 시도")
        }
    }
}

@Composable
private fun AskMount(
    context: Context,
    mountText: String,
    onMountChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onMountSelected: (String) -> Unit,
    error: String?,
    voiceError: String?,
    hasPermission: Boolean,
    onRepeat: () -> Unit,
    onRetry: () -> Unit,
) {
    val displayError = error ?: voiceError
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = context.getString(R.string.onboarding_ask_mount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (displayError != null) {
            Text(
                text = displayError,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
        OutlinedTextField(
            value = mountText,
            onValueChange = onMountChanged,
            label = { Text("위치 (어깨/가슴)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onSubmit() })
        )
        Button(
            onClick = onSubmit,
            enabled = mountText.isNotBlank()
        ) {
            Text("다음")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { onMountSelected("어깨") }) {
                Text("어깨")
            }
            Button(onClick = { onMountSelected("가슴") }) {
                Text("가슴")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onRepeat) {
                Text("다시 듣기")
            }
            Button(
                onClick = onRetry,
                enabled = !hasPermission || voiceError != null
            ) {
                Text("다시 시도")
            }
        }
        if (!hasPermission) {
            Text(
                text = context.getString(R.string.error_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun ConfirmMount(
    context: Context,
    mountPosition: BodyMountPosition?,
    onConfirmYes: () -> Unit,
    onConfirmNo: () -> Unit,
    voiceError: String?,
    onRepeat: () -> Unit,
    onRetry: () -> Unit,
) {
    val positionText = mountPosition?.let { when (it) {
        BodyMountPosition.SHOULDER -> context.getString(R.string.mount_shoulder)
        BodyMountPosition.CHEST -> context.getString(R.string.mount_chest)
    }} ?: ""
    val displayError = voiceError
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = context.getString(R.string.onboarding_confirm_mount, positionText),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (displayError != null) {
            Text(
                text = displayError,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onConfirmYes) {
                Text("예")
            }
            Button(onClick = onConfirmNo) {
                Text("아니오")
            }
        }
        Button(onClick = onRepeat) {
            Text("다시 듣기")
        }
        Button(onClick = onRetry) {
            Text("다시 시도")
        }
    }
}

@Composable
private fun SavingIndicator(context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = context.getString(R.string.onboarding_saving),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CompletedMessage(
    context: Context,
    onCompleted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = context.getString(R.string.onboarding_completed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Button(onClick = onCompleted) {
            Text("홈으로 이동")
        }
    }
}

private fun controllerHasMicrophonePermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}
