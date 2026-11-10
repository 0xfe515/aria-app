package com.ariateam.aria.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchAccessibilityPolicyTest {
    @Test fun firstUnseenLaunchShowsGate() {
        assertTrue(FirstLaunchAccessibilityPolicy.shouldShow(false, false, false))
    }

    @Test fun handledLaunchDoesNotShowGate() {
        assertFalse(FirstLaunchAccessibilityPolicy.shouldShow(true, false, false))
    }

    @Test fun enabledServiceBypassesGate() {
        assertFalse(FirstLaunchAccessibilityPolicy.shouldShow(false, true, false))
    }

    @Test fun pendingSettingsReturnKeepsGateAcrossRecreation() {
        assertTrue(FirstLaunchAccessibilityPolicy.shouldShow(true, false, true))
    }

    @Test fun settingsReturnReleasesGateRegardlessOfServiceState() {
        assertFalse(FirstLaunchAccessibilityPolicy.afterSettingsReturn(isEnabled = false).showGate)
        assertFalse(FirstLaunchAccessibilityPolicy.afterSettingsReturn(isEnabled = true).showGate)
        assertFalse(FirstLaunchAccessibilityPolicy.afterSettingsReturn(isEnabled = false).accessibilityEnabled)
        assertTrue(FirstLaunchAccessibilityPolicy.afterSettingsReturn(isEnabled = true).accessibilityEnabled)
    }
}
