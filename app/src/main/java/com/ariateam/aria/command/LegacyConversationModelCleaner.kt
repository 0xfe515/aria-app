package com.ariateam.aria.command

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Manages detection and explicit one-time cleanup of legacy on-device Gemma conversation
 * model files and temporary download artifacts.
 *
 * Rules:
 * 1. Cancels legacy DownloadManager entry before deleting files to prevent active downloader from writing new data.
 * 2. Immediately returns false without file deletion if downloader cancellation fails.
 * 3. Inaccessible storage root never reports successful cleanup; returns false conservatively without clearing retry metadata.
 * 4. Strictly deletes only:
 *    - gemma-e2b.litertlm (model binary)
 *    - gemma.pending (in-flight download file)
 *    - gemma.verified (integrity hash marker)
 *    - gemma_download SharedPreferences and pending DownloadManager queue entry
 * 5. Explicitly preserves API key, ASR models, vision models, and user profiles.
 */
open class LegacyConversationModelCleaner internal constructor(
    private val downloadDirProvider: ((Context?) -> File?)? = null,
    private val preferencesProvider: ((Context?) -> SharedPreferences?)? = null,
    private val downloaderCancelHandler: ((Long) -> Boolean)? = null,
) {
    companion object : LegacyConversationModelCleaner() {
        private const val TAG = "LegacyModelCleaner"
        private const val PREFS_NAME = "gemma_download"
        const val KEY_DOWNLOAD_ID = "id"
        const val FILE_MODEL = "gemma-e2b.litertlm"
        const val FILE_PENDING = "gemma.pending"
        const val FILE_MARKER = "gemma.verified"
    }

    private fun getPrefs(context: Context?): SharedPreferences? =
        preferencesProvider?.invoke(context) ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDownloadDir(context: Context?): File? =
        downloadDirProvider?.invoke(context) ?: context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    fun hasLegacyArtifacts(context: Context?): Boolean {
        val prefs = getPrefs(context)
        val hasDownloadId = (prefs?.getLong(KEY_DOWNLOAD_ID, -1L) ?: -1L) >= 0L

        val root = getDownloadDir(context)
        if (root == null) {
            // Preferences-only remnants must be detectable even if external directory is unavailable
            return hasDownloadId
        }

        val modelFile = File(root, FILE_MODEL)
        val pendingFile = File(root, FILE_PENDING)
        val markerFile = File(root, FILE_MARKER)
        return modelFile.exists() || pendingFile.exists() || markerFile.exists() || hasDownloadId
    }

    fun getLegacyArtifactSizeBytes(context: Context?): Long {
        val root = getDownloadDir(context) ?: return 0L
        var totalBytes = 0L
        listOf(FILE_MODEL, FILE_PENDING, FILE_MARKER).forEach { filename ->
            val file = File(root, filename)
            if (file.exists()) {
                totalBytes += file.length()
            }
        }
        return totalBytes
    }

    /**
     * Executes explicit deletion of legacy conversation model artifacts.
     *
     * Rules:
     * - If storage root is unavailable, returns false conservatively without clearing metadata (preserves retry).
     * - Cancels active DownloadManager task first. If cancellation fails, returns false immediately without deleting files.
     * - Deletes files only after cancellation succeeds.
     *
     * @return true if all detected legacy files were removed successfully and downloader cancelled.
     */
    fun deleteLegacyArtifacts(context: Context?): Boolean {
        val root = getDownloadDir(context)
        if (root == null) {
            // Inaccessible storage: cannot guarantee or verify file deletion.
            // Conservatively return false without modifying preferences to preserve retry metadata.
            return false
        }

        val prefs = getPrefs(context)
        val id = prefs?.getLong(KEY_DOWNLOAD_ID, -1L) ?: -1L
        if (id >= 0L) {
            var cancellationSucceeded = true
            try {
                if (downloaderCancelHandler != null) {
                    cancellationSucceeded = downloaderCancelHandler.invoke(id)
                } else if (context != null) {
                    val manager = context.getSystemService(DownloadManager::class.java)
                    if (manager != null) {
                        val removed = manager.remove(id)
                        if (removed < 0) {
                            cancellationSucceeded = false
                        }
                    } else {
                        cancellationSucceeded = false
                    }
                } else {
                    cancellationSucceeded = false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DownloadManager cancellation failed for id $id", t)
                cancellationSucceeded = false
            }

            if (!cancellationSucceeded) {
                // Immediately return false without deleting files if cancellation failed.
                // Preserves KEY_DOWNLOAD_ID in preferences for subsequent retry.
                return false
            }

            // Cancellation succeeded: safely clear the active download id
            prefs?.edit()?.remove(KEY_DOWNLOAD_ID)?.apply()
        }

        var overallSuccess = true
        listOf(FILE_MODEL, FILE_PENDING, FILE_MARKER).forEach { filename ->
            val file = File(root, filename)
            if (file.exists()) {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                if (!deleted) {
                    Log.w(TAG, "Failed to delete legacy artifact: ${file.absolutePath}")
                    overallSuccess = false
                }
            }
        }

        return overallSuccess
    }
}
