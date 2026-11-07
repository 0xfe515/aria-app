package com.ariateam.aria

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.ui.screen.MainScreen
import com.ariateam.aria.ui.screen.onboarding.OnboardingScreen
import com.ariateam.aria.ui.theme.AriaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { AriaTheme { OnboardingGate() } }
    }

    /**
     * First-run gate: show [OnboardingScreen] until a valid body-mount profile exists, then [MainScreen].
     *
     * Latch: the initial valid profile is snapshotted once per gate via `remember` — the gate does NOT
     * continuously collect the profile Flow. This prevents Home from acquiring the microphone while
     * onboarding is still active (the store emits the saved profile before the Done TTS completes and
     * the controller is released). Completion flows only through [onCompleted], which is invoked after
     * the controller has been released.
     *
     * The store is closed when the gate composition disposes so its
     * [SharedPreferences.OnSharedPreferenceChangeListener] is unregistered (no leaked listener).
     */
    @Composable
    fun OnboardingGate() {
        val context = LocalContext.current

        // Create the store after Composition is ready, once Context is attached.
        val profileStore = remember { BodyMountProfileStore(context) }
        DisposableEffect(profileStore) {
            onDispose { profileStore.close() }
        }

        // Latch the initial valid profile once; do not continuously gate on the profile Flow.
        val hasProfileState = remember { mutableStateOf(profileStore.profile.value != null) }

        if (!hasProfileState.value) {
            OnboardingScreen(
                store = profileStore,
                onCompleted = {
                    // Save-once gating lives in the state machine (Done only after save returned true).
                    // The controller is released by the screen before this callback runs.
                    hasProfileState.value = true
                },
            )
        } else {
            MainScreen()
        }
    }
}
