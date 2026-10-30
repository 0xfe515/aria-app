package com.ariateam.aria.vision

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class VisionPortraitModelMigrationTest {
    @Test
    fun portraitAssetAndTensorContractAreNativePortrait() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = readAsset(context, VisionContract.DEFAULT_ASSET_MODEL_PATH)

        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_BYTES, model.size.toLong())
        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_SHA256, sha256(model))
        assertEquals(
            intArrayOf(1, 512, 288, 3).toList(),
            VisionContract.EXPECTED_INPUT_SHAPE.toList(),
        )
        assertEquals(
            intArrayOf(1, 512, 288, 7).toList(),
            VisionContract.EXPECTED_OUTPUT_SHAPE.toList(),
        )
        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_PATH, VisionContract.OPTIMIZED_ASSET_MODEL_PATH)
        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_SHA256, VisionContract.OPTIMIZED_ASSET_MODEL_SHA256)
    }

    @Test
    fun freshRestoreAndExplicitProvisioningUsePortraitAsset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = createCleanStore(context)
        try {
            assertPortraitReady(store, store.restorePersistedModelSync())

            store.clearModelSync()
            assertPortraitReady(store, store.provisionDefaultAssetModelSync())

            store.clearModelSync()
            assertPortraitReady(store, store.provisionOptimizedAssetModelSync())
        } finally {
            closeAndClear(store)
        }
    }

    @Test
    fun bothKnownBuiltInLandscapeSlotsMigrateWithoutClearingData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyModels = listOf(
            LegacyModel(
                path = VisionContract.LEGACY_DEFAULT_ASSET_MODEL_PATH,
                name = VisionContract.LEGACY_DEFAULT_ASSET_MODEL_NAME,
                sha256 = VisionContract.LEGACY_DEFAULT_ASSET_MODEL_SHA256,
                sizeBytes = VisionContract.LEGACY_DEFAULT_ASSET_MODEL_BYTES,
            ),
            LegacyModel(
                path = VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_PATH,
                name = VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_NAME,
                sha256 = VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_SHA256,
                sizeBytes = VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_BYTES,
            ),
        )

        for (legacy in legacyModels) {
            resetPersistedStore(context)
            val legacyBytes = readAsset(context, legacy.path)
            assertEquals(legacy.sizeBytes, legacyBytes.size.toLong())
            assertEquals(legacy.sha256, sha256(legacyBytes))

            val visionDirectory = File(context.noBackupFilesDir, "vision").apply { mkdirs() }
            val legacySlot = File(visionDirectory, VisionModelStore.SLOT_0_FILENAME)
            legacySlot.writeBytes(legacyBytes)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_SLOT_FILE, legacySlot.name)
                .putString(KEY_DISPLAY_NAME, legacy.name)
                .putLong(KEY_SIZE_BYTES, legacy.sizeBytes)
                .putString(KEY_SHA256, legacy.sha256)
                .putLong(KEY_IMPORTED_AT, 123L)
                .putBoolean(KEY_USER_CLEARED, false)
                .commit()

            val store = VisionModelStore(context, restoreOnInit = false)
            try {
                assertPortraitReady(store, store.restorePersistedModelSync())
                assertEquals(VisionModelStore.SLOT_1_FILENAME, store.activeModelFile.name)
                assertFalse("Migrated source slot should be retired after activation", legacySlot.exists())
                assertFalse(
                    "Migration must not require clearing user data",
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(KEY_USER_CLEARED, true),
                )
            } finally {
                closeAndClear(store)
            }
        }
    }

    @Test
    fun alreadyPortraitPersistedModelRestoresWithoutChangingItsSlot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = createCleanStore(context)
        try {
            assertPortraitReady(store, store.provisionDefaultAssetModelSync())
            val activePath = store.activeModelFile.absolutePath
            val activeSlot = store.activeModelFile.name
            val modifiedAt = store.activeModelFile.lastModified()

            assertPortraitReady(store, store.restorePersistedModelSync())

            assertEquals(activePath, store.activeModelFile.absolutePath)
            assertEquals(activeSlot, store.activeModelFile.name)
            assertEquals(modifiedAt, store.activeModelFile.lastModified())
        } finally {
            closeAndClear(store)
        }
    }

    @Test
    fun explicitlyClearedModelDoesNotMigrateEvenIfLegacySlotRemains() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = createCleanStore(context)
        store.clearModelSync()
        store.close()

        val legacyBytes = readAsset(context, VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_PATH)
        val legacySlot = File(File(context.noBackupFilesDir, "vision"), VisionModelStore.SLOT_0_FILENAME)
        legacySlot.writeBytes(legacyBytes)
        val restoredStore = VisionModelStore(context, restoreOnInit = false)
        try {
            assertEquals(VisionModelState.Missing, restoredStore.restorePersistedModelSync())
            assertFalse(restoredStore.segmenter.isReady)
            assertEquals(VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_SHA256, sha256(legacySlot.readBytes()))
        } finally {
            closeAndClear(restoredStore)
        }
    }

    @Test
    fun customLandscapeImportIsRejectedWithoutReplacingActivePortraitModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = createCleanStore(context)
        try {
            assertPortraitReady(store, store.provisionDefaultAssetModelSync())
            val activePath = store.activeModelFile.absolutePath
            val activeHash = sha256(store.activeModelFile.readBytes())
            val customLandscape = readAsset(context, VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_PATH)

            val result = store.importFromStreamSync(
                input = ByteArrayInputStream(customLandscape),
                displayName = "custom_landscape.tflite",
            )

            assertTrue("The active model should remain ready: $result", result is VisionModelState.Ready)
            val ready = result as VisionModelState.Ready
            assertNotNull("The rejected custom import must explain why it was rejected", ready.rejectedImportNotice)
            assertEquals(activeHash, ready.metadata.sha256)
            assertEquals(activePath, store.activeModelFile.absolutePath)
            assertEquals(activeHash, sha256(store.activeModelFile.readBytes()))
        } finally {
            closeAndClear(store)
        }
    }

    @Test
    fun unknownLandscapePersistedModelIsRejectedWithoutDeletingUserData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetPersistedStore(context)
        val legacyBytes = readAsset(context, VisionContract.LEGACY_OPTIMIZED_ASSET_MODEL_PATH)
        val customBytes = legacyBytes + byteArrayOf(0)
        val customHash = sha256(customBytes)
        val customSlot = File(
            File(context.noBackupFilesDir, "vision").apply { mkdirs() },
            VisionModelStore.SLOT_0_FILENAME,
        )
        customSlot.writeBytes(customBytes)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_SLOT_FILE, customSlot.name)
            .putString(KEY_DISPLAY_NAME, "custom_landscape.tflite")
            .putLong(KEY_SIZE_BYTES, customBytes.size.toLong())
            .putString(KEY_SHA256, customHash)
            .putLong(KEY_IMPORTED_AT, 456L)
            .putBoolean(KEY_USER_CLEARED, false)
            .commit()

        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            val result = store.restorePersistedModelSync()

            assertTrue("Unsupported custom model must report an error: $result", result is VisionModelState.Error)
            assertTrue("The custom model file must be preserved", customSlot.isFile)
            assertEquals(customHash, sha256(customSlot.readBytes()))
            assertEquals(
                customHash,
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SHA256, null),
            )
        } finally {
            closeAndClear(store)
        }
    }

    private fun createCleanStore(context: Context): VisionModelStore {
        resetPersistedStore(context)
        return VisionModelStore(context, restoreOnInit = false)
    }

    private fun resetPersistedStore(context: Context) {
        val visionDirectory = File(context.noBackupFilesDir, "vision")
        listOf(
            VisionModelStore.SLOT_0_FILENAME,
            VisionModelStore.SLOT_1_FILENAME,
            VisionContract.STAGING_MODEL_FILENAME,
        ).forEach { File(visionDirectory, it).delete() }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun closeAndClear(store: VisionModelStore) {
        try {
            store.clearModelSync()
        } finally {
            store.close()
        }
    }

    private fun assertPortraitReady(store: VisionModelStore, state: VisionModelState) {
        assertTrue("Expected a ready portrait model, got $state", state is VisionModelState.Ready)
        val ready = state as VisionModelState.Ready
        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_NAME, ready.metadata.displayName)
        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_BYTES, ready.metadata.sizeBytes)
        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_SHA256, ready.metadata.sha256)
        assertTrue(store.segmenter.isReady)
        assertEquals(VisionContract.DEFAULT_ASSET_MODEL_SHA256, sha256(store.activeModelFile.readBytes()))
    }

    private fun readAsset(context: Context, path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }

    private data class LegacyModel(
        val path: String,
        val name: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private companion object {
        const val PREFS_NAME = "vision_model_store"
        const val KEY_ACTIVE_SLOT_FILE = "active_slot_file"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_SHA256 = "sha256"
        const val KEY_IMPORTED_AT = "imported_at"
        const val KEY_USER_CLEARED = "user_cleared_model"
    }
}
