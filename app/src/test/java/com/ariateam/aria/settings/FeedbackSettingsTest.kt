package com.ariateam.aria.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackSettingsTest {
    @Test fun corruptPersistedValuesAreSanitized() {
        assertEquals(FeedbackSettings(), sanitizeFeedbackSettings("wrong", "GARBAGE", 8))
        assertEquals(FeedbackSettings(100, AlertFrequency.QUIET, false), sanitizeFeedbackSettings(142, "QUIET", false))
    }

    @Test fun defaultsPreserveCurrentFeedbackBehavior() {
        assertEquals(FeedbackSettings(100, AlertFrequency.NORMAL, true), FeedbackSettings())
    }
}
