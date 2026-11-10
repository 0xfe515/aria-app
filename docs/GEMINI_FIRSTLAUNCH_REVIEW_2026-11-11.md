# Gemini Independent Review: First-Launch Accessibility & Naver Compose Target Selection

**Date:** 2026-11-11
**Reviewer:** Gemini 3.8 Flash (Medium)
**Task ID:** ARIA-GEMINI-FIRSTLAUNCH-REVIEW-20261111
**Dispatch ID:** ctx_90e28ac55d96

---

## 1. Executive Summary

This document reports the independent review of the first-launch accessibility setup and the Naver Compose target-selection engine for ARIA version code 18 (preview 20261111.2). The review verified code correctness against actual repository files, the captured public walking route hierarchy (`%TEMP%/aria-accessibility-firstlaunch-20261111/naver-route.xml`), and the clean release candidate (`MainActivity-task-only.kt`).

Two concrete defects were identified and resolved under exclusive coordinator-granted edit scope:
1. **First-launch policy lifecycle edge:** `FirstLaunchAccessibilityPolicy.shouldShow` previously bypassed the gate if `isEnabled` became true while `isSettingsReturnPending` was still true. This could cause early startup composition upon process recreation before the `ON_RESUME` observer ran. Fixed to `isSettingsReturnPending || (!isEnabled && !isHandled)`, with JVM regression test coverage added.
2. **AndroidTest compilation failure:** `FirstLaunchAccessibilityGateTest.kt` contained an invalid top-level import (`androidx.compose.ui.test.assertDoesNotExist`), which broke `compileDebugAndroidTestKotlin`. Removed the invalid import while preserving all valid `assertDoesNotExist()` member invocations.

---

## 2. First-Launch Accessibility Setup Review

### 2.1 One-Time Marker & Preferences Lifecycle
- **Storage:** Persisted under private preferences `first_launch_accessibility_v1` using keys `handled` and `settings_return_pending`.
- **Handling Semantics:**
  - Rendering alone never sets `handled = true`.
  - `markSettingsLaunched()` persists `handled = true` and `settings_return_pending = true` only upon successful intent launch.
  - `markSkipped()` persists `handled = true` and `settings_return_pending = false`.
  - If accessibility is already enabled on launch with no pending return, `initialState` records `handled = true` and bypasses the gate.
- **Settings-Return Lifecycle:**
  - `MainActivity` attaches a `LifecycleEventObserver` listening for `Lifecycle.Event.ON_RESUME`.
  - When returning from settings (`pending == true`), the observer calls `completeSettingsReturn()` and refreshes service state via `stateAfterSettingsReturn(isEnabled)`.
  - Gate is released (`showGate = false`) regardless of whether the user enabled the service or left it disabled.

### 2.2 Mutual Exclusion with Onboarding & MainScreen
- In `MainActivity.OnboardingGate()`, when `accessibilityGateState.showGate` is true, `Surface { FirstLaunchAccessibilityGate(...) }` is rendered and the function returns early (`return`).
- `BodyMountProfileStore`, `OnboardingScreen`, `MainScreen`, and camera/microphone permission flows are never composed or executed while the gate is active or pending return.

### 2.3 Bypass, Skip, and Failure Handling
- **Already Enabled:** Bypasses explanation completely on initial launch.
- **Skip ("나중에"):** Immediately dismisses gate and advances to body profile onboarding.
- **Settings Launch Failure:** If `openAccessibilitySettings(context)` throws or fails, `settingsUnavailable = true` is set, `markSettingsLaunched()` is skipped, and an inline error message (`R.string.first_launch_accessibility_unavailable`) is displayed with retry and skip buttons remaining responsive.

---

## 3. Naver Compose Target Selection Review

### 3.1 Verification Against Captured XML Hierarchy
The captured route hierarchy (`%TEMP%/aria-accessibility-firstlaunch-20261111/naver-route.xml`) confirms:
- Immediate clickable parent: `android.view.View`, `clickable="true"`, `enabled="true"`, bounds `[360,1973][1080,2116]`.
- Child 0: `android.view.View`, `content-desc="안내시작"`, `clickable="false"`.
- Child 1: `android.widget.TextView`, `text="안내시작"`, `clickable="false"`.
- Child 2: `android.widget.Button`, empty text/description, `clickable="false"`.

### 3.2 Target Resolution & Deduplication
- `NaverAssistTargetSelector.select` resolves both child labels to their immediate clickable parent (`candidate.parentId`).
- The resulting target set deduplicates to the single parent ID (`Set<Int>`).
- Unlabeled nonclickable decoration (Child 2) does not block resolution because `hasUnrelatedActionableChild` only flags visible descendants that are clickable or have non-start non-blank text.

### 3.3 Strict Rejection Guards
- **Ambiguous Parents:** Distinct clickable parents each generating targets produce `targets.size > 1`, which `shouldClick` rejects via `candidates.size == 1`.
- **Distant Ancestors:** Bounded by `MAX_ANCESTORS = 1`. Two-level distant ancestors are rejected.
- **Unrelated Actions:** Immediate clickable parents containing unrelated labels (e.g. "미리보기") or clickable controls are rejected.
- **Disabled Nodes:** Disabled labels and disabled parents are immediately rejected.
- **Package / Window Safety:** Strictly checks package name (`com.nhn.android.nmap`) and matching `windowId`.

### 3.4 Request Store & Privacy Invariants
- Privacy disclosure accurately states screen contents are neither recorded nor transmitted (`R.string.first_launch_accessibility_explanation`).
- Non-Naver window events never read window hierarchy and immediately disarm.
- 20-second timeout enforces automatic disarm and cleanup via main looper handler.
- `NaverAssistRequestStore.consume` is synchronized, clears pending state before executing the action, and fences duplicate or replayed clicks.
- **No Hardware Action Claim:** The service remains disabled on the connected Pixel 3 device; this review confirms algorithmic correctness without claiming hardware execution.

---

## 4. Bounded Fixes Applied

1. `app/src/main/java/com/ariateam/aria/ui/screen/FirstLaunchAccessibilityGate.kt`:
   - Updated `FirstLaunchAccessibilityPolicy.shouldShow` to `isSettingsReturnPending || (!isEnabled && !isHandled)`.
2. `app/src/test/java/com/ariateam/aria/ui/screen/FirstLaunchAccessibilityPolicyTest.kt`:
   - Added regression test `pendingSettingsReturnKeepsGateEvenWhenServiceEnabled`.
3. `app/src/androidTest/java/com/ariateam/aria/ui/screen/FirstLaunchAccessibilityGateTest.kt`:
   - Removed unresolved import `androidx.compose.ui.test.assertDoesNotExist`.

---

## 5. Validation Summary
- `git diff --check`: Passed with zero errors or whitespace issues.
- Scoped diffs verified against granted boundaries.
- Android SDK / Gradle checks, APK builds, and nondestructive device tests belong exclusively to the coordinator.
