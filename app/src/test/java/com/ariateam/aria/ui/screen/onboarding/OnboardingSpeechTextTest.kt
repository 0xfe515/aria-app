package com.ariateam.aria.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingSpeechTextTest {
    @Test
    fun convertsStandaloneAndKoreanParticleBrandVariantsForSpeech() {
        assertEquals("아리아", onboardingSpeechText("ARIA"))
        assertEquals("안녕하세요, 아리아입니다. 아리아를 불러 주세요.", onboardingSpeechText("안녕하세요, Aria입니다. ARIA를 불러 주세요."))
    }

    @Test
    fun leavesEmbeddedLatinWordsUnchanged() {
        assertEquals("MARIAN", onboardingSpeechText("MARIAN"))
    }
}
