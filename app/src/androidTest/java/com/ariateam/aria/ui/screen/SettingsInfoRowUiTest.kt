package com.ariateam.aria.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Rule
import org.junit.Test

/**
 * Static information in Settings remains readable without pretending to be an action.
 *
 * Confirms:
 * The retired local-model info row is absent from Gemini account settings; this checks the
 * current static app-version information row instead.
 */
class SettingsInfoRowUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appVersionInformationIsDisplayedWithoutClickOrButtonSemantics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appInfo = context.getString(R.string.app_info)
        val version = context.getString(R.string.app_version)

        compose.setContent {
            AriaTheme {
                SettingsScreen(state = VoiceState(), onStartWaiting = {}, onStop = {})
            }
        }

        compose.onNodeWithText(appInfo).performClick()
        compose.onNodeWithText(version).assertIsDisplayed()

        val versionNode = compose.onNodeWithText(version)
        versionNode.assert(!hasClickAction())

        val hasButtonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        versionNode.assert(!hasButtonRole)
    }
}
