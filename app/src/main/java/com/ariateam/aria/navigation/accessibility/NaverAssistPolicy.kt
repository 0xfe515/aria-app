package com.ariateam.aria.navigation.accessibility

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock

internal enum class AssistStage {
    SEARCH_RESULTS,
    ORIGIN_INPUT,
    ORIGIN_PICKER,
    AWAITING_WALKING_SELECTION,
    START_GUIDANCE,
}

/** Pure multi-step assist request. Times are monotonic elapsed-realtime milliseconds. */
internal data class NaverAssistRequest(
    val token: String,
    val destination: String,
    val expiresAt: Long,
    val armedAtElapsed: Long,
    val armedAtUptime: Long,
    val stepCount: Int = 0,
    val stage: AssistStage = AssistStage.SEARCH_RESULTS,
)

internal sealed interface AssistDecision {
    data object None : AssistDecision
    data class ClickSearchDirections(val targetId: Int, val candidateName: String) : AssistDecision
    data class ClickOriginEntry(val targetId: Int) : AssistDecision
    data class ClickMyLocation(val targetId: Int) : AssistDecision
    data class ClickWalkingTab(val targetId: Int) : AssistDecision
    data class ClickStartGuidance(val targetId: Int) : AssistDecision
}

internal object NaverAssistPolicy {
    const val NAVER_PACKAGE = "com.nhn.android.nmap"
    const val MAX_ASSIST_STEPS = 5

    fun normalize(value: String): String = value.filterNot(Char::isWhitespace).lowercase()

    fun isStartButton(value: String): Boolean =
        normalize(value) in setOf("안내시작", "도보안내시작", "길안내시작")

    fun modeEvidence(items: List<Pair<String, Boolean>>): AssistModeEvidence {
        val selectedWalking = items.any { (text, selected) ->
            selected && normalize(text).let { it.contains("도보") || it.contains("걷기") }
        }
        val selectedOther = items.any { (text, selected) ->
            selected && normalize(text).let {
                it.contains("자동차") || it.contains("운전") || it.contains("대중교통") ||
                    it.contains("버스") || it.contains("지하철")
            }
        }
        return AssistModeEvidence(selectedWalking, selectedOther)
    }

    fun isDestinationMatch(candidate: String, destination: String): Boolean {
        val c = normalize(candidate)
        val d = normalize(destination)
        if (c.isBlank() || d.isBlank()) return false
        return c == d
    }

    /** Legacy single-step entry point preserved for backward compatibility. */
    fun shouldClick(
        request: NaverAssistRequest?,
        token: String?,
        packageName: String?,
        eventTimeUptime: Long,
        nowElapsed: Long,
        rootIsCurrent: Boolean,
        rootWindowId: Int,
        eventWindowId: Int,
        destinationText: String?,
        walkingSelected: Boolean,
        drivingOrTransitSelected: Boolean,
        buttons: List<AssistButton>,
        consumed: Boolean,
    ): Boolean {
        if (request == null || consumed || token != request.token || !rootIsCurrent) return false
        if (eventTimeUptime < request.armedAtUptime || nowElapsed >= request.expiresAt || packageName != NAVER_PACKAGE) return false
        if (rootWindowId != eventWindowId || !walkingSelected || drivingOrTransitSelected) return false
        if (normalize(destinationText.orEmpty()) != normalize(request.destination)) return false
        val candidates = buttons.filter { it.visible && it.enabled && isStartButton(it.text) }
        return candidates.size == 1
    }

