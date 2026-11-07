package com.ariateam.aria.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ariateam.aria.R
import com.ariateam.aria.cloud.auth.ChatGptAccountSummary
import com.ariateam.aria.cloud.auth.ChatGptAuthRepository
import com.ariateam.aria.cloud.auth.ChatGptAuthState
import com.ariateam.aria.cloud.auth.ChatGptAuthStatus
import kotlinx.coroutines.launch

/**
 * Pure policy and formatting utilities for ChatGPT account UI representation.
 * Tested independently in JVM unit tests.
 */
object ChatGptAccountUiPolicy {
    /**
     * Formats a user-friendly account label without exposing technical IDs (clientId, subject).
     */
    fun formatFriendlyAccountLabel(
        email: String?,
        name: String?,
        fallbackIndex: Int? = null,
    ): String {
        val trimmedEmail = email?.trim()
        val trimmedName = name?.trim()
        return when {
            !trimmedName.isNullOrEmpty() && !trimmedEmail.isNullOrEmpty() -> "$trimmedName ($trimmedEmail)"
            !trimmedEmail.isNullOrEmpty() -> trimmedEmail
            !trimmedName.isNullOrEmpty() -> trimmedName
            fallbackIndex != null -> "ChatGPT 계정 $fallbackIndex"
            else -> "ChatGPT 계정"
        }
    }

    /**
     * Returns a human-friendly revocation notice string, or null if no revocation attempt occurred.
     * Only non-null after sign-out / remote revocation.
     */
    fun formatRevocationNotice(remoteRevocationConfirmed: Boolean?): String? {
        return when (remoteRevocationConfirmed) {
            true -> "원격 토큰 해지 상태: 서버 해지 확인됨"
            false -> "원격 토큰 해지 상태: 서버 미확인 (로컬 로그아웃 완료)"
            null -> null
        }
    }

    /**
     * Determines the target client ID to reuse for reconnect / reauthorization.
     * Reuses the saved issued client ID to avoid unnecessary dynamic re-registration.
     */
    fun resolveTargetClientId(
        currentAccountClientId: String?,
        savedAccounts: List<ChatGptAccountSummary>,
        preferredClientId: String? = null,
    ): String? {
        if (!preferredClientId.isNullOrBlank()) return preferredClientId
        if (!currentAccountClientId.isNullOrBlank()) return currentAccountClientId
        val activeSaved = savedAccounts.firstOrNull { it.isActive && it.clientId.isNotBlank() }
        if (activeSaved != null) return activeSaved.clientId
        val firstSaved = savedAccounts.firstOrNull { it.clientId.isNotBlank() }
        return firstSaved?.clientId
    }
}

/**
 * Settings screen section for ChatGPT official authentication and account management.
 * Conforms to OpenAI Sign in with ChatGPT (SIWC) guidelines:
 * - Uses official browser authorization only (no API key inputs or embedded webviews)
 * - Safe account display (never displays secrets, tokens, or technical UUIDs/client IDs)
 * - Explicit plan permission state
 * - Truthful revocation status displayed only post-signout (never alarming during normal session)
 * - Reconnect and returning sign-in reuse saved issued client IDs
 */
