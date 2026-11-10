package com.ariateam.aria.ui.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ariateam.aria.R

private const val NAVER_WALKING_ACCESSIBILITY_SERVICE =
    "com.ariateam.aria.navigation.accessibility.NaverWalkingAccessibilityService"

@Composable
internal fun NaverWalkingAccessibilitySettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val serviceComponent = remember(context) {
        ComponentName(context.packageName, NAVER_WALKING_ACCESSIBILITY_SERVICE)
    }
    var enabled by remember(context) { mutableStateOf(isServiceEnabled(context, serviceComponent)) }
    var settingsUnavailable by remember { mutableStateOf(false) }

    DisposableEffect(context, lifecycleOwner, serviceComponent) {
        val refresh = { enabled = isServiceEnabled(context, serviceComponent) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        SettingsSectionTitle(stringResource(R.string.nav_accessibility_settings_title))
        Text(
            text = stringResource(R.string.nav_accessibility_settings_disclosure),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = stringResource(
                if (enabled) R.string.nav_accessibility_settings_enabled
                else R.string.nav_accessibility_settings_disabled,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .semantics { contentDescription = if (enabled) "접근성 서비스 사용 중" else "접근성 서비스 사용 안 함" }
                .testTag("navWalkingAccessibilityStatus"),
        )
        OutlinedButton(
            onClick = {
                settingsUnavailable = !openAccessibilitySettings(context)
                enabled = isServiceEnabled(context, serviceComponent)
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp)
                .testTag("navWalkingAccessibilitySettingsButton"),
        ) {
            Text(
                stringResource(
                    if (enabled) R.string.nav_accessibility_settings_check
                    else R.string.nav_accessibility_settings_open,
                ),
            )
        }
        if (settingsUnavailable) {
            Text(
                text = stringResource(R.string.nav_accessibility_settings_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

internal fun isServiceEnabled(context: Context, component: ComponentName): Boolean {
    return runCatching {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == component
            }
    }.getOrDefault(false)
}

internal fun openAccessibilitySettings(context: Context): Boolean {
    val generalSettingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    return launchAccessibilitySettings { launchSettingsIntent(context, generalSettingsIntent) }
}

internal fun launchAccessibilitySettings(openSettings: () -> Unit): Boolean {
    try {
        openSettings()
        return true
    } catch (_: ActivityNotFoundException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
}

private fun launchSettingsIntent(context: Context, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
