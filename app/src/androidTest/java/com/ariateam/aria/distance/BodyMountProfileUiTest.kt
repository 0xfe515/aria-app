package com.ariateam.aria.distance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.ui.screen.BodyMountProfileDialog
import com.ariateam.aria.ui.theme.AriaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BodyMountProfileUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var store: BodyMountProfileStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = BodyMountProfileStore(context)
        store.clear()
    }

    @org.junit.After
    fun tearDown() {
        store.clear()
        store.close()
    }

    @Test
    fun firstUseNoAssumedStatureAndSaveDisabledUntilValidInput() {
        var dismissed = false
        compose.setContent {
            AriaTheme {
                BodyMountProfileDialog(
                    onDismiss = { dismissed = true },
                    profileStore = store,
                )
            }
        }

        // Dialog title & initial empty input
        compose.onNodeWithTag("bodyMountProfileTitle").assertIsDisplayed()
        compose.onNodeWithTag("bodyMountSaveButton").assertIsNotEnabled()

        // Type out of range: 119cm (< 120cm)
        compose.onNodeWithTag("bodyMountStatureInput").performTextInput("119")
        compose.onNodeWithTag("bodyMountStatureError").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bodyMountSaveButton").performScrollTo().assertIsNotEnabled()

        // Type out of range: 225cm (> 220cm)
        compose.onNodeWithTag("bodyMountStatureInput").performTextClearance()
        compose.onNodeWithTag("bodyMountStatureInput").performTextInput("225")
        compose.onNodeWithTag("bodyMountStatureError").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bodyMountSaveButton").performScrollTo().assertIsNotEnabled()

        // Valid stature: 170cm
        compose.onNodeWithTag("bodyMountStatureInput").performTextClearance()
        compose.onNodeWithTag("bodyMountStatureInput").performTextInput("170")
        compose.onNodeWithTag("bodyMountStatureError").assertDoesNotExist()
        compose.onNodeWithTag("bodyMountSaveButton").performScrollTo().assertIsEnabled()

        // Derived height preview: 170 * 0.8035428 = ~136.6cm -> rounded to 137cm
        compose.onNodeWithTag("bodyMountDerivedHeight").assertIsDisplayed()
        compose.onNodeWithText("137cm", substring = true).assertIsDisplayed()
    }

    @Test
    fun positionSwitchUpdatesDerivedHeightAndPersistsOnSave() {
        var savedProfile: BodyMountProfile? = null
        var dismissed = false
        compose.setContent {
            AriaTheme {
                BodyMountProfileDialog(
                    onDismiss = { dismissed = true },
                    profileStore = store,
                    onSaved = { savedProfile = it },
                )
            }
        }

        // Enter 170cm with default shoulder
        compose.onNodeWithTag("bodyMountStatureInput").performTextInput("170")
        compose.onNodeWithText("137cm", substring = true).assertIsDisplayed()

        // Switch to chest: 170 * 0.7164686 = ~121.8cm -> rounded to 122cm
        compose.onNodeWithTag("bodyMountPositionChest").performClick()
        compose.onNodeWithText("122cm", substring = true).assertIsDisplayed()

        // Save
        compose.onNodeWithTag("bodyMountSaveButton").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertNotNull(savedProfile)
            assertEquals(170f, savedProfile!!.statureCm, 0.01f)
            assertEquals(BodyMountPosition.CHEST, savedProfile!!.position)
            assertEquals(savedProfile, store.profile.value)
        }
    }

    @Test
    fun reopenPrefillsSavedProfileAndCancelLeavesProfileUntouched() {
        // Pre-save 180cm SHOULDER
        val initial = BodyMountProfile(180f, BodyMountPosition.SHOULDER)
        assertTrue(store.save(initial))

        var dismissed = false
        compose.setContent {
            AriaTheme {
                BodyMountProfileDialog(
                    onDismiss = { dismissed = true },
                    initialProfile = initial,
                    profileStore = store,
                )
            }
        }

        // Dialog should open with "180" prefilled and Save button enabled
        compose.onNodeWithTag("bodyMountStatureInput").assertIsDisplayed()
        compose.onNodeWithText("180", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("bodyMountSaveButton").assertIsEnabled()

        // Change stature to 160, but click Cancel
        compose.onNodeWithTag("bodyMountStatureInput").performTextClearance()
        compose.onNodeWithTag("bodyMountStatureInput").performTextInput("160")
        compose.onNodeWithTag("bodyMountCancelButton").performClick()

        compose.runOnIdle {
            assertTrue(dismissed)
            // Store must still hold the original 180f profile untouched
            assertEquals(initial, store.profile.value)
        }
    }

    @Test
    fun clearButtonRemovesProfileFromStore() {
        val initial = BodyMountProfile(172f, BodyMountPosition.CHEST)
        assertTrue(store.save(initial))

        var dismissed = false
        compose.setContent {
            AriaTheme {
                BodyMountProfileDialog(
                    onDismiss = { dismissed = true },
                    initialProfile = initial,
                    profileStore = store,
                )
            }
        }

        compose.onNodeWithTag("bodyMountClearButton").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertNull(store.profile.value)
        }
    }

    @Test
    fun commaDecimalLocaleInputHandledCorrectly() {
        compose.setContent {
            AriaTheme {
                BodyMountProfileDialog(
                    onDismiss = {},
                    profileStore = store,
                )
            }
        }

        // Enter decimal with comma: "172,5"
        compose.onNodeWithTag("bodyMountStatureInput").performTextInput("172,5")
        compose.onNodeWithTag("bodyMountStatureError").assertDoesNotExist()
        compose.onNodeWithTag("bodyMountSaveButton").assertIsEnabled()

        compose.onNodeWithTag("bodyMountSaveButton").performClick()
        compose.runOnIdle {
            val saved = store.profile.value
            assertNotNull(saved)
            assertEquals(172.5f, saved!!.statureCm, 0.01f)
        }
    }

    @Test
    fun smallViewportAndLargeFontScaleMaintainsAccessibleControlsAndScroll() {
        var dismissed = false
        var savedProfile: BodyMountProfile? = null

        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 1.5f,
                )
            ) {
                Box(modifier = Modifier.size(width = 360.dp, height = 480.dp)) {
                    AriaTheme {
                        BodyMountProfileDialog(
                            onDismiss = { dismissed = true },
                            profileStore = store,
                            onSaved = { savedProfile = it },
                        )
                    }
                }
            }
        }

        // Title is visible
        compose.onNodeWithTag("bodyMountProfileTitle").assertIsDisplayed()

        // Input field is visible and accessible
        compose.onNodeWithTag("bodyMountStatureInput").performTextInput("175")

        // Scroll to derived height and controls if needed
        compose.onNodeWithTag("bodyMountDerivedHeight")
            .performScrollTo()
            .assertIsDisplayed()

        // Chest option selectable via scroll
        compose.onNodeWithTag("bodyMountPositionChest")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        // Save button is accessible and clickable
        compose.onNodeWithTag("bodyMountSaveButton")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertTrue(dismissed)
            assertNotNull(savedProfile)
            assertEquals(175f, savedProfile!!.statureCm, 0.01f)
            assertEquals(BodyMountPosition.CHEST, savedProfile!!.position)
            assertEquals(savedProfile, store.profile.value)
        }
    }
}
