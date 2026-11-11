package com.ariateam.aria.navigation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import com.ariateam.aria.navigation.accessibility.NaverWalkingAccessibilityService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

sealed interface NavigationLaunchResult {
    data class AppLaunched(val provider: NavigationProvider, val uri: Uri) : NavigationLaunchResult
    data class WalkingRouteLaunched(val uri: Uri, val destination: ResolvedDestination) : NavigationLaunchResult
    data class WebFallbackLaunched(val provider: NavigationProvider, val uri: Uri) : NavigationLaunchResult
    data class Failed(val provider: NavigationProvider, val reason: String) : NavigationLaunchResult
}

interface NavigationLauncher {
    fun launch(provider: NavigationProvider, destination: String): NavigationLaunchResult
    fun launchWalkingRoute(destination: ResolvedDestination): NavigationLaunchResult
}

class ExternalNavigationLauncher(private val context: Context) : NavigationLauncher {
    override fun launchWalkingRoute(destination: ResolvedDestination): NavigationLaunchResult {
        val provider = NavigationProvider.Naver
        val uri = Uri.parse(provider.buildWalkingRouteUriString(destination))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(provider.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            NavigationLaunchResult.WalkingRouteLaunched(uri, destination)
        } catch (_: Exception) {
            launch(provider, destination.name)
        }
    }

    override fun launch(provider: NavigationProvider, destination: String): NavigationLaunchResult {
        val appUri = provider.buildAppUri(destination)
        val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
            setPackage(provider.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val canHandleApp = try {
            val resolve = context.packageManager.queryIntentActivities(
                appIntent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            resolve.isNotEmpty()
        } catch (_: Exception) {
            false
        }

        if (canHandleApp) {
            try {
                context.startActivity(appIntent)
                return NavigationLaunchResult.AppLaunched(provider, appUri)
            } catch (_: ActivityNotFoundException) {
                // Fall through to web fallback
            } catch (_: Exception) {
                // Other launch exception: try web fallback
            }
        }

        // Web fallback
        val webUri = provider.buildWebFallbackUri(destination)
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(webIntent)
            NavigationLaunchResult.WebFallbackLaunched(provider, webUri)
        } catch (e: Exception) {
            NavigationLaunchResult.Failed(
                provider,
                e.message ?: "지도를 열 수 있는 앱이나 웹 브라우저를 찾을 수 없습니다.",
            )
        }
    }
}

sealed interface NaverHandoffOutcome {
    data class WalkingRouteArmed(
        val destination: ResolvedDestination,
        val assistArmed: Boolean,
        val launchResult: NavigationLaunchResult,
    ) : NaverHandoffOutcome

    data class SearchFallbackArmed(
        val destination: String,
        val assistArmed: Boolean,
        val launchResult: NavigationLaunchResult,
    ) : NaverHandoffOutcome

    data class AmbiguousDestination(
        val destination: String,
        val launchResult: NavigationLaunchResult,
    ) : NaverHandoffOutcome

    data class GenericProviderLaunched(
        val provider: NavigationProvider,
        val destination: String,
        val launchResult: NavigationLaunchResult,
    ) : NaverHandoffOutcome

