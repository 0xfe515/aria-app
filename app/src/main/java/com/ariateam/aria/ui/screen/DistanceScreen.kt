package com.ariateam.aria.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.R
import com.ariateam.aria.distance.DistanceRisk
import com.ariateam.aria.distance.DistanceSetupStage
import com.ariateam.aria.distance.DistanceStatus
import com.ariateam.aria.distance.DistanceUiState
import com.ariateam.aria.ui.theme.AriaGreen
import com.ariateam.aria.ui.theme.AriaRed
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate

private val AriaAmber = Color(0xFFF59E0B)

@Composable
fun DistanceScreen(
    uiState: DistanceUiState,
    onBack: () -> Unit,
    onRecalibrate: () -> Unit,
    onDismissOnboarding: (dontShowAgain: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            DistanceHeader(
                onBack = onBack,
                onRecalibrate = onRecalibrate,
                canRecalibrate = uiState.setupStage == DistanceSetupStage.TRACKING ||
                    uiState.setupStage == DistanceSetupStage.FLOOR_SEARCH,
            )

            // Setup / Error Banners
            when (uiState.setupStage) {
                DistanceSetupStage.ERROR_AR_UNAVAILABLE -> {
                    ErrorCard(
                        title = stringResource(R.string.distance_error_arcore_unavailable),
                        buttonText = stringResource(R.string.distance_return_to_main),
                        onAction = onBack,
                    )
                }
                DistanceSetupStage.ERROR_INSTALL_REQUIRED -> {
                    ErrorCard(
                        title = stringResource(R.string.distance_error_arcore_install_required),
                        buttonText = stringResource(R.string.distance_return_to_main),
                        onAction = onBack,
                    )
                }
                DistanceSetupStage.ERROR_DEPTH_UNSUPPORTED -> {
                    ErrorCard(
                        title = stringResource(R.string.distance_error_raw_depth_unsupported),
                        buttonText = stringResource(R.string.distance_return_to_main),
                        onAction = onBack,
                    )
                }
                DistanceSetupStage.ERROR_PERMISSION -> {
                    ErrorCard(
                        title = stringResource(R.string.distance_error_permission_denied),
                        buttonText = stringResource(R.string.distance_return_to_main),
                        onAction = onBack,
                    )
                }
                else -> {
                    // Main Distance Readout & Risk Card
                    MainDistanceCard(uiState)

                    // Readiness & Tracking Quality Gates
                    ReadinessCard(uiState)

                    // Threshold and Disclaimer Card
                    GuidanceCard()
                }
            }
        }

        // Onboarding Modal
        if (uiState.showOnboarding) {
            OnboardingDialog(
                onDismiss = onDismissOnboarding,
            )
        }
    }
}

@Composable
private fun DistanceHeader(
    onBack: () -> Unit,
    onRecalibrate: () -> Unit,
    canRecalibrate: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.distance_close),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.distance_mode_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.semantics { heading() },
            )
        }

        OutlinedButton(
            onClick = onRecalibrate,
            enabled = canRecalibrate,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White,
                disabledContentColor = AriaSlate,
            ),
            modifier = Modifier.height(38.dp),
        ) {
            Text(stringResource(R.string.distance_recalibrate), fontSize = 13.sp)
        }
    }
}