@Composable
fun ChatGptAccountSettings(
    authRepository: ChatGptAuthRepository,
    hasLegacyModel: Boolean,
    legacySizeBytes: Long,
    onOpenCleanup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authState by authRepository.sessionState.collectAsStateWithLifecycle()
    val savedAccounts = remember(authState) { authRepository.getSavedAccounts() }

    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var showAccountPickerDialog by rememberSaveable { mutableStateOf(false) }
    var localActionError by remember { mutableStateOf<String?>(null) }
    val defaultActionErrorMessage = stringResource(R.string.chatgpt_action_error_message)

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsPageHeader(stringResource(R.string.settings_account_title), onBack)
        SettingsSectionTitle(stringResource(R.string.settings_cloud_model_title))

        // Model info row
        SettingsNavigationRow(
            title = stringResource(R.string.settings_cloud_model_title),
            summary = stringResource(R.string.settings_cloud_model_summary),
            onClick = {},
        )

        // ChatGPT Authentication and Account Management section
        SettingsSectionTitle(stringResource(R.string.settings_account_title))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Display remote revocation notice OUTSIDE authenticated branch (only when non-null)
            val revocationNotice = ChatGptAccountUiPolicy.formatRevocationNotice(authState.remoteRevocationConfirmed)
            if (revocationNotice != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = revocationNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("chatgpt_revocation_status"),
                    )
                }
            }

            // Display any session or local action error clearly without raw stack traces
            val displayedError = localActionError ?: authState.lastError
            if (!displayedError.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = displayedError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("chatgpt_error_text"),
                    )
                }
            }

            // Auth state specific cards and controls
            when (authState.status) {
                ChatGptAuthStatus.UNAUTHENTICATED -> {
                    Text(
                        text = stringResource(R.string.chatgpt_status_unauthenticated),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chatgpt_status_unauthenticated"),
                    )

                    val targetSavedClientId = ChatGptAccountUiPolicy.resolveTargetClientId(
                        currentAccountClientId = null,
                        savedAccounts = savedAccounts,
                    )

                    if (savedAccounts.isNotEmpty() && targetSavedClientId != null) {
                        val primarySaved = savedAccounts.firstOrNull { it.clientId == targetSavedClientId } ?: savedAccounts.first()
                        val friendlyLabel = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
                            email = primarySaved.email,
                            name = primarySaved.name,
                            fallbackIndex = 1,
                        )

                        // Reconnect saved account using issued client ID
                        Button(
                            onClick = {
                                localActionError = null
                                authRepository.startLogin(context, targetClientId = primarySaved.clientId)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chatgpt_login_button"),
                        ) {
                            Text(
                                text = stringResource(R.string.chatgpt_reconnect_saved_account, friendlyLabel),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        // Explicit button to connect another / new account via dynamic registration
                        OutlinedButton(
                            onClick = {
                                localActionError = null
                                authRepository.startLogin(context, targetClientId = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chatgpt_add_account_button"),
                        ) {
                            Text(stringResource(R.string.chatgpt_action_add_new_account))
                        }

                        if (savedAccounts.size > 1) {
                            TextButton(
                                onClick = { showAccountPickerDialog = true },
                                modifier = Modifier.testTag("chatgpt_switch_account_button"),
                            ) {
                                Text(stringResource(R.string.chatgpt_action_switch_account))
                            }
                        }
                    } else {
                        // Clean first-time login
                        Button(
                            onClick = {
                                localActionError = null
                                authRepository.startLogin(context, targetClientId = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chatgpt_login_button"),
                        ) {
                            Text(
                                text = stringResource(R.string.chatgpt_login_button),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                ChatGptAuthStatus.AUTHORIZING -> {
                    Text(
                        text = stringResource(R.string.chatgpt_status_authorizing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("chatgpt_status_authorizing"),
                    )

                    OutlinedButton(
                        onClick = { authRepository.cancelLogin() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chatgpt_cancel_button"),
                    ) {
                        Text(stringResource(R.string.chatgpt_action_cancel))
                    }
                }

                ChatGptAuthStatus.AUTHENTICATED -> {
                    val userLabel = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
                        email = authState.account?.email,
                        name = authState.account?.name,
                    )

                    Text(
                        text = stringResource(R.string.chatgpt_status_authenticated, userLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("chatgpt_account_email"),
                    )

                    // Plan usage permission state
                    val hasPlanPermission = authState.account?.hasPlanUsagePermission == true
                    Text(
                        text = if (hasPlanPermission) {
                            stringResource(R.string.chatgpt_permission_granted)
                        } else {
                            stringResource(R.string.chatgpt_permission_denied)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasPlanPermission) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.testTag("chatgpt_permission_status"),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chatgpt_logout_button"),
                        ) {
                            Text(
                                stringResource(R.string.chatgpt_action_logout),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        if (savedAccounts.size > 1) {
                            OutlinedButton(
                                onClick = { showAccountPickerDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chatgpt_switch_account_button"),
                            ) {
                                Text(stringResource(R.string.chatgpt_action_switch_account))
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            localActionError = null
                            authRepository.startLogin(context, targetClientId = null)
                        },
                        modifier = Modifier.testTag("chatgpt_add_account_button"),
                    ) {
                        Text(stringResource(R.string.chatgpt_action_add_account))
                    }
                }

                ChatGptAuthStatus.PLAN_USE_NOT_ENABLED -> {
                    Text(
                        text = stringResource(R.string.chatgpt_status_plan_not_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("chatgpt_status_plan_not_enabled"),
                    )

                    Text(
                        text = stringResource(R.string.chatgpt_permission_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("chatgpt_permission_status"),
                    )

                    val targetClientId = ChatGptAccountUiPolicy.resolveTargetClientId(
                        currentAccountClientId = authState.account?.clientId,
                        savedAccounts = savedAccounts,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                localActionError = null
                                authRepository.startLogin(context, targetClientId = targetClientId, forceReconsent = true)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chatgpt_reconnect_button"),
                        ) {
                            Text(stringResource(R.string.chatgpt_action_reconnect))
                        }

                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chatgpt_logout_button"),
                        ) {
                            Text(
                                stringResource(R.string.chatgpt_action_logout),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                ChatGptAuthStatus.REFRESH_FAILED_RECONNECT -> {
                    Text(
                        text = stringResource(R.string.chatgpt_status_refresh_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("chatgpt_status_refresh_failed"),
                    )

                    val targetClientId = ChatGptAccountUiPolicy.resolveTargetClientId(
                        currentAccountClientId = authState.account?.clientId,
                        savedAccounts = savedAccounts,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                localActionError = null
                                authRepository.startLogin(context, targetClientId = targetClientId, forceReconsent = false)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chatgpt_reconnect_button"),
                        ) {
                            Text(stringResource(R.string.chatgpt_action_reconnect))
                        }

                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chatgpt_logout_button"),
                        ) {
                            Text(
                                stringResource(R.string.chatgpt_action_logout),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_cloud_privacy_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Voice command and Question routing policy
        SettingsSectionTitle(stringResource(R.string.settings_cloud_fallback_policy_title))
        Text(
            text = stringResource(R.string.settings_cloud_fallback_policy_summary),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Storage Management (Optional legacy model file cleanup)
        SettingsSectionTitle(stringResource(R.string.settings_account_storage_section))
        val sizeText = if (legacySizeBytes > 0L) {
            val sizeMb = legacySizeBytes / (1024L * 1024L)
            if (sizeMb >= 1024L) {
                "%.2f GB".format(legacySizeBytes / (1024.0 * 1024.0 * 1024.0))
            } else {
                "$sizeMb MB"
            }
        } else {
            "0 MB"
        }
        SettingsNavigationRow(
            title = stringResource(R.string.settings_account_cleanup_title),
            summary = if (hasLegacyModel) {
                stringResource(R.string.settings_account_cleanup_summary_present, sizeText)
            } else {
                stringResource(R.string.settings_account_cleanup_summary_empty)
            },
            onClick = onOpenCleanup,
        )

        // Retained On-device AI components (ASR & Vision)
        SettingsSectionTitle(stringResource(R.string.settings_account_local_models_group))
        SettingsNavigationRow(
            title = stringResource(R.string.settings_account_local_asr_title),
            summary = stringResource(R.string.settings_account_local_asr_summary),
            onClick = {},
        )
        SettingsNavigationRow(
            title = stringResource(R.string.settings_account_local_vision_title),
            summary = stringResource(R.string.settings_account_local_vision_summary),
            onClick = {},
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.chatgpt_logout_confirm_title)) },
            text = { Text(stringResource(R.string.chatgpt_logout_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        coroutineScope.launch {
                            try {
                                localActionError = null
                                authRepository.signOut()
                            } catch (_: Throwable) {
                                localActionError = defaultActionErrorMessage
                            }
                        }
                    },
                    modifier = Modifier.testTag("chatgpt_logout_confirm_btn"),
                ) {
                    Text(
                        stringResource(R.string.chatgpt_action_logout),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showAccountPickerDialog) {
        AlertDialog(
            onDismissRequest = { showAccountPickerDialog = false },
            title = { Text(stringResource(R.string.chatgpt_account_picker_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedAccounts.forEachIndexed { index, acc ->
                        val friendlyLabel = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
                            email = acc.email,
                            name = acc.name,
                            fallbackIndex = index + 1,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAccountPickerDialog = false
                                    coroutineScope.launch {
                                        try {
                                            localActionError = null
                                            // Always reauthorize selected saved registration with startLogin(context, targetClientId = acc.clientId)
                                            // Stored grant scopes do NOT prove usable tokens (signed-out records retain scopes).
                                            authRepository.startLogin(context, targetClientId = acc.clientId)
                                        } catch (_: Throwable) {
                                            localActionError = defaultActionErrorMessage
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(friendlyLabel, style = MaterialTheme.typography.bodyMedium)
                                if (acc.isActive) {
                                    Text(
                                        "현재 활성 계정",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else if (!acc.hasPlanUsagePermission) {
                                    Text(
                                        "재인증 필요",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountPickerDialog = false }) {
                    Text(stringResource(R.string.chatgpt_dialog_close))
                }
            },
        )
    }
}
