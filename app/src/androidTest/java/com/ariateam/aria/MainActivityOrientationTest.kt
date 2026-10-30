package com.ariateam.aria

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityOrientationTest {

    @Test
    fun mainActivity_declaresPortraitOrientationInManifest() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val componentName = ComponentName(targetContext, MainActivity::class.java)

        val activityInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            targetContext.packageManager.getActivityInfo(
                componentName,
                PackageManager.ComponentInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            targetContext.packageManager.getActivityInfo(componentName, 0)
        }

        assertNotNull("MainActivity ActivityInfo must be found by PackageManager", activityInfo)
        assertEquals(
            "MainActivity must declare SCREEN_ORIENTATION_PORTRAIT in AndroidManifest.xml",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activityInfo.screenOrientation,
        )
    }
}