@Composable
private fun MainDistanceCard(uiState: DistanceUiState) {
    val (riskColor, riskText) = when (uiState.risk) {
        DistanceRisk.URGENT -> AriaRed to stringResource(R.string.distance_risk_urgent)
        DistanceRisk.CAUTION -> AriaAmber to stringResource(R.string.distance_risk_caution)
        DistanceRisk.CLEAR -> AriaGreen to stringResource(R.string.distance_risk_clear)
        DistanceRisk.UNKNOWN -> {
            if (uiState.status == DistanceStatus.UNAVAILABLE) {
                AriaRed to stringResource(R.string.distance_status_unavailable)
            } else {
                AriaSlate to stringResource(R.string.distance_risk_unknown)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC111827),
        border = androidx.compose.foundation.BorderStroke(2.dp, riskColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp),
        ) {
            // Status Tag
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(riskColor.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = riskText,
                    color = riskColor,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Large metric distance display
            val distanceText = uiState.distanceMeters?.let {
                stringResource(R.string.distance_meters_format, it)
            } ?: stringResource(R.string.distance_meters_empty)

            Text(
                text = distanceText,
                color = if (uiState.distanceMeters != null) Color.White else AriaSlate,
                fontSize = 56.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp,
            )

            Spacer(Modifier.height(4.dp))

            val statusDetail = when {
                uiState.message.isNotEmpty() -> uiState.message
                uiState.status == DistanceStatus.TRACKING -> stringResource(R.string.distance_samples_count, uiState.validSampleCount)
                uiState.status == DistanceStatus.UNAVAILABLE -> stringResource(R.string.distance_status_unavailable)
                else -> stringResource(R.string.distance_status_preparing)
            }

            Text(
                text = statusDetail,
                color = AriaSlate,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReadinessCard(uiState: DistanceUiState) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xAA1F2937),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "인식 상태 및 지면 추적",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )

            // Floor Plane Quality Gate
            Row(verticalAlignment = Alignment.CenterVertically) {
                val floorColor = if (uiState.floorDetected) AriaGreen else AriaAmber
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(floorColor),
                )
                Spacer(Modifier.width(8.dp))
                val floorText = if (uiState.floorDetected && uiState.groundOffsetMeters != null) {
                    stringResource(R.string.distance_readiness_floor_detected, uiState.groundOffsetMeters)
                } else {
                    stringResource(R.string.distance_readiness_floor_searching)
                }
                Text(
                    text = floorText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Raw Depth Sensor Quality Gate
            Row(verticalAlignment = Alignment.CenterVertically) {
                val depthColor = if (uiState.rawDepthActive) AriaGreen else AriaSlate
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(depthColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (uiState.rawDepthActive) {
                        stringResource(R.string.distance_readiness_raw_depth_active)
                    } else {
                        stringResource(R.string.distance_readiness_init)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GuidanceCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0x99111827),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.distance_threshold_guide),
                color = AriaSky,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = stringResource(R.string.distance_experimental_notice),
                color = AriaSlate,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun ErrorCard(
    title: String,
    buttonText: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xEE1F2937),
        border = androidx.compose.foundation.BorderStroke(1.dp, AriaRed),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AriaRed.copy(alpha = 0.2f))
                    .border(2.dp, AriaRed, CircleShape),
            ) {
                Text(
                    text = "!",
                    color = AriaRed,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = AriaSky),
            ) {
                Text(buttonText, color = Color.Black)
            }
        }
    }
}

@Composable
private fun OnboardingDialog(
    onDismiss: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        title = {
            Text(
                text = stringResource(R.string.distance_onboarding_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OnboardingStep(
                    title = stringResource(R.string.distance_onboarding_step1_title),
                    desc = stringResource(R.string.distance_onboarding_step1_desc),
                )
                OnboardingStep(
                    title = stringResource(R.string.distance_onboarding_step2_title),
                    desc = stringResource(R.string.distance_onboarding_step2_desc),
                )
                OnboardingStep(
                    title = stringResource(R.string.distance_onboarding_step3_title),
                    desc = stringResource(R.string.distance_onboarding_step3_desc),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        colors = CheckboxDefaults.colors(checkedColor = AriaSky),
                    )
                    Text(
                        text = stringResource(R.string.distance_onboarding_checkbox),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDismiss(dontShowAgain) },
                colors = ButtonDefaults.buttonColors(containerColor = AriaSky),
            ) {
                Text(
                    text = stringResource(R.string.distance_onboarding_confirm),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

@Composable
private fun OnboardingStep(title: String, desc: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = AriaSky,
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray,
            lineHeight = 18.sp,
        )
    }
}
