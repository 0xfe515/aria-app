package com.ariateam.aria.navigation.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** Opt-in one-action assist; non-Naver window events are used only to detect handoff/exit. */
class NaverWalkingAccessibilityService : AccessibilityService() {
    private var consumed = false
    private var naverSeen = false
    private var timeoutToken: String? = null
    private var treeComplete = true
    private val handoff by lazy { NaverAssistHandoffState(packageName) }
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable {
        val token = timeoutToken
        if (token != null) {
            val stillPending = NaverAssistBridge.isPending(token)
            disarm(token)
            if (stillPending) Toast.makeText(this, "도보 안내 시작을 확인하지 못했어요. 네이버 지도에서 직접 확인해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        NaverAssistBridge.clear()
        timeoutToken = null
        consumed = false
        naverSeen = false
        handoff.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val active = NaverAssistBridge.current() ?: return
        if (timeoutToken != active.token) {
            timeoutToken = active.token
            consumed = false
            naverSeen = false
            handoff.arm(active)
            handler.removeCallbacks(expiry)
            handler.postDelayed(expiry, (active.expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }
        if (SystemClock.elapsedRealtime() >= active.expiresAt) { expiry.run(); return }
        val pkg = event.packageName?.toString()
        if (pkg != NaverAssistPolicy.NAVER_PACKAGE) {
            if (pkg == packageName) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && handoff.isHandedOff(active.token)) {
                    handoff.cancel()
                    disarm(active.token)
                    Toast.makeText(this, "네이버 도보 안내 화면을 벗어났어요. 네이버 지도에서 직접 확인해 주세요.", Toast.LENGTH_LONG).show()
                }
                return
            }
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) {
                handoff.cancel()
                NaverAssistBridge.clear(active.token)
                disarm(active.token)
                Toast.makeText(this, "네이버 도보 안내 화면을 확인하지 못했어요. 네이버 지도에서 직접 확인해 주세요.", Toast.LENGTH_LONG).show()
            }
            return // Never retrieve or inspect another application's window content.
        }
        if (event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) return
        val root = try { rootInActiveWindow } catch (_: RuntimeException) { disarm(active.token); return } ?: return
        val tree = mutableListOf<AssistTreeNode>()
        val nodeById = mutableMapOf<Int, AccessibilityNodeInfo>()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            treeComplete = true
            val rootPackage = root.packageName?.toString()
            if (!handoff.observe(pkg, event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, event.eventTime,
                    rootPackage, root.windowId, event.windowId)) return
            naverSeen = true
            val texts = mutableListOf<String>()
            val modes = mutableListOf<Pair<String, Boolean>>()
            collect(root, texts, modes, nodes, tree, nodeById, parentId = null, isRoot = true)
            if (!treeComplete) return
            val destinationMatch = texts.firstOrNull { NaverAssistPolicy.normalize(it) == NaverAssistPolicy.normalize(active.destination) }
            val mode = NaverAssistPolicy.modeEvidence(modes)
            val targetIds = NaverAssistTargetSelector.select(tree, NaverAssistPolicy.NAVER_PACKAGE, root.windowId)
            val eligible = targetIds.mapNotNull { id -> nodeById[id]?.let { it to AssistButton("안내시작", it.isVisibleToUser, it.isEnabled) } }
            if (NaverAssistPolicy.shouldClick(
                    active, active.token, pkg, event.eventTime, SystemClock.elapsedRealtime(),
                    root.isVisibleToUser && naverSeen, root.windowId, event.windowId, destinationMatch,
                    mode.walkingSelected, mode.drivingOrTransitSelected, eligible.map { it.second }, consumed,
                )
            ) {
                consumed = true
                val node = eligible.single().first
                val actionResult = NaverAssistBridge.consume(active.token) {
                    runCatching { node.isVisibleToUser && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        .getOrDefault(false)
                }
                disarm(active.token)
                if (actionResult) {
                    Toast.makeText(this, "안내 시작을 눌렀어요. 실제 안내 상태를 확인해 주세요.", Toast.LENGTH_LONG).show()
                } else Toast.makeText(this, "안내 시작을 누르지 못했어요. 네이버 지도에서 직접 시작해 주세요.", Toast.LENGTH_LONG).show()
            }
        } catch (_: RuntimeException) {
            disarm(active.token)
        } finally {
            nodes.asReversed().forEach { node ->
                try { node.recycle() } catch (_: RuntimeException) { disarm(active.token) }
            }
            try { root.recycle() } catch (_: RuntimeException) { disarm(active.token) }
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        modes: MutableList<Pair<String, Boolean>>,
        nodes: MutableList<AccessibilityNodeInfo>,
        tree: MutableList<AssistTreeNode>,
        nodeById: MutableMap<Int, AccessibilityNodeInfo>,
        parentId: Int?,
        isRoot: Boolean = false,
        depth: Int = 0,
    ) {
        if (depth > MAX_TREE_DEPTH || nodes.size >= MAX_TREE_NODES) {
            treeComplete = false
            if (!isRoot) {
                try { node.recycle() } catch (_: RuntimeException) { disarm() }
            }
            return
        }
        val id = tree.size
        if (!isRoot) nodes += node
        nodeById[id] = node
        val text = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
        tree += AssistTreeNode(id, parentId, text, node.isVisibleToUser, node.isEnabled, node.isClickable,
            node.packageName?.toString(), node.windowId)
        if (text.isNotBlank() && node.isVisibleToUser) {
            texts += text
            modes += text to (node.isSelected || node.isChecked)
        }
        for (i in 0 until node.childCount) {
            if (nodes.size >= MAX_TREE_NODES) { treeComplete = false; break }
            val child = node.getChild(i)
            if (child == null) treeComplete = false
            else collect(child, texts, modes, nodes, tree, nodeById, id, depth = depth + 1)
        }
    }

    private fun disarm(token: String? = timeoutToken) {
        if (token != null && timeoutToken != token) {
            NaverAssistBridge.clear(token)
            return
        }
        handler.removeCallbacks(expiry)
        if (token != null) NaverAssistBridge.clear(token)
        else NaverAssistBridge.clear()
        handoff.cancel()
        timeoutToken = null
        consumed = true
        naverSeen = false
    }

    override fun onInterrupt() = disarm()
    override fun onDestroy() { disarm(); super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { disarm(); return super.onUnbind(intent) }

    companion object {
        const val TIMEOUT_MS = 20_000L
        private const val MAX_TREE_DEPTH = 30
        private const val MAX_TREE_NODES = 600

        fun arm(context: Context, token: String, destination: String) = NaverAssistBridge.arm(context, token, destination)
        fun cancel(context: Context) = NaverAssistBridge.cancel(context)
    }
}
