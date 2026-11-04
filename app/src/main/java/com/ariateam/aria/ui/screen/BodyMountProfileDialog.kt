package com.ariateam.aria.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.R
import com.ariateam.aria.distance.BodyMountHeightModel
import com.ariateam.aria.distance.BodyMountPosition
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dialog allowing user to configure their stature (cm) and body mount position (Shoulder or Chest).
 *
 * Requirements:
 * - First use: No assumed stature (input starts empty).
 * - Stature range: 120.0 .. 220.0 cm inclusive, finite, decimal allowed (dot or comma).
 * - Explicit position selection: Shoulder or Chest with anatomical lens references.
 * - Dynamic derived estimated lens height display without exposing raw coefficient math.
 * - Actions pinned and scrollable body to survive compact landscape & 1.5x font scale with IME.
 * - Persistence through [BodyMountProfileStore]. Cancel leaves saved profile untouched.
 */
@Composable
fun BodyMountProfileDialog(
    onDismiss: () -> Unit,
    initialProfile: BodyMountProfile? = null,
    profileStore: BodyMountProfileStore? = null,
    onSaved: (BodyMountProfile) -> Unit = {},
) {
    val context = LocalContext.current
    val effectiveStore = remember(context, profileStore) {
        profileStore ?: BodyMountProfileStore(context)
    }
    DisposableEffect(effectiveStore, profileStore) {
        onDispose {
            if (profileStore == null) {
                effectiveStore.close()
            }
        }
    }

    val storedProfile by effectiveStore.profile.collectAsStateWithLifecycle()
    val currentSaved = initialProfile ?: storedProfile
    val saveErrorString = stringResource(R.string.body_mount_save_error)

    var saveErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    var statureText by rememberSaveable {
        mutableStateOf(
            if (currentSaved != null && currentSaved.isValid) {
                if (currentSaved.statureCm % 1f == 0f) {
                    currentSaved.statureCm.toInt().toString()
                } else {
                    currentSaved.statureCm.toString()
                }
            } else {
                ""
            }
        )
    }

    var selectedPosition by rememberSaveable {
        mutableStateOf(currentSaved?.position ?: BodyMountPosition.SHOULDER)
    }

    // Normalize comma or dot decimal
    val normalizedInput = statureText.trim().replace(',', '.')
    val parsedStature = normalizedInput.toFloatOrNull()
    val isStatureFinite = parsedStature != null && !parsedStature.isNaN() && parsedStature.isFinite()
    val isStatureInRange = isStatureFinite &&
        parsedStature!! >= BodyMountHeightModel.MIN_STATURE_CM &&
        parsedStature <= BodyMountHeightModel.MAX_STATURE_CM

    val statureError: String? = when {
        statureText.isBlank() -> stringResource(R.string.body_mount_stature_error_required)
        !isStatureFinite -> stringResource(R.string.body_mount_stature_error_number)
        !isStatureInRange -> stringResource(R.string.body_mount_stature_error_range)
        else -> null
    }

    val isValid = isStatureInRange && statureError == null
    val estimatedHeightMeters = if (isValid && parsedStature != null) {
        BodyMountHeightModel.estimateHeightMeters(parsedStature, selectedPosition)
    } else {
        null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bodyMountProfileDialog"),
                shape = RoundedCornerShape(16.dp),
                color = AriaPanel,
                border = BorderStroke(1.dp, AriaSlate.copy(alpha = 0.35f)),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Header
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.body_mount_profile_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .semantics { heading() }
                                .testTag("bodyMountProfileTitle"),
                        )
                        Text(
                            text = "카메라 렌즈 기준 착용 높이를 설정합니다. (AR 거리 측정과 별개)",
                            style = MaterialTheme.typography.bodySmall,
                            color = AriaSlate,
                        )
                    }

                    // Stature Input Field with adjacent error feedback
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        OutlinedTextField(
                            value = statureText,
                            onValueChange = { input ->
                                saveErrorMessage = null
                                val filtered = input.filter { it.isDigit() || it == '.' || it == ',' }
                                val dotOrCommaCount = filtered.count { it == '.' || it == ',' }
                                if (dotOrCommaCount <= 1) {
                                    statureText = filtered
                                }
                            },
                            label = { Text(stringResource(R.string.body_mount_stature_label)) },
                            placeholder = { Text(stringResource(R.string.body_mount_stature_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = statureText.isNotBlank() && statureError != null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AriaSky,
                                unfocusedBorderColor = AriaSlate.copy(alpha = 0.5f),
                                errorBorderColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bodyMountStatureInput"),
                        )

                        // Explicit adjacent error message directly below input
                        if (statureText.isNotBlank() && statureError != null) {
                            Text(
                                text = statureError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .padding(start = 4.dp, top = 2.dp)
                                    .testTag("bodyMountStatureError"),
                            )
                        }
                    }

                    // Mount Position Selection (Compact Row)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.body_mount_position_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MountPositionCard(
                                modifier = Modifier.weight(1f),
                                position = BodyMountPosition.SHOULDER,
                                title = stringResource(R.string.body_mount_position_shoulder),
                                selected = selectedPosition == BodyMountPosition.SHOULDER,
                                onSelect = {
                                    saveErrorMessage = null
                                    selectedPosition = BodyMountPosition.SHOULDER
                                },
                                testTag = "bodyMountPositionShoulder",
                            )

                            MountPositionCard(
                                modifier = Modifier.weight(1f),
                                position = BodyMountPosition.CHEST,
                                title = stringResource(R.string.body_mount_position_chest),
                                selected = selectedPosition == BodyMountPosition.CHEST,
                                onSelect = {
                                    saveErrorMessage = null
                                    selectedPosition = BodyMountPosition.CHEST
                                },
                                testTag = "bodyMountPositionChest",
                            )
                        }
                    }

                    // Derived Lens Height Preview
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, AriaSlate.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (estimatedHeightMeters != null) {
                                val heightCm = (estimatedHeightMeters * 100f).roundToInt()
                                Text(
                                    text = String.format(
                                        Locale.KOREAN,
                                        "추정 렌즈 높이: 약 %dcm (%.2fm)",
                                        heightCm,
                                        estimatedHeightMeters,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AriaGreen,
                                    modifier = Modifier.testTag("bodyMountDerivedHeight"),
                                )
                            } else {
                                Text(
                                    text = "120~220cm 사이의 키를 입력하면 추정 렌즈 높이가 계산됩니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AriaSlate,
                                    modifier = Modifier.testTag("bodyMountDerivedHeight"),
                                )
                            }
                        }
                    }

                    if (saveErrorMessage != null) {
                        Text(
                            text = saveErrorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("bodyMountSaveError"),
                        )
                    }

                    // Actions: Cancel / Clear / Save
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentSaved != null && currentSaved.isValid) {
                            TextButton(
                                onClick = {
                                    effectiveStore.clear()
                                    onDismiss()
                                },
                                modifier = Modifier.testTag("bodyMountClearButton"),
                            ) {
                                Text(
                                    text = stringResource(R.string.body_mount_clear),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("bodyMountCancelButton"),
                        ) {
                            Text(
                                text = stringResource(R.string.body_mount_cancel),
                                color = AriaSlate,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (isValid && parsedStature != null) {
                                    val profile = BodyMountProfile(
                                        statureCm = parsedStature,
                                        position = selectedPosition,
                                    )
                                    val saved = effectiveStore.save(profile)
                                    if (saved) {
                                        saveErrorMessage = null
                                        onSaved(profile)
                                        onDismiss()
                                    } else {
                                        saveErrorMessage = saveErrorString
                                    }
                                }
                            },
                            enabled = isValid,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AriaSky,
                                disabledContainerColor = AriaSky.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.testTag("bodyMountSaveButton"),
                        ) {
                            Text(
                                text = stringResource(R.string.body_mount_save),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MountPositionCard(
    position: BodyMountPosition,
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) AriaSky.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
        border = BorderStroke(
            1.dp,
            if (selected) AriaSky.copy(alpha = 0.6f) else AriaSlate.copy(alpha = 0.2f),
        ),
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = AriaSky,
                    unselectedColor = AriaSlate,
                ),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) AriaSky else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
