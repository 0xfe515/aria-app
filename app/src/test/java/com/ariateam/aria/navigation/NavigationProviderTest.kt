package com.ariateam.aria.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationProviderTest {

    @Test
    fun googleMapsAppUriUsesNavigationSchemeWithWalkingMode() {
        val uri = NavigationProvider.Google.buildAppUriString("서울역")
        assertEquals("google.navigation:q=%EC%84%9C%EC%9A%B8%EC%97%AD&mode=w", uri)
    }

    @Test
    fun naverMapAppUriUsesOfficialSearchSchemeWithoutCoordinates() {
        val uri = NavigationProvider.Naver.buildAppUriString("강남역")
        assertEquals("nmap://search?query=%EA%B0%95%EB%82%A8%EC%97%AD&appname=com.ariateam.aria", uri)
    }

    @Test
    fun naverResolvedDestinationUsesWalkingRouteAndImplicitCurrentLocation() {
        val destination = ResolvedDestination("강남역", 37.4979, 127.0276)
        assertEquals(
            "nmap://route/walk?dlat=37.4979&dlng=127.0276&dname=%EA%B0%95%EB%82%A8%EC%97%AD&appname=com.ariateam.aria",
            NavigationProvider.Naver.buildWalkingRouteUriString(destination),
        )
    }

    @Test
    fun kakaoMapAppUriUsesOfficialSearchSchemeWithoutCoordinates() {
        val uri = NavigationProvider.Kakao.buildAppUriString("판교역")
        assertEquals("kakaomap://search?q=%ED%8C%90%EA%B5%90%EC%97%AD", uri)
    }

    @Test
    fun webFallbackUrisAreCorrectlyConstructed() {
        val googleWeb = NavigationProvider.Google.buildWebFallbackUriString("광화문")
        assertEquals("https://www.google.com/maps/search/?api=1&query=%EA%B4%91%ED%99%94%EB%AC%B8", googleWeb)

        val naverWeb = NavigationProvider.Naver.buildWebFallbackUriString("광화문")
        assertEquals("https://m.map.naver.com/search2/search.naver?query=%EA%B4%91%ED%99%94%EB%AC%B8", naverWeb)

        val kakaoWeb = NavigationProvider.Kakao.buildWebFallbackUriString("광화문")
        assertEquals("https://map.kakao.com/link/search/%EA%B4%91%ED%99%94%EB%AC%B8", kakaoWeb)
    }

    @Test
    fun safeUriEncodingHandlesKoreanSpacesAndSpecialCharacters() {
        val encoded = NavigationProvider.safeEncode("스타벅스 (강남점) & 카페")
        assertTrue(encoded.contains("%EC%8A%A4%ED%83%80%EB%B2%85%EC%8A%A4"))
        assertTrue(encoded.contains("%28") || encoded.contains("("))
        assertTrue(encoded.contains("%26") || encoded.contains("&"))
    }

    @Test
    fun destinationValidatorRejectsInvalidOrReservedQueries() {
        assertFalse(DestinationValidator.isValid(""))
        assertFalse(DestinationValidator.isValid("   "))
        assertFalse(DestinationValidator.isValid("길안내"))
        assertFalse(DestinationValidator.isValid("길찾기"))
        assertFalse(DestinationValidator.isValid("지도"))
        assertFalse(DestinationValidator.isValid("어디"))
        assertFalse(DestinationValidator.isValid("찾아줘"))
        assertFalse(DestinationValidator.isValid("알려줘"))
        assertFalse(DestinationValidator.isValid("가는길"))
        assertFalse(DestinationValidator.isValid("a")) // Too short (< 2 chars)
        assertFalse(DestinationValidator.isValid("x".repeat(101))) // Too long (> 100 chars)

        assertTrue(DestinationValidator.isValid("강남역"))
        assertTrue(DestinationValidator.isValid("서울역 1번 출구"))
        assertTrue(DestinationValidator.isValid("Space Needle"))
        assertTrue(DestinationValidator.isValid("스타벅스 강남점"))
    }

    @Test
    fun providerFromIdResolvesCorrectly() {
        assertEquals(NavigationProvider.Google, NavigationProvider.fromId("google"))
        assertEquals(NavigationProvider.Google, NavigationProvider.fromId("GOOGLE"))
        assertEquals(NavigationProvider.Naver, NavigationProvider.fromId("naver"))
        assertEquals(NavigationProvider.Kakao, NavigationProvider.fromId("kakao"))
        assertEquals(null, NavigationProvider.fromId("unknown"))
        assertEquals(null, NavigationProvider.fromId(null))
    }
}
