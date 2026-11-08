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

    // ---- mount parsing and simplified flow ----

    @Test
    fun `fresh setup shoulder mount saves directly without redundant confirm mount`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)
        machine.submit("어깨")
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)
        assertEquals(BodyMountPosition.SHOULDER, machine.uiState.value.mountPosition)
        assertEquals(170f, store.profile.value!!.statureCm, 0.001f)
        assertEquals(BodyMountPosition.SHOULDER, store.profile.value!!.position)
    }

    @Test
    fun `fresh setup chest mount saves directly without redundant confirm mount`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        assertEquals(OnboardingStateMachine.Step.AskMount, machine.uiState.value.step)
        machine.submit("가슴")
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)
        assertEquals(BodyMountPosition.CHEST, machine.uiState.value.mountPosition)
        assertEquals(170f, store.profile.value!!.statureCm, 0.001f)
        assertEquals(BodyMountPosition.CHEST, store.profile.value!!.position)
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
    fun `confirm yes on height advances to ask mount for fresh user`() {
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

    // ---- persistence gating ----

    @Test
    fun `mount selection completes only when save succeeds`() {
        goToAskHeight()
        machine.submit("170")
        machine.submit("예")
        machine.submit("어깨")
        // Save is synchronous: saving is transient and the machine ends in Done.
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
    }

    @Test
    fun `save failure preserves mount and retry does not re-ask mount`() {
        prefs.commitShouldFail = true
        goToAskHeight()
        machine.submit("170.0")
        machine.submit("예")
        machine.submit("어깨")
        // Save failed: not Done, stays in Saving with error without re-asking mount
        assertEquals(OnboardingStateMachine.Step.Saving, machine.uiState.value.step)
        assertNotNull(machine.uiState.value.error)
        assertEquals(BodyMountPosition.SHOULDER, machine.uiState.value.mountPosition)

        // Clear failure and retry
        prefs.commitShouldFail = false
        machine.retry()
        // Save succeeded without going back to AskMount
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
        assertEquals(170f, store.profile.value!!.statureCm, 0.001f)
        assertEquals(BodyMountPosition.SHOULDER, store.profile.value!!.position)
    }

    @Test
    fun `saved mount save failure preserves saved mount and retry does not re-ask mount`() {
        store.save(com.ariateam.aria.distance.BodyMountProfile(165f, BodyMountPosition.CHEST))
        val failMachine = OnboardingStateMachine(store) { key -> key }
        failMachine.onPromptFinished()
        failMachine.submit("173")
        prefs.commitShouldFail = true
        failMachine.submit("예")

        assertEquals(OnboardingStateMachine.Step.Saving, failMachine.uiState.value.step)
        assertNotNull(failMachine.uiState.value.error)
        assertEquals(BodyMountPosition.CHEST, failMachine.uiState.value.mountPosition)

        // Clear failure and retry
        prefs.commitShouldFail = false
        failMachine.retry()
        assertEquals(OnboardingStateMachine.Step.Done, failMachine.uiState.value.step)
        assertNull(failMachine.uiState.value.error)
        assertEquals(173f, store.profile.value!!.statureCm, 0.001f)
        assertEquals(BodyMountPosition.CHEST, store.profile.value!!.position)
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
        assertEquals(OnboardingStateMachine.Step.Done, machine.uiState.value.step)

        machine.reset()
        assertEquals(OnboardingStateMachine.Step.Welcome, machine.uiState.value.step)
        assertNull(machine.uiState.value.error)
        assertNull(machine.uiState.value.statureCm)
        // Saved profile in store has SHOULDER, so reset re-reads the valid saved mount
        assertEquals(BodyMountPosition.SHOULDER, machine.uiState.value.mountPosition)

        // If store is cleared, reset has no mount assumption
        store.clear()
        machine.reset()
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
    fun `saved shoulder mount flow is height-only and skips mount question`() {
        store.save(com.ariateam.aria.distance.BodyMountProfile(165f, BodyMountPosition.SHOULDER))
        val reenteredMachine = OnboardingStateMachine(store) { key -> key }
        reenteredMachine.onPromptFinished()
        assertEquals(OnboardingStateMachine.Step.AskHeight, reenteredMachine.uiState.value.step)
        reenteredMachine.submit("172")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, reenteredMachine.uiState.value.step)
        reenteredMachine.submit("예")
        assertEquals(OnboardingStateMachine.Step.Done, reenteredMachine.uiState.value.step)
        assertEquals(172f, store.profile.value!!.statureCm, 0.001f)
        assertEquals(BodyMountPosition.SHOULDER, store.profile.value!!.position)
    }

    @Test
    fun `saved chest mount flow is height-only and skips mount question`() {
        store.save(com.ariateam.aria.distance.BodyMountProfile(160f, BodyMountPosition.CHEST))
        val reenteredMachine = OnboardingStateMachine(store) { key -> key }
        reenteredMachine.onPromptFinished()
        assertEquals(OnboardingStateMachine.Step.AskHeight, reenteredMachine.uiState.value.step)
        reenteredMachine.submit("168.5")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, reenteredMachine.uiState.value.step)
        reenteredMachine.submit("예")
        assertEquals(OnboardingStateMachine.Step.Done, reenteredMachine.uiState.value.step)
        assertEquals(168.5f, store.profile.value!!.statureCm, 0.001f)
        assertEquals(BodyMountPosition.CHEST, store.profile.value!!.position)
    }

    @Test
    fun `rejecting height keeps mount`() {
        store.save(com.ariateam.aria.distance.BodyMountProfile(160f, BodyMountPosition.CHEST))
        val reenteredMachine = OnboardingStateMachine(store) { key -> key }
        reenteredMachine.onPromptFinished()
        reenteredMachine.submit("170")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, reenteredMachine.uiState.value.step)
        // User rejects height
        reenteredMachine.submit("아니오")
        assertEquals(OnboardingStateMachine.Step.AskHeight, reenteredMachine.uiState.value.step)
        assertEquals(BodyMountPosition.CHEST, reenteredMachine.uiState.value.mountPosition)

        // Enter new height and confirm
        reenteredMachine.submit("175.5")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, reenteredMachine.uiState.value.step)
        reenteredMachine.submit("예")
        assertEquals(OnboardingStateMachine.Step.Done, reenteredMachine.uiState.value.step)
        assertEquals(175.5f, store.profile.value!!.statureCm, 0.001f)
        assertEquals(BodyMountPosition.CHEST, store.profile.value!!.position)
    }

    @Test
    fun `no mount assumption on no saved data`() {
        assertNull(store.profile.value)
        assertNull(machine.uiState.value.mountPosition)
    }

    @Test
    fun `reset rereads valid saved mount`() {
        assertNull(machine.uiState.value.mountPosition)
        store.save(com.ariateam.aria.distance.BodyMountProfile(165f, BodyMountPosition.CHEST))
        machine.reset()
        assertEquals(BodyMountPosition.CHEST, machine.uiState.value.mountPosition)
    }

    @Test
    fun `submit decimal numeric heights 170_0 and 170_5`() {
        goToAskHeight()
        machine.submit("170.0")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170.0f, machine.uiState.value.statureCm!!, 0.001f)

        machine.submit("아니오")
        machine.submit("170.5")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170.5f, machine.uiState.value.statureCm!!, 0.001f)

        machine.submit("아니오")
        machine.submit("170.5cm")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170.5f, machine.uiState.value.statureCm!!, 0.001f)

        machine.submit("아니오")
        machine.submit("170.5 센티미터")
        assertEquals(OnboardingStateMachine.Step.ConfirmHeight, machine.uiState.value.step)
        assertEquals(170.5f, machine.uiState.value.statureCm!!, 0.001f)
    }

    @Test
    fun `production onboarding_confirm_stature resource formats single decimal without six decimals`() {
        val candidates = listOf(
            java.io.File("app/src/main/res/values/onboarding_strings.xml"),
            java.io.File("src/main/res/values/onboarding_strings.xml"),
        )
        val stringsXml = candidates.firstOrNull { it.exists() }
        assertNotNull("onboarding_strings.xml must exist at relative path", stringsXml)
        val content = stringsXml!!.readText()
        val match = Regex("""<string\s+name="onboarding_confirm_stature">(.*?)</string>""").find(content)
        assertNotNull("onboarding_confirm_stature resource must exist", match)
        val template = match!!.groupValues[1]

        assertTrue("Template must contain %1$.1f", template.contains("%1$.1f"))
        org.junit.Assert.assertFalse("Template must not contain %1f", template.contains("%1f"))

        val formatted170_0 = String.format(java.util.Locale.KOREAN, template, 170.0f)
        val formatted170_5 = String.format(java.util.Locale.KOREAN, template, 170.5f)

        assertEquals("키가 170.0 센티미터가 맞습니까? 예 또는 아니오로 답변해 주세요.", formatted170_0)
        assertEquals("키가 170.5 센티미터가 맞습니까? 예 또는 아니오로 답변해 주세요.", formatted170_5)
        org.junit.Assert.assertFalse("Must not contain six decimals", formatted170_0.contains("170.000000"))
    }

    @Test
    fun `production walking strings have safety suffix removed from ready recovered and active`() {
        val candidates = listOf(
            java.io.File("app/src/main/res/values/walking_strings.xml"),
            java.io.File("src/main/res/values/walking_strings.xml"),
        )
        val stringsXml = candidates.firstOrNull { it.exists() }
        assertNotNull("walking_strings.xml must exist at relative path", stringsXml)
        val content = stringsXml!!.readText()

        fun extract(name: String): String {
            val m = Regex("""<string\s+name="$name">(.*?)</string>""").find(content)
            assertNotNull("$name must exist", m)
            return m!!.groupValues[1]
        }

        val ready = extract("walking_assist_ready")
        val recovered = extract("walking_assist_recovered")
        val active = extract("walking_assist_status_active")

        assertEquals("보행 보조가 활성화되었습니다.", ready)
        assertEquals("보행 보조 상태가 복구되었습니다.", recovered)
        assertEquals("보행 보조가 활성입니다.", active)

        val forbidden = "안전을 보장하지 않습니다"
        org.junit.Assert.assertFalse(ready.contains(forbidden))
        org.junit.Assert.assertFalse(recovered.contains(forbidden))
        org.junit.Assert.assertFalse(active.contains(forbidden))
    }
}
