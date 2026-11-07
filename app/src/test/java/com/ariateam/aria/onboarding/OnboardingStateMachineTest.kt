package com.ariateam.aria.onboarding

import android.content.SharedPreferences
import com.ariateam.aria.distance.BodyMountPosition
import com.ariateam.aria.distance.BodyMountProfileStore
import com.ariateam.aria.ui.screen.onboarding.OnboardingStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused regressions for the onboarding state machine:
 * height/mount/confirmation parsing, step transitions, bounded retries, and
 * persistence gating (save must succeed before completion).
 */
class OnboardingStateMachineTest {

    private class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        var commitShouldFail = false

        override fun getAll(): MutableMap<String, *> = data

        override fun getString(key: String?, defValue: String?): String? {
            val v = data[key] ?: return defValue
            return if (v is String) v else throw ClassCastException()
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException()

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float {
            val v = data[key] ?: return defValue
            return if (v is Float) v else (v as? Number)?.toFloat() ?: throw ClassCastException()
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listeners.remove(listener)
        }

        fun notifyChanged(key: String?) {
            for (l in listeners.toList()) {
                l.onSharedPreferenceChanged(this, key)
            }
        }
    }

    private class FakeEditor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val toRemove = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            throw UnsupportedOperationException()

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) toRemove.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (parent.commitShouldFail) return false
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) parent.data.clear()
            for (r in toRemove) parent.data.remove(r)
            for ((k, v) in pending) parent.data[k] = v
            val changedKeys = toRemove + pending.keys
            for (k in changedKeys) {
                parent.notifyChanged(k)
            }
        }
    }

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: BodyMountProfileStore
    private lateinit var machine: OnboardingStateMachine

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = BodyMountProfileStore(prefs)
        // stringProvider returns the key verbatim; assertions only care about state/errors.
        machine = OnboardingStateMachine(store) { key -> key }
    }

    /** After the spoken greeting completes, the machine is ready to receive a height. */
    private fun goToAskHeight() {
        machine.onPromptFinished()
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
    }

    // ---- step lifecycle ----

    @Test
    fun `initial state is welcome`() {
        assertEquals(OnboardingStateMachine.Step.Welcome, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
    }

    @Test
    fun `on prompt finished advances welcome to ask height`() {
        machine.onPromptFinished()
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
    }

    @Test
    fun `on prompt finished is a no-op outside welcome`() {
        goToAskHeight()
        machine.submit("170")
        machine.onPromptFinished()
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
    }

    // ---- height parsing ----

    @Test
    fun `submit numeric height transitions to confirm height`() {
        goToAskHeight()
        machine.submit("170")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170f, machine.uiState.value.statureCm!!, 0.001f)
        assertNull(machine.uiState.value.error)
    }

    @Test
    fun `submit height with cm suffix`() {
        goToAskHeight()
        machine.submit("170cm")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170f, machine.uiState.value.statureCm!!, 0.001f)
    }

    @Test
    fun `submit height with korean unit`() {
        goToAskHeight()
        machine.submit("170 센티미터")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170f, machine.uiState.value.statureCm!!, 0.001f)
    }

    @Test
    fun `submit korean number height 백칠십`() {
        goToAskHeight()
        machine.submit("백칠십")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170f, machine.uiState.value.statureCm!!, 0.001f)
    }

    @Test
    fun `submit korean number height 백 칠십 센티미터`() {
        goToAskHeight()
        machine.submit("백 칠십 센티미터")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170f, machine.uiState.value.statureCm!!, 0.001f)
    }

    @Test
    fun `submit korean meter height 일미터 칠십센티미터`() {
        goToAskHeight()
        machine.submit("일미터 칠십센티미터")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170f, machine.uiState.value.statureCm!!, 0.001f)
    }

    @Test
    fun `submit height below range stays in ask height with error`() {
        goToAskHeight()
        machine.submit("100")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
        assertNull(machine.uiState.value.statureCm)
    }

    @Test
    fun `submit height above range stays in ask height with error`() {
        goToAskHeight()
        machine.submit("250")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `submit ambiguous multiple heights is rejected`() {
        goToAskHeight()
        machine.submit("170 180")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `submit non numeric height shows error and stays`() {
        goToAskHeight()
        machine.submit("abc")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    // ---- confirmation parsing ----

    @Test
    fun `confirmation yes variants advance`() {
        for (yes in listOf("예", "네", "yes", "YES")) {
            val m = OnboardingStateMachine(BodyMountProfileStore(FakeSharedPreferences())) { key -> key }
            m.onPromptFinished()
            m.submit("170")
            m.submit(yes)
            assertEquals("yes=$yes should advance to ask mount", OnboardingStateMachine.Step.AskMount, m.uiState.value.step)
        }
    }

    @Test
    fun `confirmation no variants return to ask height`() {
        for (no in listOf("아니오", "아니요", "no", "NO")) {
            val m = OnboardingStateMachine(BodyMountProfileStore(FakeSharedPreferences())) { key -> key }
            m.onPromptFinished()
            m.submit("170")
            m.submit(no)
            assertEquals("no=$no should return to ask height", OnboardingStateMachine.Step.AskHeight, m.uiState.value.step)
        }
    }

    @Test
    fun `confirmation ambiguous both yes and no is rejected`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예 아니오")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `confirmation unknown is rejected`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("maybe")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `confirmation empty is rejected`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    // ---- mount parsing ----

    @Test
    fun `submit shoulder mount transitions to confirm mount`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        assertEquals(OnboardingStateMachine.Step.ConfirmMount, machine.uiState.value.step)
        assertEquals(BodyMountPosition.SHOULDER, machine.uiState.value.mountPosition)
    }

    @Test
    fun `submit chest mount transitions to confirm mount`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("가슴")
        assertEquals(OnboardingStateMachine.Step.ConfirmMount, machine.uiState.value.step)
        assertEquals(BodyMountPosition.CHEST, machine.uiState.value.mountPosition)
    }

    @Test
    fun `submit invalid mount stays in ask mount with error`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("배")
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    // ---- confirmation navigation ----

    @Test
    fun `confirm yes on height advances to ask mount`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)
    }

    @Test
    fun `confirm no on height returns to ask height`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("아니오")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
    }

    @Test
    fun `confirm no on mount returns to ask mount`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        machine.submit("아니오")
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)
    }

    // ---- persistence gating ----

    @Test
    fun `confirm yes on mount completes only when save succeeds`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        machine.submit("예")
        // Save is synchronous: saving is transient and the machine ends in Done.
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
    }

    @Test
    fun `save failure does not complete and stays in ask mount`() {
        prefs.commitShouldFail = true
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        machine.submit("예")
        // Save failed: not Done, back to ask mount with an error
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `retry clears error and resets inputs`() {
        goToAskHeight()
        machine.submit("abc")
        assertNotNull(machine.uiState.value.error)
        machine.retry()
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
        assertEquals(0, machine.uiState.value.retryCount)
    }

    @Test
    fun `retry on mount stays in ask mount and clears error`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("배")
        assertNotNull(machine.uiState.value.error)
        machine.retry()
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
        assertEquals(0, machine.uiState.value.retryCount)
    }

    // ---- strict Korean height grammar ----

    @Test
    fun `submit korean height 이백 is 200`() {
        goToAskHeight()
        machine.submit("이백")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(200f, machine.uiState.value.statureCm!!, 0.001f)
    }

    @Test
    fun `submit korean height 백 is below range and rejected`() {
        goToAskHeight()
        machine.submit("백")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
        assertNull(machine.uiState.value.statureCm)
    }

    @Test
    fun `submit malformed repeated hundreds 백백 is rejected`() {
        goToAskHeight()
        machine.submit("백백")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `submit malformed repeated meter 일미터일미터 is rejected`() {
        goToAskHeight()
        machine.submit("일미터일미터")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `submit malformed 삼백 is out of range and rejected`() {
        goToAskHeight()
        machine.submit("삼백")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `submit ambiguous korean height is rejected`() {
        goToAskHeight()
        machine.submit("백 이백")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
    }

    @Test
    fun `submit korean height 일미터 is below range and rejected`() {
        goToAskHeight()
        machine.submit("일미터")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
        assertNull(machine.uiState.value.statureCm)
    }

    @Test
    fun `submit korean height 천 is out of range and rejected`() {
        goToAskHeight()
        machine.submit("천")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
        assertNull(machine.uiState.value.statureCm)
    }

    // ---- lifecycle / policy seam ----

    @Test
    fun `reset returns retained machine to a fresh welcome`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        machine.submit("예")
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)

        machine.reset()
        assertEquals(OnboardingStateMachine.Step.Welcome, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
        assertNull(machine.uiState.value.statureCm)
        assertNull(machine.uiState.value.mountPosition)
    }

    @Test
    fun `invalid submit does not advance and retry then valid submit advances`() {
        goToAskHeight()
        machine.submit("abc")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)

        machine.retry()
        machine.submit("170")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
    }

    @Test
    fun `completion only after explicit yes on both confirmations`() {
        // No to height -> stays in ask height (no premature completion)
        goToAskHeight()
        machine.submit("170")
        machine.submit("아니오")
        assertEquals(OnboardingStateMachine.Step.AskHeight, machine.uiState.value.step)

        // Yes to height, no to mount -> back to ask mount, not Done
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        machine.submit("아니오")
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)

        // Yes to both -> Done
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        machine.submit("예")
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)
    }
}
