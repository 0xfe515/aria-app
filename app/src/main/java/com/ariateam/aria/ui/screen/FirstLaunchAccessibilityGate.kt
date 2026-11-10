package com.ariateam.aria.ui.screen

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.dp
import com.ariateam.aria.R

internal data class FirstLaunchAccessibilityGateState(
    val showGate: Boolean,
    val settingsUnavailable: Boolean = false,
    val pendingSettingsReturn: Boolean = false,
    val accessibilityEnabled: Boolean = false,
)

internal object FirstLaunchAccessibilityPolicy {
    fun shouldShow(isHandled: Boolean, isEnabled: Boolean, isSettingsReturnPending: Boolean): Boolean =
        !isEnabled && (!isHandled || isSettingsReturnPending)

    fun afterSettingsReturn(isEnabled: Boolean): FirstLaunchAccessibilityGateState =
        FirstLaunchAccessibilityGateState(showGate = false, accessibilityEnabled = isEnabled)
}

internal class FirstLaunchAccessibilityPreferences(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun initialState(isEnabled: Boolean = false): FirstLaunchAccessibilityGateState {
        val pending = preferences.getBoolean(KEY_PENDING_RETURN, false)
        val handled = preferences.getBoolean(KEY_HANDLED, false)
        if (isEnabled && !pending) preferences.edit().putBoolean(KEY_HANDLED, true).apply()
        return FirstLaunchAccessibilityGateState(
            showGate = FirstLaunchAccessibilityPolicy.shouldShow(handled, isEnabled, pending),
            pendingSettingsReturn = pending,
            accessibilityEnabled = isEnabled,
        )
    }

    fun markSettingsLaunched() {
        preferences.edit().putBoolean(KEY_HANDLED, true).putBoolean(KEY_PENDING_RETURN, true).apply()
    }

    fun markSkipped() {
        preferences.edit().putBoolean(KEY_HANDLED, true).putBoolean(KEY_PENDING_RETURN, false).apply()
    }

    fun isSettingsReturnPending(): Boolean = preferences.getBoolean(KEY_PENDING_RETURN, false)

    fun completeSettingsReturn() {
        preferences.edit().putBoolean(KEY_PENDING_RETURN, false).apply()
    }

    fun stateAfterSettingsReturn(isEnabled: Boolean): FirstLaunchAccessibilityGateState =
        FirstLaunchAccessibilityPolicy.afterSettingsReturn(isEnabled)

    private companion object {
        const val PREFERENCES_NAME = "first_launch_accessibility_v1"
        const val KEY_HANDLED = "handled"
        const val KEY_PENDING_RETURN = "settings_return_pending"
    }
}

@Composable
internal fun FirstLaunchAccessibilityGate(
    settingsUnavailable: Boolean,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.first_launch_accessibility_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.first_launch_accessibility_explanation),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (settingsUnavailable) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.first_launch_accessibility_unavailable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("firstLaunchAccessibilityError"),
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("firstLaunchAccessibilityOpenSettings"),
        ) {
            Text(stringResource(R.string.first_launch_accessibility_open_settings))
        }
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.testTag("firstLaunchAccessibilitySkip"),
        ) {
            Text(stringResource(R.string.first_launch_accessibility_skip))
        }
    }
}
