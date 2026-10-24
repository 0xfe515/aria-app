package com.ariateam.aria.licenses

import java.net.URI
import java.net.URISyntaxException

data class LicenseHtmlEntry(
    val item: LicenseItem,
    val fullText: String,
)

data class LicenseHtmlTheme(
    val background: String,
    val foreground: String,
    val muted: String,
    val link: String,
)

object LicenseHtmlDocument {
    fun build(
        entries: List<LicenseHtmlEntry>,
        nativeNotice: String?,
        theme: LicenseHtmlTheme,
    ): String = buildString {
        append(
            """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                :root { color-scheme: light dark; }
                body { margin: 0; padding: 16px 0 32px; background: ${theme.background}; color: ${theme.foreground}; font: 16px sans-serif; line-height: 1.5; }
                h2 { margin: 24px 0 8px; font-size: 1.2em; }
                p { margin: 6px 0; overflow-wrap: anywhere; }
                a { color: ${theme.link}; }
                .metadata, .project-notice { color: ${theme.muted}; }
                .license-text { white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.45 monospace; }
                section { padding-bottom: 20px; border-bottom: 1px solid ${theme.muted}55; }
                details { margin-top: 24px; }
                summary { padding: 12px 0; color: ${theme.link}; font-weight: 600; }
              </style>
            </head>
            <body>
            """.trimIndent(),
        )
        entries.forEach { entry ->
            val item = entry.item
            append("<section id=\"").appendEscaped(item.id).append("\">")
            append("<h2>").appendEscaped(item.name).append("</h2>")
            append("<p class=\"metadata\"><strong>버전:</strong> ")
                .appendEscaped(item.version)
                .append(" &middot; <strong>라이선스:</strong> ")
                .appendEscaped(item.licenseName)
                .append("</p>")
            val projectUrl = trustedProjectUrl(item.projectUrl)
            if (projectUrl == null) {
                append("<p class=\"metadata\">")
                    .appendEscaped(item.projectUrl)
                    .append("</p>")
            } else {
                append("<p><a href=\"")
                    .appendEscaped(projectUrl)
                    .append("\">")
                    .appendEscaped(item.projectUrl)
                    .append("</a></p>")
            }
            append("<pre class=\"license-text\">")
                .appendEscaped(entry.fullText)
                .append("</pre></section>")
        }
        if (nativeNotice != null) {
            append("<details><summary>동봉된 LiteRT-LM 네이티브 타사 고지 전문 보기</summary>")
            append("<p class=\"project-notice\">")
                .append("아래 고지는 LiteRT-LM AAR에 포함된 공식 타사 저작권 고지 전문입니다.")
                .append("</p><pre class=\"license-text\">")
                .appendEscaped(nativeNotice)
                .append("</pre></details>")
        }
        append("</body></html>")
    }

    fun escapeHtml(value: String): String = buildString(value.length) {
        appendEscaped(value)
    }

    private fun StringBuilder.appendEscaped(value: String): StringBuilder = apply {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private fun trustedProjectUrl(value: String): String? = try {
        URI(value).takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()
        }?.toASCIIString()
    } catch (_: URISyntaxException) {
        null
    }
}