    data class Ignored(val reason: String) : NaverHandoffOutcome
}

class NaverNavigationHandoff(
    private val context: Context? = null,
    private val launcher: NavigationLauncher = ExternalNavigationLauncher(
        context ?: error("Context required for ExternalNavigationLauncher"),
    ),
    private val destinationResolver: (suspend (String) -> DestinationResolution)? = null,
    private val isAccessibilityEnabled: (() -> Boolean)? = null,
    private val armAssist: ((Context?, String, String) -> Boolean)? = null,
    private val cancelAssist: ((Context?) -> Unit)? = null,
) {
    suspend fun execute(
        eventId: Long,
        provider: NavigationProvider,
        rawDestination: String,
        screenVisible: Boolean,
        isRequestStillValid: (() -> Boolean)? = null,
    ): NaverHandoffOutcome {
        val destination = rawDestination.trim()
        if (destination.isBlank()) return NaverHandoffOutcome.Ignored("Blank destination")
        if (!screenVisible) return NaverHandoffOutcome.Ignored("Screen not visible")

        if (provider != NavigationProvider.Naver) {
            val result = launcher.launch(provider, destination)
            return NaverHandoffOutcome.GenericProviderLaunched(provider, destination, result)
        }

        val resolver = destinationResolver ?: { query ->
            AndroidDestinationResolver(
                context ?: error("Context required for AndroidDestinationResolver"),
            ).resolve(query)
        }
        val rawResolution = withTimeoutOrNull(4_000L) { resolver(destination) }
            ?: DestinationResolution.Unavailable
        val resolution = rawResolution.withQueryLabel(destination)

        currentCoroutineContext().ensureActive()
        if (isRequestStillValid != null && !isRequestStillValid()) {
            return NaverHandoffOutcome.Ignored("Request invalidated during resolution")
        }

        val checkAccessibility = isAccessibilityEnabled ?: {
            context?.let { NaverWalkingAccessibilityService.isEnabled(it) } ?: false
        }
        val arm = armAssist ?: { ctx, tok, dest ->
            ctx?.let { NaverWalkingAccessibilityService.arm(it, tok, dest) } ?: false
        }
        val cancel = cancelAssist ?: { ctx ->
            ctx?.let { NaverWalkingAccessibilityService.cancel(it) }
        }

        val accessibilityEnabled = checkAccessibility()
        val assistToken = "$eventId-${System.nanoTime()}"

        return when (resolution) {
            is DestinationResolution.Resolved -> {
                val assistArmed = accessibilityEnabled && arm(context, assistToken, resolution.destination.name)
                val launchResult = launcher.launchWalkingRoute(resolution.destination)
                if (launchResult !is NavigationLaunchResult.WalkingRouteLaunched) {
                    cancel(context)
                }
                NaverHandoffOutcome.WalkingRouteArmed(resolution.destination, assistArmed, launchResult)
            }
            DestinationResolution.Ambiguous, DestinationResolution.NotFound, DestinationResolution.Unavailable -> {
                // When Geocoder coordinates are ambiguous, missing, or unavailable, arm Naver search fallback
                // without coordinates. Naver Map's internal exact card matching will resolve the campus POI,
                // while preserving truthful rejection if Naver results are also ambiguous.
                val assistArmed = accessibilityEnabled && arm(context, assistToken, destination)
                val launchResult = launcher.launch(provider, destination)
                if (launchResult !is NavigationLaunchResult.AppLaunched) {
                    cancel(context)
                }
                NaverHandoffOutcome.SearchFallbackArmed(destination, assistArmed, launchResult)
            }
        }
    }

    companion object {
        fun feedbackMessage(outcome: NaverHandoffOutcome): String = when (outcome) {
            is NaverHandoffOutcome.WalkingRouteArmed -> when {
                outcome.launchResult is NavigationLaunchResult.WebFallbackLaunched ->
                    "네이버 앱에서 경로를 열지 못해 웹 검색으로 연결합니다."
                outcome.launchResult is NavigationLaunchResult.AppLaunched ->
                    "네이버 앱에서 목적지를 검색합니다. 도보 안내는 앱에서 시작해 주세요."
                outcome.launchResult is NavigationLaunchResult.Failed ->
                    "목적지 정보를 찾을 수 없습니다."
                outcome.assistArmed ->
                    "도보 경로를 전달했어요. 안내 시작 여부를 확인하는 중이에요."
                else ->
                    "도보 경로를 네이버 지도에 전달했어요. 자동 안내 시작을 원하면 일반 설정에서 접근성 보조를 켜 주세요."
            }
            is NaverHandoffOutcome.SearchFallbackArmed -> when {
                outcome.launchResult is NavigationLaunchResult.WebFallbackLaunched ->
                    "네이버 앱이 없어 웹 검색을 엽니다. 도보 경로는 네이버 지도 앱에서 확인해 주세요."
                outcome.launchResult is NavigationLaunchResult.Failed ->
                    "목적지 정보를 찾을 수 없습니다."
                outcome.assistArmed ->
                    "도보 경로를 찾고 있어요. 네이버 지도에서 안내 시작 여부를 확인하는 중이에요."
                else ->
                    "목적지 좌표를 찾지 못해 네이버 지도에서 검색합니다. 도보 안내는 네이버 지도에서 시작해 주세요."
            }
            is NaverHandoffOutcome.AmbiguousDestination ->
                "목적지가 여러 곳으로 확인되어 검색 결과만 엽니다. 정확한 장소를 말해 주세요."
            is NaverHandoffOutcome.GenericProviderLaunched -> when (outcome.launchResult) {
                is NavigationLaunchResult.AppLaunched ->
                    "${outcome.provider.displayName} 앱으로 ${outcome.destination} 검색을 엽니다."
                is NavigationLaunchResult.WebFallbackLaunched ->
                    "${outcome.provider.displayName} 앱이 설치되어 있지 않아 웹 검색으로 연결합니다."
                else -> "목적지 정보를 찾을 수 없습니다."
            }
            is NaverHandoffOutcome.Ignored -> ""
        }
    }
}
