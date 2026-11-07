package com.ariateam.aria.ui.screen

import com.ariateam.aria.cloud.auth.ChatGptAccountInfo
import com.ariateam.aria.cloud.auth.ChatGptAccountSummary
import com.ariateam.aria.cloud.auth.ChatGptAuthConstants
import com.ariateam.aria.cloud.auth.ChatGptAuthState
import com.ariateam.aria.cloud.auth.ChatGptAuthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM unit tests for production ChatGPT Account UI policy,
 * friendly identity formatting, revocation status resolution, and provider constraints.
 */
class ChatGptAccountUiTest {

    @Test
    fun friendlyAccountLabel_formatsNameAndEmailWithoutTechnicalIds() {
        // Name and email
        val label1 = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
            email = "user@example.com",
            name = "홍길동",
        )
        assertEquals("홍길동 (user@example.com)", label1)

        // Email only
        val label2 = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
            email = "test@aria.org",
            name = null,
        )
        assertEquals("test@aria.org", label2)

        // Name only
        val label3 = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
            email = null,
            name = "아리아",
        )
        assertEquals("아리아", label3)

        // Fallback with index
        val label4 = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
            email = null,
            name = "",
            fallbackIndex = 2,
        )
        assertEquals("ChatGPT 계정 2", label4)

        // Generic fallback
        val label5 = ChatGptAccountUiPolicy.formatFriendlyAccountLabel(
            email = "",
            name = null,
        )
        assertEquals("ChatGPT 계정", label5)
    }

    @Test
    fun revocationNotice_onlyVisibleWhenNonnullAfterRevocationAttempt() {
        // Normal active session: remoteRevocationConfirmed is null; must NOT show alarming notice
        assertNull(ChatGptAccountUiPolicy.formatRevocationNotice(null))

        // Revocation confirmed by server
        val confirmedNotice = ChatGptAccountUiPolicy.formatRevocationNotice(true)
        assertEquals("원격 토큰 해지 상태: 서버 해지 확인됨", confirmedNotice)

        // Revocation unconfirmed by server (offline / server error)
        val unconfirmedNotice = ChatGptAccountUiPolicy.formatRevocationNotice(false)
        assertEquals("원격 토큰 해지 상태: 서버 미확인 (로컬 로그아웃 완료)", unconfirmedNotice)
    }

    @Test
    fun resolveTargetClientId_reusesSavedIssuedIdToPreventDynamicReRegistration() {
        val saved1 = ChatGptAccountSummary(
            subject = "sub_1",
            email = "user1@example.com",
            name = "User 1",
            clientId = "dyn_client_issued_101",
            isActive = false,
            hasPlanUsagePermission = true,
        )
        val saved2 = ChatGptAccountSummary(
            subject = "sub_2",
            email = "user2@example.com",
            name = "User 2",
            clientId = "dyn_client_issued_202",
            isActive = true,
            hasPlanUsagePermission = true,
        )
        val list = listOf(saved1, saved2)

        // 1. Explicit preferred client ID takes precedence
        val target1 = ChatGptAccountUiPolicy.resolveTargetClientId(
            currentAccountClientId = "dyn_client_issued_101",
            savedAccounts = list,
            preferredClientId = "dyn_client_issued_202",
        )
        assertEquals("dyn_client_issued_202", target1)

        // 2. Current account client ID reused when reconnecting
        val target2 = ChatGptAccountUiPolicy.resolveTargetClientId(
            currentAccountClientId = "dyn_client_issued_101",
            savedAccounts = list,
        )
        assertEquals("dyn_client_issued_101", target2)

        // 3. Active saved account client ID reused when current is null
        val target3 = ChatGptAccountUiPolicy.resolveTargetClientId(
            currentAccountClientId = null,
            savedAccounts = list,
        )
        assertEquals("dyn_client_issued_202", target3)

        // 4. Clean start with no saved accounts yields null (initiates dynamic client registration)
        val target4 = ChatGptAccountUiPolicy.resolveTargetClientId(
            currentAccountClientId = null,
            savedAccounts = emptyList(),
        )
        assertNull(target4)
    }

    @Test
    fun settingsPolicy_onlyPermitsOfficialChatGptProviders() {
        assertTrue(SettingsPolicy.isSupportedAccountProvider("chatgpt"))
        assertTrue(SettingsPolicy.isSupportedAccountProvider("CHATGPT"))
        assertTrue(SettingsPolicy.isSupportedAccountProvider("openai"))
        assertTrue(SettingsPolicy.isSupportedAccountProvider("OpenAI"))

        // Legacy / rejected providers
        assertFalse(SettingsPolicy.isSupportedAccountProvider("gemini"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("GEMINI"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("claude"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("anthropic"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("ollama"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider("api_key"))
        assertFalse(SettingsPolicy.isSupportedAccountProvider(""))
    }

    @Test
    fun chatGptAuthState_toStringDoesNotLeakSecrets() {
        val account = ChatGptAccountInfo(
            subject = "sub_private_123",
            email = "sensitive@example.com",
            name = "Sensitive User",
            clientId = "dyn_client_xyz",
            scopes = setOf(ChatGptAuthConstants.SCOPE_CHATGPT_TOKENS_USE_DIRECT),
            hasPlanUsagePermission = true,
        )
        val state = ChatGptAuthState(
            status = ChatGptAuthStatus.AUTHENTICATED,
            account = account,
            remoteRevocationConfirmed = null,
        )

        val debugString = state.toString()
        assertTrue(debugString.contains("sensitive@example.com"))
        assertFalse(debugString.contains("Bearer"))
        assertFalse(debugString.contains("access_token"))
        assertFalse(debugString.contains("refresh_token"))
    }
}
