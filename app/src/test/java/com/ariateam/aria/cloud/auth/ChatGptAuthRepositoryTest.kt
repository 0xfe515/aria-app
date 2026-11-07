package com.ariateam.aria.cloud.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatGptAuthRepositoryTest {

    private lateinit var transport: FakeAuthHttpTransport
    private lateinit var loopbackServer: FakeAuthLoopbackServer
    private lateinit var storage: InMemoryChatGptCredentialStorage
    private lateinit var jwtVerifier: FakeJwtVerifier
    private lateinit var browserLauncher: FakeBrowserLauncher
    private lateinit var clock: TestClock
    private lateinit var repository: DefaultChatGptAuthRepository

    @Before
    fun setUp() {
        transport = FakeAuthHttpTransport()
        loopbackServer = FakeAuthLoopbackServer()
        storage = InMemoryChatGptCredentialStorage("urn:uuid:test-host-id-001")
        jwtVerifier = FakeJwtVerifier()
        browserLauncher = FakeBrowserLauncher()
        clock = TestClock(1790000000L)

        repository = DefaultChatGptAuthRepository(
            transport = transport,
            loopbackServer = loopbackServer,
            storage = storage,
            jwtVerifier = jwtVerifier,
            browserLauncher = browserLauncher,
            clock = clock,
            dispatcher = Dispatchers.Unconfined,
            loginTimeoutMs = 1000L,
        )
    }

    @Test
    fun testInitialStateUnauthenticated() {
        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.UNAUTHENTICATED, state.status)
        assertNull(state.account)
        assertEquals(1L, state.sessionGeneration)
    }

    @Test
    fun testValidAccessTokenReturnsNullWhenUnauthenticatedEvenWithStoredCreds() = runBlocking {
        // Creds exist in storage but sessionState is UNAUTHENTICATED
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_stale",
            subject = "sub_stale",
            email = "stale@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "stale_token",
            refreshToken = "stale_refresh",
            expiresAtEpochSec = clock.currentEpochSec() + 3600L,
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = false)

        val token = repository.validAccessToken()
        assertNull(token)
    }

    @Test
    fun testDynamicRegistrationFullLoginSuccess() = runBlocking {
        repository.startLoginInternal(null)
        assertEquals(ChatGptAuthStatus.AUTHORIZING, repository.sessionState.value.status)
        assertEquals(2L, repository.sessionState.value.sessionGeneration)

        assertNotNull(browserLauncher.lastOpenedUrl)
        val url = browserLauncher.lastOpenedUrl!!
        assertTrue(url.contains("client_id=dynamic_agent_client"))
        assertTrue(url.contains("agent_name_hint=Aria"))
        assertTrue(url.contains("ext_agent_host_id=urn%3Auuid%3Atest-host-id-001"))
        assertTrue(url.contains("chatgpt.tokens.use.direct"))
        assertTrue(url.contains("resource.invoke"))

        transport.tokenResponseJson = JSONObject().apply {
            put("access_token", "at_live_123")
            put("token_type", "Bearer")
            put("expires_in", 3600L)
            put("refresh_token", "rt_live_456")
            put("id_token", "mock_id_token_789")
            put("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
        }.toString()

        jwtVerifier.verifiedClaims = IdTokenClaims(
            sub = "sub_user_test",
            iss = ChatGptAuthConstants.ISSUER,
            aud = listOf("oaiapp_issued_999"),
            azp = "oaiapp_issued_999",
            exp = clock.currentEpochSec() + 3600L,
            iat = clock.currentEpochSec(),
            nonce = null,
            email = "user@example.com",
            name = "Test User",
        )

        loopbackServer.deliverResult(
            LoopbackCallbackResult.Success(
                code = "code_abc_123",
                clientId = "oaiapp_issued_999",
                scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            ),
        )

        val finalState = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.AUTHENTICATED, finalState.status)
        assertNotNull(finalState.account)
        assertEquals("sub_user_test", finalState.account?.subject)
        assertEquals("user@example.com", finalState.account?.email)
        assertEquals("oaiapp_issued_999", finalState.account?.clientId)
        assertTrue(finalState.account?.hasPlanUsagePermission == true)

        val token = repository.validAccessToken()
        assertEquals("at_live_123", token)
    }

    @Test
    fun testPlanUsagePermissionMissingBlocksInference() = runBlocking {
        repository.startLoginInternal(null)

        transport.tokenResponseJson = JSONObject().apply {
            put("access_token", "at_identity_only")
            put("token_type", "Bearer")
            put("expires_in", 3600L)
            put("refresh_token", "rt_identity_only")
            put("id_token", "mock_id_token_identity")
            put("scope", "openid profile email offline_access")
        }.toString()

        jwtVerifier.verifiedClaims = IdTokenClaims(
            sub = "sub_identity_only",
            iss = ChatGptAuthConstants.ISSUER,
            aud = listOf("oaiapp_issued_555"),
            azp = "oaiapp_issued_555",
            exp = clock.currentEpochSec() + 3600L,
            iat = clock.currentEpochSec(),
            nonce = null,
            email = "id@example.com",
            name = "Identity User",
        )

        loopbackServer.deliverResult(
            LoopbackCallbackResult.Success(
                code = "code_identity",
                clientId = "oaiapp_issued_555",
                scope = "openid profile email offline_access",
            ),
        )

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.PLAN_USE_NOT_ENABLED, state.status)
        assertFalse(state.account?.hasPlanUsagePermission == true)

        try {
            repository.validAccessToken()
            fail("Expected PlanUsageNotEnabledException when plan usage permission missing")
        } catch (e: ChatGptAuthException.PlanUsageNotEnabledException) {
            assertTrue(e.message?.contains("권한") == true)
        }
    }

    @Test
    fun testReturningSignInUsesSavedClientId() = runBlocking {
        val saved = ChatGptStoredCredentials(
            clientId = "oaiapp_saved_888",
            subject = "sub_returning_user",
            email = "returning@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "old_at",
            refreshToken = "old_rt",
            idToken = "old_id_token",
            expiresAtEpochSec = clock.currentEpochSec() - 100,
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(saved, makeActive = true)

        repository.startLoginInternal(null, targetClientId = "oaiapp_saved_888")

        val url = browserLauncher.lastOpenedUrl!!
        assertTrue(url.contains("client_id=oaiapp_saved_888"))
        assertFalse(url.contains("client_id=dynamic_agent_client"))
        assertFalse(url.contains("agent_name_hint="))
        assertTrue(url.contains("login_hint=returning%40example.com"))
        assertTrue(url.contains("id_token_hint=old_id_token"))
    }

    @Test
    fun testTokenRefreshSerializationAndRotation() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_refresh_test",
            subject = "sub_refresh",
            email = "refresh@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "at_initial",
            refreshToken = "rt_initial_rot_1",
            expiresAtEpochSec = clock.currentEpochSec() + 100L,
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)

        // Make state active in repository
        repository.switchAccount("oaiapp_refresh_test")

        transport.tokenResponseJson = JSONObject().apply {
            put("access_token", "at_refreshed_new")
            put("token_type", "Bearer")
            put("expires_in", 3600L)
            put("refresh_token", "rt_rot_replacement_2")
            put("scope", "openid resource.invoke chatgpt.tokens.use.direct")
        }.toString()

        val tokenDeferreds = (1..5).map {
            async(Dispatchers.Default) {
                repository.validAccessToken()
            }
        }
        val tokens = tokenDeferreds.awaitAll()

        tokens.forEach { assertEquals("at_refreshed_new", it) }
        assertEquals(1, transport.refreshCallCount.get())

        val updated = storage.loadAllCredentials()["oaiapp_refresh_test"]
        assertNotNull(updated)
        assertEquals("at_refreshed_new", updated?.accessToken)
        assertEquals("rt_rot_replacement_2", updated?.refreshToken)
        assertEquals(clock.currentEpochSec() + 3600L, updated?.expiresAtEpochSec)
    }

    @Test
    fun testExpiredTokenWithFutureEarliestRefreshAtNeverReturnsStaleToken() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_expired_prohibited",
            subject = "sub_expired_prohibited",
            email = "prohibited@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "stale_expired_token",
            refreshToken = "rt_prohibited",
            expiresAtEpochSec = clock.currentEpochSec() - 10L, // Already EXPIRED!
            earliestRefreshAtEpochSec = clock.currentEpochSec() + 100L, // But refresh prohibited for 100s!
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)
        repository.switchAccount("oaiapp_expired_prohibited")

        try {
            repository.validAccessToken()
            fail("Expected UnusableRefreshTokenException: must never return expired token when refresh is prohibited")
        } catch (e: ChatGptAuthException.UnusableRefreshTokenException) {
            assertTrue(e.message?.contains("만료") == true)
        }
    }

    @Test
    fun testRefreshedScopesLosingPlanUsePermissionIncrementsGenerationAndReturnsNull() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_lose_permission",
            subject = "sub_lose_permission",
            email = "lose@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "at_old",
            refreshToken = "rt_old",
            expiresAtEpochSec = clock.currentEpochSec() + 100L,
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)
        repository.switchAccount("oaiapp_lose_permission")
        val initialGen = repository.sessionState.value.sessionGeneration

        // Refreshed response lacks chatgpt.tokens.use.direct
        transport.tokenResponseJson = JSONObject().apply {
            put("access_token", "at_new_identity_only")
            put("token_type", "Bearer")
            put("expires_in", 3600L)
            put("refresh_token", "rt_new")
            put("scope", "openid profile email")
        }.toString()

        val token = repository.validAccessToken()
        assertNull(token)

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.PLAN_USE_NOT_ENABLED, state.status)
        assertTrue(state.sessionGeneration > initialGen)
    }

    @Test
    fun testPermanentRefreshFailureWipesUnusableTokensAndTransitionsToReconnect() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_perm_fail",
            subject = "sub_perm_fail",
            email = "fail@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "at_old",
            refreshToken = "rt_invalidated",
            expiresAtEpochSec = clock.currentEpochSec() - 10L,
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)
        repository.switchAccount("oaiapp_perm_fail")

        transport.tokenResponseStatusCode = 400
        transport.tokenResponseJson = JSONObject().apply {
            put("error", "invalid_grant")
            put("error_description", "Refresh token was invalidated or expired")
        }.toString()

        try {
            repository.validAccessToken()
            fail("Expected UnusableRefreshTokenException on invalid_grant")
        } catch (e: ChatGptAuthException.UnusableRefreshTokenException) {
            assertEquals("invalid_grant", e.errorCode)
            assertTrue(e.message?.contains("만료") == true)
        }

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.REFRESH_FAILED_RECONNECT, state.status)
        assertEquals("sub_perm_fail", state.account?.subject)
        assertEquals("fail@example.com", state.account?.email)

        val stored = storage.loadAllCredentials()["oaiapp_perm_fail"]
        assertNotNull(stored)
        assertEquals("sub_perm_fail", stored?.subject)
        assertNull(stored?.accessToken)
        assertNull(stored?.refreshToken)
    }

    @Test
    fun testStorageClearFailureOnSignOutFailsClosedWithTombstone() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_clear_fail",
            subject = "sub_clear_fail",
            email = "clear_fail@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "token_that_could_not_be_cleared",
            refreshToken = "refresh_that_could_not_be_cleared",
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)
        repository.switchAccount("oaiapp_clear_fail")

        // Simulate storage failure during clearTokensForClient
        storage.failClearTokens = true

        repository.signOut()

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.UNAUTHENTICATED, state.status)
        assertNull(state.account)
        assertTrue(state.lastError?.contains("격리") == true)

        // Must fail closed: validAccessToken returns null even though underlying map had tokens
        val token = repository.validAccessToken()
        assertNull(token)
    }

    @Test
    fun testStorageSaveFailureOnExchangeFailsClosed() = runBlocking {
        repository.startLoginInternal(null)

        storage.failSaveCredentials = true

        transport.tokenResponseJson = JSONObject().apply {
            put("access_token", "at_save_fail")
            put("token_type", "Bearer")
            put("expires_in", 3600L)
            put("refresh_token", "rt_save_fail")
            put("id_token", "mock_id_token")
            put("scope", "openid resource.invoke chatgpt.tokens.use.direct")
        }.toString()

        loopbackServer.deliverResult(
            LoopbackCallbackResult.Success(
                code = "code_save_fail",
                clientId = "oaiapp_save_fail",
                scope = "openid resource.invoke chatgpt.tokens.use.direct",
            ),
        )

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.UNAUTHENTICATED, state.status)
        assertNull(state.account)
        assertTrue(state.lastError?.contains("저장하지 못했습니다") == true)
    }

    @Test
    fun testLogoutFencesPendingLateRefresh() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_refresh_fence",
            subject = "sub_fence",
            email = "fence@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "at_old",
            refreshToken = "rt_old",
            expiresAtEpochSec = clock.currentEpochSec() - 10L,
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)
        repository.switchAccount("oaiapp_refresh_fence")

        transport.onPostTokenHook = {
            runBlocking {
                repository.signOut()
            }
        }

        transport.tokenResponseJson = JSONObject().apply {
            put("access_token", "at_late_refresh")
            put("token_type", "Bearer")
            put("expires_in", 3600L)
            put("refresh_token", "rt_late_refresh")
            put("scope", "openid resource.invoke chatgpt.tokens.use.direct")
        }.toString()

        val tokenResult = repository.validAccessToken()
        assertNull(tokenResult)

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.UNAUTHENTICATED, state.status)
        assertNull(state.account)

        val stored = storage.loadAllCredentials()["oaiapp_refresh_fence"]
        assertNull(stored?.accessToken)
        assertNull(stored?.refreshToken)
    }

    @Test
    fun testRevocationDiscoveryIssuerMismatchFailsClosed() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_discovery_mismatch",
            subject = "sub_mismatch",
            email = "mismatch@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "at_mismatch",
            refreshToken = "rt_mismatch",
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)
        repository.switchAccount("oaiapp_discovery_mismatch")

        // Discovery returns wrong issuer
        transport.discoveryResponseJson = JSONObject().apply {
            put("issuer", "https://untrusted-issuer.com")
            put("revocation_endpoint", "https://auth.openai.com/api/accounts/oauth/revoke")
        }.toString()

        repository.signOut()

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.UNAUTHENTICATED, state.status)
        // Must be unconfirmed (false) because discovery issuer was untrusted
        assertEquals(false, state.remoteRevocationConfirmed)
    }

    @Test
    fun testRevocationDiscoveryUntrustedEndpointFailsClosed() = runBlocking {
        val creds = ChatGptStoredCredentials(
            clientId = "oaiapp_discovery_untrusted_ep",
            subject = "sub_ep",
            email = "ep@example.com",
            extAgentHostId = storage.getExtAgentHostId(),
            accessToken = "at_ep",
            refreshToken = "rt_ep",
            scopes = setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        )
        storage.saveCredentials(creds, makeActive = true)
        repository.switchAccount("oaiapp_discovery_untrusted_ep")

        // Discovery returns untrusted host
        transport.discoveryResponseJson = JSONObject().apply {
            put("issuer", ChatGptAuthConstants.ISSUER)
            put("revocation_endpoint", "https://evil-host.com/api/accounts/oauth/revoke")
        }.toString()

        repository.signOut()

        val state = repository.sessionState.value
        assertEquals(ChatGptAuthStatus.UNAUTHENTICATED, state.status)
        assertEquals(false, state.remoteRevocationConfirmed)
    }

    @Test
    fun testListenerStartStopRaceFencing() {
        val latch = CountDownLatch(1)

        // Start attempt 1
        repository.startLoginInternal(null)
        val attempt1Gen = repository.sessionState.value.sessionGeneration

        // Start attempt 2 immediately (supersedes attempt 1)
        repository.startLoginInternal(null)
        val attempt2Gen = repository.sessionState.value.sessionGeneration
        assertTrue(attempt2Gen > attempt1Gen)

        // Attempt 1 callback or cancel arrives late
        loopbackServer.stop(attempt1Gen)

        // Attempt 2 listener must still be alive!
        assertFalse(loopbackServer.isStopped)
        assertEquals(attempt2Gen, loopbackServer.activeAttemptId)
    }

    @Test
    fun testCancelLoginClosesRealServerSocket() {
        val realServer = DefaultChatGptAuthLoopbackServer()
        val repo = DefaultChatGptAuthRepository(
            transport = transport,
            loopbackServer = realServer,
            storage = storage,
            jwtVerifier = jwtVerifier,
            browserLauncher = browserLauncher,
            clock = clock,
        )

        repo.startLoginInternal(null)
        val port = browserLauncher.lastOpenedUrl?.let { rawUrl ->
            val decoded = URLDecoder.decode(rawUrl, "UTF-8")
            val match = Regex("http://127\\.0\\.0\\.1:(\\d+)").find(decoded)
            match?.groupValues?.get(1)?.toIntOrNull()
        }
        assertNotNull("Server port should be present in authorization url", port)

        // Verify socket is open and accepting connection
        val socketBefore = Socket("127.0.0.1", port!!)
        assertTrue(socketBefore.isConnected)
        socketBefore.close()

        // Cancel login
        repo.cancelLogin()

        // Socket should now be closed / connection refused
        try {
            val socketAfter = Socket("127.0.0.1", port)
            socketAfter.close()
            fail("Expected connection to fail after cancelLogin()")
        } catch (_: Throwable) {
            // Success: socket is closed and refused
        }
    }

    @Test
    fun testSignOutClosesRealServerSocket() = runBlocking {
        val realServer = DefaultChatGptAuthLoopbackServer()
        val repo = DefaultChatGptAuthRepository(
            transport = transport,
            loopbackServer = realServer,
            storage = storage,
            jwtVerifier = jwtVerifier,
            browserLauncher = browserLauncher,
            clock = clock,
        )

        repo.startLoginInternal(null)
        val port = browserLauncher.lastOpenedUrl?.let { rawUrl ->
            val decoded = URLDecoder.decode(rawUrl, "UTF-8")
            val match = Regex("http://127\\.0\\.0\\.1:(\\d+)").find(decoded)
            match?.groupValues?.get(1)?.toIntOrNull()
        }
        assertNotNull("Server port should be present in authorization url", port)

        // Verify socket is open
        val socketBefore = Socket("127.0.0.1", port!!)
        assertTrue(socketBefore.isConnected)
        socketBefore.close()

        // Sign out
        repo.signOut()

        // Socket should now be closed / connection refused
        try {
            val socketAfter = Socket("127.0.0.1", port)
            socketAfter.close()
            fail("Expected connection to fail after signOut()")
        } catch (_: Throwable) {
            // Success: socket is closed and refused
        }
    }

    // --- Fake Test Seams ---

    private class FakeAuthHttpTransport : ChatGptAuthHttpTransport {
        var tokenResponseStatusCode = 200
        var tokenResponseJson = "{}"
        var revocationResponseStatusCode = 200
        var discoveryResponseJson = JSONObject().apply {
            put("issuer", ChatGptAuthConstants.ISSUER)
            put("revocation_endpoint", "https://auth.openai.com/api/accounts/oauth/revoke")
        }.toString()
        val refreshCallCount = AtomicInteger(0)
        var onPostTokenHook: (() -> Unit)? = null

        override suspend fun postForm(
            url: String,
            formParams: Map<String, String>,
            headers: Map<String, String>,
        ): AuthHttpResponse {
            return if (url.contains("oauth/token")) {
                if (formParams["grant_type"] == "refresh_token") {
                    refreshCallCount.incrementAndGet()
                }
                onPostTokenHook?.invoke()
                AuthHttpResponse(tokenResponseStatusCode, tokenResponseJson)
            } else if (url.contains("oauth/revoke")) {
                AuthHttpResponse(revocationResponseStatusCode, "")
            } else {
                AuthHttpResponse(404, "Not Found")
            }
        }

        override suspend fun getJson(
            url: String,
            headers: Map<String, String>,
        ): AuthHttpResponse {
            return if (url.contains("openid-configuration")) {
                AuthHttpResponse(200, discoveryResponseJson)
            } else {
                AuthHttpResponse(200, "{}")
            }
        }
    }

    private class FakeAuthLoopbackServer : ChatGptAuthLoopbackServer {
        private var callback: ((LoopbackCallbackResult) -> Unit)? = null
        var isStopped = false
        var activeAttemptId: Long = 0L

        override fun start(
            attemptId: Long,
            expectedState: String,
            savedClientId: String?,
            onResult: (LoopbackCallbackResult) -> Unit,
        ): Int {
            isStopped = false
            activeAttemptId = attemptId
            callback = onResult
            return 1455
        }

        override fun stop(attemptId: Long?) {
            if (attemptId != null && attemptId != 0L && activeAttemptId != attemptId) {
                return
            }
            isStopped = true
            callback = null
        }

        fun deliverResult(result: LoopbackCallbackResult) {
            callback?.invoke(result)
        }
    }

    private class FakeJwtVerifier : ChatGptJwtVerifier {
        var verifiedClaims: IdTokenClaims = IdTokenClaims(
            sub = "default_sub",
            iss = ChatGptAuthConstants.ISSUER,
            aud = listOf("default_client_id"),
            azp = null,
            exp = 2000000000L,
            iat = 1700000000L,
            nonce = null,
            email = null,
            name = null,
        )

        override suspend fun verifyIdToken(
            idToken: String,
            expectedClientId: String,
            expectedNonce: String?,
            currentEpochSec: Long,
        ): IdTokenClaims = verifiedClaims
    }

    private class FakeBrowserLauncher : ChatGptBrowserLauncher {
        var lastOpenedUrl: String? = null

        override fun openBrowser(context: Context?, url: String): Boolean {
            lastOpenedUrl = url
            return true
        }
    }

    private class TestClock(private var epochSec: Long) : ChatGptClock {
        override fun currentTimeMillis(): Long = epochSec * 1000L
        override fun currentEpochSec(): Long = epochSec
    }
}
