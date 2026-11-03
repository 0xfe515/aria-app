# External Navigation Integration & Validation

## Overview

Aria implements voice-triggered external navigation integration for **Google Maps**, **Naver Map**, and **KakaoMap**, integrated with the existing camera foreground service (`VisionForegroundService`) and voice command pipeline.

Navigation commands are parsed completely offline through `NavigationCommandParser` within `RuleCommandInterpreter`. Navigation deep links are dispatched via `CommandCoordinator` through `CommandSnapshot` IPC to `MainScreen`, where they launch verified external deep links or web fallbacks only from an admitted, visible Activity.

## Official Provider Capabilities & Deep Link Schemes

1. **Google Maps (`com.google.android.apps.maps`)**:
   - **App Deep Link**: `google.navigation:q={encoded_destination}&mode=w`
     Requests pedestrian (walking) routing mode where supported.
   - **Web Fallback**: `https://www.google.com/maps/search/?api=1&query={encoded_destination}`
   - **Capability Reality**: In South Korea, Google Maps turn-by-turn walking guidance is restricted by government spatial data regulations. Aria truthfully states that destination search is opened rather than claiming turn-by-turn walking guidance has started.

2. **Naver Map (`com.nhn.android.nmap`)**:
   - **App Deep Link**: `nmap://search?query={encoded_destination}&appname=com.ariateam.aria`
     Per official Naver Maps SDK specifications, `nmap://route/walk` strictly requires destination latitude and longitude (`dlat`, `dlng`). When navigating by voice query without coordinates, `nmap://search` is the documented, supported deep link to locate the destination.
   - **Web Fallback**: `https://m.map.naver.com/search2/search.naver?query={encoded_destination}`
   - **Capability Reality**: Turn-by-turn guidance cannot be initiated without coordinates; opening the place search allows the user to inspect the location and tap route guidance.

3. **KakaoMap (`net.daum.android.map`)**:
   - **App Deep Link**: `kakaomap://search?q={encoded_destination}`
     Per official Kakao Developer documentation, `kakaomap://route` requires endpoint coordinates (`ep`). Without coordinates, `kakaomap://search` is the documented supported deep link.
   - **Web Fallback**: `https://map.kakao.com/link/search/{encoded_destination}`
   - **Capability Reality**: Opens place search result directly in the KakaoMap application.

## Integration with Background Vision Session

- **Preserved Vision & Hazard Detection**:
  When an external map or browser opens, `MainActivity` moves to the background (`onStop()`). `MainScreen` detaches the `PreviewView` surface (`updateSurfaceProvider(null)`), while `VisionForegroundService` continues its CameraX frame analysis and IMU sensor sampling in the background. Returning to Aria reattaches the preview surface seamlessly without restarting the camera.
- **Replay Protection**:
  `lastExternalNavId` and `hasExternalNavBaseline` are stored using `rememberSaveable`. Any replayed command state, recomposition, or process reconnect will not re-trigger external Activity launches.
- **Activity Visibility Gates**:
  External intent launching is gated by `screenVisible`, `startupModel.hasBeenAdmitted`, and valid runtime permissions. External launches are never initiated from an unadmitted screen or background state.
- **Background Voice Handling**:
  If a navigation command is spoken while Aria is in the background, `CommandCoordinator` posts an ongoing notification (`Aria 길안내`) with `PendingIntent` directing the user to tap to launch `MainActivity`, complying with Android Background Activity Launch (BAL) restrictions.

## Conversational Voice Interaction & Clarification

- **Single-turn explicit commands**:
  - "네이버 지도로 강남역 안내해줘" -> Launches Naver Map with destination "강남역".
  - "카카오맵 판교역 가줘" -> Launches KakaoMap with destination "판교역".
  - "구글 지도로 서울역 가자" -> Launches Google Maps with destination "서울역".
- **Destination without provider**:
  - User: "강남역 길안내해줘"
  - Aria: "네이버 지도, 카카오맵, 구글 지도 중 어떤 지도로 안내할까요?" (stores `nav_dest:강남역` in context for 30s)
  - Follow-up: "카카오맵" -> Launches KakaoMap with destination "강남역".
- **Provider without destination**:
  - User: "네이버 지도로 안내해줘"
  - Aria: "네이버 지도로 갈 목적지를 말씀해 주세요." (stores `nav_prov:naver` in context for 30s)
  - Follow-up: "서울역" -> Launches Naver Map with destination "서울역".
