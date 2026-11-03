package com.ariateam.aria.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.R
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaRed
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate
import com.ariateam.aria.vision.CameraSessionStatus

/**
 * Dedicated recovery action surface located on the Home screen outside of the read-only vision dashboard.
 *
 * Provides accessible recovery controls when user intervention is required (e.g. camera permission,
 * explicit camera restart, profile setup) without contaminating the read-only visual dashboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardHomeRecoveryBar(
    hasCameraPermission: Boolean,
    cameraStatus: CameraSessionStatus,
    requiresExplicitRestart: Boolean,
    isProfileConfigured: Boolean,
    onRequestCameraPermission: () -> Unit,
    onExplicitRestartCamera: () -> Unit,
    onRetryCamera: () -> Unit,
    onOpenProfileSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsPermission = !hasCameraPermission || cameraStatus == CameraSessionStatus.PermissionDenied
    val needsRestart = requiresExplicitRestart
    val needsRetry = cameraStatus == CameraSessionStatus.Error || cameraStatus == CameraSessionStatus.CameraUnavailable
    val needsProfile = !isProfileConfigured

    // If everything is running smoothly, don't show recovery controls
    if (!needsPermission && !needsRestart && !needsRetry && !needsProfile) {
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("dashboardHomeRecoveryBar"),
        shape = RoundedCornerShape(12.dp),
        color = AriaPanel.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, AriaSlate.copy(alpha = 0.35f)),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.dashboard_recovery_title),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = AriaSlate,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (needsPermission) {
                    Button(
                        onClick = onRequestCameraPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = AriaSky),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .testTag("dashboardRecoveryGrantPermissionButton")
                            .semantics { role = Role.Button },
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_recovery_grant_permission),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }

                if (needsRestart) {
                    Button(
                        onClick = onExplicitRestartCamera,
                        colors = ButtonDefaults.buttonColors(containerColor = AriaSky),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .testTag("dashboardRecoveryRestartCameraButton")
                            .semantics { role = Role.Button },
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_recovery_restart_camera),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }

                if (needsRetry && !needsRestart && !needsPermission) {
                    Button(
                        onClick = onRetryCamera,
                        colors = ButtonDefaults.buttonColors(containerColor = AriaRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .testTag("dashboardRecoveryRetryCameraButton")
                            .semantics { role = Role.Button },
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_recovery_retry_camera),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }

                if (needsProfile) {
                    OutlinedButton(
                        onClick = onOpenProfileSetup,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AriaSky.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .testTag("dashboardRecoveryProfileSetupButton")
                            .semantics { role = Role.Button },
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_recovery_profile_setup),
                            color = AriaSky,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}