    /** Multi-step state machine evaluation across search results, origin selection, and route planning. */
    fun evaluate(
        request: NaverAssistRequest?,
        token: String?,
        packageName: String?,
        eventTimeUptime: Long,
        nowElapsed: Long,
        rootIsCurrent: Boolean,
        rootWindowId: Int,
        eventWindowId: Int,
        tree: List<AssistTreeNode>,
        consumed: Boolean,
    ): AssistDecision {
        if (request == null || consumed || token != request.token || !rootIsCurrent) return AssistDecision.None
        if (eventTimeUptime < request.armedAtUptime || nowElapsed >= request.expiresAt || packageName != NAVER_PACKAGE) return AssistDecision.None
        if (rootWindowId != eventWindowId) return AssistDecision.None
        if (request.stepCount >= MAX_ASSIST_STEPS) return AssistDecision.None

        val byId = tree.associateBy { it.id }

        // Determine high-level screen mode
        val hasModeTabs = tree.any {
            it.visible && normalize(it.label).let { l ->
                l == "대중교통" || l == "자동차" || l == "자전거" || l.contains("도보")
            }
        }

        // On route screen, scope exact destination match to the route header's destination (second) input field
        val hasMatchingDestination = if (hasModeTabs) {
            val routeDest = findRouteDestination(tree, byId)
            if (routeDest != null) {
                isDestinationMatch(routeDest, request.destination)
            } else {
                tree.any {
                    it.visible && isDestinationMatch(it.label, request.destination) &&
                        !normalize(it.label).contains("출발지") &&
                        !normalize(it.label).contains("전환")
                }
            }
        } else {
            tree.any { it.visible && isDestinationMatch(it.label, request.destination) }
        }
        val originEmptyNode = tree.firstOrNull { it.visible && normalize(it.label) == "출발지입력" }

        // 1. Stage: Route Planning screen (has mode tabs)
        if (hasModeTabs) {
            if (hasMatchingDestination && originEmptyNode != null) {
                // Must be in SEARCH_RESULTS or ORIGIN_INPUT stage to enter origin picker
                if (request.stage == AssistStage.SEARCH_RESULTS || request.stage == AssistStage.ORIGIN_INPUT) {
                    val target = NaverAssistTargetSelector.findClickableTarget(
                        originEmptyNode, byId, packageName, rootWindowId,
                    )
                    if (target != null) {
                        return AssistDecision.ClickOriginEntry(target.id)
                    }
                }
            }

            // Mode evidence strictly using actual selected state
            val modes = tree.filter { it.visible && it.label.isNotBlank() }
                .map { it.label to it.selected }
            val modeEvidence = modeEvidence(modes)

            // Start guidance check
            val startButtonTargets = NaverAssistTargetSelector.select(tree, NAVER_PACKAGE, rootWindowId)
            val eligibleButtons = startButtonTargets.mapNotNull { id ->
                byId[id]?.let {
                    // Target returned by select is the validated clickable parent (which is unlabeled in Naver UI);
                    // the canonical verified label evidence verified by the selector is "안내시작".
                    val verifiedLabel = if (isStartButton(it.label)) it.label else "안내시작"
                    AssistButton(verifiedLabel, it.visible, it.enabled)
                }
            }

            if (hasMatchingDestination && originEmptyNode == null && modeEvidence.walkingSelected &&
                !modeEvidence.drivingOrTransitSelected && eligibleButtons.size == 1
            ) {
                val candidate = eligibleButtons.single()
                if (shouldClick(
                        request, token, packageName, eventTimeUptime, nowElapsed,
                        rootIsCurrent, rootWindowId, eventWindowId, request.destination,
                        modeEvidence.walkingSelected, modeEvidence.drivingOrTransitSelected,
                        listOf(candidate), consumed,
                    )
                ) {
                    return AssistDecision.ClickStartGuidance(startButtonTargets.single())
                }
            }

            // Mode switch to walking if not selected (gated to prevent replay before selected state updates)
            if (hasMatchingDestination && originEmptyNode == null && !modeEvidence.walkingSelected) {
                if (request.stage != AssistStage.AWAITING_WALKING_SELECTION) {
                    val walkingTab = tree.firstOrNull {
                        it.visible && it.enabled && normalize(it.label).contains("도보")
                    }
                    if (walkingTab != null) {
                        val target = NaverAssistTargetSelector.findClickableTarget(
                            walkingTab, byId, packageName, rootWindowId,
                        )
                        if (target != null) {
                            return AssistDecision.ClickWalkingTab(target.id)
                        }
                    }
                }
            }
            return AssistDecision.None
        }

        // 2. Stage: Origin selection screen (no mode tabs, gated by ORIGIN_PICKER stage)
        val isOriginPickerScreen = !hasModeTabs && (request.stage == AssistStage.ORIGIN_PICKER) &&
            tree.any { it.visible && (it.viewIdResourceName?.endsWith("edit_text_search") == true || normalize(it.label) == "출발지입력") } &&
            tree.any { it.visible && (it.viewIdResourceName?.endsWith("btn_back") == true || normalize(it.label) in setOf("뒤로", "뒤로가기")) }

        if (isOriginPickerScreen) {
            val myLocationNode = tree.firstOrNull {
                it.visible && it.enabled && (normalize(it.label) in setOf("내위치", "내위치선택") ||
                    it.viewIdResourceName?.endsWith("btn_my_location") == true ||
                    it.viewIdResourceName?.endsWith("tv_my_location") == true)
            }
            if (myLocationNode != null) {
                val target = NaverAssistTargetSelector.findClickableTarget(
                    myLocationNode, byId, packageName, rootWindowId,
                )
                if (target != null) {
                    return AssistDecision.ClickMyLocation(target.id)
                }
            }
        }

        // 3. Stage: Search results screen (gated by SEARCH_RESULTS stage)
        if (request.stage == AssistStage.SEARCH_RESULTS) {
            val searchDirectionsDecision = findMatchingSearchDirections(
                tree, byId, request.destination, packageName, rootWindowId,
            )
            if (searchDirectionsDecision != null) {
                return searchDirectionsDecision
            }
        }

        return AssistDecision.None
    }

