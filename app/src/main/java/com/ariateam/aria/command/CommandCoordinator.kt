package com.ariateam.aria.command

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ariateam.aria.MainActivity
import com.ariateam.aria.R
import com.ariateam.aria.navigation.DestinationValidator
import com.ariateam.aria.navigation.NavigationCommandParser
import com.ariateam.aria.navigation.NavigationProvider
import com.ariateam.aria.navigation.accessibility.NaverWalkingAccessibilityService
import com.ariateam.aria.settings.AlertFrequency
import com.ariateam.aria.settings.FeedbackSettings
import com.ariateam.aria.settings.FeedbackSettingsClient
import com.ariateam.aria.hazard.AlertSeverity

/** Narrow settings seam for deterministic coordinator tests. */
internal interface FeedbackSettingsAccess : AutoCloseable {
    val cachedSettings: FeedbackSettings? get() = null
    fun read(): FeedbackSettings?
    fun setVolumePercent(value: Int): FeedbackSettings?
    fun adjustVolumePercent(delta: Int): FeedbackSettings?
    fun setAlertFrequency(value: AlertFrequency): FeedbackSettings?
    fun setCautionEnabled(value: Boolean): FeedbackSettings?
    override fun close() {}
}

enum class LanguageStatus(val wireValue: Int) {
    Missing(0),
    Preparing(1),
    Ready(2),
    Failed(3);

    companion object {
        fun fromWire(value: Int): LanguageStatus = entries.firstOrNull { it.wireValue == value } ?: Ready
    }
}

/** Snapshot is replayable state; navigation is consumed explicitly, never inferred from text. */
data class CommandSnapshot(
    val message: String = "호출어 뒤에 명령을 말해 주세요",
    val processing: Boolean = false,
    val model: String = "클라우드",
    val runtime: String = "Aria 준비",
    val audible: Boolean = true,
    val navigationId: Long = 0,
    val destination: String = "",
    val ttsAvailable: Boolean = false,
    val eventId: Long = 0L,
    val languageStatus: LanguageStatus = LanguageStatus.Ready,
    val screenVisibilityId: Long = 0L,
    val screenHidden: Boolean = false,
    val externalNavId: Long = 0L,
    val externalNavProvider: String = "",
    val externalNavDestination: String = "",
    val cloudQuestionId: Long = 0L,
    val cloudQuestionType: String = "",
    val cloudQuestionPrompt: String = "",
) {
    fun toBundle() = Bundle().apply {
        putString("message", message); putBoolean("processing", processing)
        putString("model", model); putString("runtime", runtime); putBoolean("audible", audible)
        putLong("navigationId", navigationId); putString("destination", destination)
        putBoolean("ttsAvailable", ttsAvailable)
        putLong("eventId", eventId)
        putInt("languageStatus", languageStatus.wireValue)
        putLong("screenVisibilityId", screenVisibilityId)
        putBoolean("screenHidden", screenHidden)
        putLong("externalNavId", externalNavId)
        putString("externalNavProvider", externalNavProvider)
        putString("externalNavDestination", externalNavDestination)
        putLong("cloudQuestionId", cloudQuestionId)
        putString("cloudQuestionType", cloudQuestionType)
        putString("cloudQuestionPrompt", cloudQuestionPrompt)
    }
    companion object {
        fun fromBundle(value: Bundle?) = if (value == null) CommandSnapshot() else CommandSnapshot(
            value.getString("message").orEmpty(), value.getBoolean("processing"),
            value.getString("model", "클라우드").orEmpty(), value.getString("runtime", "Aria 준비").orEmpty(),
            value.getBoolean("audible", true), value.getLong("navigationId"),
            value.getString("destination").orEmpty(), value.getBoolean("ttsAvailable"),
            value.getLong("eventId"),
            LanguageStatus.fromWire(value.getInt("languageStatus", 2)),
            value.getLong("screenVisibilityId"),
            value.getBoolean("screenHidden", false),
            value.getLong("externalNavId", 0L),
            value.getString("externalNavProvider").orEmpty(),
            value.getString("externalNavDestination").orEmpty(),
            value.getLong("cloudQuestionId", 0L),
            value.getString("cloudQuestionType").orEmpty(),
            value.getString("cloudQuestionPrompt").orEmpty(),
        )
    }
}

