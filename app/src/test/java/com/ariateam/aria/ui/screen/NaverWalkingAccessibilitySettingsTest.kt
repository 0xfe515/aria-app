package com.ariateam.aria.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverWalkingAccessibilitySettingsTest {

    @Test
    fun publicAccessibilitySettingsLaunchSucceeds() {
        var attempts = 0
        val opened = launchAccessibilitySettings { attempts++ }
        assertTrue(opened)
        assertEquals(1, attempts)
    }

    @Test
    fun unavailableSettingsHandlersReportFailure() {
        val opened = launchAccessibilitySettings { throw SecurityException("blocked") }

        assertFalse(opened)
    }

    @Test
    fun unavailableAccessibilitySettingsHandlerReportsFailure() {
        assertFalse(launchAccessibilitySettings { throw android.content.ActivityNotFoundException() })
    }
}