    private fun findMatchingSearchDirections(
        tree: List<AssistTreeNode>,
        byId: Map<Int, AssistTreeNode>,
        destination: String,
        packageName: String,
        windowId: Int,
    ): AssistDecision? {
        val navButtons = tree.filter {
            it.visible && it.enabled && it.clickable && it.packageName == packageName &&
                it.windowId == windowId && normalize(it.label) == "길찾기"
        }
        if (navButtons.isEmpty()) return null

        val childrenMap = mutableMapOf<Int?, MutableList<AssistTreeNode>>()
        for (n in tree) {
            childrenMap.getOrPut(n.parentId) { mutableListOf() }.add(n)
        }

        fun getDescendants(parentId: Int): List<AssistTreeNode> {
            val res = mutableListOf<AssistTreeNode>()
            val stack = mutableListOf<AssistTreeNode>()
            childrenMap[parentId]?.let(stack::addAll)
            while (stack.isNotEmpty()) {
                val curr = stack.removeAt(stack.size - 1)
                res.add(curr)
                childrenMap[curr.id]?.let(stack::addAll)
            }
            return res
        }

        val CARD_CONTAINER_CLASSES = setOf(
            "listview", "recyclerview", "webview", "scrollview", "viewpager",
        )

        val matchingCards = mutableListOf<Pair<AssistTreeNode, String>>()
        for (btn in navButtons) {
            var curr: AssistTreeNode = btn
            var cardNode: AssistTreeNode? = null

            // Ascend to find the immediate card container, stopping BEFORE list/scroll/web containers or root
            while (curr.parentId != null) {
                val p = byId[curr.parentId] ?: break
                val pClass = p.className.orEmpty().lowercase()
                val isContainer = CARD_CONTAINER_CLASSES.any { pClass.contains(it) } ||
                    p.parentId == null // Stop before global window root

                if (isContainer) {
                    cardNode = curr // curr is the direct child of the list container = the card
                    break
                }

                val pButtons = getDescendants(p.id).filter { it.clickable && normalize(it.label) == "길찾기" }
                if (pButtons.size > 1) {
                    // Exceeded single-card boundary to multi-card parent; curr is the card
                    cardNode = curr
                    break
                }
                curr = p
            }

            if (cardNode == null) {
                cardNode = curr
            }

            // Never search outside the card boundary (never search global root or list container)
            if (cardNode.parentId != null) {
                val cardDescendants = listOf(cardNode) + getDescendants(cardNode.id)
                val matchingTexts = cardDescendants.filter { it.visible && isDestinationMatch(it.label, destination) }
                if (matchingTexts.isNotEmpty()) {
                    matchingCards.add(btn to matchingTexts.first().label)
                }
            }
        }

        // Must be unambiguous: exactly 1 matching card
        if (matchingCards.size == 1) {
            val (targetBtn, matchedLabel) = matchingCards.single()
            return AssistDecision.ClickSearchDirections(targetBtn.id, matchedLabel)
        }

        return null
    }

