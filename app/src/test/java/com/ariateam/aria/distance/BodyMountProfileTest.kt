package com.ariateam.aria.distance

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMountProfileTest {

    @Test
    fun documentedAistRatios_exactConstants() {
        assertEquals(0.8035428f, BodyMountHeightModel.SHOULDER_RATIO, 1e-6f)
        assertEquals(0.7164686f, BodyMountHeightModel.CHEST_RATIO, 1e-6f)
        assertEquals(120f, BodyMountHeightModel.MIN_STATURE_CM, 1e-6f)
        assertEquals(220f, BodyMountHeightModel.MAX_STATURE_CM, 1e-6f)
    }

    @Test
    fun standardStature170cm_estimatesExpectedHeights() {
        // 170cm Shoulder: 1.70 * 0.8035428 = 1.3660228m
        val shoulderProfile = BodyMountProfile(statureCm = 170f, position = BodyMountPosition.SHOULDER)
        assertTrue(shoulderProfile.isValid)
        assertNotNull(shoulderProfile.estimatedCameraHeightMeters)
        assertEquals(1.3660228f, shoulderProfile.estimatedCameraHeightMeters!!, 1e-4f)

        // 170cm Chest: 1.70 * 0.7164686 = 1.2179966m
        val chestProfile = BodyMountProfile(statureCm = 170f, position = BodyMountPosition.CHEST)
        assertTrue(chestProfile.isValid)
        assertNotNull(chestProfile.estimatedCameraHeightMeters)
        assertEquals(1.2179966f, chestProfile.estimatedCameraHeightMeters!!, 1e-4f)
    }

    @Test
    fun boundaryStatureValues_validOnlyWithin120to220() {
        // Lower bound 120cm
        val p120 = BodyMountProfile(120f, BodyMountPosition.SHOULDER)
        assertTrue(p120.isValid)
        assertNotNull(p120.estimatedCameraHeightMeters)

        // Below lower bound 119.9cm
        val p119 = BodyMountProfile(119.9f, BodyMountPosition.SHOULDER)
        assertFalse(p119.isValid)
        assertNull(p119.estimatedCameraHeightMeters)

        // Upper bound 220cm
        val p220 = BodyMountProfile(220f, BodyMountPosition.CHEST)
        assertTrue(p220.isValid)
        assertNotNull(p220.estimatedCameraHeightMeters)

        // Above upper bound 220.1cm
        val p221 = BodyMountProfile(220.1f, BodyMountPosition.CHEST)
        assertFalse(p221.isValid)
        assertNull(p221.estimatedCameraHeightMeters)
    }

    @Test
    fun nonFiniteStature_isRejected() {
        val pNan = BodyMountProfile(Float.NaN, BodyMountPosition.SHOULDER)
        assertFalse(pNan.isValid)
        assertNull(pNan.estimatedCameraHeightMeters)

        val pInf = BodyMountProfile(Float.POSITIVE_INFINITY, BodyMountPosition.CHEST)
        assertFalse(pInf.isValid)
        assertNull(pInf.estimatedCameraHeightMeters)

        val pNegInf = BodyMountProfile(Float.NEGATIVE_INFINITY, BodyMountPosition.SHOULDER)
        assertFalse(pNegInf.isValid)
        assertNull(pNegInf.estimatedCameraHeightMeters)
    }

    @Test
    fun store_nullByDefault_savesAndClearsProfile() {
        val fakePrefs = FakeSharedPreferences()
        val store = BodyMountProfileStore(fakePrefs)

        // 1. Initial state is null (no default stature saved)
        assertNull("Initial profile must be null until explicitly saved", store.profile.value)

        // 2. Reject invalid profile on save
        val invalid = BodyMountProfile(100f, BodyMountPosition.SHOULDER)
        val saveInvalidResult = store.save(invalid)
        assertFalse("Store must reject invalid profile", saveInvalidResult)
        assertNull(store.profile.value)

        // 3. Save valid profile
        val valid = BodyMountProfile(175f, BodyMountPosition.SHOULDER)
        val saveValidResult = store.save(valid)
        assertTrue(saveValidResult)
        assertEquals(valid, store.profile.value)

        // 4. Persistence verification
        val store2 = BodyMountProfileStore(fakePrefs)
        assertEquals("New store instance must read persisted profile", valid, store2.profile.value)

        // 5. Clear
        store.clear()
        assertNull(store.profile.value)
        assertNull(store2.profile.value)
    }

    @Test
    fun store_corruptedPreferences_returnsNullSafely() {
        val fakePrefs = FakeSharedPreferences()
        // Put invalid non-finite value or string into stature_cm
        fakePrefs.data[BodyMountProfileStore.KEY_STATURE_CM] = "not_a_float"
        fakePrefs.data[BodyMountProfileStore.KEY_POSITION] = "SHOULDER"

        val store = BodyMountProfileStore(fakePrefs)
        assertNull("Corrupted preference type must return null without crashing", store.profile.value)
    }

    private class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

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
}
