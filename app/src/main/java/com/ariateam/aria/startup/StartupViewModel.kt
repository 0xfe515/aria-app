package com.ariateam.aria.startup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StartupViewModel : ViewModel() {
    var hasBeenAdmitted by mutableStateOf(false)
        private set
    var userAcceptedDegraded by mutableStateOf(false)
        private set
    var userAcceptedPermissionDegraded by mutableStateOf(false)
        private set
    var hasCompletedRuntimePermissionCheck by mutableStateOf(false)
        private set
    var isRequestingRuntimePermissions by mutableStateOf(false)
        private set
    var shouldRetryRuntimePermissionRequest by mutableStateOf(false)
        private set
    var isStartupSettingsVisible by mutableStateOf(false)
        private set
    var timedOutStage by mutableStateOf<StartupStage?>(null)
        private set
    var hasPlayedStartupCue by mutableStateOf(false)
        private set

    private var previouslyGrantedPermissions: Set<String>? = null
    private var observedStage: StartupStage? = null
    private var observationGeneration = 0L

    fun admit(grantedPermissions: Set<String>) {
        hasBeenAdmitted = true
        previouslyGrantedPermissions = grantedPermissions
    }

    fun markStartupCuePlayed() {
        hasPlayedStartupCue = true
    }

    fun observeGrantedPermissions(grantedPermissions: Set<String>): Set<String> {
        val previouslyGranted = previouslyGrantedPermissions
        previouslyGrantedPermissions = grantedPermissions
        return if (hasBeenAdmitted && previouslyGranted != null) {
            previouslyGranted - grantedPermissions
        } else {
            emptySet()
        }
    }

    fun grantedPermissionsLostSinceAdmission(grantedPermissions: Set<String>): Set<String> =
        if (hasBeenAdmitted) previouslyGrantedPermissions.orEmpty() - grantedPermissions else emptySet()

    fun acceptDegraded() {
        userAcceptedDegraded = true
    }

    fun acceptPermissionDegraded() {
        userAcceptedPermissionDegraded = true
    }

    fun showSettingsFromStartup() {
        isStartupSettingsVisible = true
    }

    fun returnToStartup() {
        isStartupSettingsVisible = false
    }

    fun beginRuntimePermissionRequest() {
        hasCompletedRuntimePermissionCheck = false
        shouldRetryRuntimePermissionRequest = false
        isRequestingRuntimePermissions = true
    }

    fun completeRuntimePermissionCheck() {
        hasCompletedRuntimePermissionCheck = true
        shouldRetryRuntimePermissionRequest = false
    }

    fun finishRuntimePermissionRequest() {
        isRequestingRuntimePermissions = false
        hasCompletedRuntimePermissionCheck = true
        shouldRetryRuntimePermissionRequest = false
    }

    fun retryRuntimePermissionRequest() {
        if (isRequestingRuntimePermissions) return
        hasCompletedRuntimePermissionCheck = false
        shouldRetryRuntimePermissionRequest = true
    }

    fun enterPermissionRecovery() {
        hasBeenAdmitted = false
        userAcceptedDegraded = false
        userAcceptedPermissionDegraded = false
        hasCompletedRuntimePermissionCheck = true
        isRequestingRuntimePermissions = false
        shouldRetryRuntimePermissionRequest = false
        isStartupSettingsVisible = false
        observationGeneration++
        observedStage = null
        timedOutStage = null
    }

    fun observePendingStage(stage: StartupStage) {
        if (observedStage == stage) return
        observedStage = stage
        timedOutStage = null
        val generation = ++observationGeneration
        viewModelScope.launch {
            delay(STARTUP_STAGE_TIMEOUT_MILLIS)
            if (generation == observationGeneration && observedStage == stage) {
                timedOutStage = stage
            }
        }
    }

    fun retry() {
        observationGeneration++
        observedStage = null
        timedOutStage = null
        userAcceptedDegraded = false
        userAcceptedPermissionDegraded = false
    }

    companion object {
        const val STARTUP_STAGE_TIMEOUT_MILLIS = 60_000L
    }
}
