package com.ariateam.aria.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Test

class StartupViewModelTest {
    @Test
    fun admissionAndPermissionRequestStateSurviveViewModelReuseButNotProcessRecreation() {
        val configurationStore = ViewModelStore()
        val factory = ViewModelProvider.NewInstanceFactory()
        val retainedViewModel = ViewModelProvider(configurationStore, factory)
            .get(StartupViewModel::class.java)
        retainedViewModel.beginRuntimePermissionRequest()
        assertTrue(retainedViewModel.isRequestingRuntimePermissions)
        retainedViewModel.finishRuntimePermissionRequest()
        retainedViewModel.acceptDegraded()
        retainedViewModel.admit(emptySet())

        val afterConfigurationChange = ViewModelProvider(configurationStore, factory)
            .get(StartupViewModel::class.java)
        assertSame(retainedViewModel, afterConfigurationChange)
        assertTrue(afterConfigurationChange.hasBeenAdmitted)
        assertTrue(afterConfigurationChange.userAcceptedDegraded)
        assertTrue(afterConfigurationChange.hasCompletedRuntimePermissionCheck)
        assertFalse(afterConfigurationChange.isRequestingRuntimePermissions)

        configurationStore.clear()
        val afterProcessRecreation = ViewModelProvider(ViewModelStore(), factory)
            .get(StartupViewModel::class.java)
        assertFalse(afterProcessRecreation.hasBeenAdmitted)
        assertFalse(afterProcessRecreation.userAcceptedDegraded)
        assertFalse(afterProcessRecreation.hasCompletedRuntimePermissionCheck)
    }

    @Test
    fun retryClearsDegradedChoiceWithoutReplayingPermissionRequestState() {
        val viewModel = StartupViewModel()
        viewModel.acceptDegraded()
        viewModel.finishRuntimePermissionRequest()

        viewModel.retry()

        assertFalse(viewModel.userAcceptedDegraded)
        assertTrue(viewModel.hasCompletedRuntimePermissionCheck)
    }

    @Test
    fun startupSettingsCanReturnToGateWithoutAdmittingHome() {
        val viewModel = StartupViewModel()
        viewModel.showSettingsFromStartup()

        assertTrue(viewModel.isStartupSettingsVisible)
        assertFalse(viewModel.hasBeenAdmitted)

        viewModel.returnToStartup()

        assertFalse(viewModel.isStartupSettingsVisible)
        assertFalse(viewModel.hasBeenAdmitted)
    }

    @Test
    fun deniedPermissionRequestDoesNotRetryUntilExplicitRetry() {
        val viewModel = StartupViewModel()
        viewModel.beginRuntimePermissionRequest()
        viewModel.finishRuntimePermissionRequest()

        assertTrue(viewModel.hasCompletedRuntimePermissionCheck)
        assertFalse(viewModel.shouldRetryRuntimePermissionRequest)

        viewModel.retryRuntimePermissionRequest()

        assertTrue(viewModel.shouldRetryRuntimePermissionRequest)
        assertFalse(viewModel.hasCompletedRuntimePermissionCheck)
    }

    @Test
    fun inFlightPermissionRequestSurvivesConfigurationReuseUntilCallback() {
        val store = ViewModelStore()
        val factory = ViewModelProvider.NewInstanceFactory()
        val viewModel = ViewModelProvider(store, factory).get(StartupViewModel::class.java)
        viewModel.beginRuntimePermissionRequest()

        val afterRotation = ViewModelProvider(store, factory).get(StartupViewModel::class.java)

        assertSame(viewModel, afterRotation)
        assertTrue(afterRotation.isRequestingRuntimePermissions)
        assertFalse(afterRotation.hasCompletedRuntimePermissionCheck)
        afterRotation.finishRuntimePermissionRequest()
        assertFalse(afterRotation.isRequestingRuntimePermissions)
        assertTrue(afterRotation.hasCompletedRuntimePermissionCheck)
    }

    @Test
    fun settingsGrantAfterAdmissionIsNotTreatedAsPermissionLoss() {
        val viewModel = StartupViewModel()
        viewModel.admit(emptySet())

        val lostPermissions = viewModel.observeGrantedPermissions(
            setOf(StartupPermissionInventory.RECORD_AUDIO, StartupPermissionInventory.CAMERA),
        )

        assertTrue(lostPermissions.isEmpty())
        assertTrue(viewModel.hasBeenAdmitted)
    }

    @Test
    fun permissionRevokedAfterAdmissionIsDetectedBeforeHomeIsShown() {
        val viewModel = StartupViewModel()
        viewModel.admit(setOf(StartupPermissionInventory.RECORD_AUDIO, StartupPermissionInventory.CAMERA))

        val lostPermissions = viewModel.grantedPermissionsLostSinceAdmission(
            setOf(StartupPermissionInventory.RECORD_AUDIO),
        )

        assertEquals(setOf(StartupPermissionInventory.CAMERA), lostPermissions)
        assertTrue(viewModel.hasBeenAdmitted)
    }

    @Test
    fun revokedPermissionReentersRecoveryAndClearsStaleTimeout() {
        val viewModel = StartupViewModel()
        viewModel.admit(setOf(StartupPermissionInventory.RECORD_AUDIO))
        val lostPermissions = viewModel.observeGrantedPermissions(emptySet())
        assertEquals(setOf(StartupPermissionInventory.RECORD_AUDIO), lostPermissions)
        viewModel.enterPermissionRecovery()

        assertFalse(viewModel.hasBeenAdmitted)
        assertFalse(viewModel.userAcceptedDegraded)
        assertFalse(viewModel.userAcceptedPermissionDegraded)
        assertTrue(viewModel.hasCompletedRuntimePermissionCheck)
        assertFalse(viewModel.isStartupSettingsVisible)
    }

    @Test
    fun startupCuePlayedStateSurvivesViewModelReuseAcrossConfigurationChanges() {
        val configurationStore = ViewModelStore()
        val factory = ViewModelProvider.NewInstanceFactory()
        val viewModel = ViewModelProvider(configurationStore, factory)
            .get(StartupViewModel::class.java)

        assertFalse(viewModel.hasPlayedStartupCue)
        viewModel.markStartupCuePlayed()
        assertTrue(viewModel.hasPlayedStartupCue)

        val afterRotation = ViewModelProvider(configurationStore, factory)
            .get(StartupViewModel::class.java)
        assertSame(viewModel, afterRotation)
        assertTrue(afterRotation.hasPlayedStartupCue)

        configurationStore.clear()
        val afterProcessRecreation = ViewModelProvider(ViewModelStore(), factory)
            .get(StartupViewModel::class.java)
        assertFalse(afterProcessRecreation.hasPlayedStartupCue)
    }

    @Test
    fun startupCuePreservedAcrossPermissionRecoveryAndRetryInSameSession() {
        val viewModel = StartupViewModel()
        assertFalse(viewModel.hasPlayedStartupCue)
        viewModel.markStartupCuePlayed()
        assertTrue(viewModel.hasPlayedStartupCue)

        viewModel.enterPermissionRecovery()
        assertTrue(
            "Startup cue status must be retained across permission recovery in the same session",
            viewModel.hasPlayedStartupCue,
        )

        viewModel.retry()
        assertTrue(
            "Startup cue status must be retained across retry in the same session",
            viewModel.hasPlayedStartupCue,
        )
    }
}
