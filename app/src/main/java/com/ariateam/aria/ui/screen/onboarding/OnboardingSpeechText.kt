package com.ariateam.aria.ui.screen.onboarding

/** Converts the app name to its Korean pronunciation in onboarding speech only. */
internal fun onboardingSpeechText(text: String): String =
    text.replace(Regex("(?i)(?<![A-Za-z])ARIA(?=[^A-Za-z]|$)"), "아리아")
