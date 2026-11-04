package com.ariateam.aria.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.R
import com.ariateam.aria.cloud.AriaKeyStorage
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelMetadata
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionUiState
import org.junit.Rule
import org.junit.Test

private class FakeAriaKeyStorage(private var key: String? = null) : AriaKeyStorage {
    override fun saveApiKey(key: String): Boolean {
        this.key = key
        return true
    }
    override fun getApiKey(): String? = key
    override fun clearApiKey(): Boolean {
        this.key = null
        return true
    }
    override fun hasApiKey(): Boolean = !key.isNullOrBlank()
    override fun getMaskedApiKey(): String? = if (hasApiKey()) "***" else null
}

class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsRootShowsConciseCategoriesAndVoiceDetailsOnItsOwnPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme { SettingsScreen(VoiceState(), {}, {}) }
        }
        // Verify exact 5 reorganized root categories
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_account_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_diagnostics_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_demo_page_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.app_info)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.engine_description)).assertDoesNotExist()
        compose.onNodeWithText("음성 엔진 확인").assertDoesNotExist()

        // Click General ("일반")
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_voice_service_summary)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.body_mount_profile_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.engine_description)).assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).assertIsDisplayed()
    }

    @Test fun voiceHelpUsesConciseWakeCommandWording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme { SettingsScreen(VoiceState(), {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).performClick()
        compose.onNodeWithText("“헤이 아리아”라고 말해 음성 명령을 시작합니다.").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.engine_description)).assertDoesNotExist()
    }

    @Test fun accountPageManagesApiKeyAndRetainsLocalModels() {
        val fakeStorage = FakeAriaKeyStorage()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = VoiceState(),
                    onStartWaiting = {},
                    onStop = {},
                    keyStorage = fakeStorage,
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_account_title)).performClick()

        // Unregistered key status
        compose.onNodeWithText(context.getString(R.string.settings_cloud_key_empty)).assertIsDisplayed()
        compose.onNodeWithTag("gemini_register_key_btn").assertIsDisplayed().performClick()

        // Enter key in dialog
        compose.onNodeWithTag("gemini_key_input").performTextInput("test-gemini-key")
        compose.onNodeWithText(context.getString(R.string.settings_cloud_key_save)).performClick()

        // Key is registered and masked key displayed
        compose.onNodeWithTag("gemini_change_key_btn").assertIsDisplayed()
        compose.onNodeWithTag("gemini_clear_key_btn").assertIsDisplayed()

        // Retained on-device local models (ASR and Vision) are displayed
        compose.onNodeWithText(context.getString(R.string.settings_account_local_asr_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_account_local_vision_title)).assertIsDisplayed()

        // Clear key
        compose.onNodeWithTag("gemini_clear_key_btn").performClick()
        compose.onNodeWithText(context.getString(R.string.settings_cloud_key_delete_confirm)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_cloud_key_empty)).assertIsDisplayed()
    }

    @Test fun feedbackSwitchUpdatesTheSelectedMode() {
        val audible = mutableStateOf(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = VoiceState(),
                    onStartWaiting = {},
                    onStop = {},
                    audibleFeedback = audible.value,
                    onAudibleFeedbackChanged = { audible.value = it },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).performClick()
        val switch = compose.onNodeWithText(context.getString(R.string.audible_feedback))
        switch.assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.settings_feedback_disabled)).assertIsDisplayed()
    }

    @Test fun voicePageControlsWakeAndOpensNotificationSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = mutableStateOf(VoiceState())
        var notificationSettingsOpened = 0
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = state.value,
                    onStartWaiting = { state.value = VoiceState(VoicePhase.Waiting) },
                    onStop = { state.value = VoiceState() },
                    onNotificationSettings = { notificationSettingsOpened++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).performClick()
        val wakeSwitch = compose.onNodeWithText(context.getString(R.string.wake_setting))
        wakeSwitch.assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.command_notification_settings)).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, notificationSettingsOpened) }
    }

    @Test fun diagnosticsSubpageOpensDeveloperProbesAndHasNoDemoEntrance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var openedVision = 0
        var openedVoice = 0
        var openedAppSettings = 0
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = VoiceState(),
                    onStartWaiting = {},
                    onStop = {},
                    onOpenVisionDiagnostics = { openedVision++ },
                    onOpenVoiceDiagnostics = { openedVoice++ },
                    onOpenAppSettings = { openedAppSettings++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_diagnostics_title)).performClick()

        // Diagnostics only has developer probes (Vision, Voice, App Settings)
        // Presentation demo and AR distance must NOT exist in Diagnostics
        compose.onNodeWithText(context.getString(R.string.debug_open_demo)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.distance_mode_title)).assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.debug_open_vision))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.debug_open_voice))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.open_app_settings))
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, openedVision)
            org.junit.Assert.assertEquals(1, openedVoice)
            org.junit.Assert.assertEquals(1, openedAppSettings)
        }
    }

    @Test fun initialDebugExpandedOpensDiagnosticsAndBackReturnsToRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = VoiceState(),
                    onStartWaiting = {},
                    onStop = {},
                    initialDebugExpanded = true,
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.debug_open_vision)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.debug_back_to_settings))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).assertIsDisplayed()
    }

    @Test fun generalSubpageShowsBodyMountProfileAndOpensDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var profileSetupOpened = 0
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = VoiceState(),
                    onStartWaiting = {},
                    onStop = {},
                    onOpenProfileSetup = { profileSetupOpened++ },
                )
            }
        }

        // Stature/mount profile is consolidated in General
        compose.onNodeWithText(context.getString(R.string.settings_general_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.body_mount_profile_title))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, profileSetupOpened) }
    }

    @Test fun visionDiagnosticsAndPresentationDemoShowHonestMissingModelAndBackNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var backCount = 0
        val showDemo = mutableStateOf(false)
        compose.setContent {
            AriaTheme {
                if (!showDemo.value) {
                    VisionDiagnosticsScreen(
                        uiState = com.ariateam.aria.vision.VisionUiState(
                            cameraStatus = com.ariateam.aria.vision.CameraSessionStatus.PermissionDenied,
                            cameraErrorDetail = "카메라 권한이 거부되었습니다.",
                        ),
                        modelState = com.ariateam.aria.vision.VisionModelState.Missing,
                        onBack = { backCount++ },
                        onStartCamera = { _, _, _ -> },
                        onStopCamera = {},
                        onSelectPipelineMode = {},
                        onResetMetrics = {},
                        onOpenAppSettings = {},
                    )
                } else {
                    PresentationDemoScreen(
                        voiceState = VoiceState(VoicePhase.Waiting),
                        visionUiState = com.ariateam.aria.vision.VisionUiState(),
                        visionModelState = com.ariateam.aria.vision.VisionModelState.Missing,
                        onBack = { backCount++ },
                        onStartCamera = { _, _, _ -> },
                        onStopCamera = {},
                        onStartVoiceWaiting = {},
                        onStopVoice = {},
                        onOpenAppSettings = {},
                    )
                }
            }
        }
        compose.onNodeWithText("권한 거부됨", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3. 순수 모델 추론 (Model Invoke): N/A (모델 미적용)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.debug_back_to_settings)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.debug_back_to_settings))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, backCount)
            showDemo.value = true
        }
        compose.onNodeWithTag("presentationModelStatus").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.debug_back_to_settings)).assertDoesNotExist()
        compose.onNodeWithTag("presentationDemoBackButton")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(2, backCount)
        }
    }

    @Test fun visionDiagnosticsRemovesModelAndBackendControlsAndReportsOnlyActiveBackend() {
        val uiState = mutableStateOf(
            VisionUiState(activeBackendDescription = "LiteRT GPU FP32"),
        )
        val modelState = mutableStateOf<VisionModelState>(
            VisionModelState.Ready(
                VisionModelMetadata(
                    displayName = "production-model",
                    sizeBytes = 1L,
                    sha256 = "hash",
                    importedAtMillis = 0L,
                ),
            ),
        )
        compose.setContent {
            AriaTheme {
                VisionDiagnosticsScreen(
                    uiState = uiState.value,
                    modelState = modelState.value,
                    onBack = {},
                    onStartCamera = { _, _, _ -> },
                    onStopCamera = {},
                    onSelectPipelineMode = {},
                    onResetMetrics = {},
                    onOpenAppSettings = {},
                )
            }
        }

        listOf(
            "GPU 최적화 모델 (FP32)",
            "기본 원본 복원",
            "새 모델 불러오기 (.tflite)",
            "모델 해제",
            "CPU 4스레드",
            "CPU 2스레드",
            "CPU 1스레드",
            "GPU 대리자",
        ).forEach { label -> compose.onNodeWithText(label).assertDoesNotExist() }
        compose.onNodeWithText("GPU", substring = true).assertDoesNotExist()

        compose.runOnIdle {
            uiState.value = uiState.value.copy(cameraStatus = CameraSessionStatus.Running)
        }
        compose.onNodeWithText("실제 추론 백엔드: LiteRT GPU FP32").performScrollTo().assertIsDisplayed()

        compose.runOnIdle {
            uiState.value = uiState.value.copy(cameraStatus = CameraSessionStatus.Error)
            modelState.value = VisionModelState.Error("Model rejected", "invalid model")
        }
        compose.onNodeWithText("추론 백엔드: 모델 오류로 추론할 수 없습니다")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("GPU", substring = true).assertDoesNotExist()
    }

    @Test fun licensesMenuIsReachableAndNavigatesBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var openedLicenses = 0
        var backClicked = 0
        val showLicenses = mutableStateOf(false)

        compose.setContent {
            AriaTheme {
                if (!showLicenses.value) {
                    SettingsScreen(
                        state = VoiceState(),
                        onStartWaiting = {},
                        onStop = {},
                        onOpenLicenses = {
                            openedLicenses++
                            showLicenses.value = true
                        },
                    )
                } else {
                    LicensesScreen(
                        onBack = {
                            backClicked++
                            showLicenses.value = false
                        },
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.app_info)).performClick()
        compose.onNodeWithText(context.getString(R.string.licenses_title))
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, openedLicenses)
            org.junit.Assert.assertTrue(showLicenses.value)
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("licensesWebView").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("licensesWebView").assertIsDisplayed()

        compose.onNodeWithTag("licensesBackButton")
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, backClicked)
            org.junit.Assert.assertFalse(showLicenses.value)
        }
    }

    @Test fun licensesOpenInSingleScrollableHtmlViewerAndNavigateBack() {
        var backClicked = 0
        compose.setContent {
            AriaTheme {
                LicensesScreen(onBack = { backClicked++ })
            }
        }

        compose.onNodeWithTag("licensesTitle").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("licensesWebView").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("licensesWebView")
            .assertIsDisplayed()
        compose.onNodeWithTag("licensesScreenRoot")
            .assertIsDisplayed()
        compose.onNodeWithTag("licensesBackButton")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, backClicked) }
    }

    @Test fun voiceDiagnosticsAndLicensesUseAccessibleChevronBackButton() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var voiceBackClicked = 0
        var licensesBackClicked = 0
        val showLicenses = mutableStateOf(false)

        compose.setContent {
            AriaTheme {
                if (!showLicenses.value) {
                    VoiceDiagnosticsScreen(
                        state = VoiceState(),
                        audibleFeedback = true,
                        onBack = { voiceBackClicked++ },
                        onStartWaiting = {},
                        onStop = {},
                        onStartDictation = {},
                        onFinishDictation = {},
                        onAudibleFeedbackChanged = {},
                        onOpenSettings = {},
                    )
                } else {
                    LicensesScreen(
                        onBack = { licensesBackClicked++ },
                    )
                }
            }
        }

        // VoiceDiagnosticsScreen uses chevron button with accessible content description
        compose.onNodeWithText(context.getString(R.string.debug_back_to_settings)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.debug_back_to_settings))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, voiceBackClicked)
            showLicenses.value = true
        }

        // LicensesScreen uses chevron button with tag and accessible content description
        compose.onNodeWithText(context.getString(R.string.debug_back_to_settings)).assertDoesNotExist()
        compose.onNodeWithTag("licensesBackButton")
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, licensesBackClicked)
        }
    }

    @Test fun demoSubpageUnifiesPresentationAndArDistanceAndHasNoOrdinarySettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var openedArDistance = 0
        var openedDemo = 0
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = VoiceState(),
                    onStartWaiting = {},
                    onStop = {},
                    onOpenDistanceMode = { openedArDistance++ },
                    onOpenPresentationDemo = { openedDemo++ },
                )
            }
        }

        // Navigate from Root to Demo page
        compose.onNodeWithText(context.getString(R.string.settings_demo_page_title))
            .assertIsDisplayed()
            .performClick()

        // 1. Presentation demo row
        compose.onNodeWithText(context.getString(R.string.debug_open_demo))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, openedDemo) }

        // 2. AR Distance row
        compose.onNodeWithText(context.getString(R.string.settings_demo_ar_title))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, openedArDistance) }

        // 3. Stature/mount profile is NOT in Demo (moved to General)
        compose.onNodeWithText(context.getString(R.string.body_mount_profile_title))
            .assertDoesNotExist()

        // 4. Developer diagnostics are NOT in Demo
        compose.onNodeWithText(context.getString(R.string.debug_open_vision))
            .assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.debug_open_voice))
            .assertDoesNotExist()

        // 5. Flat ground safety disclaimer
        compose.onNodeWithTag("demoSafetyDisclaimer")
            .assertIsDisplayed()

        // 6. Back navigation returns to Root
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(context.getString(R.string.settings_demo_page_title)).assertIsDisplayed()
    }
}