    internal fun findRouteDestination(tree: List<AssistTreeNode>, byId: Map<Int, AssistTreeNode>): String? {
        val childrenMap = mutableMapOf<Int?, MutableList<AssistTreeNode>>()
        for (n in tree) {
            childrenMap.getOrPut(n.parentId) { mutableListOf() }.add(n)
        }

        fun getDescendants(parentId: Int): List<AssistTreeNode> {
            val res = mutableListOf<AssistTreeNode>()
            val stack = mutableListOf<AssistTreeNode>()
            childrenMap[parentId]?.let(stack::addAll)
            while (stack.isNotEmpty()) {
                val curr = stack.removeAt(stack.size - 1)
                res.add(curr)
                childrenMap[curr.id]?.let(stack::addAll)
            }
            return res
        }

        val switchNode = tree.firstOrNull {
            it.visible && normalize(it.label).let { l ->
                l.contains("출발지") && l.contains("도착지") && l.contains("전환")
            }
        } ?: return null

        var curr: AssistTreeNode? = switchNode
        while (curr != null) {
            val p = curr.parentId?.let { byId[it] } ?: break
            val directChildren = childrenMap[p.id].orEmpty()
            val inputFields = mutableListOf<String>()

            for (child in directChildren) {
                if (!child.visible) continue
                val descTexts = (listOf(child) + getDescendants(child.id))
                    .map { it.label }
                    .filter { it.isNotBlank() }
                val joined = descTexts.joinToString(" ")
                val normJoined = normalize(joined)
                if (normJoined.contains("전환") || normJoined.contains("경유지") ||
                    normJoined.contains("닫기") || normJoined.contains("더보기") ||
                    normJoined.contains("출입구") || normJoined.contains("옵션")
                ) {
                    continue
                }
                if (descTexts.isNotEmpty()) {
                    inputFields.add(descTexts.first())
                }
            }

            if (inputFields.size >= 2) {
                // First field is origin, second field is destination!
                return inputFields[1]
            }
            curr = p
        }
        return null
    }
}

/** Bounded accessibility-tree snapshot used to resolve labels to actionable nodes. */
internal data class AssistTreeNode(
    val id: Int,
    val parentId: Int?,
    val label: String,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val packageName: String?,
    val windowId: Int,
    val selected: Boolean = false,
    val className: String? = null,
    val viewIdResourceName: String? = null,
)

internal object NaverAssistTargetSelector {
    private const val MAX_ANCESTORS = 1

    fun select(nodes: List<AssistTreeNode>, packageName: String, windowId: Int): Set<Int> {
        val byId = nodes.associateBy { it.id }
        val targets = mutableSetOf<Int>()
        for (labelNode in nodes) {
            if (!labelNode.visible || !labelNode.enabled || labelNode.packageName != packageName || labelNode.windowId != windowId ||
                !NaverAssistPolicy.isStartButton(labelNode.label)) continue
            var candidate: AssistTreeNode? = labelNode
            var steps = 0
            while (candidate != null && steps <= MAX_ANCESTORS) {
                if (candidate.visible && candidate.enabled && candidate.clickable &&
                    candidate.packageName == packageName && candidate.windowId == windowId &&
                    (candidate.id == labelNode.id || !hasUnrelatedActionableChild(candidate, labelNode, nodes, byId))) {
                    targets += candidate.id
                    break
                }
                if (steps == MAX_ANCESTORS) break
                candidate = candidate.parentId?.let(byId::get)
                steps++
            }
        }
        return targets
    }

    fun findClickableTarget(
        startNode: AssistTreeNode,
        byId: Map<Int, AssistTreeNode>,
        packageName: String,
        windowId: Int,
    ): AssistTreeNode? {
        var candidate: AssistTreeNode? = startNode
        var steps = 0
        while (candidate != null && steps <= 3) {
            if (candidate.visible && candidate.enabled && candidate.clickable &&
                candidate.packageName == packageName && candidate.windowId == windowId
            ) {
                return candidate
            }
            candidate = candidate.parentId?.let(byId::get)
            steps++
        }
        return null
    }

    private fun hasUnrelatedActionableChild(
        candidate: AssistTreeNode,
        matchedLabel: AssistTreeNode,
        nodes: List<AssistTreeNode>,
        byId: Map<Int, AssistTreeNode>,
    ): Boolean = nodes.any { descendant ->
        if (descendant.id == matchedLabel.id || !descendant.visible || !isDescendantOf(descendant, candidate, byId)) {
            false
        } else {
            descendant.clickable || (descendant.label.isNotBlank() && !NaverAssistPolicy.isStartButton(descendant.label))
        }
    }

