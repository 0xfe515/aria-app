package com.ariateam.aria.licenses

enum class LicenseCategory(val displayName: String) {
    NativeBinary("네이티브 및 온디바이스 엔진"),
    AndroidJvm("AndroidX & 런타임 라이브러리"),
    ModelAsset("모델 및 자산"),
}

/**
 * Metadata for a third-party open-source component.
 *
 * @param id Unique identifier.
 * @param name User-visible project name.
 * @param version Resolved dependency or asset version.
 * @param projectUrl Upstream project URL.
 * @param licenseName Exact SPDX or official license identifier.
 * @param category Category grouping.
 * @param projectNotice Specific upstream copyright/NOTICE text without fabricated dates/authors.
 * @param licenseAssetPath Path in assets to the authoritative full license text (e.g. Apache-2.0, MIT). Empty if NOT PROVIDED.
 * @param thirdPartyNoticeAssetPath Path in assets to bundled native third-party notice (e.g. for LiteRT-LM), if any.
 */
data class LicenseItem(
    val id: String,
    val name: String,
    val version: String,
    val projectUrl: String,
    val licenseName: String,
    val category: LicenseCategory,
    val projectNotice: String = "",
    val licenseAssetPath: String = "licenses/apache-2.0.txt",
    val thirdPartyNoticeAssetPath: String? = null,
)