- **Ambiguity / Conflicts**:
  - "네이버 지도 카카오맵으로 가줘" -> "네이버 지도, 카카오맵, 구글 지도 중 하나만 선택해 주세요."
- **Empty / Reserved Destination**:
  - "길안내" -> "어디로 갈까요? 목적지를 말씀해 주세요."
- **Negation Interception**:
  - "길안내 하지마", "가지마", "지도 열지마" -> "원하는 명령을 하나만 다시 말해 주세요."
- **Compound Commands**:
  - "강남역 가고 그리고 소리 꺼" -> "명령을 하나씩 말해 주세요."

## Measured Device Verification (Pixel 3 / Android 12)

Actual package inventory measured on the connected Google Pixel 3 (Android 12, Build SP1A.210812.016.A2):
- `com.google.android.apps.maps` (Google Maps): **INSTALLED**
- `com.nhn.android.nmap` (Naver Map): **ABSENT**
- `net.daum.android.map` (KakaoMap): **ABSENT**
- `com.android.chrome` (Chrome Browser): **INSTALLED**

Measured test behaviors on Pixel 3:
1. `googleMapsAppIntentResolvesWhenInstalled`:
   - Verified that `google.navigation:q=...&mode=w` with package `com.google.android.apps.maps` successfully resolves to Google Maps.
2. `absentProviderFallsBackToWebBrowserResolvableIntent`:
   - Verified that `nmap://search` app intent fails to resolve (app absent).
   - Verified fallback web URL `https://m.map.naver.com/search2/search.naver?query=...` resolves to Chrome.
3. `kakaoMapFallsBackToWebWhenAbsent`:
   - Verified that `kakaomap://search` app intent fails to resolve (app absent).
   - Verified fallback web URL `https://map.kakao.com/link/search/...` resolves to Chrome.
4. `launcherHandlesRealDeviceLaunchOrFallback`:
   - Verified `ExternalNavigationLauncher` produces `AppLaunched` for Google Maps and `WebFallbackLaunched` for Naver/Kakao without exceptions.
5. `CommandCoordinatorNavigationTest`:
   - Verified command interpretation, snapshot bundling, IPC state, notification posting, and `consumeNavigation` cleanup.
6. `CommandSnapshotBundleTest`:
   - Verified Bundle serialization/deserialization for `externalNavId`, `externalNavProvider`, and `externalNavDestination`, preserving backward compatibility with legacy Bundles.

## Validation Commands and Execution Results

| Command | Status | Notes |
| --- | --- | --- |
| `./gradlew :app:assembleDebug` | **Passed (0)** | Debug APK generated cleanly. |
| `./gradlew :app:testDebugUnitTest` | **Passed (0)** | 318 unit tests passed (including `NavigationProviderTest`, `NavigationCommandParserTest`, `RuleCommandInterpreterTest`). |
| `./gradlew :app:lintDebug` | **Passed (0)** | Android Lint completed with 0 errors. |
| `./gradlew :app:assembleDebugAndroidTest` | **Passed (0)** | Instrumented test APK assembled cleanly. |
| `./gradlew :app:connectedDebugAndroidTest` (`ExternalNavigationIntegrationTest`) | **Passed (0)** | 4 device tests passed on Pixel 3 (Android 12). |
| `./gradlew :app:connectedDebugAndroidTest` (`CommandCoordinatorNavigationTest`) | **Passed (0)** | 1 device test passed on Pixel 3. |
| `./gradlew :app:connectedDebugAndroidTest` (`CommandSnapshotBundleTest`) | **Passed (0)** | 3 device tests passed on Pixel 3. |
| `git diff --check` | **Passed (0)** | Clean formatting, no whitespace or merge issues. |

## Limitations and Unverified Environments

- **Physical walking**: Actual outdoor turn-by-turn guidance was not physically tested while walking; platform deep link and fallback contracts were verified programmatically.
- **Hardware variants**: Tested on Pixel 3 (Snapdragon / Android 12). Samsung Galaxy / Exynos devices and Android 13+ runtime behavior remain unverified.
- **Provider app installation states**: Google Maps tested as installed; Naver Map and KakaoMap tested as uninstalled with browser fallback.
