package com.ariateam.aria.licenses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesScreenTest {
    @Test
    fun htmlEscapesComponentAndLicenseContentAndDoesNotLinkUnsafeProjectUrls() {
        val item = LicenseItem(
            id = "unsafe",
            name = "<script>alert('name')</script>",
            version = "1 < 2",
            projectUrl = "javascript:alert(1)",
            licenseName = "NOT PROVIDED",
            category = LicenseCategory.ModelAsset,
            projectNotice = "<img src=x onerror=alert(1)>",
        )

        val html = LicenseHtmlDocument.build(
            entries = listOf(
                LicenseHtmlEntry(
                    item,
                    "${item.projectNotice}\nTerms </pre><script>alert('license')</script>",
                ),
            ),
            nativeNotice = "Native notice </pre><script>alert('native')</script>",
            theme = testTheme,
        )

        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("&lt;script&gt;alert(&#39;name&#39;)&lt;/script&gt;"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(html.contains("Terms &lt;/pre&gt;&lt;script&gt;"))
        assertTrue(html.contains("Native notice &lt;/pre&gt;&lt;script&gt;"))
        assertTrue(html.contains("NOT PROVIDED"))
    }

    @Test
    fun htmlContainsEveryInventoryEntryAndTheNativeNoticeOnce() {
        val entries = LicenseRepository.allLicenses.map { item ->
            LicenseHtmlEntry(item, "Full terms for ${item.id}")
        }
        val nativeNotice = "Complete bundled native notice"

        val html = LicenseHtmlDocument.build(entries, nativeNotice, testTheme)

        LicenseRepository.allLicenses.forEach { item ->
            assertTrue("Missing component ${item.id}", html.contains(LicenseHtmlDocument.escapeHtml(item.name)))
            assertTrue("Missing version for ${item.id}", html.contains(LicenseHtmlDocument.escapeHtml(item.version)))
            assertTrue("Missing license for ${item.id}", html.contains(LicenseHtmlDocument.escapeHtml(item.licenseName)))
            assertTrue("Missing full terms for ${item.id}", html.contains("Full terms for ${item.id}"))
        }
        assertTrue(html.contains(nativeNotice))
        assertTrue(html.indexOf(nativeNotice) == html.lastIndexOf(nativeNotice))
    }

    private companion object {
        val testTheme = LicenseHtmlTheme(
            background = "#ffffff",
            foreground = "#000000",
            muted = "#444444",
            link = "#0000ff",
        )
    }
}
