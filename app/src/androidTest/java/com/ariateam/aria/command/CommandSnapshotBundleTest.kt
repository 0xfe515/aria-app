package com.ariateam.aria.command

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandSnapshotBundleTest {
    @Test
    fun oldSnapshotBundlesDefaultToVisibleWithoutAnEvent() {
        val legacy = Bundle().apply {
            putString("message", "기존 명령 상태")
            putBoolean("processing", false)
        }

        val restored = CommandSnapshot.fromBundle(legacy)

        assertEquals("기존 명령 상태", restored.message)
        assertEquals(0L, restored.screenVisibilityId)
        assertFalse(restored.screenHidden)
    }

    @Test
    fun screenVisibilityEventRoundTrips() {
        val original = CommandSnapshot(screenVisibilityId = 42L, screenHidden = true)

        val restored = CommandSnapshot.fromBundle(original.toBundle())

        assertEquals(42L, restored.screenVisibilityId)
        assertTrue(restored.screenHidden)
    }

    @Test
    fun externalNavigationEventRoundTrips() {
        val original = CommandSnapshot(
            externalNavId = 1001L,
            externalNavProvider = "naver",
            externalNavDestination = "강남역",
        )

        val restored = CommandSnapshot.fromBundle(original.toBundle())

        assertEquals(1001L, restored.externalNavId)
        assertEquals("naver", restored.externalNavProvider)
        assertEquals("강남역", restored.externalNavDestination)
    }

    @Test
    fun cloudQuestionEventRoundTrips() {
        val original = CommandSnapshot(
            cloudQuestionId = 2002L,
            cloudQuestionType = "photo",
            cloudQuestionPrompt = "앞에 무엇이 있는지 설명해 주세요.",
        )

        val restored = CommandSnapshot.fromBundle(original.toBundle())

        assertEquals(2002L, restored.cloudQuestionId)
        assertEquals("photo", restored.cloudQuestionType)
        assertEquals("앞에 무엇이 있는지 설명해 주세요.", restored.cloudQuestionPrompt)
    }
}
