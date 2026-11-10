package com.ariateam.aria.command

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.ariateam.aria.settings.AlertFrequency
import com.ariateam.aria.settings.FeedbackSettings
import com.ariateam.aria.hazard.AlertSeverity

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
    private class FakeFeedbackSettings : FeedbackSettingsAccess {
        var current = FeedbackSettings()
        var fail = false
        override val cachedSettings get() = current
        override fun read() = if (fail) null else current
        override fun setVolumePercent(value: Int) = if (fail) null else current.copy(volumePercent = value).also { current = it }
        override fun adjustVolumePercent(delta: Int) = if (fail) null else current.copy(volumePercent = (current.volumePercent + delta).coerceIn(0, 100)).also { current = it }
        override fun setAlertFrequency(value: AlertFrequency) = if (fail) null else current.copy(alertFrequency = value).also { current = it }
        override fun setCautionEnabled(value: Boolean) = if (fail) null else current.copy(cautionEnabled = value).also { current = it }
    }
    private lateinit var feedbackSettings: FakeFeedbackSettings
    private val testNowMs = 10_000L
    private var testClockMs = testNowMs

    @Before
    fun setUp() {
        emittedSnapshots.clear()
        vibrationCount = 0
        suppressionState = false
        feedbackSettings = FakeFeedbackSettings()
        testClockMs = testNowMs
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
            clockRealtimeMs = { testClockMs },
            clockElapsedNanos = { testClockMs * 1_000_000L },
            feedbackSettingsOverride = feedbackSettings,
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
    fun finalized_genericNavigationFollowUpDefaultsToNaverWithoutCloudFallback() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()

        coordinator.finalized("길안내해줘")
        assertEquals("어디로 갈까요? 목적지를 말씀해 주세요.", coordinator.snapshot.message)
        assertEquals(0L, coordinator.snapshot.cloudQuestionId)

        coordinator.finalized("강남역")
        assertTrue(coordinator.snapshot.externalNavId > 0L)
        assertEquals("naver", coordinator.snapshot.externalNavProvider)
        assertEquals("강남역", coordinator.snapshot.externalNavDestination)
        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        coordinator.close()
    }

    @Test
    fun inputStarted_cancelsPendingExternalNavigation() {
        val coordinator = createCoordinator(audible = false)
        coordinator.started()
        coordinator.finalized("네이버 지도로 강남역 길안내")
        assertTrue(coordinator.snapshot.externalNavId > 0L)

        coordinator.inputStarted()

        assertEquals(0L, coordinator.snapshot.externalNavId)
        assertEquals("", coordinator.snapshot.externalNavDestination)
        coordinator.close()
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
    fun finalized_conversationalQuery_fallsBackToChatGptCloudQuestion() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()

        coordinator.finalized("오늘 서울 날씨 어때?")

        assertTrue("Conversational query should assign a cloudQuestionId", coordinator.snapshot.cloudQuestionId > 0L)
        assertEquals("text", coordinator.snapshot.cloudQuestionType)
        assertEquals("오늘 서울 날씨 어때?", coordinator.snapshot.cloudQuestionPrompt)
        assertTrue(coordinator.snapshot.processing)
        assertEquals("질문 처리 중", coordinator.snapshot.message)
        assertTrue(emittedSnapshots.any { it.cloudQuestionId == coordinator.snapshot.cloudQuestionId && it.processing })
        assertTrue(emittedSnapshots.none { it.message == "질문을 확인하고 있어요." })
        coordinator.close()
    }

    @Test
    fun finalized_explicitPhotoQuestion_staysProcessingWithoutProgressAcknowledgment() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        emittedSnapshots.clear()

        coordinator.finalized("사진 찍어서 알려줘")

        assertTrue("Photo query should assign a cloudQuestionId", coordinator.snapshot.cloudQuestionId > 0L)
        assertEquals("photo", coordinator.snapshot.cloudQuestionType)
        assertTrue(coordinator.snapshot.processing)
        assertEquals("사진 질문 처리 중", coordinator.snapshot.message)
        assertTrue(emittedSnapshots.any { it.cloudQuestionId == coordinator.snapshot.cloudQuestionId && it.processing })
        assertTrue(emittedSnapshots.none { it.message == "사진을 촬영하여 ChatGPT에 질문을 보냅니다." })
        coordinator.close()
    }

    @Test
    fun speakCloudResponse_assistantOutputCannotExecuteAppCommands() {
        val coordinator = createCoordinator(audible = true)
        coordinator.started()
        coordinator.finalized("오늘 날씨 어때")
        val qId = coordinator.snapshot.cloudQuestionId

        // Assistant outputs a string that matches an app command like "홈으로 가"
        coordinator.speakCloudResponse(qId, "홈으로 가")

        // Should speak the response text, NOT navigate screen to home
        assertEquals("홈으로 가", coordinator.snapshot.message)
        assertEquals(0L, coordinator.snapshot.navigationId)
        assertFalse(coordinator.snapshot.processing)
        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        coordinator.close()
    }

    @Test
    fun reportCloudError_clearsProcessingAndDeliversActualError() {
        val coordinator = createCoordinator(audible = false)
        coordinator.started()
        coordinator.finalized("오늘 날씨 어때")
        val qId = coordinator.snapshot.cloudQuestionId

        coordinator.reportCloudError(qId, "질문을 처리하지 못했어요.")

        assertEquals("질문을 처리하지 못했어요.", coordinator.snapshot.message)
        assertFalse(coordinator.snapshot.processing)
        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        coordinator.close()
    }

    @Test fun feedbackCommandsPersistAndReadBackActualSettings() {
        val coordinator = createCoordinator()
        coordinator.started()
        coordinator.finalized("볼륨 50퍼센트")
        assertEquals(50, feedbackSettings.current.volumePercent)
        assertTrue(coordinator.snapshot.message.contains("50퍼센트"))
        coordinator.finalized("볼륨 올려")
        assertEquals(60, feedbackSettings.current.volumePercent)
        coordinator.finalized("경고 빈도 덜 자주")
        assertEquals(AlertFrequency.QUIET, feedbackSettings.current.alertFrequency)
        coordinator.finalized("주의 알림 꺼")
        assertEquals(false, feedbackSettings.current.cautionEnabled)
        coordinator.finalized("현재 알림 설정 알려줘")
        assertTrue(coordinator.snapshot.message.contains("60퍼센트"))
        assertTrue(coordinator.snapshot.message.contains("덜 자주"))
        assertTrue(coordinator.snapshot.message.contains("꺼져"))
        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        feedbackSettings.current = feedbackSettings.current.copy(volumePercent = 95)
        coordinator.finalized("음량 올려")
        assertEquals(100, feedbackSettings.current.volumePercent)
        assertTrue(coordinator.snapshot.message.contains("최대치인 100퍼센트"))
        feedbackSettings.current = feedbackSettings.current.copy(volumePercent = 5)
        coordinator.finalized("음량 낮춰")
        assertEquals(0, feedbackSettings.current.volumePercent)
        assertTrue(coordinator.snapshot.message.contains("최저치인 0퍼센트"))
        coordinator.close()
    }

    @Test fun failedFeedbackMutationDoesNotClaimSuccessOrUseCloud() {
        val coordinator = createCoordinator()
        coordinator.started()
        feedbackSettings.fail = true
        coordinator.finalized("음량 45퍼센트")
        assertTrue(coordinator.snapshot.message.contains("저장하거나 읽지 못했어요"))
        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        assertEquals(100, feedbackSettings.current.volumePercent)
        coordinator.finalized("경고 빈도")
        assertTrue(coordinator.snapshot.message.contains("경고 빈도를 자주, 보통, 또는 덜 자주"))
        assertEquals(0L, coordinator.snapshot.cloudQuestionId)
        coordinator.close()
    }

    @Test fun disabledCautionDropsOnlyCautionSeverityBeforeAlertSideEffects() {
        val coordinator = createCoordinator()
        coordinator.started()
        feedbackSettings.current = feedbackSettings.current.copy(cautionEnabled = false)
        emittedSnapshots.clear()
        vibrationCount = 0
        val nowNanos = testNowMs * 1_000_000L
        coordinator.postHazardAlert(1L, 1L, nowNanos, "주의 단계 알림", AlertSeverity.CAUTION)
        assertTrue(emittedSnapshots.isEmpty())
        assertEquals(0, vibrationCount)
        coordinator.postHazardAlert(1L, 2L, nowNanos, "위험 경고", AlertSeverity.WARNING)
        assertEquals("위험 경고", coordinator.snapshot.message)
        assertEquals(1, vibrationCount)
        coordinator.close()
    }

    @Test fun frequentSettingShortensRepeatCooldownForSameWarning() {
        val coordinator = createCoordinator()
        coordinator.started()
        feedbackSettings.current = feedbackSettings.current.copy(alertFrequency = AlertFrequency.FREQUENT)
        val timestamp = testClockMs * 1_000_000L
        coordinator.postHazardAlert(5L, 1L, timestamp, "같은 위험", AlertSeverity.WARNING)
        testClockMs += 1_300L
        coordinator.postHazardAlert(5L, 2L, timestamp, "같은 위험", AlertSeverity.WARNING)
        assertEquals(2, vibrationCount)
        coordinator.close()
    }

    @Test fun warningEscalationBypassesPriorCautionCooldownForSameMessage() {
        val coordinator = createCoordinator()
        coordinator.started()
        val timestamp = testClockMs * 1_000_000L
        coordinator.postHazardAlert(6L, 1L, timestamp, "같은 위험", AlertSeverity.CAUTION)
        coordinator.postHazardAlert(6L, 2L, timestamp, "같은 위험", AlertSeverity.WARNING)
        assertEquals(2, vibrationCount)
        assertEquals("같은 위험", coordinator.snapshot.message)
        coordinator.close()
    }
}