    private fun isDescendantOf(node: AssistTreeNode, ancestor: AssistTreeNode, byId: Map<Int, AssistTreeNode>): Boolean {
        var parent = node.parentId?.let(byId::get)
        while (parent != null) {
            if (parent.id == ancestor.id) return true
            parent = parent.parentId?.let(byId::get)
        }
        return false
    }
}

internal data class AssistButton(val text: String, val visible: Boolean, val enabled: Boolean)

internal data class AssistModeEvidence(val walkingSelected: Boolean, val drivingOrTransitSelected: Boolean)

/** Pure synchronized request lifecycle; consume() fences cancellation/replacement before action. */
internal class NaverAssistRequestStore {
    @Volatile private var pending: NaverAssistRequest? = null

    @Synchronized fun arm(request: NaverAssistRequest) { pending = request }
    @Synchronized fun cancel(token: String? = null) {
        if (token == null || pending?.token == token) pending = null
    }
    fun current(nowElapsed: Long): NaverAssistRequest? = pending?.takeIf { nowElapsed < it.expiresAt }

    @Synchronized fun tryStep(
        token: String,
        expectedStage: AssistStage,
        nextStage: AssistStage,
        nowElapsed: Long,
        action: () -> Boolean,
    ): Boolean {
        val current = pending ?: return false
        if (current.token != token || nowElapsed >= current.expiresAt) return false
        if (current.stage != expectedStage || current.stepCount >= NaverAssistPolicy.MAX_ASSIST_STEPS) return false
        val success = action()
        if (success) {
            pending = current.copy(
                stepCount = current.stepCount + 1,
                stage = nextStage,
            )
        }
        return success
    }

    @Synchronized fun consume(token: String, nowElapsed: Long, action: () -> Boolean): Boolean {
        val current = pending ?: return false
        if (current.token != token || nowElapsed >= current.expiresAt) return false
        pending = null
        return action()
    }

    fun isPending(token: String): Boolean = pending?.token == token
}

/** Accepts only fresh Naver window-state transitions; allows transition between Naver activities/windows. */
internal class NaverAssistHandoffState(private val ownPackage: String) {
    private var request: NaverAssistRequest? = null
    private var handoffToken: String? = null
    private var windowId: Int? = null

    @Synchronized fun arm(value: NaverAssistRequest) {
        request = value
        handoffToken = null
        windowId = null
    }

    @Synchronized fun cancel() {
        request = null
        handoffToken = null
        windowId = null
    }

    @Synchronized fun isHandedOff(token: String): Boolean = handoffToken == token

    @Synchronized fun observe(
        packageName: String?,
        isWindowState: Boolean,
        eventTimeUptime: Long,
        rootPackage: String?,
        rootWindowId: Int,
        eventWindowId: Int,
    ): Boolean {
        val active = request ?: return false
        if (packageName == ownPackage) return false
        if (packageName != NaverAssistPolicy.NAVER_PACKAGE) {
            cancel()
            return false
        }
        if (eventTimeUptime < active.armedAtUptime || rootPackage != NaverAssistPolicy.NAVER_PACKAGE || rootWindowId != eventWindowId) return false
        if (handoffToken == active.token) {
            if (windowId == eventWindowId) return true
            if (isWindowState) {
                windowId = eventWindowId
                return true
            }
            return false
        }
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
            METHOD_CANCEL -> {
                store.cancel()
                Bundle().apply { putBoolean("cancelled", true) }
            }
            else -> null
        }
    }

    internal fun current(): NaverAssistRequest? = store.current(SystemClock.elapsedRealtime())
    internal fun isPending(token: String): Boolean = store.isPending(token)
    internal fun tryStep(token: String, expectedStage: AssistStage, nextStage: AssistStage, action: () -> Boolean): Boolean =
        store.tryStep(token, expectedStage, nextStage, SystemClock.elapsedRealtime(), action)
    internal fun consume(token: String, action: () -> Boolean): Boolean =
        store.consume(token, SystemClock.elapsedRealtime(), action)
    internal fun clear(token: String? = null) = store.cancel(token)
}
