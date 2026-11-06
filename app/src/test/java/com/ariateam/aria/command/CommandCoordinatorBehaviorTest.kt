package com.ariateam.aria.command

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandCoordinatorBehaviorTest {

    private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { data.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { data.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    private class FakeSharedPreferences(val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private val emittedSnapshots = mutableListOf<CommandSnapshot>()
    private var vibrationCount = 0
    private var suppressionState = false
    private val testNowMs = 10_000L

    @Before
    fun setUp() {
        emittedSnapshots.clear()
        vibrationCount = 0
        suppressionState = false
        CommandCoordinator.resetGlobalEventIdForTesting(1_000L)
    }

    private fun createCoordinator(audible: Boolean = true): CommandCoordinator {
        val fakePrefs = FakeSharedPreferences(mutableMapOf("audible" to audible))
        return CommandCoordinator(
            context = null,
            update = { emittedSnapshots.add(it) },
            soundChanged = {},
            suppress = { suppressionState = it },
            followUp = {},
            finishFollowUp = {},
            stopListening = {},
            vibrate = { vibrationCount++ },
            preferencesOverride = fakePrefs,
            notificationManagerOverride = null,
            clockRealtimeMs = { testNowMs },
            clockElapsedNanos = { testNowMs * 1_000_000L },
        )
    }

    @Test
    fun postHazardAlert_rejectsStaleAlertsOlderThan2Seconds() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()
        vibrationCount = 0

        val nowNanos = testNowMs * 1_000_000L
        val staleTimestamp = nowNanos - 3_000_000_000L // 3 seconds ago (stale > 2.0s)

        coordinator.postHazardAlert(
            session = 1L,
            sequence = 1L,
            timestampNanos = staleTimestamp,
            message = "전방 보행로 공사중",
        )

        assertEquals("Stale hazard alert should be dropped", 0, vibrationCount)
        assertTrue(emittedSnapshots.isEmpty())
    }

    @Test
    fun walkingStatusCommandReadsFreshLimitationWithoutClaimingSafety() {
        val coordinator = createCoordinator(audible = false)
        coordinator.started()
        val nowNanos = testNowMs * 1_000_000L
        coordinator.updateWalkingAssistState(
            session = 3L,
            sequence = 1L,
            timestampNanos = nowNanos,
            status = "보행 보조 제한: 새 분석 결과가 없습니다.",
            shouldAnnounce = false,
            stopped = false,
        )

        coordinator.finalized("보행 상태")

        assertTrue(emittedSnapshots.last().message.contains("보행 보조 제한"))
        assertFalse(emittedSnapshots.last().message.contains("안전합니다"))
        coordinator.close()
    }

    @Test
    fun postHazardAlert_acceptsFreshAlertAndVibratesAndEmits() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()
        vibrationCount = 0

        val nowNanos = testNowMs * 1_000_000L
        val freshTimestamp = nowNanos - 100_000_000L // 100ms ago (fresh)

        coordinator.postHazardAlert(
            session = 1L,
            sequence = 1L,
            timestampNanos = freshTimestamp,
            message = "전방 1m 장애물 주의",
        )

        assertEquals("Fresh hazard alert should vibrate", 1, vibrationCount)
        assertFalse(emittedSnapshots.isEmpty())
        assertEquals("전방 1m 장애물 주의", coordinator.snapshot.message)
    }

    @Test
    fun postHazardAlert_deduplicatesIdenticalMessageWithinCooldown() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()
        vibrationCount = 0

        val nowNanos = testNowMs * 1_000_000L

        // First alert
        coordinator.postHazardAlert(
            session = 1L,
            sequence = 1L,
            timestampNanos = nowNanos,
            message = "전방 1m 장애물 주의",
        )
        assertEquals(1, vibrationCount)

        // Duplicate alert immediately arriving in same session
        coordinator.postHazardAlert(
            session = 1L,
            sequence = 2L,
            timestampNanos = nowNanos + 50_000_000L,
            message = "전방 1m 장애물 주의",
        )
        // Should be suppressed by message cooldown (2.5s)
        assertEquals("Duplicate message within cooldown should not trigger a second vibration", 1, vibrationCount)
    }

    @Test
    fun postHazardAlert_whenMuted_vibratesWithoutAudio() {
        val coordinator = createCoordinator(audible = false)
        coordinator.started()
        emittedSnapshots.clear()
        vibrationCount = 0

        val nowNanos = testNowMs * 1_000_000L

        coordinator.postHazardAlert(
            session = 1L,
            sequence = 1L,
            timestampNanos = nowNanos,
            message = "경로 막힘",
        )

        // Vibration must still fire even when audio is muted
        assertEquals(1, vibrationCount)
        assertEquals("경로 막힘", coordinator.snapshot.message)
        assertFalse(coordinator.snapshot.audible)
    }

    @Test
    fun finalized_localCommand_executesLocallyWithoutCloudQuestion() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()

        coordinator.finalized("홈으로 가")

        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        assertEquals("", coordinator.snapshot.cloudQuestionType)
    }

    @Test
    fun finalized_cancellation_rejectsLocallyWithoutCloudQuestion() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()

        coordinator.finalized("취소해줘")

        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        assertEquals("취소했어요.", coordinator.snapshot.message)
    }

    @Test
    fun finalized_negation_rejectsLocallyWithoutCloudQuestion() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()

        coordinator.finalized("길찾지 마")

        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        assertEquals("원하는 명령을 하나만 다시 말해 주세요.", coordinator.snapshot.message)
    }

    @Test
    fun finalized_conversationalQuery_fallsBackToGeminiCloudQuestion() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()

        coordinator.finalized("오늘 서울 날씨 어때?")

        assertTrue("Conversational query should assign a cloudQuestionId", coordinator.snapshot.cloudQuestionId > 0L)
        assertEquals("text", coordinator.snapshot.cloudQuestionType)
        assertEquals("오늘 서울 날씨 어때?", coordinator.snapshot.cloudQuestionPrompt)
    }
}
