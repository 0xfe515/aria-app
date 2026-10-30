package com.ariateam.aria.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.R
import com.ariateam.aria.speech.VoicePhase
import com.ariateam.aria.speech.VoiceState
import com.ariateam.aria.ui.theme.AriaTheme
import com.ariateam.aria.vision.CameraSessionStatus
import com.ariateam.aria.vision.VisionModelMetadata
import com.ariateam.aria.vision.VisionModelState
import com.ariateam.aria.vision.VisionUiState
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsRootShowsConciseCategoriesAndVoiceDetailsOnItsOwnPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme { SettingsScreen(VoiceState(), {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.settings_voice_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_model_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_diagnostics_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.app_info)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.engine_description)).assertDoesNotExist()
        compose.onNodeWithText("음성 엔진 확인").assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.settings_voice_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_voice_service_summary)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.engine_description)).assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(context.getString(R.string.settings_model_title)).assertIsDisplayed()
    }

    @Test fun voiceHelpUsesConciseWakeCommandWording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme { SettingsScreen(VoiceState(), {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.settings_voice_title)).performClick()
        compose.onNodeWithText("“헤이 아리아”라고 말해 음성 명령을 시작합니다.").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.engine_description)).assertDoesNotExist()
    }

    @Test fun modelDownloadDefaultsToWifiAndCanBeCancelled() {
        var mobileAllowed: Boolean? = null
        var cancelled = false
        val state = mutableStateOf(VoiceState())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = state.value,
                    onStartWaiting = {},
                    onStop = {},
                    onDownloadModel = { mobileAllowed = it },
                    onCancelDownload = { cancelled = true },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_model_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.gemma_download)).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(false, mobileAllowed) }
        compose.onNodeWithText(context.getString(R.string.gemma_mobile_data)).assertIsOff().performClick()
        compose.onNodeWithText(context.getString(R.string.gemma_download)).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(true, mobileAllowed) }
        compose.onNodeWithText(context.getString(R.string.gemma_delete)).assertIsNotEnabled()
        state.value = VoiceState(command = VoiceState().command.copy(model = "다운로드 대기 중"))
        compose.onNodeWithText(context.getString(R.string.gemma_cancel)).performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(cancelled) }
    }

    @Test fun feedbackSwitchUpdatesTheSelectedMode() {
        val audible = mutableStateOf(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            AriaTheme {
                SettingsScreen(VoiceState(), {}, {}, audibleFeedback = audible.value,
                    onAudibleFeedbackChanged = { audible.value = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_voice_title)).performClick()
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
        compose.onNodeWithText(context.getString(R.string.settings_voice_title)).performClick()
        val wakeSwitch = compose.onNodeWithText(context.getString(R.string.wake_setting))
        wakeSwitch.assertIsOff().performClick().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.command_notification_settings)).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, notificationSettingsOpened) }
    }

    @Test fun diagnosticsSubpageOpensAllThreeDestinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var openedVision = 0
        var openedVoice = 0
        var openedDemo = 0
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = VoiceState(),
                    onStartWaiting = {},
                    onStop = {},
                    onOpenVisionDiagnostics = { openedVision++ },
                    onOpenVoiceDiagnostics = { openedVoice++ },
                    onOpenPresentationDemo = { openedDemo++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_diagnostics_title)).performClick()

        compose.onNodeWithText(context.getString(R.string.debug_open_vision))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.debug_open_voice))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.debug_open_demo))
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            org.junit.Assert.assertEquals(1, openedVision)
            org.junit.Assert.assertEquals(1, openedVoice)
            org.junit.Assert.assertEquals(1, openedDemo)
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
        compose.onNodeWithText(context.getString(R.string.settings_model_title)).assertIsDisplayed()
    }

    @Test fun modelDeleteRequiresConfirmationAndCanBeDismissed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installedState = VoiceState(command = VoiceState().command.copy(model = "설치됨 · 오프라인 사용 가능"))
        var deleteCount = 0
        compose.setContent {
            AriaTheme {
                SettingsScreen(
                    state = installedState,
                    onStartWaiting = {},
                    onStop = {},
                    onDeleteModel = { deleteCount++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.settings_model_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.gemma_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_model_delete_title)).assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, deleteCount) }

        compose.onNodeWithText(context.getString(R.string.settings_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.gemma_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_model_delete_confirm)).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, deleteCount) }
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
}
