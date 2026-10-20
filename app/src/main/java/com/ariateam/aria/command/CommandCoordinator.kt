package com.ariateam.aria.command

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ariateam.aria.MainActivity
import com.ariateam.aria.R
import org.json.JSONObject

/** Snapshot is replayable state; navigation is consumed explicitly, never inferred from text. */
data class CommandSnapshot(
    val message: String = "호출어 뒤에 명령을 말해 주세요",
    val processing: Boolean = false,
    val model: String = "모델 미설치",
    val runtime: String = "Gemma 대기",
    val audible: Boolean = true,
    val navigationId: Long = 0,
    val destination: String = "",
    val ttsAvailable: Boolean = false,
) {
    fun toBundle() = Bundle().apply {
        putString("message", message); putBoolean("processing", processing)
        putString("model", model); putString("runtime", runtime); putBoolean("audible", audible)
        putLong("navigationId", navigationId); putString("destination", destination)
        putBoolean("ttsAvailable", ttsAvailable)
    }
    companion object {
        fun fromBundle(value: Bundle?) = if (value == null) CommandSnapshot() else CommandSnapshot(
            value.getString("message").orEmpty(), value.getBoolean("processing"),
            value.getString("model").orEmpty(), value.getString("runtime").orEmpty(),
            value.getBoolean("audible", true), value.getLong("navigationId"),
            value.getString("destination").orEmpty(), value.getBoolean("ttsAvailable"),
        )
    }
}

