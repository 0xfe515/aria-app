package com.ariateam.aria.startup

object StartupPermissionInventory {
    const val CAMERA = "android.permission.CAMERA"
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    fun requiredRuntimePermissions(sdkInt: Int): List<String> = buildList {
        add(RECORD_AUDIO)
        add(CAMERA)
        if (sdkInt >= 33) add(POST_NOTIFICATIONS)
    }

    fun missingPermissions(
        sdkInt: Int,
        grantedPermissions: Set<String>,
    ): Set<String> = requiredRuntimePermissions(sdkInt).filterNot(grantedPermissions::contains).toSet()

    fun deniedPermissionSummary(missingPermissions: Set<String>): String =
        listOf(
            RECORD_AUDIO to "마이크",
            CAMERA to "카메라",
            POST_NOTIFICATIONS to "알림",
        )
            .filter { (permission, _) -> permission in missingPermissions }
            .map { (_, label) -> label }
            .joinToString("·")
}
