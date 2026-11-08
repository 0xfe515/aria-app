package com.ariateam.aria.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRequestPolicyTest {
    @Test
    fun interruptedResolutionCanBeRetriedWhenScreenReturns() {
        // Cancellation during resolution does not advance the consumed ID.
        assertTrue(NavigationRequestPolicy.mayHandle(12L, 11L, 12L, isVisible = true))
    }

    @Test
    fun supersededAndAlreadyConsumedRequestsCannotLaunch() {
        assertFalse(NavigationRequestPolicy.mayHandle(12L, 11L, 13L, isVisible = true))
        assertFalse(NavigationRequestPolicy.mayHandle(12L, 12L, 12L, isVisible = true))
        assertFalse(NavigationRequestPolicy.mayHandle(12L, 11L, 12L, isVisible = false))
    }
}
