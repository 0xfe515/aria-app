package com.ariateam.aria.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationResolverTest {
    @Test
    fun parsesOnlyValidNaverCoordinateDestinations() {
        val destination = AndroidDestinationResolver.parseCoordinates("37.4979, 127.0276")
        assertEquals(37.4979, destination?.latitude ?: 0.0, 0.00001)
        assertEquals(127.0276, destination?.longitude ?: 0.0, 0.00001)
        assertNull(AndroidDestinationResolver.parseCoordinates("91, 127"))
        assertNull(AndroidDestinationResolver.parseCoordinates("37.4, 132.1"))
        assertNull(AndroidDestinationResolver.parseCoordinates("not coordinates"))
    }

    @Test
    fun validatesResolvedCoordinatesBeforeRouteUriConstruction() {
        assertTrue(ResolvedDestination("서울", 37.5, 127.0).isWithinNaverBounds)
        assertFalse(ResolvedDestination("outside", 10.0, 127.0).isWithinNaverBounds)
    }

    @Test
    fun resolverPolicyRejectsUnresolvedAndAmbiguousResults() {
        assertEquals(DestinationResolution.NotFound, AndroidDestinationResolver.chooseCandidates(emptyList()))
        val candidates = listOf(
            ResolvedDestination("강남역", 37.4979, 127.0276),
            ResolvedDestination("강남역", 37.4979, 127.0276),
        )
        assertEquals(DestinationResolution.Resolved(candidates.first()), AndroidDestinationResolver.chooseCandidates(candidates))
        val ambiguous = candidates + ResolvedDestination("강남역", 35.1595, 126.8526)
        assertEquals(DestinationResolution.Ambiguous, AndroidDestinationResolver.chooseCandidates(ambiguous))
        assertEquals(
            DestinationResolution.NotFound,
            AndroidDestinationResolver.chooseCandidates(listOf(ResolvedDestination("invalid", 10.0, 127.0))),
        )
    }

    @Test
    fun resolvedRouteKeepsOriginalSpokenDestinationAsNameAndFallbackQuery() {
        val geocoderResult = ResolvedDestination("70", 37.5547, 126.9707)
        val query = "서울역 1번 출구"

        val resolved = DestinationResolution.Resolved(geocoderResult).withQueryLabel(query)
        assertTrue(resolved is DestinationResolution.Resolved)
        val destination = (resolved as DestinationResolution.Resolved).destination
        assertEquals(query, destination.name)
        assertEquals(37.5547, destination.latitude, 0.00001)
        val routeUri = NavigationProvider.Naver.buildWalkingRouteUriString(destination)
        assertTrue(routeUri.contains("dname=%EC%84%9C%EC%9A%B8%EC%97%AD+1%EB%B2%88+%EC%B6%9C%EA%B5%AC"))
        assertTrue(NavigationProvider.Naver.buildAppUriString(destination.name).contains("query=%EC%84%9C%EC%9A%B8%EC%97%AD+1%EB%B2%88+%EC%B6%9C%EA%B5%AC"))
        assertTrue(NavigationProvider.Naver.buildWebFallbackUriString(destination.name).contains("query=%EC%84%9C%EC%9A%B8%EC%97%AD+1%EB%B2%88+%EC%B6%9C%EA%B5%AC"))
    }
}
