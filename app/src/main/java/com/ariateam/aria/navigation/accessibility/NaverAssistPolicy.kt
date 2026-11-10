package com.ariateam.aria.navigation.accessibility

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock

/** Pure one-shot guard. Times are monotonic elapsed-realtime milliseconds. */
internal data class NaverAssistRequest(
    val token: String, val destination: String, val expiresAt: Long,
    val armedAtElapsed: Long, val armedAtUptime: Long,
)

internal object NaverAssistPolicy {
    const val NAVER_PACKAGE = "com.nhn.android.nmap"

    fun shouldClick(
        request: NaverAssistRequest?, token: String?, packageName: String?, eventTimeUptime: Long,
        nowElapsed: Long, rootIsCurrent: Boolean, rootWindowId: Int, eventWindowId: Int, destinationText: String?,
        walkingSelected: Boolean, drivingOrTransitSelected: Boolean,
        buttons: List<AssistButton>, consumed: Boolean,
    ): Boolean {
        if (request == null || consumed || token != request.token || !rootIsCurrent) return false
        if (eventTimeUptime < request.armedAtUptime || nowElapsed >= request.expiresAt || packageName != NAVER_PACKAGE) return false
        if (rootWindowId != eventWindowId || !walkingSelected || drivingOrTransitSelected) return false
        if (normalize(destinationText.orEmpty()) != normalize(request.destination)) return false
        val candidates = buttons.filter { it.visible && it.enabled && isStartButton(it.text) }
        return candidates.size == 1
    }

    fun normalize(value: String) = value.filterNot(Char::isWhitespace).lowercase()
    fun isStartButton(value: String) = normalize(value) in setOf("안내시작", "도보안내시작", "길안내시작")

    fun modeEvidence(items: List<Pair<String, Boolean>>): AssistModeEvidence {
        val selectedWalking = items.any { (text, selected) -> selected && normalize(text).let { it.contains("도보") || it.contains("걷기") } }
        val selectedOther = items.any { (text, selected) -> selected && normalize(text).let { it.contains("자동차") || it.contains("운전") || it.contains("대중교통") || it.contains("버스") || it.contains("지하철") } }
        return AssistModeEvidence(selectedWalking, selectedOther)
    }
}

internal data class AssistButton(val text: String, val visible: Boolean, val enabled: Boolean)

internal data class AssistModeEvidence(val walkingSelected: Boolean, val drivingOrTransitSelected: Boolean)

/** Pure synchronized request lifecycle; consume() fences cancellation/replacement before action. */
internal class NaverAssistRequestStore {
    @Volatile private var pending: NaverAssistRequest? = null
    @Synchronized fun arm(request: NaverAssistRequest) { pending = request }
    @Synchronized fun cancel(token: String? = null) { if (token == null || pending?.token == token) pending = null }
    fun current(nowElapsed: Long): NaverAssistRequest? = pending?.takeIf { nowElapsed < it.expiresAt }
    @Synchronized fun consume(token: String, nowElapsed: Long, action: () -> Boolean): Boolean {
        val current = pending ?: return false
        if (current.token != token || nowElapsed >= current.expiresAt) return false
        pending = null
        return action()
    }
    fun isPending(token: String) = pending?.token == token
}

/** Accepts only the first fresh Naver window-state transition; content changes alone never establish handoff. */
internal class NaverAssistHandoffState(private val ownPackage: String) {
    private var request: NaverAssistRequest? = null
    private var handoffToken: String? = null
    private var windowId: Int? = null
    @Synchronized fun arm(value: NaverAssistRequest) { request = value; handoffToken = null; windowId = null }
    @Synchronized fun cancel() { request = null; handoffToken = null; windowId = null }
    @Synchronized fun isHandedOff(token: String) = handoffToken == token
    @Synchronized fun observe(
        packageName: String?, isWindowState: Boolean, eventTimeUptime: Long,
        rootPackage: String?, rootWindowId: Int, eventWindowId: Int,
    ): Boolean {
        val active = request ?: return false
        if (packageName == ownPackage) return false
        if (packageName != NaverAssistPolicy.NAVER_PACKAGE) { cancel(); return false }
        if (eventTimeUptime < active.armedAtUptime || rootPackage != NaverAssistPolicy.NAVER_PACKAGE || rootWindowId != eventWindowId) return false
        if (handoffToken == active.token) return windowId == eventWindowId
        if (!isWindowState) return false
        handoffToken = active.token
        windowId = eventWindowId
        return true
    }
}

/** Process-local pending request exposed only through the app's existing non-exported provider. */
object NaverAssistBridge {
    const val METHOD_ARM = "naverAssistArm"
    const val METHOD_CANCEL = "naverAssistCancel"
    private const val AUTH = ".runtime.provider"
    private val store = NaverAssistRequestStore()

    fun arm(context: Context, token: String, destination: String): Boolean {
        val extras = Bundle().apply { putString("token", token); putString("destination", destination) }
        return runCatching {
            context.contentResolver.call(Uri.parse("content://${context.packageName}$AUTH"), METHOD_ARM, null, extras)
                ?.getBoolean("armed", false) == true
        }.getOrDefault(false)
    }

    fun cancel(context: Context) {
        runCatching { context.contentResolver.call(Uri.parse("content://${context.packageName}$AUTH"), METHOD_CANCEL, null, null) }
    }

    internal fun handle(method: String, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_ARM -> {
                val token = extras?.getString("token").orEmpty()
                val destination = extras?.getString("destination").orEmpty()
                if (token.isBlank() || destination.isBlank()) return Bundle().apply { putBoolean("armed", false) }
                val now = SystemClock.elapsedRealtime()
                store.arm(NaverAssistRequest(token, destination, now + NaverWalkingAccessibilityService.TIMEOUT_MS, now, SystemClock.uptimeMillis()))
                Bundle().apply { putBoolean("armed", true) }
            }
            METHOD_CANCEL -> { store.cancel(); Bundle().apply { putBoolean("cancelled", true) } }
            else -> null
        }
    }

    internal fun current(): NaverAssistRequest? = store.current(SystemClock.elapsedRealtime())
    internal fun isPending(token: String) = store.isPending(token)
    internal fun consume(token: String, action: () -> Boolean) = store.consume(token, SystemClock.elapsedRealtime(), action)
    internal fun clear(token: String? = null) = store.cancel(token)
}
