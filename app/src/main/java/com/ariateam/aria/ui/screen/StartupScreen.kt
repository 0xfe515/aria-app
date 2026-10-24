package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.R
import com.ariateam.aria.startup.StartupDecision
import com.ariateam.aria.ui.theme.AriaNavy
import com.ariateam.aria.ui.theme.AriaPanel
import com.ariateam.aria.ui.theme.AriaSky
import com.ariateam.aria.ui.theme.AriaSlate

@Composable
fun StartupScreen(
    decision: StartupDecision,
    onRetry: () -> Unit,
    onContinueDegraded: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("startup_screen"),
        color = AriaNavy,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("startup_scroll"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.startup_app_name),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                    ),
                    color = AriaSky,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                when (decision) {
                    is StartupDecision.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("startup_loading_indicator"),
                            color = AriaSky,
                            strokeWidth = 3.dp,
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = decision.message,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("startup_message"),
                        )
                    }

                    is StartupDecision.ActionRequired -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("startup_action_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = AriaPanel,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = decision.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = decision.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AriaSlate,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag("startup_message"),
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (decision.canRetry) {
                                        Button(
                                            onClick = onRetry,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("startup_retry_button"),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AriaSky,
                                                contentColor = AriaNavy,
                                            ),
                                        ) {
                                            Text(stringResource(R.string.startup_btn_retry), fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    if (decision.needsSettings) {
                                        OutlinedButton(
                                            onClick = onOpenSettings,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("startup_settings_button"),
                                        ) {
                                            Text(stringResource(R.string.startup_btn_settings))
                                        }
                                    }

                                    if (decision.canContinueDegraded) {
                                        TextButton(
                                            onClick = onContinueDegraded,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("startup_continue_button"),
                                        ) {
                                            Text(
                                                stringResource(R.string.startup_btn_continue),
                                                color = AriaSlate,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is StartupDecision.Ready -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = AriaSky,
                        )
                    }
                }
            }
        }
    }
}
