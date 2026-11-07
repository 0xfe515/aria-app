# Official Sign in with ChatGPT Authentication Module for Android Aria

**Date:** 2026-11-08  
**Package:** `com.ariateam.aria.cloud.auth`  
**Reference & Adaptation:**  
- Official documentation: [developers.openai.com/siwc](https://developers.openai.com/siwc)
- Reference repository: [openai/sign-in-with-chatgpt-devkit](https://github.com/openai/sign-in-with-chatgpt-devkit) (commit `0a36fefeb913055c8c7a1a29b63d82b96b2e841a`, Noncommercial License v1.0).
- Platform adaptation: Because the official devkit targets Node.js with Node `crypto` and `jose`, it cannot be directly embedded into an Android runtime. To ensure complete licensing safety and native performance without third-party dependencies, the documented SIWC protocol was adapted to standard Android/Java platform APIs: Java Cryptography Architecture (JCA `Signature`, `KeyFactory`, `SecureRandom`, `MessageDigest`), Android Keystore (AES-256-GCM), Android's standard `org.json`, and native JVM `ServerSocket` loopback binding.

---

## 1. Overview & Open-Source Eligibility

Aria is an on-device accessibility assistant running locally on Android. Under OpenAI's official Sign in with ChatGPT guidelines for open-source and locally-hosted applications:
- Users authorize their personal ChatGPT Plus or Pro plan directly without developer-managed API keys or shared service credentials.
- The authorization flow uses **dynamic client registration** with public client semantics (no client secret or partner backend required).
- Dynamic registration assigns a stable per-install `ext_agent_host_id` (using standard `urn:uuid:...` format) and application hint `Aria`.
- Upon user authorization in the system browser, OpenAI returns an issued `client_id` (e.g., `oaiapp_...`) bound to the user's selected workspace.
- Subsequent sign-ins reuse the issued `client_id` and include `id_token_hint` / `login_hint` to streamline reauthorization without repeating workspace or consent dialogs.
- ChatGPT plan usage requires explicit scopes: `resource.invoke` and `chatgpt.tokens.use.direct`. Identity-only tokens without these scopes are retained with plan usage marked as disabled.

---

## 2. File Tree & Architecture

All module components reside strictly under `app/src/main/java/com/ariateam/aria/cloud/auth/`:

```
com.ariateam.aria.cloud.auth/
├── ChatGptAuthConstants.kt          # Pinned authority hosts, endpoints, scopes, lifetimes
├── ChatGptAuthModels.kt             # Public safe state, account info, stored credentials, token responses
├── ChatGptAuthExceptions.kt         # Sanitized Korean exceptions (never leaking secrets in messages/stacktraces)
├── ChatGptCrypto.kt                 # PKCE S256, timing-safe comparisons, UUID host ID, base64url utils
├── ChatGptAuthHttpTransport.kt      # HTTPS transport with pinned authority validation (auth.openai.com) & bounded buffers
├── ChatGptJwtVerifier.kt            # Fail-closed RS256 signature verifier via trusted JWKS & claims checker
├── ChatGptAuthLoopbackServer.kt     # Strict 127.0.0.1 ephemeral loopback listener with CSP, bounded buffers & attempt fencing
├── ChatGptCredentialStorage.kt      # Android Keystore AES-GCM encrypted persistence in aria_chatgpt_auth_prefs with tombstoning
├── ChatGptBrowserLauncher.kt        # System browser launcher seam (also contains ChatGptClock / SystemChatGptClock)
└── ChatGptAuthRepository.kt         # Process-singleton repository exposing StateFlow, token provider & atomic state lock
```

### Shared Public Interface: `ChatGptAuthRepository`

Accessible by SettingsScreen and MainScreen:

```kotlin
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
```

Factory:
```kotlin
DefaultChatGptAuthRepository.getInstance(applicationContext)
```
Guarantees a process singleton so Settings, MainScreen, and inference clients share identical state, mutex, and generation counter.

---

## 3. Concurrency, Fencing & Storage Fail-Closed Safeguards

### 3.1 Attempt-Fenced Loopback Lifecycle
- `ChatGptAuthLoopbackServer.start(attemptId, ...)` and `stop(attemptId)` enforce attempt identity.
- When `startLogin` is called, a new generation `currentGen = ++activeSessionGeneration` is bound to the loopback server attempt.
- `signOut`, `cancelLogin`, callback delivery, or timeouts passing an older generation cannot stop or close a newer active listener.
- Loopback line reading is strictly bounded during stream read (max 4096 bytes per line, 16KB max headers) preventing denial-of-service or memory inflation.

### 3.2 Atomic State Lock & Fencing
- A short, synchronous state lock (`synchronized(stateLock)`) protects all state transitions and storage modifications across `startLogin`, `cancelLogin`, `signOut`, `switchAccount`, timeout handlers, callback delivery, and refresh commits.
- Network IO (code exchange, refresh POST, revocation POST) is strictly executed outside locks.
- Concurrency during token refresh is serialized via `refreshMutex`, while state commits are validated against `activeSessionGeneration` under `stateLock`.
- In-flight background operations (delayed callback or delayed refresh) verify the generation counter matches their dispatch generation under lock. Any stale callback or refresh result is dropped to prevent resurrecting logged-out sessions or overwriting active accounts.

### 3.3 Storage Fail-Closed & Durable Tombstoning
- If `storage.clearTokensForClient(activeId)` fails on logout (e.g., storage corruption or Keystore write failure):
  1. The repository records the client in an in-memory `processRevokedClients` set, ensuring `validAccessToken()` returns `null` immediately.
  2. The storage records a durable tombstone (`markRevoked(clientId)`).
  3. If tombstone creation also fails, the account is deleted, or the Keystore alias is invalidated.
  4. On app restart, `loadAllCredentials()` checks `isRevoked(clientId)` and strips lingering tokens to `null`.
  5. The UI is honestly updated with `lastError = "인증 저장소를 완전히 비우지 못해 계정이 안전하게 격리되었습니다."`.

### 3.4 Expired Tokens & Minimum Refresh Boundaries
- In `validAccessToken()`:
  - If UI/session state is `UNAUTHENTICATED` or `account == null`, it immediately returns `null` without reading stored tokens.
  - If an access token is expired (`now >= expiresAt`) and `now < earliestRefreshAt`, refresh is prohibited by policy and the token is expired. The repository fails closed by throwing `ChatGptAuthException.UnusableRefreshTokenException` rather than returning a stale token.
  - If refreshed scopes lose plan usage permission, generation is incremented, state becomes `PLAN_USE_NOT_ENABLED`, and `validAccessToken()` returns `null`.

### 3.5 Dynamic Discovery Revocation
- Remote revocation on `signOut()` does not rely on a hardcoded endpoint; it queries `https://auth.openai.com/.well-known/openid-configuration`, verifies `issuer == "https://auth.openai.com"`, and extracts `revocation_endpoint`.
- The endpoint URL is verified to enforce HTTPS and authority pinning (`auth.openai.com`, standard port, no userinfo, no fragment).
- Only HTTP 200 is confirmed (`remoteRevocationConfirmed = true`); any discovery mismatch or failure reports honest unconfirmed status (`remoteRevocationConfirmed = false`).

---

## 4. Test Suite

Implemented in `app/src/test/java/com/ariateam/aria/cloud/auth/`:
1. `ChatGptCryptoTest`: PKCE S256 test vectors, Base64Url roundtrip, timing-safe equality, host UUID format.
2. `ChatGptJwtVerifierTest`: Real 2048-bit RSA key signature verification, fail-closed signature tampering, claim tampering, algorithm `none` rejection, future `nbf` rejection, expired token rejection, audience/issuer/nonce mismatch checks.
3. `ChatGptAuthHttpTransportTest`: HTTPS enforcement, authority pinning, non-443 port rejection, userinfo/fragment rejection, redacted `toString()`.
4. `ChatGptAuthLoopbackServerTest`: Ephemeral loopback socket binding, callback delivery, state mismatch rejection, duplicate query parameter rejection, oversized line rejection, cancel/restart race preservation, redacted `toString()`.
5. `ChatGptCredentialStorageTest`: Credential saving/loading, clearTokens retaining registration mapping, durable revoked tombstone fail-closed verification, credential masking.
6. `ChatGptAuthRepositoryTest`:
   - Full dynamic registration sign-in flow.
   - Plan usage scope enforcement and permission loss on refresh.
   - Returning sign-in with saved client ID.
   - Serialized token refresh and rotation.
   - Expired token with future earliest_refresh_at never returning stale token.
   - Permanent refresh error recovery with preserved registration.
   - Temporary refresh error recovery (credentials preserved).
   - Storage save failure on exchange failing closed.
   - Storage clear failure on logout failing closed with tombstone.
   - Logout fencing against delayed exchange and delayed refresh.
   - Revocation endpoint discovery issuer/endpoint mismatch failing closed.
   - Listener start/stop race fencing.
