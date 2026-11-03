package com.ariateam.aria.command

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandCoordinatorNavigationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun externalNavigationCommandUpdatesSnapshotAndAnnounces() {
        var latestSnapshot = CommandSnapshot()
        val coordinator = CommandCoordinator(
            context = context,
            update = { latestSnapshot = it },
            soundChanged = {},
            suppress = {},
            followUp = {},
            finishFollowUp = {},
            stopListening = {},
            vibrate = {},
        )
        coordinator.started()

        coordinator.finalized("네이버 지도로 강남역 안내해줘")

        assertTrue(latestSnapshot.externalNavId > 0L)
        assertEquals("naver", latestSnapshot.externalNavProvider)
        assertEquals("강남역", latestSnapshot.externalNavDestination)
        assertEquals("네이버 지도로 강남역 검색을 열어요.", latestSnapshot.message)

        // Consuming external navigation clears the event
        val navId = latestSnapshot.externalNavId
        coordinator.consumeNavigation(navId)

        assertEquals(0L, latestSnapshot.externalNavId)
        assertEquals("", latestSnapshot.externalNavProvider)
        assertEquals("", latestSnapshot.externalNavDestination)

        coordinator.close()
    }

    @Test
    fun externalNavigationPreservesKoreanStreetNamesEndingWithRo() {
        var latestSnapshot = CommandSnapshot()
        val coordinator = CommandCoordinator(
            context = context,
            update = { latestSnapshot = it },
            soundChanged = {},
            suppress = {},
            followUp = {},
            finishFollowUp = {},
            stopListening = {},
            vibrate = {},
        )
        coordinator.started()

        coordinator.finalized("카카오맵으로 대학로 가자")
        assertEquals("kakao", latestSnapshot.externalNavProvider)
        assertEquals("대학로", latestSnapshot.externalNavDestination)

        coordinator.finalized("구글 지도로 을지로 안내해줘")
        assertEquals("google", latestSnapshot.externalNavProvider)
        assertEquals("을지로", latestSnapshot.externalNavDestination)

        coordinator.close()
    }

    @Test
    fun cancelCloudQuestionClearsPendingCloudState() {
        var latestSnapshot = CommandSnapshot()
        val coordinator = CommandCoordinator(
            context = context,
            update = { latestSnapshot = it },
            soundChanged = {},
            suppress = {},
            followUp = {},
            finishFollowUp = {},
            stopListening = {},
            vibrate = {},
        )
        coordinator.started()

        coordinator.finalized("질문 오늘 날씨 어때")
        assertTrue(latestSnapshot.cloudQuestionId > 0L)
        assertEquals("text", latestSnapshot.cloudQuestionType)
        assertTrue(latestSnapshot.processing)

        val qId = latestSnapshot.cloudQuestionId
        coordinator.cancelCloudQuestion(qId)

        assertEquals(0L, latestSnapshot.cloudQuestionId)
        assertEquals("", latestSnapshot.cloudQuestionType)
        assertFalse(latestSnapshot.processing)

        coordinator.close()
    }

    @Test
    fun speakHazardWarningEmitsHazardMessage() {
        var latestSnapshot = CommandSnapshot()
        val coordinator = CommandCoordinator(
            context = context,
            update = { latestSnapshot = it },
            soundChanged = {},
            suppress = {},
            followUp = {},
            finishFollowUp = {},
            stopListening = {},
            vibrate = {},
        )
        coordinator.started()

        coordinator.speakHazardWarning("전방 보행로 차단")
        assertEquals("전방 보행로 차단", latestSnapshot.message)
        assertFalse(latestSnapshot.processing)

        coordinator.close()
    }
}
