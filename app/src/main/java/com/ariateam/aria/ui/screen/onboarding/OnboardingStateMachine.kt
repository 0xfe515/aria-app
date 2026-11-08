package com.ariateam.aria.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import com.ariateam.aria.distance.BodyMountHeightModel
import com.ariateam.aria.distance.BodyMountPosition
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.BodyMountProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pure onboarding state machine.
 *
 * Owns the first-run flow steps Welcome -> AskHeight -> ConfirmHeight -> AskMount (if fresh) ->
 * Saving -> Done, together with the parsed stature/mount candidates.
 *
 * Mount is accepted once without redundant confirmation; stature is confirmed with single-decimal
 * formatting. If a valid profile was previously saved, mount is preserved and skipped upon stature
 * confirmation; save errors preserve mount and allow coherent retry without re-asking.
 *
 * Public actions:
 *   - [submit]: parse recognized or manually entered text and advance based on the
 *     current step (height / mount / explicit yes-no confirmation).
 *   - [retry]: clear the current error, reset the step inputs and offer another attempt.
 *   - [onPromptFinished]: called when a TTS prompt finishes; Welcome advances to AskHeight.
 *
 * Confirmation accepts explicit yes/no only; ambiguous or unknown responses are rejected
 * (never substring-matched). All state changes go through the public actions — there are
 * no composition-time mutations.
 */
