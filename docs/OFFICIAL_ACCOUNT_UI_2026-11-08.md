# Official ChatGPT Account UI Implementation & Review Corrections Report

## 1. Overview
In accordance with user directives, the official login execution plan (`docs/OFFICIAL_LOGIN_EXECUTION_2026-11-08.md`), and follow-up review findings (`ARIA-OFFICIAL-ACCOUNT-REVIEW`), the Settings Account screen has been updated and refined for the official **Sign in with ChatGPT (SIWC)** browser authentication workflow.

- **Initial Task ID**: ARIA-OFFICIAL-ACCOUNT-UI (`task_7188fc74209b` / `ctx_19518a09fe3c`)
- **Review Task ID**: ARIA-OFFICIAL-ACCOUNT-REVIEW (`task_afb91afc7007` / `ctx_cb65da9a1736`)
- **Category Order & Home Invariants Preserved**: 일반, 계정, 진단, 데모, 앱 정보. General mount choices remain strictly "어깨" and "가슴". Home layout and vision overlays remain untouched.
- **Provider Policy**: Official ChatGPT (OpenAI) only. Gemini, Claude, and API-key entry paths are strictly rejected.
- **On-Device Models**: Korean Speech Recognition (Sherpa-ONNX) and Pedestrian Safety Vision (LiteRT) remain on-device and listed in the account section.

---

## 2. Review Corrections & Refinements

### 2.1 Technical Identifiers Removed from UI
- Removed `clientId` and `subject` exposure from all user-facing composables (`chatgpt_client_id` node eliminated).
- Standardized user identity formatting via `ChatGptAccountUiPolicy.formatFriendlyAccountLabel(email, name, fallbackIndex)`.
- If both name and email exist: `"이름 (email@example.com)"`. If only email: `"email@example.com"`. If only name: `"이름"`.
- Fallbacks use friendly ordinal labels (e.g. `"ChatGPT 계정 1"`) rather than raw UUIDs, subjects, or dynamic client IDs.

### 2.2 Remote Revocation Notice Placed Outside Authenticated Branch
- During normal active authenticated sessions, `remoteRevocationConfirmed` is `null`. The previous code erroneously showed an alarming "원격 토큰 해지 상태: 확인 안 됨" on healthy accounts.
- Refactored revocation notice rendering via `ChatGptAccountUiPolicy.formatRevocationNotice(confirmed)`:
  - `null`: No notice rendered (clean normal authenticated view).
  - `true`: `"원격 토큰 해지 상태: 서버 해지 확인됨"`.
  - `false`: `"원격 토큰 해지 상태: 서버 미확인 (로컬 로그아웃 완료)"`.
- Rendered only after signout or when a revocation attempt has occurred and set a non-null result.

### 2.3 Saved Issued Client ID Reuse on Reconnect & Returning Login
- Per official specification (`profiles-and-sessions.md`), returning sign-in and reauthorizations must reuse the registration's issued `client_id` and host ID rather than generating new dynamic clients.
- `PLAN_USE_NOT_ENABLED` and `REFRESH_FAILED_RECONNECT`:
  - `startLogin(context, targetClientId = resolvedSavedId, forceReconsent = ...)` reuses the saved issued client ID.
- `UNAUTHENTICATED`:
  - When saved accounts exist, the primary login button reconnects the existing registration (`targetClientId = primarySaved.clientId`).
  - An explicit secondary button (`chatgpt_add_account_button`) allows registering a new account via dynamic registration (`targetClientId = null`).
  - Account picker dialog allows switching to or re-authorizing specific retained accounts by their issued client ID.

### 2.4 Robust Local Error Handling for Suspend Actions
- Wrapped `signOut()` and `switchAccount()` in `try ... catch (t: Throwable)`.
- Dispatches clean Korean message (`R.string.chatgpt_action_error_message`) without exposing internal exception details or stack traces to the user.

---

## 3. Testing & Verification

### 3.1 Tests Authored
1. `app/src/test/java/com/ariateam/aria/ui/screen/ChatGptAccountUiTest.kt` (JVM Unit Tests):
   - Verified `ChatGptAccountUiPolicy.formatFriendlyAccountLabel` across all combinations of name, email, and ordinal fallback.
   - Verified `ChatGptAccountUiPolicy.formatRevocationNotice` returns `null` for `null` (avoiding false alarms during active sessions), and returns correct copy for `true` and `false`.
   - Verified `ChatGptAccountUiPolicy.resolveTargetClientId` reuses issued client IDs and only yields `null` for clean dynamic registration.
   - Verified `SettingsPolicy.isSupportedAccountProvider` accepts `chatgpt`/`openai` and strictly rejects `gemini`, `claude`, `anthropic`, `ollama`, `api_key`.
   - Verified `ChatGptAuthState.toString()` does not leak sensitive tokens.
2. `app/src/test/java/com/ariateam/aria/ui/screen/SettingsPolicyTest.kt`:
   - Verified official ChatGPT provider acceptance and strict rejection of Gemini, Claude, and API-key providers.
   - Retained all category ordering and mount choice regression assertions.
3. `app/src/androidTest/java/com/ariateam/aria/ui/screen/SettingsScreenTest.kt` (Android Compose Tests):
   - `accountPageShowsPlanPermissionAndAllowsSignOutWithRevocationNotice`: Verifies friendly label display without client ID leaks, absence of revocation notice while authenticated, and appearance of truthful unconfirmed notice after logout.
   - `accountPageShowsReconnectReusingSavedClientIdWhenRefreshFails`: Verifies reconnect invokes `startLogin` with the existing saved `clientId`.
   - `accountPageUnauthenticatedOffersSavedAccountReconnectWithSavedId`: Verifies unauthenticated state offers returning reauthorization with saved client ID and dynamic registration for new accounts.
   - Retained all existing root, voice, and subpage assertions.

### 3.2 Worker Verification
- Executed `git diff --check`: Exit code 0 (clean formatting, no trailing whitespace errors).
- Gradle build and device execution ownership:
  - Per policy contract, coordinator owns Gradle execution (`:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebugAndroidTest`).
  - No real-device test or live network claims are made.

---

## 4. Pending External Call Sites & Integration Notes
- `app/src/main/java/com/ariateam/aria/ui/screen/MainScreen.kt:320`:
  `SettingsScreen(..., keyStorage = keyStorage)`
  The obsolete `keyStorage` parameter was removed from `SettingsScreen`. The later integration task (which owns `MainScreen` and `CloudQuestionCoordinator`) will update `MainScreen.kt` to inject `DefaultChatGptAuthRepository.getInstance(context)` into `SettingsScreen` and replace `CloudQuestionCoordinator`'s key dependency.
