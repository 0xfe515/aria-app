package com.ariateam.aria.distance

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe persistence store for [BodyMountProfile] backed by [SharedPreferences].
 *
 * Requirements:
 * - Profile is null until explicitly configured and saved; no default stature is assumed.
 * - Rejects non-finite or out-of-range persisted stature data or invalid preference types.
 * - Emits updates via [profile] StateFlow immediately when saved, cleared, or modified
 *   by other instances in the same process via [SharedPreferences.OnSharedPreferenceChangeListener].
 */
class BodyMountProfileStore internal constructor(
    private val prefs: SharedPreferences,
) : AutoCloseable {

    constructor(
        context: Context,
        prefsName: String = PREFS_NAME,
    ) : this(context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE))

    private val _profile = MutableStateFlow<BodyMountProfile?>(loadFromPrefs())
    val profile: StateFlow<BodyMountProfile?> = _profile.asStateFlow()

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_STATURE_CM || key == KEY_POSITION) {
            reload()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    private fun loadFromPrefs(): BodyMountProfile? {
        return runCatching {
            if (!prefs.contains(KEY_STATURE_CM) || !prefs.contains(KEY_POSITION)) {
                return null
            }
            val stature = prefs.getFloat(KEY_STATURE_CM, -1f)
            val posStr = prefs.getString(KEY_POSITION, null) ?: return null
            val position = BodyMountPosition.valueOf(posStr)
            val candidate = BodyMountProfile(statureCm = stature, position = position)
            if (candidate.isValid) candidate else null
        }.getOrNull()
    }

    private fun reload() {
        val loaded = loadFromPrefs()
        if (_profile.value != loaded) {
            _profile.value = loaded
        }
    }

    /**
     * Persists [profile] to SharedPreferences.
     * Returns true if valid and saved, false if [profile] is invalid.
     */
    fun save(profile: BodyMountProfile): Boolean {
        if (!profile.isValid) {
            return false
        }
        val success = runCatching {
            prefs.edit()
                .putFloat(KEY_STATURE_CM, profile.statureCm)
                .putString(KEY_POSITION, profile.position.name)
                .commit()
        }.getOrDefault(false)

        if (success) {
            _profile.value = profile
            return true
        }
        return false
    }

    /**
     * Clears persisted profile data, setting [profile] to null.
     */
    fun clear() {
        runCatching {
            prefs.edit()
                .remove(KEY_STATURE_CM)
                .remove(KEY_POSITION)
                .commit()
        }
        _profile.value = null
    }

    override fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }

    companion object {
        const val PREFS_NAME = "aria_body_mount_profile"
        const val KEY_STATURE_CM = "stature_cm"
        const val KEY_POSITION = "mount_position"
    }
}