class OnboardingStateMachine(
    private val profileStore: BodyMountProfileStore,
    private val stringProvider: (key: String) -> String,
) : ViewModel() {

    companion object {
        /** Maximum failed submission attempts before the error/retry path is offered. */
        private const val MAX_RETRIES = 3
    }

    data class UiState(
        val step: Step = Step.Welcome,
        val heightInput: String = "",
        val mountInput: String = "",
        val statureCm: Float? = null,
        val mountPosition: BodyMountPosition? = null,
        val error: String? = null,
        val retryCount: Int = 0,
    )

    private fun initialMountPosition(): BodyMountPosition? =
        profileStore.profile.value?.takeIf { it.isValid }?.position

    private val _uiState = MutableStateFlow(UiState(mountPosition = initialMountPosition()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Submit recognized or manually entered text for the current step. */
    fun submit(text: String) {
        val input = text.trim()
        when (val current = _uiState.value.step) {
            Step.Welcome -> Unit
            Step.AskHeight -> submitHeight(input)
            Step.AskMount -> submitMount(input)
            Step.ConfirmHeight -> {
                val nextStep = if (_uiState.value.mountPosition != null) Step.Saving else Step.AskMount
                submitConfirmation(input, yesStep = nextStep, noStep = Step.AskHeight)
            }
            Step.ConfirmMount -> submitConfirmation(input, yesStep = Step.Saving, noStep = Step.AskMount)
            Step.Saving -> {
                if (_uiState.value.error != null) {
                    retry()
                }
            }
            Step.Done -> Unit
        }
    }

    /** Clear the current error, reset the current step inputs and offer another attempt. */
    fun retry() {
        val current = _uiState.value
        when (current.step) {
            Step.AskHeight -> _uiState.update { it.copy(error = null, retryCount = 0, heightInput = "") }
            Step.AskMount -> _uiState.update { it.copy(error = null, retryCount = 0, mountInput = "") }
            Step.ConfirmHeight ->
                _uiState.update { it.copy(step = Step.AskHeight, error = null, retryCount = 0, heightInput = "") }
            Step.ConfirmMount ->
                _uiState.update { it.copy(step = Step.AskMount, error = null, retryCount = 0, mountInput = "") }
            Step.Saving -> {
                _uiState.update { it.copy(error = null) }
                attemptSave()
            }
            Step.Welcome, Step.Done -> Unit
        }
    }

    /** Called when the current TTS prompt finishes speaking (real engine callback, not a timer). */
    fun onPromptFinished() {
        _uiState.update { current ->
            if (current.step == Step.Welcome) {
                current.copy(step = Step.AskHeight, error = null, retryCount = 0)
            } else {
                current
            }
        }
    }

    /** Reset to a fresh Welcome state (used when onboarding is (re)started, never mid-flow). */
    fun reset() {
        _uiState.value = UiState(mountPosition = initialMountPosition())
    }

    // ---- private actions ----

    private fun submitHeight(input: String) {
        val parsed = parseHeight(input)
        if (parsed != null) {
            _uiState.update {
                it.copy(
                    step = Step.ConfirmHeight,
                    statureCm = parsed,
                    heightInput = input,
                    error = null,
                    retryCount = 0,
                )
            }
        } else {
            failCurrent(Step.AskHeight, "onboarding_error_stature", heightInput = input)
        }
    }

    private fun submitMount(input: String) {
        val parsed = parseMount(input)
        if (parsed != null) {
            _uiState.update {
                it.copy(
                    step = Step.Saving,
                    mountPosition = parsed,
                    mountInput = input,
                    error = null,
                    retryCount = 0,
                )
            }
            attemptSave()
        } else {
            failCurrent(Step.AskMount, "onboarding_error_mount", mountInput = input)
        }
    }

    private fun submitConfirmation(input: String, yesStep: Step, noStep: Step) {
        val confirmed = parseConfirmation(input)
        when (confirmed) {
            true -> {
                _uiState.update { it.copy(step = yesStep, error = null, retryCount = 0) }
                if (yesStep == Step.Saving) {
                    attemptSave()
                }
            }
            false -> _uiState.update { it.copy(step = noStep, error = null, retryCount = 0) }
            null -> failCurrent(_uiState.value.step, "onboarding_error_confirmation")
        }
    }

    /** Synchronously persist the confirmed profile; Done only if save returns true. */
    private fun attemptSave() {
        val current = _uiState.value
        val stature = current.statureCm
        val position = current.mountPosition
        if (stature == null || position == null) {
            _uiState.update {
                it.copy(
                    step = if (stature == null) Step.AskHeight else Step.AskMount,
                    error = stringProvider("onboarding_error_save"),
                    retryCount = it.retryCount + 1,
                )
            }
            return
        }
        val success = profileStore.save(BodyMountProfile(statureCm = stature, position = position))
        _uiState.update {
            if (success) {
                it.copy(step = Step.Done, error = null)
            } else {
                it.copy(
                    step = Step.Saving,
                    error = stringProvider("onboarding_error_save"),
                    retryCount = it.retryCount + 1,
                )
            }
        }
    }

    private fun failCurrent(
        step: Step,
        errorKey: String,
        heightInput: String = "",
        mountInput: String = "",
    ) {
        _uiState.update { current ->
            val newRetryCount = current.retryCount + 1
            current.copy(
                step = step,
                heightInput = if (step == Step.AskHeight) heightInput else current.heightInput,
                mountInput = if (step == Step.AskMount) mountInput else current.mountInput,
                error = stringProvider(errorKey),
                retryCount = newRetryCount,
            )
        }
    }

    // ---- parsing ----

    /**
     * Parse height from: 170 / 170cm / 170 센티미터 / 백칠십 / 백 칠십 센티미터 /
     * 일미터 칠십센티미터. Range 120..220. Rejects ambiguous multiple heights and
     * out-of-range values.
     */
    private fun parseHeight(input: String): Float? {
        if (input.isBlank()) return null
        val cleaned = input.trim()

        // 1. Numeric: "170", "170cm", "170 센티미터", "170 cm"
        val numericRegex = Regex("""^(\d+(?:\.\d+)?)\s*(?:cm|센티미터)?$""")
        val numericMatch = numericRegex.matchEntire(cleaned)
        if (numericMatch != null) {
            val value = numericMatch.groupValues[1].toFloatOrNull() ?: return null
            return if (value.isFinite() && value in BodyMountHeightModel.MIN_STATURE_CM..BodyMountHeightModel.MAX_STATURE_CM) {
                value
            } else {
                null
            }
        }

        // 2. Reject ambiguous multiple heights.
        if (Regex("""\d+""").findAll(cleaned).count() > 1) return null

        // 3. Korean number forms (strict bounded grammar).
        return parseKoreanHeight(cleaned.replace(Regex("\\s+"), ""))
    }

    /**
     * Strict bounded Korean height grammar.
     *
     * Valid shape (whitespace removed): an optional [n]미터 group (미터 adds n*100 cm), followed by
     * an optional [n?백] [n?십] [n] remainder and an optional "센티미터" unit label. Each base
     * (미터, 백, 십) and the unit digits appear at most once, in order; the entire string must be
     * consumed. "센티미터" is a unit label and adds nothing. Repeated/malformed units (백백,
     * 일미터일미터, 삼백, 백천, ...) and out-of-range results are rejected.
     *
     * Accepts: 일미터 칠십센티미터 (170), 백칠십 (170), 백칠십 센티미터 (170), 이백 (200), 백 (100->out of range).
     */
    private fun parseKoreanHeight(normalized: String): Float? {
        var i = 0
        var total = 0

        // Optional 미터 group: [n]미터 (미터 adds n * 100 cm; "미터" is 2 chars)
        val (d1, l1) = readKoreanUnit(normalized, i)
        val meterStart = i + l1
        if (meterStart < normalized.length && normalized.substring(meterStart).startsWith("미터")) {
            total += d1 * 100
            i = meterStart + 2
        }

        // Remainder: [n?백] [n?십] [n] then optional "센티미터" unit label ("백"/"천"/"십" are 1 char each)
        val (d2, l2) = readKoreanUnit(normalized, i)
        val hundredStart = i + l2
        if (hundredStart < normalized.length) {
            when {
                normalized.substring(hundredStart).startsWith("백") -> {
                    total += d2 * 100
                    i = hundredStart + 1
                }
                normalized.substring(hundredStart).startsWith("천") -> {
                    total += d2 * 1000
                    i = hundredStart + 1
                }
            }
        }
        val (d3, l3) = readKoreanUnit(normalized, i)
        val tenStart = i + l3
        if (tenStart < normalized.length && normalized.substring(tenStart).startsWith("십")) {
            total += d3 * 10
            i = tenStart + 1
        }
        val (d4, l4) = readKoreanUnit(normalized, i)
        if (l4 > 0) {
            total += d4
            i += l4
        }
        if (i < normalized.length && normalized.substring(i).startsWith("센티미터")) {
            i += 4  // "센티미터" is 4 chars
        }

        val result = total.toFloat()
        return if (i == normalized.length && result in BodyMountHeightModel.MIN_STATURE_CM..BodyMountHeightModel.MAX_STATURE_CM) {
            result
        } else {
            null
        }
    }

    /**
     * Reads a Korean unit digit (일~구) at position [i]. Returns (value, consumedLength). When no
     * unit digit is present returns (1, 0) — an implicit 1 that only combines with a following base
     * word (백/십/미터/센티미터). Never matches 십/백/천 themselves (those are bases, handled above).
     */
    private fun readKoreanUnit(s: String, i: Int): Pair<Int, Int> {
        val koreanDigits = mapOf(
            "일" to 1,
            "이" to 2,
            "삼" to 3,
            "사" to 4,
            "오" to 5,
            "육" to 6,
            "칠" to 7,
            "팔" to 8,
            "구" to 9,
        )
        for ((word, value) in koreanDigits.entries.sortedByDescending { it.key.length }) {
            if (s.substring(i).startsWith(word)) {
                return Pair(value, word.length)
            }
        }
        return Pair(1, 0) // implicit 1 before a base word
    }

    /** Exact mount match only: 어깨 / 가슴. */
    private fun parseMount(input: String): BodyMountPosition? {
        return when (input.trim()) {
            "어깨" -> BodyMountPosition.SHOULDER
            "가슴" -> BodyMountPosition.CHEST
            else -> null
        }
    }

    /**
     * Explicit yes/no only: 예 / 네 / yes vs 아니오 / 아니요 / no.
     * Returns null for empty, unknown, or ambiguous (both yes and no) input — never a
     * substring match on 네.
     */
    private fun parseConfirmation(input: String): Boolean? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null
        val isYes = cleaned == "예" || cleaned == "네" || cleaned.equals("yes", ignoreCase = true)
        val isNo = cleaned == "아니오" || cleaned == "아니요" || cleaned.equals("no", ignoreCase = true)
        return when {
            isYes && isNo -> null // both yes and no -> ambiguous, reject
            isYes -> true
            isNo -> false
            else -> null
        }
    }

    enum class Step {
        Welcome,
        AskHeight,
        ConfirmHeight,
        AskMount,
        ConfirmMount,
        Saving,
        Done,
    }
}
