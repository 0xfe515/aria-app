package com.ariateam.aria.navigation

import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Supported external navigation providers and their documented deep link URI schemes.
 *
 * Official scheme behaviors:
 * - Google Maps: `google.navigation:q={query}&mode=w` requests pedestrian mode navigation.
 *   Fallback: `https://www.google.com/maps/search/?api=1&query={query}`.
 * - Naver Map: text queries use search; resolved destinations use the official walking route.
 *   Fallback: `https://m.map.naver.com/search2/search.naver?query={query}`.
 * - KakaoMap: Without destination coordinates, the official supported deep link
 *   is `kakaomap://search?q={query}`.
 *   Fallback: `https://map.kakao.com/link/search/{query}`.
 */
enum class NavigationProvider(
    val id: String,
    val displayName: String,
    val packageName: String,
    val appSchemePrefix: String,
) {
    Google(
        id = "google",
        displayName = "구글 지도",
        packageName = "com.google.android.apps.maps",
        appSchemePrefix = "google.navigation:",
    ),
    Naver(
        id = "naver",
        displayName = "네이버 지도",
        packageName = "com.nhn.android.nmap",
        appSchemePrefix = "nmap://",
    ),
    Kakao(
        id = "kakao",
        displayName = "카카오맵",
        packageName = "net.daum.android.map",
        appSchemePrefix = "kakaomap://",
    );

    fun buildAppUriString(destination: String): String {
        val encoded = safeEncode(destination)
        return when (this) {
            Google -> "google.navigation:q=$encoded&mode=w"
            Naver -> "nmap://search?query=$encoded&appname=com.ariateam.aria"
            Kakao -> "kakaomap://search?q=$encoded"
        }
    }

    fun buildWalkingRouteUriString(destination: ResolvedDestination): String {
        require(this == Naver)
        require(destination.isWithinNaverBounds)
        return "nmap://route/walk?dlat=${destination.latitude}&dlng=${destination.longitude}" +
            "&dname=${safeEncode(destination.name)}&appname=com.ariateam.aria"
    }

    fun buildAppUri(destination: String): Uri = Uri.parse(buildAppUriString(destination))

    fun buildWebFallbackUriString(destination: String): String {
        val encoded = safeEncode(destination)
        return when (this) {
            Google -> "https://www.google.com/maps/search/?api=1&query=$encoded"
            Naver -> "https://m.map.naver.com/search2/search.naver?query=$encoded"
            Kakao -> "https://map.kakao.com/link/search/${encoded.replace("+", "%20")}"
        }
    }

    fun buildWebFallbackUri(destination: String): Uri = Uri.parse(buildWebFallbackUriString(destination))

    companion object {
        fun fromId(id: String?): NavigationProvider? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        fun safeEncode(value: String): String =
            URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name())
    }
}
