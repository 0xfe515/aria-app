package com.ariateam.aria.settings

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class FeedbackSettingsClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val mutable = MutableStateFlow(FeedbackSettings())
    private val mutableAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = mutableAvailable.asStateFlow()
    val settings: StateFlow<FeedbackSettings> = mutable.asStateFlow()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) { refresh() }
    }
    init {
        appContext.contentResolver.registerContentObserver(FeedbackSettingsProvider.uri(appContext), false, observer)
        refresh()
    }
    fun read(): FeedbackSettings {
        check(!closed.get()) { "Feedback settings client is closed" }
        return invoke(FeedbackSettingsProvider.READ, null)?.also { mutable.value = it; mutableAvailable.value = true }
            ?: run { mutableAvailable.value = false; throw IllegalStateException("Feedback settings could not be read") }
    }
    fun setVolumePercent(percent: Int): FeedbackSettings? {
        if (percent !in 0..100) return null
        return call(FeedbackSettingsProvider.WRITE_VOLUME, android.os.Bundle().apply { putInt(FeedbackSettingsProvider.VOLUME, percent) })
    }
    fun adjustVolumePercent(delta: Int): FeedbackSettings? = call(FeedbackSettingsProvider.ADJUST_VOLUME, android.os.Bundle().apply { putInt(FeedbackSettingsProvider.DELTA, delta) })
    fun setAlertFrequency(frequency: AlertFrequency): FeedbackSettings? = call(FeedbackSettingsProvider.WRITE_FREQUENCY, android.os.Bundle().apply { putString(FeedbackSettingsProvider.FREQUENCY, frequency.name) })
    fun setCautionEnabled(enabled: Boolean): FeedbackSettings? = call(FeedbackSettingsProvider.WRITE_CAUTION, android.os.Bundle().apply { putBoolean(FeedbackSettingsProvider.CAUTION, enabled) })
    private fun refresh() { if (!closed.get()) runCatching { read() }.onFailure { mutableAvailable.value = false } }
    private fun call(method: String, extras: android.os.Bundle? = null): FeedbackSettings? {
        if (closed.get()) return null
        return invoke(method, extras)?.also { mutable.value = it; mutableAvailable.value = true }
            ?: run { mutableAvailable.value = false; null }
    }
    private fun invoke(method: String, extras: android.os.Bundle?): FeedbackSettings? = runCatching {
        appContext.contentResolver.call(FeedbackSettingsProvider.uri(appContext), method, null, extras)?.let { bundle ->
            FeedbackSettingsProvider.decode(bundle)
        }
    }.getOrNull()
    override fun close() { if (closed.compareAndSet(false, true)) appContext.contentResolver.unregisterContentObserver(observer) }
}
