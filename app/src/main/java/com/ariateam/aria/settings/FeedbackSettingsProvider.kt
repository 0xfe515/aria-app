package com.ariateam.aria.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** Main-process persistence endpoint. Calls are serialized by the provider process. */
class FeedbackSettingsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        synchronized(LOCK) {
            val current = readPrefs(prefs) ?: return null
            val next = when (method) {
                READ -> current
                WRITE_VOLUME -> current.copy(volumePercent = extras?.takeIf { it.get(VOLUME) is Int }?.getInt(VOLUME)?.takeIf { it in 0..100 } ?: return null)
                ADJUST_VOLUME -> current.copy(volumePercent = (current.volumePercent.toLong() + (extras?.takeIf { it.get(DELTA) is Int }?.getInt(DELTA)?.toLong() ?: return null)).coerceIn(0L, 100L).toInt())
                WRITE_FREQUENCY -> current.copy(alertFrequency = extras?.takeIf { it.get(FREQUENCY) is String }?.getString(FREQUENCY)?.let { runCatching { AlertFrequency.valueOf(it) }.getOrNull() } ?: return null)
                WRITE_CAUTION -> current.copy(cautionEnabled = extras?.takeIf { it.get(CAUTION) is Boolean }?.getBoolean(CAUTION) ?: return null)
                else -> return null
            }
            if (next != current && method != READ) {
                val ok = prefs.edit().putInt(VOLUME, next.volumePercent).putString(FREQUENCY, next.alertFrequency.name)
                    .putBoolean(CAUTION, next.cautionEnabled).commit()
                if (!ok) return null
                context?.contentResolver?.notifyChange(uri(context!!), null)
            }
            return next.toBundle()
        }
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".feedback.settings"
        const val READ = "read"
        const val WRITE_VOLUME = "volume"
        const val ADJUST_VOLUME = "adjust_volume"
        const val WRITE_FREQUENCY = "frequency"
        const val WRITE_CAUTION = "caution"
        const val VOLUME = "volume_percent"
        const val FREQUENCY = "frequency_value"
        const val CAUTION = "caution_enabled"
        const val DELTA = "delta"
        private const val PREFS = "feedback_settings"
        private val LOCK = Any()
        fun uri(context: Context) = Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/settings")
        private fun readPrefs(prefs: android.content.SharedPreferences): FeedbackSettings? {
            val values = runCatching { prefs.all }.getOrNull() ?: return null
            val sanitized = sanitizeFeedbackSettings(values[VOLUME], values[FREQUENCY], values[CAUTION])
            val volume = sanitized.volumePercent
            val frequency = sanitized.alertFrequency
            val caution = sanitized.cautionEnabled
            if (values[VOLUME] !is Int || values[VOLUME] != volume || values[FREQUENCY] != frequency.name || values[CAUTION] !is Boolean) {
                val ok = prefs.edit().putInt(VOLUME, volume).putString(FREQUENCY, frequency.name).putBoolean(CAUTION, caution).commit()
                if (!ok) return null
            }
            return sanitized
        }
        internal fun decode(bundle: Bundle): FeedbackSettings? = runCatching {
            if (!bundle.containsKey(VOLUME) || bundle.get(VOLUME) !is Int ||
                !bundle.containsKey(FREQUENCY) || bundle.get(FREQUENCY) !is String ||
                !bundle.containsKey(CAUTION) || bundle.get(CAUTION) !is Boolean) return null
            val volume = bundle.getInt(VOLUME, -1)
            val frequency = AlertFrequency.valueOf(bundle.getString(FREQUENCY) ?: return null)
            if (volume !in 0..100) return null
            FeedbackSettings(volume, frequency, bundle.getBoolean(CAUTION))
        }.getOrNull()
        private fun FeedbackSettings.toBundle() = Bundle().apply {
            putInt(VOLUME, volumePercent); putString(FREQUENCY, alertFrequency.name); putBoolean(CAUTION, cautionEnabled)
        }
    }
}