class CommandCoordinator(
    private val context: Context,
    private val update: (CommandSnapshot) -> Unit,
    private val soundChanged: (Boolean) -> Unit,
    suppress: (Boolean) -> Unit,
    private val followUp: () -> Unit,
    private val finishFollowUp: () -> Unit,
    private val stopListening: () -> Unit,
    private val vibrate: () -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val rules = RuleCommandInterpreter()
    private val generation = CommandGeneration()
    private var commandContext = CommandContext()
    private var active = false
    private var visible = false
    private var screen = "home"
    private var navigationSequence = System.currentTimeMillis()
    private var awaiting = false
    private var stopPending = false
    private val stopDeadline = Runnable { finishStop() }
    private val speech = CommandSpeech(context, suppress) { emit(snapshot) }
    var snapshot = CommandSnapshot(audible = preferences.getBoolean("audible", true))
        private set
    private val language = LanguageClient(context) { value -> emit(snapshot.copy(runtime = value)) }
    private val store: GemmaModelStore = GemmaModelStore(context) { value ->
        emit(snapshot.copy(model = value))
        if (active && storeInstalled()) prepareModel()
    }
    private fun storeInstalled() = store.installed()

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Aria 명령 결과", NotificationManager.IMPORTANCE_DEFAULT))
        soundChanged(snapshot.audible)
    }

    private fun emit(value: CommandSnapshot) {
        snapshot = value.copy(ttsAvailable = speech.available)
        update(snapshot)
    }

    fun started() { active = true; prepareModel() }
    private fun prepareModel() { if (store.installed()) language.prepare(store.modelFile.absolutePath) }
    fun setVisible(value: Boolean) { visible = value }
    fun setScreen(value: String) { if (value in setOf("home", "settings")) screen = value }

    fun setAudible(enabled: Boolean) {
        preferences.edit().putBoolean("audible", enabled).apply()
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

    fun download(mobileData: Boolean) = store.start(mobileData)
    fun cancelDownload() = store.cancel()
    fun deleteModel() {
        if (awaiting) finishFollowUp()
        invalidate()
        language.close()
        store.cancel(deleteInstalled = true)
        emit(snapshot.copy(runtime = "Gemma 대기"))
    }

    /** Called at Waiting -> Dictating, so an old interpretation cannot win while user speaks. */
    fun inputStarted() {
        if (awaiting) {
            generation.advance()
            speech.stop()
            return
        }
        invalidate()
        speech.stop()
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
        val now = SystemClock.elapsedRealtime()
        val result = rules.interpret(text, commandContext, now)
        if (result != Interpretation.Unknown) { handle(result); return }
        if (!store.installed()) {
            announce("지원하는 명령을 확인해 주세요. 홈, 설정, 안내 소리, 현재 상태, 도움말을 사용할 수 있어요.")
            return
        }
        if (!language.ready) {
            if (language.preparing) {
                announce("Gemma 모델을 준비 중이에요. 잠시 후 다시 말하거나 기본 명령을 사용해 주세요.")
            } else {
                prepareModel()
                announce("Gemma 모델을 다시 준비하고 있어요. 잠시 후 다시 말하거나 기본 명령을 사용해 주세요.")
            }
            return
        }
        if (language.busy) {
            announce("이전 명령을 아직 처리 중이에요. 잠시 후 다시 말하거나 기본 명령을 사용해 주세요.")
            return
        }
        emit(snapshot.copy(message = "명령 해석 중", processing = true))
        val prompt = buildModelPrompt(text, commandContext.target(now))
        language.interpretOutcome(prompt) { outcome ->
            if (!active || !generation.accepts(ticket)) return@interpretOutcome
            when (outcome) {
                is LanguageOutcome.Success -> handle(parseModelResult(outcome.output))
                is LanguageOutcome.Failure -> announce(
                    when (outcome.reason) {
                        FailureReason.ResponseTimeout ->
                            "Gemma 응답 시간이 초과되어 언어 엔진을 정리했어요. 다음 명령에 다시 준비하며, 지금은 기본 명령을 사용할 수 있어요."
                        FailureReason.MemoryError ->
                            "기기 메모리가 부족해 Gemma 해석을 중단했어요. 기본 명령으로 다시 말해 주세요."
                        FailureReason.Busy ->
                            "이전 명령을 아직 처리 중이에요. 잠시 후 다시 말하거나 기본 명령을 사용해 주세요."
                        FailureReason.NotReady, FailureReason.PreparationTimeout ->
                            "Gemma 모델이 아직 준비되지 않았어요. 기본 명령으로 다시 말해 주세요."
                        FailureReason.ConnectionError, FailureReason.InferenceError ->
                            "Gemma 해석 오류가 발생했어요. 기본 명령으로 다시 말해 주세요."
                    }
                )
            }
        }
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
        }
    }

    private fun execute(command: AppCommand) {
        when (command) {
            AppCommand.Home -> navigate("home")
            AppCommand.Settings -> navigate("settings")
            AppCommand.SoundOn, AppCommand.SoundOff -> {
                val enabled = command == AppCommand.SoundOn
                setAudible(enabled)
                commandContext = CommandContext("sound", SystemClock.elapsedRealtime() + 30_000)
                announce(if (enabled) "안내 소리를 켰어요." else "안내 소리를 껐어요.")
            }
            AppCommand.Status -> announce("음성 명령 대기 중이에요. 안내 소리는 ${if (snapshot.audible) "켜져" else "꺼져"} 있어요. ${snapshot.runtime.substringBefore(" · ")}.")
            AppCommand.Help -> announce("홈으로 가, 설정 열어, 안내 소리 켜 또는 꺼, 현재 상태, 취소, 듣기 중지를 말할 수 있어요.")
            AppCommand.Cancel -> {
                commandContext = CommandContext()
                clearNavigation()
                announce("취소했어요.")
            }
            AppCommand.Stop -> {
                commandContext = CommandContext()
                clearNavigation()
                stopPending = true
                main.postDelayed(stopDeadline, 5_000)
                announce("듣기를 중지해요.") { finishStop() }
            }
        }
    }

    private fun finishStop() {
        if (!stopPending) return
        stopPending = false
        main.removeCallbacks(stopDeadline)
        stopListening()
    }

    private fun announce(message: String, done: () -> Unit = {}) {
        emit(snapshot.copy(message = message, processing = false))
        vibrate()
        if (!visible) notifyResult(message)
        if (snapshot.audible) speech.speak(message, done) else { speech.stop(); done() }
    }

    private fun navigate(destination: String) {
        commandContext = CommandContext(destination, SystemClock.elapsedRealtime() + 30_000)
        val id = ++navigationSequence
        emit(snapshot.copy(navigationId = id, destination = destination))
        main.postDelayed({ if (snapshot.navigationId == id) clearNavigation() }, 30_000)
        if (!visible) {
            notifyResult("알림을 눌러 ${if (destination == "home") "홈" else "설정"} 화면을 열어 주세요.", id, destination)
        }
        announce(when {
            visible -> "${if (destination == "home") "홈" else "설정"} 화면으로 이동해요."
            !notifications.areNotificationsEnabled() -> "알림이 꺼져 있어요. 30초 안에 Aria 앱을 열면 요청한 화면으로 이동해요."
            else -> "화면을 열려면 알림을 눌러 주세요."
        })
    }

    fun consumeNavigation(id: Long) {
        if (id != 0L && id == snapshot.navigationId) clearNavigation()
    }

    private fun clearNavigation() {
        emit(snapshot.copy(navigationId = 0, destination = ""))
        notifications.cancel(NAVIGATION_NOTIFICATION)
    }

    private fun notifyResult(message: String, id: Long = 0, destination: String = "") {
        val navigation = id != 0L || snapshot.navigationId != 0L
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (navigation) {
                putExtra("navigationId", if (id != 0L) id else snapshot.navigationId)
                putExtra("destination", destination.ifEmpty { snapshot.destination })
            }
        }
        val pending = PendingIntent.getActivity(context, if (navigation) 12 else 11, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_wakeword)
            .setContentTitle("Aria").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        runCatching { notifications.notify(if (navigation) NAVIGATION_NOTIFICATION else 11, notification) }
    }

    fun stopped() {
        stopPending = false
        main.removeCallbacks(stopDeadline)
        active = false
        invalidate()
        speech.stop()
        language.close()
        clearNavigation()
        emit(snapshot.copy(processing = false, runtime = "Gemma 대기"))
    }

    override fun close() { stopped(); speech.close(); store.close() }

    companion object {
        private const val CHANNEL = "aria_commands"
        private const val NAVIGATION_NOTIFICATION = 12
        fun parseModelResult(text: String): Interpretation = ModelCommandParser.parse(text)
        internal fun buildModelPrompt(text: String, lastTarget: String?): String =
            JSONObject()
                .put("utterance", text.trim().take(LanguageLimits.MAX_UTTERANCE_CHARS))
                .put("lastTarget", lastTarget ?: JSONObject.NULL)
                .toString()
    }
}
