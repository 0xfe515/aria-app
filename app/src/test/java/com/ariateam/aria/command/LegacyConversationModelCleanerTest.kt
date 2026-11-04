package com.ariateam.aria.command

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyConversationModelCleanerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { data[key ?: ""] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { data.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { data.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    private class FakeSharedPreferences(val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun hasLegacyArtifacts_returnsFalseWhenDirectoryEmptyAndNoPrefs() {
        val downloadDir = tempFolder.newFolder("downloads_empty")
        val fakePrefs = FakeSharedPreferences()
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { downloadDir },
            preferencesProvider = { fakePrefs }
        )

        assertFalse(cleaner.hasLegacyArtifacts(null))
        assertEquals(0L, cleaner.getLegacyArtifactSizeBytes(null))
    }

    @Test
    fun hasLegacyArtifacts_detectsModelFileAndCalculatesSize() {
        val downloadDir = tempFolder.newFolder("downloads_with_model")
        val modelFile = File(downloadDir, LegacyConversationModelCleaner.FILE_MODEL)
        modelFile.writeBytes(ByteArray(2048))

        val fakePrefs = FakeSharedPreferences()
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { downloadDir },
            preferencesProvider = { fakePrefs }
        )

        assertTrue(cleaner.hasLegacyArtifacts(null))
        assertEquals(2048L, cleaner.getLegacyArtifactSizeBytes(null))
    }

    @Test
    fun hasLegacyArtifacts_detectsPendingAndMarkerFiles() {
        val downloadDir = tempFolder.newFolder("downloads_pending")
        val pendingFile = File(downloadDir, LegacyConversationModelCleaner.FILE_PENDING)
        pendingFile.writeBytes(ByteArray(1024))
        val markerFile = File(downloadDir, LegacyConversationModelCleaner.FILE_MARKER)
        markerFile.writeText("sha256_dummy_hash")

        val fakePrefs = FakeSharedPreferences()
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { downloadDir },
            preferencesProvider = { fakePrefs }
        )

        assertTrue(cleaner.hasLegacyArtifacts(null))
        assertEquals(1024L + markerFile.length(), cleaner.getLegacyArtifactSizeBytes(null))
    }

    @Test
    fun hasLegacyArtifacts_detectsActiveDownloadPref() {
        val downloadDir = tempFolder.newFolder("downloads_pref_only")
        val fakePrefs = FakeSharedPreferences(mutableMapOf("id" to 999L))
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { downloadDir },
            preferencesProvider = { fakePrefs }
        )

        assertTrue(cleaner.hasLegacyArtifacts(null))
    }

    @Test
    fun deleteLegacyArtifacts_deletesOnlyLegacyFilesAndPreservesOtherFiles() {
        val downloadDir = tempFolder.newFolder("downloads_mixed")
        val modelFile = File(downloadDir, LegacyConversationModelCleaner.FILE_MODEL)
        modelFile.writeBytes(ByteArray(1024))
        val pendingFile = File(downloadDir, LegacyConversationModelCleaner.FILE_PENDING)
        pendingFile.writeBytes(ByteArray(512))
        val markerFile = File(downloadDir, LegacyConversationModelCleaner.FILE_MARKER)
        markerFile.writeText("marker")

        // Unrelated files that MUST NOT be deleted:
        val preservedVisionModel = File(downloadDir, "walkable_seg.tflite")
        preservedVisionModel.writeBytes(ByteArray(4096))
        val preservedUserFile = File(downloadDir, "my_custom_photo.jpg")
        preservedUserFile.writeText("photo")

        val fakePrefs = FakeSharedPreferences(mutableMapOf("id" to 1234L))
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { downloadDir },
            preferencesProvider = { fakePrefs },
            downloaderCancelHandler = { true }
        )

        assertTrue(cleaner.hasLegacyArtifacts(null))

        val result = cleaner.deleteLegacyArtifacts(null)
        assertTrue("deleteLegacyArtifacts should succeed", result)

        assertFalse(modelFile.exists())
        assertFalse(pendingFile.exists())
        assertFalse(markerFile.exists())

        // Verify preserved files are still intact
        assertTrue(preservedVisionModel.exists())
        assertTrue(preservedUserFile.exists())
        assertEquals(4096, preservedVisionModel.length())
        assertEquals("photo", preservedUserFile.readText())

        assertFalse(cleaner.hasLegacyArtifacts(null))
        assertEquals(0L, cleaner.getLegacyArtifactSizeBytes(null))
    }

    @Test
    fun hasLegacyArtifacts_detectsDownloadIdEvenWhenExternalDirIsNull() {
        val fakePrefsWithId = FakeSharedPreferences(mutableMapOf(LegacyConversationModelCleaner.KEY_DOWNLOAD_ID to 42L))
        val cleanerWithId = LegacyConversationModelCleaner(
            downloadDirProvider = { null },
            preferencesProvider = { fakePrefsWithId }
        )

        assertTrue(cleanerWithId.hasLegacyArtifacts(null))
        assertEquals(0L, cleanerWithId.getLegacyArtifactSizeBytes(null))

        val fakePrefsWithoutId = FakeSharedPreferences()
        val cleanerWithoutId = LegacyConversationModelCleaner(
            downloadDirProvider = { null },
            preferencesProvider = { fakePrefsWithoutId }
        )

        assertFalse(cleanerWithoutId.hasLegacyArtifacts(null))
        assertEquals(0L, cleanerWithoutId.getLegacyArtifactSizeBytes(null))
    }

    @Test
    fun deleteLegacyArtifacts_cancelsDownloaderBeforeFiles_andPreservesPrefAndFilesOnFailure() {
        val downloadDir = tempFolder.newFolder("downloads_cancel_fail")
        val modelFile = File(downloadDir, LegacyConversationModelCleaner.FILE_MODEL)
        modelFile.writeBytes(ByteArray(1024))
        val pendingFile = File(downloadDir, LegacyConversationModelCleaner.FILE_PENDING)
        pendingFile.writeBytes(ByteArray(512))

        val fakePrefs = FakeSharedPreferences(mutableMapOf(LegacyConversationModelCleaner.KEY_DOWNLOAD_ID to 777L))
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { downloadDir },
            preferencesProvider = { fakePrefs },
            // Downloader cancellation failure:
            downloaderCancelHandler = { false }
        )

        val result = cleaner.deleteLegacyArtifacts(null)
        assertFalse("deleteLegacyArtifacts must return false immediately when downloader cancellation fails", result)
        assertEquals("Download ID must be preserved in preferences for retry", 777L, fakePrefs.map[LegacyConversationModelCleaner.KEY_DOWNLOAD_ID])

        // Critical: files must NOT be deleted if cancellation failed!
        assertTrue("Model file must remain when downloader cancellation fails", modelFile.exists())
        assertTrue("Pending file must remain when downloader cancellation fails", pendingFile.exists())
    }

    @Test
    fun deleteLegacyArtifacts_inaccessibleStorageNeverReportsSuccess_andPreservesMetadata() {
        val fakePrefs = FakeSharedPreferences(mutableMapOf(LegacyConversationModelCleaner.KEY_DOWNLOAD_ID to 999L))
        var cancelAttempted = false
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { null }, // Inaccessible storage
            preferencesProvider = { fakePrefs },
            downloaderCancelHandler = {
                cancelAttempted = true
                true
            }
        )

        val result = cleaner.deleteLegacyArtifacts(null)
        assertFalse("Inaccessible storage must never report successful cleanup", result)
        assertEquals("Retry metadata must be preserved when storage root is unavailable", 999L, fakePrefs.map[LegacyConversationModelCleaner.KEY_DOWNLOAD_ID])
        assertFalse("Downloader cancellation should not run when storage is unavailable", cancelAttempted)
    }

    @Test
    fun deleteLegacyArtifacts_cancelsDownloaderBeforeDeletingFiles() {
        val downloadDir = tempFolder.newFolder("downloads_cancel_order")
        val modelFile = File(downloadDir, LegacyConversationModelCleaner.FILE_MODEL)
        modelFile.writeBytes(ByteArray(1024))

        var fileExistedDuringCancellation = false
        val fakePrefs = FakeSharedPreferences(mutableMapOf(LegacyConversationModelCleaner.KEY_DOWNLOAD_ID to 888L))
        val cleaner = LegacyConversationModelCleaner(
            downloadDirProvider = { downloadDir },
            preferencesProvider = { fakePrefs },
            downloaderCancelHandler = {
                fileExistedDuringCancellation = modelFile.exists()
                true
            }
        )

        val result = cleaner.deleteLegacyArtifacts(null)
        assertTrue(result)
        assertTrue("Model file must still exist when downloader is cancelled to verify correct cancellation ordering", fileExistedDuringCancellation)
        assertFalse(modelFile.exists())
        assertFalse(fakePrefs.map.containsKey(LegacyConversationModelCleaner.KEY_DOWNLOAD_ID))
    }
}
