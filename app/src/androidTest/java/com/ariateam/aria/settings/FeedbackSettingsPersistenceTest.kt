package com.ariateam.aria.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackSettingsPersistenceTest {
    @Test
    fun mutationsCommitAndReadBackWithValidationAndClose() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = FeedbackSettingsClient(context)
        val original = client.read()
        try {
            assertNull(client.setVolumePercent(101))
            assertEquals(FeedbackSettings(45, AlertFrequency.QUIET, false), client.setVolumePercent(45)?.let {
                val frequency = client.setAlertFrequency(AlertFrequency.QUIET)
                val caution = client.setCautionEnabled(false)
                assertEquals(45, frequency?.volumePercent)
                assertEquals(false, caution?.cautionEnabled)
                client.read()
            })
            assertEquals(100, client.adjustVolumePercent(Int.MAX_VALUE)?.volumePercent)
            assertEquals(100, client.read().volumePercent)
        } finally {
            client.setVolumePercent(original.volumePercent)
            client.setAlertFrequency(original.alertFrequency)
            client.setCautionEnabled(original.cautionEnabled)
            client.close()
        }
        assertNull(client.setCautionEnabled(false))
        val readAfterCloseFails = runCatching { client.read() }.isFailure
        assertTrue(readAfterCloseFails)
    }
}
