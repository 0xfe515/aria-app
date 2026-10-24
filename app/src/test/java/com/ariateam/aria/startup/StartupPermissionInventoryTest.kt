package com.ariateam.aria.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPermissionInventoryTest {
    @Test
    fun api31ExcludesRuntimeNotificationPermission() {
        assertEquals(
            listOf(StartupPermissionInventory.RECORD_AUDIO, StartupPermissionInventory.CAMERA),
            StartupPermissionInventory.requiredRuntimePermissions(31),
        )
    }

    @Test
    fun api33IncludesRuntimeNotificationPermission() {
        assertEquals(
            listOf(
                StartupPermissionInventory.RECORD_AUDIO,
                StartupPermissionInventory.CAMERA,
                StartupPermissionInventory.POST_NOTIFICATIONS,
            ),
            StartupPermissionInventory.requiredRuntimePermissions(33),
        )
    }

    @Test
    fun allGrantedPermissionsProduceNoRequest() {
        val required = StartupPermissionInventory.requiredRuntimePermissions(33)
        assertTrue(StartupPermissionInventory.missingPermissions(33, required.toSet()).isEmpty())
    }

    @Test
    fun missingPermissionInventoryIdentifiesCameraMicrophoneNotificationAndMixedCases() {
        val all = StartupPermissionInventory.requiredRuntimePermissions(33).toSet()
        val cases = listOf(
            StartupPermissionInventory.missingPermissions(33, all - StartupPermissionInventory.CAMERA) to
                setOf(StartupPermissionInventory.CAMERA),
            StartupPermissionInventory.missingPermissions(33, all - StartupPermissionInventory.RECORD_AUDIO) to
                setOf(StartupPermissionInventory.RECORD_AUDIO),
            StartupPermissionInventory.missingPermissions(33, all - StartupPermissionInventory.POST_NOTIFICATIONS) to
                setOf(StartupPermissionInventory.POST_NOTIFICATIONS),
            StartupPermissionInventory.missingPermissions(
                33,
                all - setOf(StartupPermissionInventory.CAMERA, StartupPermissionInventory.POST_NOTIFICATIONS),
            ) to setOf(StartupPermissionInventory.CAMERA, StartupPermissionInventory.POST_NOTIFICATIONS),
        )
        cases.forEach { (missing, expected) ->
            assertEquals(expected, missing)
        }
        assertEquals(
            "카메라",
            StartupPermissionInventory.deniedPermissionSummary(setOf(StartupPermissionInventory.CAMERA)),
        )
    }
}
