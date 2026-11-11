package com.ariateam.aria.navigation

import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.navigation.accessibility.NaverAssistPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostic accessibility tree capture for Naver Map search results.
 * Runs without launching host Compose UI to leave live foreground Map intact.
 */
@RunWith(AndroidJUnit4::class)
class NaverDiagnosticTreeAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    companion object {
        private const val TAG = "NaverDiagnosticTree"
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 2000
    }

    @Test
    fun captureNaverSearchResultsDiagnosticTree() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertNotNull("UiAutomation must be initialized with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES", uiAutomation)

        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var root: AccessibilityNodeInfo? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = runCatching { uiAutomation.rootInActiveWindow }.getOrNull()
            if (candidate != null) {
                val pkg = candidate.packageName?.toString()
                if (pkg == NaverAssistPolicy.NAVER_PACKAGE) {
                    root = candidate
                    break
                }
                candidate.recycle()
            }
            SystemClock.sleep(200L)
        }

        if (root == null) {
            val fallback = runCatching { uiAutomation.rootInActiveWindow }.getOrNull()
            val observedPkg = fallback?.packageName?.toString()
            fallback?.recycle()
            fail("Expected Naver Map (${NaverAssistPolicy.NAVER_PACKAGE}) root in active window, but got: '$observedPkg'. Map must be in foreground.")
        }

        val nonNullRoot = root!!
        val xmlBuilder = StringBuilder()
        xmlBuilder.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        xmlBuilder.append("<hierarchy rotation=\"0\">\n")

        var totalNodes = 0
        var reachedLimits = false

        fun dumpNode(node: AccessibilityNodeInfo, depth: Int, index: Int) {
            if (depth > MAX_DEPTH || totalNodes >= MAX_NODES) {
                reachedLimits = true
                return
            }
            totalNodes++

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val cls = node.className?.toString().orEmpty()
            val resId = node.viewIdResourceName.orEmpty()
            val pkg = node.packageName?.toString().orEmpty()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val boundsStr = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
            val visible = node.isVisibleToUser
            val clickable = node.isClickable
            val enabled = node.isEnabled
            val childCount = node.childCount

            val indent = "  ".repeat(depth + 1)
            xmlBuilder.append(indent)
            xmlBuilder.append("<node index=\"$index\" ")
            xmlBuilder.append("text=\"${escapeXml(text)}\" ")
            xmlBuilder.append("content-desc=\"${escapeXml(desc)}\" ")
            xmlBuilder.append("resource-id=\"${escapeXml(resId)}\" ")
            xmlBuilder.append("class=\"${escapeXml(cls)}\" ")
            xmlBuilder.append("package=\"${escapeXml(pkg)}\" ")
            xmlBuilder.append("bounds=\"$boundsStr\" ")
            xmlBuilder.append("clickable=\"$clickable\" ")
            xmlBuilder.append("enabled=\"$enabled\" ")
            xmlBuilder.append("visible-to-user=\"$visible\" ")
            xmlBuilder.append("depth=\"$depth\" ")
            xmlBuilder.append("child-count=\"$childCount\"")

            if (childCount == 0) {
                xmlBuilder.append(" />\n")
            } else {
                xmlBuilder.append(">\n")
                for (i in 0 until childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        try {
                            dumpNode(child, depth + 1, i)
                        } finally {
                            runCatching { child.recycle() }
                        }
                    }
                }
                xmlBuilder.append(indent)
                xmlBuilder.append("</node>\n")
            }
        }

        try {
            dumpNode(nonNullRoot, 0, 0)
        } finally {
            runCatching { nonNullRoot.recycle() }
        }

        xmlBuilder.append("</hierarchy>\n")
        val xmlContent = xmlBuilder.toString()

        val cacheFile = File(context.cacheDir, "card-fix-tree.xml")
        cacheFile.writeText(xmlContent, Charsets.UTF_8)

        Log.i(TAG, "Captured $totalNodes accessible nodes (limitsReached=$reachedLimits). Wrote to ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")

        assertTrue("Captured tree must contain at least 1 node", totalNodes > 0)
        assertTrue("Captured file must exist and have positive size", cacheFile.exists() && cacheFile.length() > 0)
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
