package com.ariateam.aria.cloud.auth

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

interface ChatGptAuthRepository {
    val sessionState: StateFlow<ChatGptAuthState>

    suspend fun validAccessToken(): String?

    fun startLogin(
        context: Context,
        targetClientId: String? = null,
        forceReconsent: Boolean = false,
    )

    fun cancelLogin()

    suspend fun signOut()

    suspend fun switchAccount(clientId: String)

    fun getSavedAccounts(): List<ChatGptAccountSummary>
}

class DefaultChatGptAuthRepository internal constructor(
    private val transport: ChatGptAuthHttpTransport,
    private val loopbackServer: ChatGptAuthLoopbackServer,
    private val storage: ChatGptCredentialStorage,
    private val jwtVerifier: ChatGptJwtVerifier,
    private val browserLauncher: ChatGptBrowserLauncher,
    private val clock: ChatGptClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loginTimeoutMs: Long = 300_000L, // 5 minutes bounded timeout
) : ChatGptAuthRepository {

    constructor(context: Context) : this(
        transport = DefaultChatGptAuthHttpTransport(),
        loopbackServer = DefaultChatGptAuthLoopbackServer(),
        storage = AndroidKeyStoreChatGptCredentialStorage(context.applicationContext),
        jwtVerifier = DefaultChatGptJwtVerifier(DefaultChatGptAuthHttpTransport()),
        browserLauncher = DefaultChatGptBrowserLauncher(),
        clock = SystemChatGptClock(),
        dispatcher = Dispatchers.IO,
    )

    private val scope = CoroutineScope(dispatcher)
    private val stateLock = Any()
    private var activeSessionGeneration: Long = 1L
    private val refreshMutex = Mutex()
    private var loginTimeoutJob: Job? = null
    private val processRevokedClients = ConcurrentHashMap.newKeySet<String>()

    private val _sessionState = MutableStateFlow(resolveInitialState())
    override val sessionState: StateFlow<ChatGptAuthState> = _sessionState.asStateFlow()

    private fun resolveInitialState(): ChatGptAuthState {
        val activeClientId = storage.getActiveClientId() ?: return ChatGptAuthState(
            sessionGeneration = 1L,
            status = ChatGptAuthStatus.UNAUTHENTICATED,
        )
        if (storage.isRevoked(activeClientId)) {
            return ChatGptAuthState(
                sessionGeneration = 1L,
                status = ChatGptAuthStatus.UNAUTHENTICATED,
            )
        }

        val all = storage.loadAllCredentials()
        val activeCreds = all[activeClientId] ?: return ChatGptAuthState(
            sessionGeneration = 1L,
            status = ChatGptAuthStatus.UNAUTHENTICATED,
        )

        if (!activeCreds.hasActiveTokens) {
            return ChatGptAuthState(
                sessionGeneration = 1L,
                status = ChatGptAuthStatus.UNAUTHENTICATED,
            )
        }

        val account = toAccountInfo(activeCreds)
        val status = if (account.hasPlanUsagePermission) {
            ChatGptAuthStatus.AUTHENTICATED
        } else {
            ChatGptAuthStatus.PLAN_USE_NOT_ENABLED
        }

        return ChatGptAuthState(
            sessionGeneration = 1L,
            status = status,
            account = account,
        )
    }

    override fun startLogin(context: Context, targetClientId: String?, forceReconsent: Boolean) {
        startLoginInternal(context, targetClientId, forceReconsent)
    }

    internal fun startLoginInternal(context: Context?, targetClientId: String? = null, forceReconsent: Boolean = false) {
        val currentGen: Long
        var port = 0
        val state = ChatGptCrypto.randomBase64Url(32)
        val nonce = ChatGptCrypto.randomBase64Url(32)
        val pkce = ChatGptCrypto.generatePkcePair()

        synchronized(stateLock) {
            currentGen = ++activeSessionGeneration
            loginTimeoutJob?.cancel()
            loginTimeoutJob = null
            _sessionState.value = ChatGptAuthState(
                sessionGeneration = currentGen,
                status = ChatGptAuthStatus.AUTHORIZING,
                account = _sessionState.value.account,
            )

            try {
                port = loopbackServer.start(
                    attemptId = currentGen,
                    expectedState = state,
                    savedClientId = targetClientId,
                ) { callbackResult ->
                    scope.launch {
                        handleCallback(
                            result = callbackResult,
                            expectedGen = currentGen,
                            verifier = pkce.codeVerifier,
                            nonce = nonce,
                            targetClientId = targetClientId,
                            port = port,
                        )
                    }
                }
            } catch (_: Throwable) {
                _sessionState.value = ChatGptAuthState(
                    sessionGeneration = currentGen,
                    status = ChatGptAuthStatus.UNAUTHENTICATED,
                    lastError = "로컬 인증 리스너를 시작할 수 없습니다.",
                )
                return
            }
        }

        val hostId = try {
            storage.getExtAgentHostId()
        } catch (_: Throwable) {
            synchronized(stateLock) {
                if (activeSessionGeneration == currentGen) {
                    loopbackServer.stop(currentGen)
                    _sessionState.value = ChatGptAuthState(
                        sessionGeneration = currentGen,
                        status = ChatGptAuthStatus.UNAUTHENTICATED,
                        lastError = "저장소 오류로 로그인을 시작할 수 없습니다.",
                    )
                }
            }
            return
        }
        val allAccounts = storage.loadAllCredentials()
        val targetCreds = targetClientId?.let { allAccounts[it] }

        val isDynamicRegistration = targetClientId == null
        val clientIdToSend = targetClientId ?: ChatGptAuthConstants.DYNAMIC_CLIENT_ID

        val redirectUri = "http://${ChatGptAuthConstants.LOOPBACK_HOST}:$port${ChatGptAuthConstants.CALLBACK_PATH}"
        val authUrl = buildAuthorizationUrl(
            clientId = clientIdToSend,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeChallenge = pkce.codeChallenge,
            hostId = hostId,
            isDynamicRegistration = isDynamicRegistration,
            loginHint = targetCreds?.email,
            idTokenHint = targetCreds?.idToken,
            forceReconsent = forceReconsent,
        )

        val opened = browserLauncher.openBrowser(context, authUrl)
        if (!opened) {
            synchronized(stateLock) {
                if (activeSessionGeneration == currentGen) {
                    loopbackServer.stop(currentGen)
                    _sessionState.value = ChatGptAuthState(
                        sessionGeneration = currentGen,
                        status = ChatGptAuthStatus.UNAUTHENTICATED,
                        lastError = "브라우저를 열 수 없습니다.",
                    )
                }
            }
            return
        }

        // Bounded login timeout
        val timeoutJob = scope.launch {
            delay(loginTimeoutMs)
            synchronized(stateLock) {
                if (activeSessionGeneration == currentGen && _sessionState.value.status == ChatGptAuthStatus.AUTHORIZING) {
                    loopbackServer.stop(currentGen)
                    _sessionState.value = ChatGptAuthState(
                        sessionGeneration = currentGen,
                        status = ChatGptAuthStatus.UNAUTHENTICATED,
                        account = null,
                        lastError = "로그인 시간이 초과되었습니다.",
                    )
                }
            }
        }

        synchronized(stateLock) {
            if (activeSessionGeneration == currentGen) {
                loginTimeoutJob = timeoutJob
            } else {
                timeoutJob.cancel()
            }
        }
    }

    private suspend fun handleCallback(
        result: LoopbackCallbackResult,
        expectedGen: Long,
        verifier: String,
        nonce: String,
        targetClientId: String?,
        port: Int,
    ) = withContext(dispatcher) {
        synchronized(stateLock) {
            if (activeSessionGeneration != expectedGen) {
                // Mismatched generation must NOT stop a newly started listener
                return@withContext
            }
        }

        when (result) {
            is LoopbackCallbackResult.Error -> {
                synchronized(stateLock) {
                    if (activeSessionGeneration == expectedGen) {
                        loopbackServer.stop(expectedGen)
                        loginTimeoutJob?.cancel()
                        loginTimeoutJob = null
                        val sanitizedMsg = if (result.errorCode == "access_denied") {
                            "로그인이 취소되었습니다."
                        } else {
                            "로그인에 실패했습니다. 다시 시도해 주세요."
                        }
                        _sessionState.value = ChatGptAuthState(
                            sessionGeneration = expectedGen,
                            status = ChatGptAuthStatus.UNAUTHENTICATED,
                            account = null,
                            lastError = sanitizedMsg,
                        )
                    }
                }
            }
            is LoopbackCallbackResult.Success -> {
                synchronized(stateLock) {
                    if (activeSessionGeneration == expectedGen) {
                        loopbackServer.stop(expectedGen)
                        loginTimeoutJob?.cancel()
                        loginTimeoutJob = null
                    }
                }

                try {
                    val redirectUri = "http://${ChatGptAuthConstants.LOOPBACK_HOST}:$port${ChatGptAuthConstants.CALLBACK_PATH}"
                    exchangeCodeAndEstablishSession(
                        code = result.code,
                        clientId = result.clientId,
                        verifier = verifier,
                        redirectUri = redirectUri,
                        nonce = nonce,
                        targetClientId = targetClientId,
                        expectedGen = expectedGen,
                    )
                } catch (t: Throwable) {
                    synchronized(stateLock) {
                        if (activeSessionGeneration == expectedGen) {
                            val sanitizedMsg = when (t) {
                                is ChatGptAuthException.TokenValidationException -> "ID 토큰 검증에 실패했습니다."
                                is ChatGptAuthException.PlanUsageNotEnabledException -> "ChatGPT 요금제 사용 권한이 부여되지 않았습니다."
                                is ChatGptAuthException.NetworkAuthException -> "네트워크 연결을 확인해 주세요."
                                else -> "로그인에 실패했습니다. 다시 시도해 주세요."
                            }
                            _sessionState.value = ChatGptAuthState(
                                sessionGeneration = expectedGen,
                                status = ChatGptAuthStatus.UNAUTHENTICATED,
                                account = null,
                                lastError = sanitizedMsg,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun exchangeCodeAndEstablishSession(
        code: String,
        clientId: String,
        verifier: String,
        redirectUri: String,
        nonce: String,
        targetClientId: String?,
        expectedGen: Long,
    ) {
        val formParams = mapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri,
            "resource" to ChatGptAuthConstants.RESOURCE,
        )

        val response = transport.postForm(ChatGptAuthConstants.DEFAULT_TOKEN_ENDPOINT, formParams)
        if (!response.isSuccessful) {
            val errCode = parseErrorCode(response.body)
            throw ChatGptAuthException.AuthenticationFailedException(
                "로그인에 실패했습니다. 다시 시도해 주세요.",
                errCode,
            )
        }

        val tokenResp = parseTokenResponse(response.body)
        val idToken = tokenResp.idToken
            ?: throw ChatGptAuthException.TokenValidationException("ID 토큰이 제공되지 않았습니다.")

        val claims = jwtVerifier.verifyIdToken(
            idToken = idToken,
            expectedClientId = clientId,
            expectedNonce = nonce,
            currentEpochSec = clock.currentEpochSec(),
        )

        if (targetClientId != null) {
            val existing = storage.loadAllCredentials()[targetClientId]
            if (existing != null && existing.subject != claims.sub) {
                throw ChatGptAuthException.AuthenticationFailedException("계정 불일치: 기존 계정 식별자와 일치하지 않습니다.")
            }
        }

        val rawScopes = tokenResp.scope.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
        val hostId = storage.getExtAgentHostId()

        val stored = ChatGptStoredCredentials(
            clientId = clientId,
            subject = claims.sub,
            email = claims.email,
            name = claims.name,
            extAgentHostId = hostId,
            accessToken = tokenResp.accessToken,
            refreshToken = tokenResp.refreshToken,
            idToken = idToken,
            tokenType = tokenResp.tokenType,
            expiresAtEpochSec = clock.currentEpochSec() + tokenResp.expiresIn,
            earliestRefreshAtEpochSec = tokenResp.earliestRefreshAt,
            scopes = rawScopes,
            savedAtIso = clock.currentEpochSec().toString(),
        )

        synchronized(stateLock) {
            if (activeSessionGeneration != expectedGen) {
                // Operation was fenced (logout, cancel, or newer login occurred)
                return
            }

            processRevokedClients.remove(clientId)
            val savedOk = storage.saveCredentials(stored, makeActive = true)
            if (!savedOk) {
                _sessionState.value = ChatGptAuthState(
                    sessionGeneration = expectedGen,
                    status = ChatGptAuthStatus.UNAUTHENTICATED,
                    account = null,
                    lastError = "인증 정보를 안전하게 저장하지 못했습니다.",
                )
                return
            }

            val accountInfo = toAccountInfo(stored)
            val status = if (accountInfo.hasPlanUsagePermission) {
                ChatGptAuthStatus.AUTHENTICATED
            } else {
                ChatGptAuthStatus.PLAN_USE_NOT_ENABLED
            }
            _sessionState.value = ChatGptAuthState(
                sessionGeneration = expectedGen,
                status = status,
                account = accountInfo,
            )
        }
    }

    override fun cancelLogin() {
        val newGen: Long
        synchronized(stateLock) {
            val oldGen = activeSessionGeneration
            newGen = ++activeSessionGeneration
            loginTimeoutJob?.cancel()
            loginTimeoutJob = null
            loopbackServer.stop(oldGen)
            loopbackServer.stop()

            val activeId = storage.getActiveClientId()
            val activeCreds = activeId?.let { storage.loadAllCredentials()[it] }
            val currentAccount = if (activeCreds != null && activeCreds.hasActiveTokens && !isRevokedInternal(activeId)) toAccountInfo(activeCreds) else null
            val status = if (currentAccount != null) {
                if (currentAccount.hasPlanUsagePermission) ChatGptAuthStatus.AUTHENTICATED else ChatGptAuthStatus.PLAN_USE_NOT_ENABLED
            } else {
                ChatGptAuthStatus.UNAUTHENTICATED
            }
            _sessionState.value = ChatGptAuthState(
                sessionGeneration = newGen,
                status = status,
                account = currentAccount,
                lastError = "로그인이 취소되었습니다.",
            )
        }
    }

    override suspend fun signOut() = withContext(dispatcher) {
        val signOutGen: Long
        var refreshTokenToRevoke: String? = null
        var clientIdToRevoke: String? = null
        var storageError: String? = null

        synchronized(stateLock) {
            val oldGen = activeSessionGeneration
            signOutGen = ++activeSessionGeneration
            loginTimeoutJob?.cancel()
            loginTimeoutJob = null
            loopbackServer.stop(oldGen)
            loopbackServer.stop()

            val activeId = storage.getActiveClientId()
            if (activeId != null) {
                processRevokedClients.add(activeId)
                val all = storage.loadAllCredentials()
                val creds = all[activeId]
                refreshTokenToRevoke = creds?.refreshToken
                clientIdToRevoke = activeId

                // Immediately clear active tokens from storage under lock
                val clearedOk = storage.clearTokensForClient(activeId)
                if (!clearedOk) {
                    val tombstoneOk = storage.markRevoked(activeId)
                    if (!tombstoneOk) {
                        storage.deleteAccount(activeId)
                    }
                    storageError = "인증 저장소를 완전히 비우지 못해 계정이 안전하게 격리되었습니다."
                }
            }

            // Invalidate UI/session immediately
            _sessionState.value = ChatGptAuthState(
                sessionGeneration = signOutGen,
                status = ChatGptAuthStatus.UNAUTHENTICATED,
                account = null,
                remoteRevocationConfirmed = null,
                lastError = storageError,
            )
        }

        if (!refreshTokenToRevoke.isNullOrBlank() && !clientIdToRevoke.isNullOrBlank()) {
            val confirmed = attemptRemoteRevocation(refreshTokenToRevoke!!, clientIdToRevoke!!)
            synchronized(stateLock) {
                if (activeSessionGeneration == signOutGen && _sessionState.value.status == ChatGptAuthStatus.UNAUTHENTICATED) {
                    _sessionState.value = _sessionState.value.copy(
                        remoteRevocationConfirmed = confirmed,
                    )
                }
            }
        }
    }

    private suspend fun attemptRemoteRevocation(refreshToken: String, clientId: String): Boolean {
        // Validate trusted discovery endpoint per official specifications
        val revocationEndpoint = try {
            val discoveryResp = transport.getJson(ChatGptAuthConstants.DISCOVERY_URL)
            if (!discoveryResp.isSuccessful) return false
            val discoveryObj = JSONObject(discoveryResp.body)
            val iss = discoveryObj.optString("issuer", "")
            if (iss != ChatGptAuthConstants.ISSUER) return false
            val endpoint = discoveryObj.optString("revocation_endpoint", "")
            if (endpoint.isBlank()) return false
            validateDiscoveryEndpointUrl(endpoint)
            endpoint
        } catch (_: Throwable) {
            return false
        }

        val formParams = mapOf(
            "token" to refreshToken,
            "token_type_hint" to "refresh_token",
            "client_id" to clientId,
        )

        for (attempt in 0..1) {
            try {
                val resp = transport.postForm(revocationEndpoint, formParams)
                if (resp.statusCode == 200) {
                    return true
                }
                if (resp.statusCode in 400..499) {
                    return false
                }
            } catch (_: Throwable) {
                if (attempt == 0) {
                    delay(300)
                }
            }
        }
        return false
    }

    private fun validateDiscoveryEndpointUrl(urlString: String) {
        val parsed = URL(urlString)
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("HTTPS required for revocation endpoint: $urlString")
        }
        val host = parsed.host.lowercase()
        if (host != ChatGptAuthConstants.AUTHORITY_HOST) {
            throw SecurityException("Untrusted revocation host '$host'. Pinned authority is '${ChatGptAuthConstants.AUTHORITY_HOST}'")
        }
        if (parsed.port != -1 && parsed.port != 443) {
            throw SecurityException("Non-standard HTTPS port rejected: ${parsed.port}")
        }
        if (parsed.userInfo != null) {
            throw SecurityException("UserInfo component forbidden in revocation URL")
        }
    }

    override suspend fun switchAccount(clientId: String) = withContext(dispatcher) {
        val newGen: Long
        synchronized(stateLock) {
            val oldGen = activeSessionGeneration
            newGen = ++activeSessionGeneration
            loginTimeoutJob?.cancel()
            loginTimeoutJob = null
            loopbackServer.stop(oldGen)
            loopbackServer.stop()

            if (isRevokedInternal(clientId)) {
                _sessionState.value = ChatGptAuthState(
                    sessionGeneration = newGen,
                    status = ChatGptAuthStatus.UNAUTHENTICATED,
                    account = null,
                )
                return@withContext
            }

            storage.setActiveClientId(clientId)
            val all = storage.loadAllCredentials()
            val creds = all[clientId]
            if (creds != null && creds.hasActiveTokens) {
                val account = toAccountInfo(creds)
                val status = if (account.hasPlanUsagePermission) {
                    ChatGptAuthStatus.AUTHENTICATED
                } else {
                    ChatGptAuthStatus.PLAN_USE_NOT_ENABLED
                }
                _sessionState.value = ChatGptAuthState(
                    sessionGeneration = newGen,
                    status = status,
                    account = account,
                )
            } else {
                _sessionState.value = ChatGptAuthState(
                    sessionGeneration = newGen,
                    status = ChatGptAuthStatus.UNAUTHENTICATED,
                    account = null,
                )
            }
        }
    }

    override fun getSavedAccounts(): List<ChatGptAccountSummary> {
        synchronized(stateLock) {
            val activeId = storage.getActiveClientId()
            val all = storage.loadAllCredentials()
            return all.values.map { creds ->
                val isRevoked = isRevokedInternal(creds.clientId)
                ChatGptAccountSummary(
                    subject = creds.subject,
                    email = creds.email,
                    name = creds.name,
                    clientId = creds.clientId,
                    isActive = creds.clientId == activeId && !isRevoked,
                    hasPlanUsagePermission = creds.hasPlanUsagePermission && !isRevoked,
                )
            }
        }
    }

    override suspend fun validAccessToken(): String? = withContext(dispatcher) {
        val creds: ChatGptStoredCredentials
        val startGen: Long

        synchronized(stateLock) {
            // Guard: if UI/session is unauthenticated or account is null, immediately return null
            if (_sessionState.value.status == ChatGptAuthStatus.UNAUTHENTICATED || _sessionState.value.account == null) {
                return@withContext null
            }

            val activeId = storage.getActiveClientId() ?: return@withContext null
            if (isRevokedInternal(activeId)) {
                return@withContext null
            }

            val all = storage.loadAllCredentials()
            val active = all[activeId] ?: return@withContext null
            if (!active.hasActiveTokens) return@withContext null
            if (!active.hasPlanUsagePermission) {
                throw ChatGptAuthException.PlanUsageNotEnabledException("ChatGPT 요금제 사용 권한이 부여되지 않았습니다.")
            }
            creds = active
            startGen = activeSessionGeneration

            val nowSec = clock.currentEpochSec()
            val expiresAt = creds.expiresAtEpochSec
            val earliestRefresh = creds.earliestRefreshAtEpochSec

            val isAccessExpired = nowSec >= expiresAt
            val withinExpiryWindow = nowSec >= (expiresAt - ChatGptAuthConstants.REFRESH_WINDOW_SECONDS)
            val pastEarliestRefresh = (earliestRefresh == null || nowSec >= earliestRefresh)

            if (isAccessExpired) {
                if (!pastEarliestRefresh) {
                    // Access token is expired, but earliest_refresh_at prohibits refresh!
                    // Prohibit returning expired token; fail closed!
                    throw ChatGptAuthException.UnusableRefreshTokenException(
                        "액세스 토큰이 만료되었으나 최소 갱신 대기 시간 전입니다. 다시 로그인해 주세요."
                    )
                }
                // Proceeed to refresh
            } else if (!withinExpiryWindow || !pastEarliestRefresh) {
                // Token is still valid (not expired) and not within refresh window
                return@withContext creds.accessToken
            }
        }

        // Serialized network refresh under refreshMutex
        return@withContext refreshMutex.withLock {
            val currentCreds: ChatGptStoredCredentials
            val currentGen: Long
            synchronized(stateLock) {
                val activeId = storage.getActiveClientId() ?: return@withLock null
                if (activeId != creds.clientId || activeSessionGeneration != startGen || isRevokedInternal(activeId)) {
                    return@withLock null
                }
                val all = storage.loadAllCredentials()
                val current = all[activeId] ?: return@withLock null
                if (!current.hasActiveTokens) return@withLock null

                val nowSec = clock.currentEpochSec()
                val isAccessExpired = nowSec >= current.expiresAtEpochSec
                val withinExpiryWindow = nowSec >= (current.expiresAtEpochSec - ChatGptAuthConstants.REFRESH_WINDOW_SECONDS)
                val pastEarliestRefresh = (current.earliestRefreshAtEpochSec == null || nowSec >= current.earliestRefreshAtEpochSec)

                if (isAccessExpired) {
                    if (!pastEarliestRefresh) {
                        throw ChatGptAuthException.UnusableRefreshTokenException(
                            "액세스 토큰이 만료되었으나 최소 갱신 대기 시간 전입니다. 다시 로그인해 주세요."
                        )
                    }
                } else if (!withinExpiryWindow || !pastEarliestRefresh) {
                    return@withLock current.accessToken
                }

                currentCreds = current
                currentGen = activeSessionGeneration
            }

            val rToken = currentCreds.refreshToken
                ?: throw ChatGptAuthException.UnusableRefreshTokenException("인증이 만료되었습니다. 다시 로그인해 주세요.")

            val form = mapOf(
                "grant_type" to "refresh_token",
                "client_id" to currentCreds.clientId,
                "refresh_token" to rToken,
                "resource" to ChatGptAuthConstants.RESOURCE,
            )

            val response = try {
                transport.postForm(ChatGptAuthConstants.DEFAULT_TOKEN_ENDPOINT, form)
            } catch (e: Exception) {
                throw ChatGptAuthException.NetworkAuthException("네트워크 연결을 확인해 주세요.", e)
            }

            synchronized(stateLock) {
                if (activeSessionGeneration != currentGen || storage.getActiveClientId() != currentCreds.clientId || isRevokedInternal(currentCreds.clientId)) {
                    // Session was invalidated, logged out, or switched while refresh was executing
                    return@withLock null
                }

                if (response.isSuccessful) {
                    val tokenResp = parseTokenResponse(response.body)
                    val updatedScopes = if (tokenResp.scope.isNotBlank()) {
                        tokenResp.scope.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
                    } else {
                        currentCreds.scopes
                    }

                    val updatedCreds = currentCreds.copy(
                        accessToken = tokenResp.accessToken,
                        refreshToken = tokenResp.refreshToken ?: currentCreds.refreshToken,
                        expiresAtEpochSec = clock.currentEpochSec() + tokenResp.expiresIn,
                        earliestRefreshAtEpochSec = tokenResp.earliestRefreshAt,
                        scopes = updatedScopes,
                        savedAtIso = clock.currentEpochSec().toString(),
                    )

                    val savedOk = storage.saveCredentials(updatedCreds, makeActive = true)
                    if (!savedOk) {
                        throw ChatGptAuthException.AuthenticationFailedException("인증 정보를 안전하게 저장하지 못했습니다.")
                    }

                    val acc = toAccountInfo(updatedCreds)
                    if (!updatedCreds.hasPlanUsagePermission) {
                        val newGen = ++activeSessionGeneration
                        _sessionState.value = ChatGptAuthState(
                            sessionGeneration = newGen,
                            status = ChatGptAuthStatus.PLAN_USE_NOT_ENABLED,
                            account = acc,
                            lastError = "ChatGPT 요금제 사용 권한이 부여되지 않았습니다.",
                        )
                        return@withLock null
                    }

                    _sessionState.value = _sessionState.value.copy(
                        account = acc,
                        status = ChatGptAuthStatus.AUTHENTICATED,
                    )

                    return@withLock updatedCreds.accessToken
                } else {
                    val errCode = parseErrorCode(response.body)
                    if (isDocumentedPermanentRefreshError(errCode)) {
                        val newGen = ++activeSessionGeneration
                        storage.clearTokensForClient(currentCreds.clientId)
                        val preservedAccount = toAccountInfo(currentCreds.copy(accessToken = null, refreshToken = null, idToken = null))
                        _sessionState.value = ChatGptAuthState(
                            sessionGeneration = newGen,
                            status = ChatGptAuthStatus.REFRESH_FAILED_RECONNECT,
                            account = preservedAccount,
                            lastError = "인증이 만료되었습니다. 다시 로그인해 주세요.",
                        )
                        throw ChatGptAuthException.UnusableRefreshTokenException(
                            "인증이 만료되었습니다. 다시 로그인해 주세요.",
                            errCode,
                        )
                    } else {
                        throw ChatGptAuthException.NetworkAuthException(
                            "네트워크 연결을 확인해 주세요. (HTTP ${response.statusCode})",
                        )
                    }
                }
            }
        }
    }

    private fun isRevokedInternal(clientId: String): Boolean {
        return processRevokedClients.contains(clientId) || storage.isRevoked(clientId)
    }

    private fun isDocumentedPermanentRefreshError(errorCode: String?): Boolean {
        val permanentCodes = setOf(
            "invalid_grant",
            "invalid_refresh_token",
            "token_expired",
            "refresh_token_expired",
            "refresh_token_invalidated",
            "refresh_token_reused",
            "invalid_client",
        )
        return errorCode != null && permanentCodes.contains(errorCode.lowercase())
    }

    private fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        codeChallenge: String,
        hostId: String,
        isDynamicRegistration: Boolean,
        loginHint: String?,
        idTokenHint: String?,
        forceReconsent: Boolean,
    ): String {
        val queryBuilder = StringBuilder(ChatGptAuthConstants.DEFAULT_AUTHORIZE_ENDPOINT)
        queryBuilder.append("?")
        queryBuilder.append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
        queryBuilder.append("&response_type=code")
        queryBuilder.append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
        queryBuilder.append("&scope=").append(URLEncoder.encode(ChatGptAuthConstants.DEFAULT_REQUESTED_SCOPES, "UTF-8"))
        queryBuilder.append("&resource=").append(URLEncoder.encode(ChatGptAuthConstants.RESOURCE, "UTF-8"))
        queryBuilder.append("&state=").append(URLEncoder.encode(state, "UTF-8"))
        queryBuilder.append("&nonce=").append(URLEncoder.encode(nonce, "UTF-8"))
        queryBuilder.append("&code_challenge_method=S256")
        queryBuilder.append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
        queryBuilder.append("&ext_agent_host_id=").append(URLEncoder.encode(hostId, "UTF-8"))

        if (isDynamicRegistration) {
            queryBuilder.append("&agent_name_hint=").append(URLEncoder.encode(ChatGptAuthConstants.AGENT_NAME_HINT, "UTF-8"))
        }

        if (!loginHint.isNullOrBlank()) {
            queryBuilder.append("&login_hint=").append(URLEncoder.encode(loginHint, "UTF-8"))
        }

        if (!idTokenHint.isNullOrBlank()) {
            queryBuilder.append("&id_token_hint=").append(URLEncoder.encode(idTokenHint, "UTF-8"))
        }

        if (forceReconsent) {
            queryBuilder.append("&prompt=consent")
        }

        return queryBuilder.toString()
    }

    private fun parseTokenResponse(body: String): ChatGptTokenResponse {
        val obj = JSONObject(body)
        val accessToken = obj.optString("access_token", "")
        if (accessToken.isBlank()) {
            throw ChatGptAuthException.TokenValidationException("액세스 토큰이 누락되었습니다.")
        }

        val tokenType = obj.optString("token_type", "")
        if (!tokenType.equals("Bearer", ignoreCase = true)) {
            throw ChatGptAuthException.TokenValidationException("유효하지 않은 토큰 타입입니다: $tokenType")
        }

        val expiresIn = obj.optLong("expires_in", 0L)
        if (expiresIn <= 0L) {
            throw ChatGptAuthException.TokenValidationException("유효하지 않은 토큰 만료 시간입니다: $expiresIn")
        }

        val refreshToken = if (obj.has("refresh_token") && obj.getString("refresh_token").isNotBlank()) {
            obj.getString("refresh_token")
        } else {
            null
        }

        val idToken = if (obj.has("id_token") && obj.getString("id_token").isNotBlank()) {
            obj.getString("id_token")
        } else {
            null
        }

        return ChatGptTokenResponse(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresIn = expiresIn,
            refreshToken = refreshToken,
            idToken = idToken,
            scope = obj.optString("scope", ""),
            earliestRefreshAt = if (obj.has("earliest_refresh_at")) obj.optLong("earliest_refresh_at") else null,
        )
    }

    private fun parseErrorCode(body: String): String? {
        return try {
            val obj = JSONObject(body)
            if (obj.has("error")) {
                obj.optString("error")
            } else if (obj.has("error_description")) {
                obj.optString("error_description")
            } else if (obj.has("detail")) {
                obj.optString("detail")
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun toAccountInfo(creds: ChatGptStoredCredentials): ChatGptAccountInfo {
        return ChatGptAccountInfo(
            subject = creds.subject,
            email = creds.email,
            name = creds.name,
            clientId = creds.clientId,
            scopes = creds.scopes,
            hasPlanUsagePermission = creds.hasPlanUsagePermission,
        )
    }

    companion object {
        @Volatile
        private var instance: DefaultChatGptAuthRepository? = null

        fun getInstance(context: Context): DefaultChatGptAuthRepository {
            return instance ?: synchronized(this) {
                instance ?: DefaultChatGptAuthRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
