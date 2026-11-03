package com.ariateam.aria.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalNavigationIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun googleMapsAppIntentResolvesWhenInstalled() {
        val provider = NavigationProvider.Google
        val destination = "서울역"
        val appUri = provider.buildAppUri(destination)
        val intent = Intent(Intent.ACTION_VIEW, appUri).apply {
            setPackage(provider.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val resolveList = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        // Pixel 3 has Google Maps installed
        assertTrue("Google Maps should resolve on device", resolveList.isNotEmpty())
        assertEquals(provider.packageName, resolveList.first().activityInfo.packageName)
    }

    @Test
    fun absentProviderFallsBackToWebBrowserResolvableIntent() {
        val naverProvider = NavigationProvider.Naver
        val destination = "강남역"
        val naverAppIntent = Intent(Intent.ACTION_VIEW, naverProvider.buildAppUri(destination)).apply {
            setPackage(naverProvider.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val naverAppResolved = context.packageManager.queryIntentActivities(
            naverAppIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        // Naver Map is absent on Pixel 3
        assertTrue("Naver Map app is absent", naverAppResolved.isEmpty())

        // Fallback web intent must resolve to an installed browser
        val webUri = naverProvider.buildWebFallbackUri(destination)
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val webResolved = context.packageManager.queryIntentActivities(
            webIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue("Browser should resolve fallback web URI", webResolved.isNotEmpty())
    }

    @Test
    fun kakaoMapFallsBackToWebWhenAbsent() {
        val kakaoProvider = NavigationProvider.Kakao
        val destination = "판교역"
        val kakaoAppIntent = Intent(Intent.ACTION_VIEW, kakaoProvider.buildAppUri(destination)).apply {
            setPackage(kakaoProvider.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val kakaoAppResolved = context.packageManager.queryIntentActivities(
            kakaoAppIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue("KakaoMap app is absent", kakaoAppResolved.isEmpty())

        val webUri = kakaoProvider.buildWebFallbackUri(destination)
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val webResolved = context.packageManager.queryIntentActivities(
            webIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue("Browser should resolve KakaoMap web fallback", webResolved.isNotEmpty())
    }

    @Test
    fun launcherHandlesRealDeviceLaunchOrFallback() {
        val launcher = ExternalNavigationLauncher(context)

        // For Google Maps (installed), launcher produces AppLaunched or WebFallbackLaunched
        val googleResult = launcher.launch(NavigationProvider.Google, "서울시청")
        assertTrue(
            "Expected AppLaunched or WebFallbackLaunched, got $googleResult",
            googleResult is NavigationLaunchResult.AppLaunched || googleResult is NavigationLaunchResult.WebFallbackLaunched,
        )

        // For Naver Map (absent), launcher produces WebFallbackLaunched
        val naverResult = launcher.launch(NavigationProvider.Naver, "강남역")
        assertTrue(
            "Expected WebFallbackLaunched for absent Naver Map, got $naverResult",
            naverResult is NavigationLaunchResult.WebFallbackLaunched,
        )

        // For KakaoMap (absent), launcher produces WebFallbackLaunched
        val kakaoResult = launcher.launch(NavigationProvider.Kakao, "판교역")
        assertTrue(
            "Expected WebFallbackLaunched for absent KakaoMap, got $kakaoResult",
            kakaoResult is NavigationLaunchResult.WebFallbackLaunched,
        )
    }
}
