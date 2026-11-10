package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.settings.AlertFrequency
import com.ariateam.aria.settings.FeedbackSettingsClient

@Composable
internal fun FeedbackSettingsControls(client: FeedbackSettingsClient) {
    val settings by client.settings.collectAsStateWithLifecycle()
    val available by client.isAvailable.collectAsStateWithLifecycle()
    var saveFailed by remember { mutableStateOf(false) }
    var draftVolume by remember(settings.volumePercent) { mutableFloatStateOf(settings.volumePercent.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("앱 음성·효과음 크기: ${draftVolume.toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(value = draftVolume, onValueChange = { draftVolume = it }, onValueChangeFinished = {
            saveFailed = client.setVolumePercent(draftVolume.toInt()) == null
            if (saveFailed) draftVolume = settings.volumePercent.toFloat()
        }, valueRange = 0f..100f)
        Text("알림 반복 빈도: ${when (settings.alertFrequency) { AlertFrequency.FREQUENT -> "자주"; AlertFrequency.NORMAL -> "보통"; AlertFrequency.QUIET -> "덜 자주" }}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(AlertFrequency.FREQUENT to "자주", AlertFrequency.NORMAL to "보통", AlertFrequency.QUIET to "덜 자주").forEach { (frequency, label) ->
                TextButton(onClick = { saveFailed = client.setAlertFrequency(frequency) == null }) { Text(label) }
            }
        }
        if (!available || saveFailed) Text("피드백 설정을 저장하거나 읽지 못했습니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("주의 알림 사용", modifier = Modifier.weight(1f))
            Switch(checked = settings.cautionEnabled, onCheckedChange = { saveFailed = client.setCautionEnabled(it) == null })
        }
    }
}
