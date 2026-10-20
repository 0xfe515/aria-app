package com.ariateam.aria

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ariateam.aria.speech.VoiceViewModel
import com.ariateam.aria.ui.screen.SettingsScreen
import com.ariateam.aria.ui.screen.VoiceDemoScreen
import com.ariateam.aria.ui.theme.AriaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { AriaTheme { MainScreen() } }
    }
}

@Composable
private fun MainScreen(model: VoiceViewModel = viewModel()) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val audibleFeedback by model.audibleFeedback.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
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
    LaunchedEffect(selectedTab) { model.setUiScreen(if (selectedTab == 1) "settings" else "home") }
    var screenVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, _ ->
            screenVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            model.setUiVisible(screenVisible)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        screenVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        model.setUiVisible(screenVisible)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.setUiVisible(false)
        }
    }
    LaunchedEffect(state.command.navigationId, screenVisible) {
        if (screenVisible && state.command.navigationId != 0L) {
            selectedTab = if (state.command.destination == "settings") 1 else 0
            model.consumeNavigation(state.command.navigationId)
        }
    }
    var pendingAction by rememberSaveable { mutableIntStateOf(0) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    var initialRequestHandled by rememberSaveable { mutableStateOf(false) }
    val dispatch: (Int) -> Unit = { action ->
        when (action) {
            1 -> model.beginDictation()
            else -> model.startWaiting()
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            dispatch(pendingAction)
        } else model.permissionDenied()
    }
    val requestAction: (Int) -> Unit = { action ->
        pendingAction = action
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationRequested && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequested = true
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) dispatch(action) else permissions.launch(needed.toTypedArray())
    }
    // Restore the original app-entry wake-word behavior. Recomposition does not start another session.
    LaunchedEffect(Unit) {
        if (!initialRequestHandled) {
            initialRequestHandled = true
            requestAction(0)
        }
    }
    val openSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { FloatingTabBar(selectedTab) { selectedTab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> VoiceDemoScreen(state, onStart = { requestAction(1) }, onFinish = model::finishDictation,
                    onOpenSettings = openSettings, onStartWaiting = { requestAction(0) },
                    onStop = model::stop)
                1 -> SettingsScreen(state, onStartWaiting = { requestAction(0) }, onStop = model::stop,
                    audibleFeedback = audibleFeedback,
                    onAudibleFeedbackChanged = model::setAudibleFeedback,
                    onDownloadModel = model::downloadModel, onCancelDownload = model::cancelModelDownload,
                    onDeleteModel = model::deleteModel,
                    onNotificationSettings = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    })
            }
        }
    }
}

@Composable
private fun FloatingTabBar(selectedTab: Int, onSelected: (Int) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 48.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(Modifier.widthIn(max = 240.dp).fillMaxWidth(), shape = RoundedCornerShape(48),
            color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
            Row(Modifier.selectableGroup().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.home_tab, R.string.settings_tab).forEachIndexed { index, label ->
                    val selected = selectedTab == index
                    Box(Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelected(index) })
                        .padding(horizontal = 8.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
