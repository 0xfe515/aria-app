package com.ariateam.aria.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

sealed interface NavigationLaunchResult {
    data class AppLaunched(val provider: NavigationProvider, val uri: Uri) : NavigationLaunchResult
    data class WebFallbackLaunched(val provider: NavigationProvider, val uri: Uri) : NavigationLaunchResult
    data class Failed(val provider: NavigationProvider, val reason: String) : NavigationLaunchResult
}

interface NavigationLauncher {
    fun launch(provider: NavigationProvider, destination: String): NavigationLaunchResult
}

class ExternalNavigationLauncher(private val context: Context) : NavigationLauncher {
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
            } catch (e: Exception) {
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