class CommandCoordinator internal constructor(
    private val context: Context?,
    private val update: (CommandSnapshot) -> Unit,
    private val soundChanged: (Boolean) -> Unit,
    suppress: (Boolean) -> Unit,
    private val followUp: () -> Unit,
    private val finishFollowUp: () -> Unit,
    private val stopListening: () -> Unit,
    private val vibrate: () -> Unit,
    preferencesOverride: SharedPreferences? = null,
    notificationManagerOverride: NotificationManager? = null,
    private val clockRealtimeMs: () -> Long = {
        try { SystemClock.elapsedRealtime() } catch (_: Throwable) { System.currentTimeMillis() }
    },
    private val clockElapsedNanos: () -> Long = {
        try { SystemClock.elapsedRealtimeNanos() } catch (_: Throwable) { System.nanoTime() }
    },
    speechOverride: CommandSpeech? = null,
    feedbackSettingsOverride: FeedbackSettingsAccess? = null,
) : AutoCloseable {
    private val main: Handler? = try { Handler(Looper.getMainLooper()) } catch (_: Throwable) { null }
    private val preferences = preferencesOverride ?: context?.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    private val notifications = notificationManagerOverride ?: context?.getSystemService(NotificationManager::class.java)
    private val rules = RuleCommandInterpreter()
    private val feedbackSettingsClient = context?.takeIf { feedbackSettingsOverride == null }?.let { appContext ->
        runCatching { FeedbackSettingsClient(appContext) }.getOrNull()
    }
    private val feedbackSettings = feedbackSettingsOverride ?: feedbackSettingsClient?.let { client ->
        object : FeedbackSettingsAccess {
            override val cachedSettings get() = client.settings.value
            override fun read() = client.read()
            override fun setVolumePercent(value: Int) = client.setVolumePercent(value)
            override fun adjustVolumePercent(delta: Int) = client.adjustVolumePercent(delta)
            override fun setAlertFrequency(value: AlertFrequency) = client.setAlertFrequency(value)
            override fun setCautionEnabled(value: Boolean) = client.setCautionEnabled(value)
            override fun close() = client.close()
        }
    }
    private val generation = CommandGeneration()
    private var commandContext = CommandContext()
    private var active = false
    private var visible = false
    private var screen = "home"
    private var screenVisibilityAvailable = false
    private var navigationSequence = System.currentTimeMillis()
    private var awaiting = false
    private var stopPending = false
    private val stopDeadline = Runnable { finishStop() }
    private val speech = speechOverride ?: CommandSpeech(
        context = context,
        suppress = suppress,
        changed = { emit(snapshot) },
    )
    var snapshot = CommandSnapshot(audible = preferences?.getBoolean("audible", true) ?: true)
        private set

    // Hazard warning sequence and dedup tracking
    private var lastHazardSession: Long = 0L
    private var lastHazardSequence: Long = 0L
    private var lastHazardMessage: String = ""
    private var lastHazardTimestamp: Long = 0L
    private var lastHazardSeverity: AlertSeverity = AlertSeverity.NONE
    private var walkingSession: Long = 0L
    private var walkingSequence: Long = 0L
    private var walkingStatusTimestampNanos: Long = 0L
    private var walkingStatus: String = "보행 보조 상태를 아직 받지 못했어요."
    private var walkingStopped = false
    private var pendingCalibrationRequestId = 0L
    private var calibrationTimeout: Runnable? = null

    init {
        try {
            notifications?.createNotificationChannel(NotificationChannel(CHANNEL, "Aria 명령 결과", NotificationManager.IMPORTANCE_DEFAULT))
        } catch (_: Throwable) {}
        soundChanged(snapshot.audible)
    }

    private fun emit(value: CommandSnapshot) {
        snapshot = value.copy(ttsAvailable = speech.available)
        update(snapshot)
    }

    fun started() {
        active = true
    }

    fun retryPreparation() {
        if (!active) active = true
    }

    fun setVisible(value: Boolean) { visible = value }
    fun setScreen(value: String) {
        if (value in setOf("home", "settings")) screen = value
        if (value in setOf("home", "settings", "blocked")) screenVisibilityAvailable = value == "home"
    }

    fun setAudible(enabled: Boolean) {
        preferences?.edit()?.putBoolean("audible", enabled)?.apply()
        soundChanged(enabled)
        if (!enabled) {
            speech.stop()
            if (awaiting) {
                invalidate()
                finishFollowUp()
            }
        }
        emit(snapshot.copy(audible = enabled))
        if (!enabled && stopPending) finishStop()
    }

    /** Called at Waiting -> Dictating, so an old interpretation cannot win while user speaks. */
    fun inputStarted() {
        if (snapshot.externalNavId != 0L) clearExternalNavigation()
        context?.let(NaverWalkingAccessibilityService::cancel)
        if (awaiting) {
            generation.advance()
            speech.stop()
            return
        }
        invalidate()
        speech.stop()
        clearCloudQuestion()
        emit(snapshot.copy(processing = false))
    }

    private fun invalidate() {
        generation.advance()
        awaiting = false
    }

    fun recognitionFailed(message: String) {
        if (!active) return
        invalidate()
        announce(message)
    }

    fun finalized(text: String) {
        if (!active || stopPending || text.isBlank()) return
        invalidate()
        val ticket = generation.advance()
        val now = clockRealtimeMs()

        // 1. Prioritize valid local commands (offline/on-device)
        val result = rules.interpret(text, commandContext, now)
        if (result != Interpretation.Unknown) {
            if (result is Interpretation.Clarify) {
                val pending = NavigationCommandParser.extractPendingTarget(text)
                if (pending != null) {
                    commandContext = CommandContext(pending, now + 30_000)
                }
            }
            handle(result)
            return
        }

        // 2. Reject cancellation, negation, or incomplete local intent before cloud fallback
        if (LocalFallbackPolicy.shouldRejectBeforeCloudFallback(text)) {
            val message = LocalFallbackPolicy.rejectionMessage(text)
            announce(message)
            return
        }

        // 3. Conversational text / questions route directly to ChatGPT cloud
        handleCloudTextQuestion(text)
    }

    private fun handle(result: Interpretation) {
        when (result) {
            is Interpretation.Clarify -> {
                awaiting = true
                val ticket = generation.advance()
                announce(result.question) {
                    if (active && awaiting && generation.accepts(ticket) && snapshot.audible && speech.available) {
                        followUp()
                    }
                }
            }
            Interpretation.Unknown -> announce("지원하지 않는 명령이에요. 도움말이라고 말해 주세요.")
            is Interpretation.Execute -> execute(result.command)
            is Interpretation.NavigateExternal -> navigateExternal(result.provider, result.destination)
            is Interpretation.CloudTextQuestion -> handleCloudTextQuestion(result.question)
            is Interpretation.CloudPhotoQuestion -> handleCloudPhotoQuestion(result.prompt)
            is Interpretation.Feedback -> executeFeedback(result.command)
        }
    }

    private fun execute(command: AppCommand) {
        when (command) {
            AppCommand.Home -> navigate("home")
            AppCommand.Settings -> navigate("settings")
            AppCommand.HideScreen -> setScreenHidden(true)
            AppCommand.ShowScreen -> setScreenHidden(false)
            AppCommand.SoundOn, AppCommand.SoundOff -> {
                val enabled = command == AppCommand.SoundOn
                setAudible(enabled)
                commandContext = CommandContext("sound", clockRealtimeMs() + 30_000)
                announce(if (enabled) "안내 소리를 켰어요." else "안내 소리를 껐어요.")
            }
            AppCommand.Status -> announce("음성 명령 대기 중이에요. 안내 소리는 ${if (snapshot.audible) "켜져" else "꺼져"} 있어요. ${currentWalkingStatus()}")
            AppCommand.WalkingStatus -> announce(currentWalkingStatus())
            AppCommand.ForwardCalibration -> requestForwardCalibration(generateEventId())
            AppCommand.Help -> announce("홈으로 돌아가, 설정 화면 열어, 네이버 지도 또는 카카오맵으로 길안내, 앞에 뭐가 있는지 또는 글씨 읽어줘, 앱 안내 음량 50퍼센트 또는 음량 낮춰, 경고 빈도 덜 자주, 주의 알림 켜기, 현재 알림 설정, 보행 상태, 정면 다시 보정, 취소, 전체 중지를 말할 수 있어요.")
            AppCommand.Cancel -> {
                context?.let(NaverWalkingAccessibilityService::cancel)
                commandContext = CommandContext()
                clearNavigation()
                clearExternalNavigation()
                clearCloudQuestion()
                announce("취소했어요.")
            }
            AppCommand.Stop -> {
                context?.let(NaverWalkingAccessibilityService::cancel)
                commandContext = CommandContext()
                clearNavigation()
                clearExternalNavigation()
                clearCloudQuestion()
                stopPending = true
                main?.postDelayed(stopDeadline, 5_000)
                announce("듣기를 중지해요.") { finishStop() }
            }
        }
    }

    private fun executeFeedback(command: FeedbackCommand) {
        val access = feedbackSettings
        if (access == null) {
            announce("알림 설정에 연결하지 못했어요. 설정 화면에서 다시 시도해 주세요.")
            return
        }
        val updated = try {
            when (command) {
                is FeedbackCommand.SetVolume -> access.setVolumePercent(command.percent)
                is FeedbackCommand.AdjustVolume -> access.adjustVolumePercent(command.deltaPercent)
                is FeedbackCommand.SetFrequency -> access.setAlertFrequency(when (command.frequency) {
                    FeedbackCommand.Frequency.FREQUENT -> AlertFrequency.FREQUENT
                    FeedbackCommand.Frequency.NORMAL -> AlertFrequency.NORMAL
                    FeedbackCommand.Frequency.QUIET -> AlertFrequency.QUIET
                })
                is FeedbackCommand.SetCaution -> access.setCautionEnabled(command.enabled)
                else -> null
            }
        } catch (_: Throwable) { null }
        val result = updated ?: if (command is FeedbackCommand.ReadSettings || command is FeedbackCommand.ReadVolume || command is FeedbackCommand.ReadFrequency || command is FeedbackCommand.ReadCaution) {
            try { access.read() } catch (_: Throwable) { null }
        } else null
        if (result == null) {
            announce("알림 설정을 저장하거나 읽지 못했어요. 다시 시도해 주세요.")
            return
        }
        val message = when (command) {
            is FeedbackCommand.SetVolume, FeedbackCommand.ReadVolume -> "앱 안내 음량은 ${result.volumePercent}퍼센트예요."
            is FeedbackCommand.AdjustVolume -> when {
                command.deltaPercent > 0 && result.volumePercent == 100 -> "앱 안내 음량이 최대치인 100퍼센트에 도달했어요."
                command.deltaPercent < 0 && result.volumePercent == 0 -> "앱 안내 음량이 최저치인 0퍼센트에 도달했어요."
                else -> "앱 안내 음량은 ${result.volumePercent}퍼센트예요."
            }
            is FeedbackCommand.SetFrequency, FeedbackCommand.ReadFrequency -> "경고 빈도는 ${frequencyLabel(result.alertFrequency)}예요."
            is FeedbackCommand.SetCaution, FeedbackCommand.ReadCaution -> "주의 단계 알림은 ${if (result.cautionEnabled) "켜져" else "꺼져"} 있어요."
            else -> "앱 안내 음량은 ${result.volumePercent}퍼센트, 경고 빈도는 ${frequencyLabel(result.alertFrequency)}, 주의 단계 알림은 ${if (result.cautionEnabled) "켜져" else "꺼져"} 있어요."
        }
        announce(message)
    }

    private fun frequencyLabel(frequency: AlertFrequency) = when (frequency) {
        AlertFrequency.FREQUENT -> "자주"
        AlertFrequency.NORMAL -> "보통"
        AlertFrequency.QUIET -> "덜 자주"
    }

    private fun setScreenHidden(hidden: Boolean) {
        if (!visible || screen != "home") {
            announce("홈 화면이 표시될 때 화면 숨김 명령을 사용할 수 있어요.")
            return
        }
        if (hidden && !screenVisibilityAvailable) {
            announce("화면을 숨길 수 없는 상태예요. 화면의 안내를 확인해 주세요.")
            return
        }
        emit(snapshot.copy(screenVisibilityId = generateEventId(), screenHidden = hidden))
        announce(if (hidden) "화면을 숨겼어요. 화면을 탭하거나 화면 보여줘라고 말해 주세요." else "화면을 다시 표시할게요.")
    }

    private fun finishStop() {
        if (!stopPending) return
        stopPending = false
        main?.removeCallbacks(stopDeadline)
        stopListening()
    }

    private fun announce(message: String, started: (() -> Unit)? = null, done: () -> Unit = {}) {
        val nextEventId = generateEventId()
        emit(snapshot.copy(message = message, eventId = nextEventId, processing = false))
        vibrate()
        if (!visible) notifyResult(message)
        if (snapshot.audible) speech.speak(message, SpeechPriority.NormalCommand, started = started, done = done) else { speech.stop(); done() }
    }

    private fun navigate(destination: String) {
        commandContext = CommandContext(destination, clockRealtimeMs() + 30_000)
        val id = ++navigationSequence
        emit(snapshot.copy(navigationId = id, destination = destination))
        main?.postDelayed({ if (snapshot.navigationId == id) clearNavigation() }, 30_000)
        if (!visible) {
            notifyResult("알림을 눌러 ${if (destination == "home") "홈" else "설정"} 화면을 열어 주세요.", id, destination)
        }
        announce(when {
            visible -> "${if (destination == "home") "홈" else "설정"} 화면으로 이동해요."
            notifications?.areNotificationsEnabled() == false -> "알림이 꺼져 있어요. 30초 안에 Aria 앱을 열면 요청한 화면으로 이동해요."
            else -> "화면을 열려면 알림을 눌러 주세요."
        })
    }

    private fun navigateExternal(provider: NavigationProvider, destination: String) {
        context?.let(NaverWalkingAccessibilityService::cancel)
        if (!DestinationValidator.isValid(destination)) {
            announce("목적지를 올바르게 말씀해 주세요.")
            return
        }
        val id = generateEventId()
        commandContext = CommandContext()
        emit(
            snapshot.copy(
                externalNavId = id,
                externalNavProvider = provider.id,
                externalNavDestination = destination,
            )
        )
        main?.postDelayed({
            if (snapshot.externalNavId == id) clearExternalNavigation()
        }, 30_000)

        val message = if (provider == NavigationProvider.Naver) {
            "네이버 지도 도보 경로를 준비해요: $destination."
        } else {
            "${provider.displayName}로 ${destination} 검색을 열어요."
        }
        if (!visible) {
            notifyExternalNav(message, id, provider, destination)
        }
        announce(message)
    }

    fun consumeNavigation(id: Long) {
        if (id != 0L && id == snapshot.navigationId) clearNavigation()
        if (id != 0L && id == snapshot.externalNavId) clearExternalNavigation()
    }

    private fun clearNavigation() {
        emit(snapshot.copy(navigationId = 0, destination = ""))
        notifications?.cancel(NAVIGATION_NOTIFICATION)
    }

    private fun clearExternalNavigation() {
        emit(snapshot.copy(externalNavId = 0L, externalNavProvider = "", externalNavDestination = ""))
        notifications?.cancel(EXTERNAL_NAVIGATION_NOTIFICATION)
    }

    private fun notifyExternalNav(message: String, id: Long, provider: NavigationProvider, destination: String) {
        val ctx = context ?: return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("externalNavId", id)
            putExtra("externalNavProvider", provider.id)
            putExtra("externalNavDestination", destination)
        }
        val pending = PendingIntent.getActivity(
            ctx,
            13,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_wakeword)
            .setContentTitle("Aria 길안내").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        runCatching { notifications?.notify(EXTERNAL_NAVIGATION_NOTIFICATION, notification) }
    }

    private fun notifyResult(message: String, id: Long = 0, destination: String = "") {
        val ctx = context ?: return
        val navigation = id != 0L || snapshot.navigationId != 0L
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (navigation) {
                putExtra("navigationId", if (id != 0L) id else snapshot.navigationId)
                putExtra("destination", destination.ifEmpty { snapshot.destination })
            }
        }
        val pending = PendingIntent.getActivity(ctx, if (navigation) 12 else 11, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_wakeword)
            .setContentTitle("Aria").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        runCatching { notifications?.notify(if (navigation) NAVIGATION_NOTIFICATION else 11, notification) }
    }

    private fun handleCloudTextQuestion(question: String) {
        val id = generateEventId()
        commandContext = CommandContext()
        clearExternalNavigation()
        emit(
            snapshot.copy(
                cloudQuestionId = id,
                cloudQuestionType = "text",
                cloudQuestionPrompt = question,
                message = "질문 처리 중",
                processing = true,
            )
        )
        main?.postDelayed({
            if (snapshot.cloudQuestionId == id) {
                clearCloudQuestion()
                announce("질문 응답 시간이 초과되었어요.")
            }
        }, 60_000)
        // Keep the request active without emitting a progress toast or spoken acknowledgment.
    }

    private fun handleCloudPhotoQuestion(prompt: String) {
        val id = generateEventId()
        commandContext = CommandContext()
        clearExternalNavigation()
        emit(
            snapshot.copy(
                cloudQuestionId = id,
                cloudQuestionType = "photo",
                cloudQuestionPrompt = prompt,
                message = "사진 질문 처리 중",
                processing = true,
            )
        )
        main?.postDelayed({
            if (snapshot.cloudQuestionId == id) {
                clearCloudQuestion()
                announce("사진 질문 응답 시간이 초과되었어요.")
            }
        }, 60_000)
        // Keep the request active without emitting a progress toast or spoken acknowledgment.
    }

    fun consumeCloudQuestion(id: Long) {
        // Keeps question identifier tracked until completion or cancel
    }

    fun cancelCloudQuestion(id: Long) {
        if (id == 0L || id == snapshot.cloudQuestionId) {
            clearCloudQuestion()
            announce("질문을 취소했어요.")
        }
    }

    /**
     * Entry point for background hazard alert events with session/sequence dedup and timestamp freshness.
     */
    fun postHazardAlert(session: Long, sequence: Long, timestampNanos: Long, message: String, severity: AlertSeverity = AlertSeverity.WARNING) {
        if (!active || message.isBlank()) return
        if (severity == AlertSeverity.CAUTION && feedbackSettings?.cachedSettings?.cautionEnabled == false) return

        // 1. Freshness check: reject stale alerts older than 2.0 seconds
        if (timestampNanos > 0L) {
            val nowNanos = clockElapsedNanos()
            val ageNanos = nowNanos - timestampNanos
            if (ageNanos > 2_000_000_000L) {
                return
            }
        }

        // 2. Dedup & sequence check
        val now = clockRealtimeMs()
        if (session == lastHazardSession && session != 0L) {
            if (sequence <= lastHazardSequence && sequence != 0L) {
                return
            }
            if (message == lastHazardMessage) {
                val escalated = severity.rank > lastHazardSeverity.rank
                val baseCooldownMs = if (severity == AlertSeverity.CAUTION) 6_000L else 2_500L
                val frequencyScale = when (feedbackSettings?.cachedSettings?.alertFrequency) {
                    AlertFrequency.FREQUENT -> 0.5
                    AlertFrequency.QUIET -> 2.0
                    else -> 1.0
                }
                if (!escalated && (now - lastHazardTimestamp) < (baseCooldownMs * frequencyScale).toLong()) return
            }
        }

        lastHazardSession = session
        lastHazardSequence = sequence
        lastHazardMessage = message
        lastHazardTimestamp = now
        lastHazardSeverity = severity

        // A hazard invalidates the in-flight question as well as its utterance; late ChatGPT
        // callbacks then fail the existing question-id check and cannot resume speech.
        clearCloudQuestion()

        // 3. Preempt cloud and ordinary speech
        speech.preemptSpeech(SpeechPriority.HazardWarning)

        // 4. Mute / Vibration behavior
        val nextEventId = generateEventId()
        emit(snapshot.copy(message = message, eventId = nextEventId, processing = false))
        vibrate()
        if (snapshot.audible) {
            speech.speak(message, SpeechPriority.HazardWarning)
        } else {
            speech.stop()
        }
    }

    fun updateWalkingAssistState(
        session: Long,
        sequence: Long,
        timestampNanos: Long,
        status: String,
        shouldAnnounce: Boolean,
        stopped: Boolean,
    ) {
        if (!active || session <= 0L || sequence <= 0L || status.isBlank()) return
        val nowNanos = clockElapsedNanos()
        if (timestampNanos <= 0L || timestampNanos > nowNanos + 500_000_000L ||
            nowNanos - timestampNanos > WALKING_STATUS_FRESHNESS_NANOS
        ) return
        if (session < walkingSession || (session == walkingSession && sequence <= walkingSequence)) return
        walkingSession = session
        walkingSequence = sequence
        walkingStatusTimestampNanos = timestampNanos
        walkingStatus = status
        walkingStopped = stopped
        if (shouldAnnounce) announce(status, started = {
            val ctx = context ?: return@announce
            runCatching {
                ctx.sendBroadcast(
                    Intent(ACTION_WALKING_SPEECH_STARTED)
                        .setPackage(ctx.packageName)
                        .putExtra(EXTRA_WALKING_SESSION, session)
                        .putExtra(EXTRA_WALKING_SEQUENCE, sequence)
                        .putExtra(EXTRA_WALKING_SPEECH_STARTED_NANOS, clockElapsedNanos()),
                )
            }
        })
    }

    private fun currentWalkingStatus(): String {
        val age = clockElapsedNanos() - walkingStatusTimestampNanos
        return if (!walkingStopped && (walkingStatusTimestampNanos == 0L || age < 0L || age > WALKING_STATUS_FRESHNESS_NANOS)) {
            "보행 보조 상태를 확인할 수 없어요. 카메라, 모델, 움직임 센서 상태를 확인해 주세요."
        } else walkingStatus
    }

    fun requestForwardCalibration(requestId: Long) {
        if (!active || requestId <= 0L) return
        calibrationTimeout?.let { main?.removeCallbacks(it) }
        pendingCalibrationRequestId = requestId
        val timeout = Runnable {
            if (pendingCalibrationRequestId == requestId) {
                completeForwardCalibration(requestId, successful = false)
            }
        }
        calibrationTimeout = timeout
        main?.postDelayed(timeout, FORWARD_CALIBRATION_TIMEOUT_MS)
        val ctx = context
        val sent = if (ctx != null) {
            runCatching {
                ctx.sendBroadcast(
                    Intent(ACTION_FORWARD_CALIBRATION)
                        .setPackage(ctx.packageName)
                        .putExtra(EXTRA_CALIBRATION_REQUEST_ID, requestId),
                )
                true
            }.getOrDefault(false)
        } else false
        if (sent) announce("정면 보정을 시도합니다.")
        else completeForwardCalibration(requestId, successful = false)
    }

    fun reportForwardCalibrationResult(requestId: Long, successful: Boolean) {
        completeForwardCalibration(requestId, successful)
    }

    private fun completeForwardCalibration(requestId: Long, successful: Boolean) {
        if (!active || requestId <= 0L || requestId != pendingCalibrationRequestId) return
        pendingCalibrationRequestId = 0L
        calibrationTimeout?.let { main?.removeCallbacks(it) }
        calibrationTimeout = null
        val message = if (successful) {
            context?.getString(R.string.walking_forward_calibration_succeeded) ?: "정면 보정이 완료되었습니다."
        } else {
            context?.getString(R.string.walking_forward_calibration_failed)
                ?: "정면 보정을 완료하지 못했습니다. 자세를 안정시키고 다시 시도해 주세요."
        }
        announce(message)
    }

    fun speakHazardWarning(message: String) {
        val nowNanos = clockElapsedNanos()
        postHazardAlert(session = 0L, sequence = 0L, timestampNanos = nowNanos, message = message)
    }

    fun preemptSpeech() {
        speech.preemptSpeech(SpeechPriority.HazardWarning)
    }

    fun speakCloudResponse(requestId: Long, responseText: String) {
        if (!active || requestId == 0L || requestId != snapshot.cloudQuestionId || responseText.isBlank()) {
            return
        }
        val nextEventId = generateEventId()
        emit(
            snapshot.copy(
                message = responseText,
                eventId = nextEventId,
                processing = false,
                cloudQuestionId = 0L,
                cloudQuestionType = "",
                cloudQuestionPrompt = "",
            )
        )
        vibrate()
        if (!visible) notifyResult(responseText)
        if (snapshot.audible) {
            speech.speak(responseText, SpeechPriority.CloudResponse)
        }
    }

    fun reportCloudError(requestId: Long, errorMessage: String) {
        if (!active || requestId == 0L || requestId != snapshot.cloudQuestionId) {
            return
        }
        emit(
            snapshot.copy(
                cloudQuestionId = 0L,
                cloudQuestionType = "",
                cloudQuestionPrompt = "",
                processing = false,
            )
        )
        announce(errorMessage)
    }

    private fun clearCloudQuestion() {
        speech.preemptCloudSpeech()
        if (snapshot.cloudQuestionId != 0L) {
            emit(
                snapshot.copy(
                    cloudQuestionId = 0L,
                    cloudQuestionType = "",
                    cloudQuestionPrompt = "",
                    processing = false,
                )
            )
        }
    }

    fun stopped() {
        context?.let(NaverWalkingAccessibilityService::cancel)
        stopPending = false
        main?.removeCallbacks(stopDeadline)
        calibrationTimeout?.let { main?.removeCallbacks(it) }
        calibrationTimeout = null
        pendingCalibrationRequestId = 0L
        active = false
        invalidate()
        speech.stop()
        clearNavigation()
        clearExternalNavigation()
        clearCloudQuestion()
        emit(snapshot.copy(processing = false, runtime = "Aria 대기"))
    }

    override fun close() {
        stopped()
        speech.close()
        runCatching { feedbackSettings?.close() }
    }

    companion object {
        const val ACTION_FORWARD_CALIBRATION = "com.ariateam.aria.action.FORWARD_CALIBRATION"
        const val EXTRA_CALIBRATION_REQUEST_ID = "calibrationRequestId"
        const val ACTION_WALKING_SPEECH_STARTED = "com.ariateam.aria.action.WALKING_SPEECH_STARTED"
        const val EXTRA_WALKING_SESSION = "walkingSession"
        const val EXTRA_WALKING_SEQUENCE = "walkingSequence"
        const val EXTRA_WALKING_SPEECH_STARTED_NANOS = "walkingSpeechStartedNanos"
        private const val WALKING_STATUS_FRESHNESS_NANOS = 2_000_000_000L
        private const val FORWARD_CALIBRATION_TIMEOUT_MS = 5_000L
        private const val CHANNEL = "aria_commands"
        private const val NAVIGATION_NOTIFICATION = 12
        private const val EXTERNAL_NAVIGATION_NOTIFICATION = 13
        private val eventSequenceLock = Any()
        private var lastGlobalEventId = 0L

        fun generateEventId(): Long = synchronized(eventSequenceLock) {
            val now = try {
                SystemClock.elapsedRealtimeNanos()
            } catch (_: Throwable) {
                System.nanoTime()
            }
            val candidate = if (now > lastGlobalEventId) now else lastGlobalEventId + 1L
            lastGlobalEventId = candidate
            candidate
        }

        internal fun resetGlobalEventIdForTesting(value: Long = 0L) = synchronized(eventSequenceLock) {
            lastGlobalEventId = value
        }
    }
}
