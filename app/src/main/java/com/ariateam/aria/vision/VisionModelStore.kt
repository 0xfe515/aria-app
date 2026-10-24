package com.ariateam.aria.vision

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages offline `.tflite` model import, bounded stream copy, SHA-256 computation,
 * tensor contract validation, and persistence in `context.noBackupFilesDir/vision`.
 *
 * Guarantees:
 * - Never copies unbounded streams (`> 64 MiB` or `0` bytes rejected).
 * - Uses two alternating slot files (`walkable_seg_aria_slot0.tflite` / `walkable_seg_aria_slot1.tflite`)
 *   and same-directory `ATOMIC_MOVE` from staging to the inactive slot before memory-mapping, so an
 *   active memory-mapped model file is NEVER overwritten or truncated in place.
 * - Prepares a single candidate `Interpreter` on the inactive slot file; if move, preparation, or activation
 *   fails, rolls back the inactive slot so the existing validated model file, metadata, and runtime remain intact.
 * - Synchronizes terminal [closed] state and enqueues final unload after queued jobs so a pending import
 *   cannot reload a model after [close].
 */
class VisionModelStore(
    private val context: Context,
    val segmenter: LiteRtVisionSegmenter = LiteRtVisionSegmenter(),
    restoreOnInit: Boolean = true,
    private val mutableState: MutableStateFlow<VisionModelState> = MutableStateFlow(VisionModelState.Missing),
    private val mutableInitialRestoreCompleted: MutableStateFlow<Boolean> = MutableStateFlow(!restoreOnInit),
) : AutoCloseable {
    val isInitialRestoreCompleted: StateFlow<Boolean> = mutableInitialRestoreCompleted.asStateFlow()
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aria-vision-model-store").apply { isDaemon = true }
    }
    internal val visionDir: File
        get() = File(context.noBackupFilesDir, "vision").apply { mkdirs() }
    private val stagingModelFile: File
        get() = File(visionDir, VisionContract.STAGING_MODEL_FILENAME)

    val activeModelFile: File
        get() {
            val activeSlotName = prefs.getString(KEY_ACTIVE_SLOT_FILE, SLOT_0_FILENAME)
                ?.takeIf { it == SLOT_0_FILENAME || it == SLOT_1_FILENAME }
                ?: SLOT_0_FILENAME
            return File(visionDir, activeSlotName)
        }

    private fun inactiveSlotFile(): File {
        val currentName = prefs.getString(KEY_ACTIVE_SLOT_FILE, null)
        val nextName = if (currentName == SLOT_0_FILENAME) SLOT_1_FILENAME else SLOT_0_FILENAME
        return File(visionDir, nextName)
    }

    val state: StateFlow<VisionModelState> = mutableState.asStateFlow()

    @Volatile
    private var activeMetadata: VisionModelMetadata? = null

    init {
        if (restoreOnInit) {
            restorePersistedModelAsync()
        }
    }

    fun restorePersistedModelAsync() {
        synchronized(stateLock) {
            if (closed.get()) return
            mutableInitialRestoreCompleted.value = false
            mutableState.value = VisionModelState.Loading(
                prefs.getString(KEY_DISPLAY_NAME, VisionContract.DEFAULT_MODEL_FILENAME)
                    ?: VisionContract.DEFAULT_MODEL_FILENAME,
            )
            try {
                executor.execute {
                    try {
                        if (!closed.get()) {
                            restorePersistedModelSync()
                        }
                    } catch (error: Exception) {
                        publishRestoreFailure(error)
                    } finally {
                        mutableInitialRestoreCompleted.value = true
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                publishRestoreFailure(error)
                mutableInitialRestoreCompleted.value = true
            }
        }
    }

    fun getPreferredBackend(): VisionBackendType {
        val saved = prefs.getString(KEY_PREFERRED_BACKEND, null)
        if (saved != null) {
            return runCatching { VisionBackendType.valueOf(saved) }.getOrDefault(VisionBackendType.CPU)
        }
        val isGpuSupported = runCatching {
            org.tensorflow.lite.gpu.CompatibilityList().use { it.isDelegateSupportedOnThisDevice }
        }.getOrDefault(false)
        return if (isGpuSupported) VisionBackendType.GPU else VisionBackendType.CPU
    }

    fun getPreferredCpuThreads(): Int {
        return prefs.getInt(KEY_PREFERRED_CPU_THREADS, VisionContract.DEFAULT_CPU_THREADS)
    }

    fun setPreferredBackend(backend: VisionBackendType, threads: Int = VisionContract.DEFAULT_CPU_THREADS) {
        prefs.edit()
            .putString(KEY_PREFERRED_BACKEND, backend.name)
            .putInt(KEY_PREFERRED_CPU_THREADS, threads)
            .apply()
        segmenter.configureBackend(backend, threads)
    }

    internal fun restorePersistedModelSync(): VisionModelState {
        try {
            if (closed.get()) return VisionModelState.Missing
            val preferredBackend = getPreferredBackend()
            val preferredThreads = getPreferredCpuThreads()
            segmenter.configureBackend(preferredBackend, preferredThreads)

            val file = activeModelFile
            if (!file.isFile || file.length() <= 0L) {
                val userCleared = prefs.getBoolean(KEY_USER_CLEARED, false)
                if (!userCleared) {
                    if (hasOptimizedAssetModel()) {
                        val provisionOutcome = provisionOptimizedAssetModelSync()
                        if (provisionOutcome is VisionModelState.Ready) {
                            return provisionOutcome
                        }
                    }
                    if (hasDefaultAssetModel()) {
                        val provisionOutcome = provisionDefaultAssetModelSync()
                        if (provisionOutcome is VisionModelState.Ready) {
                            return provisionOutcome
                        }
                    }
                }
                clearPersistedPrefs(preserveClearedFlag = userCleared)
                segmenter.unloadModel()
                activeMetadata = null
                val missing = VisionModelState.Missing
                mutableState.value = missing
                return missing
            }

            val displayName = prefs.getString(KEY_DISPLAY_NAME, VisionContract.DEFAULT_MODEL_FILENAME)
                ?: VisionContract.DEFAULT_MODEL_FILENAME
            mutableState.value = VisionModelState.Loading(displayName)

            if (file.length() > VisionContract.MAX_MODEL_BYTES) {
                runCatching { file.delete() }
                clearPersistedPrefs()
                segmenter.unloadModel()
                activeMetadata = null
                val err = VisionModelState.Error(
                    reason = "저장된 모델이 크기 제한(64 MB)을 초과하여 해제되었습니다.",
                    detail = "size=${file.length()}",
                )
                mutableState.value = err
                return err
            }

            val actualSha256 = runCatching { computeSha256(file) }.getOrDefault("")
            val expectedSha256 = prefs.getString(KEY_SHA256, "").orEmpty()
            if (actualSha256.isEmpty() || (expectedSha256.isNotEmpty() && !actualSha256.equals(expectedSha256, ignoreCase = true))) {
                runCatching { file.delete() }
                clearPersistedPrefs()
                segmenter.unloadModel()
                activeMetadata = null
                val err = VisionModelState.Error(
                    reason = "저장된 모델의 SHA-256 무결성 검증에 실패했습니다.",
                    detail = "sha256_mismatch",
                )
                mutableState.value = err
                return err
            }

            return when (val outcome = segmenter.prepareModelSession(file)) {
                is LiteRtVisionSegmenter.PrepareOutcome.Valid -> {
                    if (closed.get()) {
                        outcome.session.close()
                        return VisionModelState.Missing
                    }
                    segmenter.activatePreparedSession(outcome.session, preferredBackend, preferredThreads)
                    val importedAt = prefs.getLong(KEY_IMPORTED_AT, file.lastModified())
                    val metadata = VisionModelMetadata(
                        displayName = displayName,
                        sizeBytes = file.length(),
                        sha256 = actualSha256,
                        importedAtMillis = importedAt,
                    )
                    savePersistedPrefs(file.name, metadata)
                    activeMetadata = metadata
                    val ready = VisionModelState.Ready(metadata)
                    mutableState.value = ready
                    ready
                }
                is LiteRtVisionSegmenter.PrepareOutcome.Invalid -> {
                    runCatching { file.delete() }
                    clearPersistedPrefs()
                    segmenter.unloadModel()
                    activeMetadata = null
                    val err = VisionModelState.Error(
                        reason = outcome.failure.koreanMessage,
                        detail = outcome.failure.detail,
                    )
                    mutableState.value = err
                    err
                }
            }
        } catch (error: Exception) {
            val failure = VisionModelState.Error(
                reason = "주변 인식을 준비하는 중 문제가 발생했습니다.",
                detail = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}",
            )
            mutableState.value = failure
            return failure
        } finally {
            mutableInitialRestoreCompleted.value = true
        }
    }

    fun importFromUri(uri: Uri) {
        synchronized(stateLock) {
            if (closed.get()) return
            executor.execute {
                if (closed.get()) return@execute
                val displayName = resolveDisplayName(uri)
                val openResult = runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("선택한 파일을 열 수 없습니다.")
                }
                val stream = openResult.getOrElse { error ->
                    handleImportFailure(
                        reason = "파일 열기 실패: ${error.javaClass.simpleName}",
                        detail = error.message.orEmpty().take(120),
                    )
                    return@execute
                }
                stream.use { input ->
                    importFromStreamSync(input, displayName)
                }
            }
        }
    }

    /**
     * Synchronous stream import helper (directly testable in unit and instrumented tests).
     *
     * Flow:
     * 1. Stream-copy bounded bytes into `stagingModelFile` while computing SHA-256.
     * 2. Atomically move `stagingModelFile` into `inactiveSlotFile` (`ATOMIC_MOVE` within `visionDir`)
     *    without touching `activeModelFile`.
     * 3. Prepare and validate the candidate `Interpreter` ONCE on `inactiveSlotFile`.
     * 4. Commit metadata and swap active session. On any failure at steps 1–4, delete `inactiveSlotFile` / `stagingModelFile`
     *    and keep `activeModelFile`, persisted metadata, and the active runtime untouched.
     */
    internal fun importFromStreamSync(
        input: InputStream,
        displayName: String,
        maxBytes: Long = VisionContract.MAX_MODEL_BYTES,
        mover: (File, File) -> Boolean = ::atomicMoveSameDirectory,
        sessionPreparer: (File) -> LiteRtVisionSegmenter.PrepareOutcome = segmenter::prepareModelSession,
        sessionSwapper: (LiteRtVisionSegmenter.PreparedModelSession) -> AutoCloseable? = { session ->
            segmenter.activatePreparedSession(session, getPreferredBackend(), getPreferredCpuThreads())
            null
        },
        prefsCommitter: (String, VisionModelMetadata) -> Boolean = ::savePersistedPrefs,
        runtimeReadyCheck: () -> Boolean = { segmenter.isReady },
    ): VisionModelState {
        if (closed.get()) return VisionModelState.Missing
        val safeName = sanitizeFileName(displayName)
        mutableState.value = VisionModelState.Loading(safeName)

        val staging = stagingModelFile
        val candidateSlot = inactiveSlotFile()
        val previousSlot = activeModelFile
        val previousMetadata = activeMetadata
        runCatching { staging.delete() }
        runCatching { candidateSlot.delete() }

        val copyOutcome = copyBoundedWithSha256(input, staging, maxBytes)
        if (copyOutcome !is BoundedCopyOutcome.Success) {
            runCatching { staging.delete() }
            val failure = copyOutcome as BoundedCopyOutcome.Failure
            return handleImportFailure(
                reason = failure.koreanMessage,
                detail = failure.code,
                runtimeReadyCheck = runtimeReadyCheck,
            )
        }

        val movedToSlot = runCatching { mover(staging, candidateSlot) }.getOrDefault(false)
        if (!movedToSlot || !candidateSlot.isFile) {
            runCatching { staging.delete() }
            runCatching { candidateSlot.delete() }
            return handleImportFailure(
                reason = "모델 파일을 앱 전용 슬롯으로 원자적 이동하지 못했습니다.",
                detail = "atomic_move_failed",
                runtimeReadyCheck = runtimeReadyCheck,
            )
        }

        val prepareOutcome = sessionPreparer(candidateSlot)
        if (prepareOutcome is LiteRtVisionSegmenter.PrepareOutcome.Invalid) {
            runCatching { candidateSlot.delete() }
            return handleImportFailure(
                reason = prepareOutcome.failure.koreanMessage,
                detail = prepareOutcome.failure.detail,
                runtimeReadyCheck = runtimeReadyCheck,
            )
        }

        val preparedSession = (prepareOutcome as LiteRtVisionSegmenter.PrepareOutcome.Valid).session
        if (closed.get()) {
            preparedSession.close()
            runCatching { candidateSlot.delete() }
            return VisionModelState.Missing
        }

        val metadata = VisionModelMetadata(
            displayName = safeName,
            sizeBytes = copyOutcome.bytesWritten,
            sha256 = copyOutcome.sha256Hex,
            importedAtMillis = System.currentTimeMillis(),
        )

        // Step 1: Commit metadata to SharedPreferences BEFORE touching the live interpreter.
        val prefsCommitted = runCatching { prefsCommitter(candidateSlot.name, metadata) }.getOrDefault(false)
        if (!prefsCommitted) {
            preparedSession.close()
            runCatching { candidateSlot.delete() }
            if (previousMetadata != null) {
                runCatching { savePersistedPrefs(previousSlot.name, previousMetadata) }
            } else {
                clearPersistedPrefs()
            }
            return handleImportFailure(
                reason = "모델 메타데이터 저장에 실패했습니다.",
                detail = "prefs_commit_failed",
                runtimeReadyCheck = runtimeReadyCheck,
            )
        }

        // Step 2: Swap in the prepared session while retaining old session handle until activation succeeds.
        val oldSessionCloseable: AutoCloseable? = try {
            sessionSwapper(preparedSession)
        } catch (t: Throwable) {
            preparedSession.close()
            runCatching { candidateSlot.delete() }
            if (previousMetadata != null) {
                runCatching { savePersistedPrefs(previousSlot.name, previousMetadata) }
            } else {
                clearPersistedPrefs()
            }
            return handleImportFailure(
                reason = "모델 활성화 중 오류가 발생했습니다 (${t.javaClass.simpleName}).",
                detail = "activation_failed",
                runtimeReadyCheck = runtimeReadyCheck,
            )
        }

        // Step 3: Both commit and swap succeeded; now close the old interpreter and remove the old slot file.
        activeMetadata = metadata
        runCatching { oldSessionCloseable?.close() }
        if (previousSlot.absolutePath != candidateSlot.absolutePath) {
            runCatching { previousSlot.delete() }
        }
        val ready = VisionModelState.Ready(metadata)
        mutableState.value = ready
        return ready
    }

    fun hasDefaultAssetModel(): Boolean {
        return runCatching {
            context.assets.open(VisionContract.DEFAULT_ASSET_MODEL_PATH).use { true }
        }.getOrDefault(false)
    }

    fun hasOptimizedAssetModel(): Boolean {
        return runCatching {
            context.assets.open(VisionContract.OPTIMIZED_ASSET_MODEL_PATH).use { true }
        }.getOrDefault(false)
    }

    fun provisionDefaultAssetModelAsync() {
        synchronized(stateLock) {
            if (closed.get()) return
            executor.execute {
                if (!closed.get()) {
                    provisionDefaultAssetModelSync()
                }
            }
        }
    }

    fun provisionOptimizedAssetModelAsync() {
        synchronized(stateLock) {
            if (closed.get()) return
            executor.execute {
                if (!closed.get()) {
                    provisionOptimizedAssetModelSync()
                }
            }
        }
    }

    fun provisionOptimizedAssetModelSync(): VisionModelState {
        if (closed.get()) return VisionModelState.Missing
        val openResult = runCatching {
            context.assets.open(VisionContract.OPTIMIZED_ASSET_MODEL_PATH)
        }
        val stream = openResult.getOrElse { error ->
            val err = VisionModelState.Error(
                reason = "최적화 번들 모델 에셋을 열 수 없습니다.",
                detail = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}",
            )
            mutableState.value = err
            return err
        }
        val preferred = getPreferredBackend()
        val threads = getPreferredCpuThreads()
        return stream.use { input ->
            provisionDefaultAssetStreamSync(
                input = input,
                expectedBytes = VisionContract.OPTIMIZED_ASSET_MODEL_BYTES,
                expectedSha256 = VisionContract.OPTIMIZED_ASSET_MODEL_SHA256,
                displayName = VisionContract.OPTIMIZED_ASSET_MODEL_NAME,
                targetBackend = preferred,
                targetCpuThreads = threads,
            )
        }
    }

    fun provisionDefaultAssetModelSync(): VisionModelState {
        if (closed.get()) return VisionModelState.Missing
        val openResult = runCatching {
            context.assets.open(VisionContract.DEFAULT_ASSET_MODEL_PATH)
        }
        val stream = openResult.getOrElse { error ->
            val err = VisionModelState.Error(
                reason = "기본 번들 모델 에셋을 열 수 없습니다.",
                detail = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}",
            )
            mutableState.value = err
            return err
        }
        // Original un-unfused model has 140 CPU fallback partitions on GPU; configure CPU unless user explicitly chose otherwise
        val explicitPref = prefs.getString(KEY_PREFERRED_BACKEND, null)
        val desiredBackend = if (explicitPref != null) {
            getPreferredBackend()
        } else {
            VisionBackendType.CPU
        }
        val threads = getPreferredCpuThreads()
        return stream.use { input ->
            provisionDefaultAssetStreamSync(
                input = input,
                targetBackend = desiredBackend,
                targetCpuThreads = threads,
            )
        }
    }

    internal fun provisionDefaultAssetStreamSync(
        input: InputStream,
        expectedBytes: Long = VisionContract.DEFAULT_ASSET_MODEL_BYTES,
        expectedSha256: String = VisionContract.DEFAULT_ASSET_MODEL_SHA256,
        displayName: String = VisionContract.DEFAULT_ASSET_MODEL_NAME,
        targetBackend: VisionBackendType? = null,
        targetCpuThreads: Int? = null,
    ): VisionModelState {
        if (closed.get()) return VisionModelState.Missing
        val safeName = sanitizeFileName(displayName)
        mutableState.value = VisionModelState.Loading(safeName)

        val staging = stagingModelFile
        val candidateSlot = inactiveSlotFile()
        val previousSlot = activeModelFile
        val previousMetadata = activeMetadata
        runCatching { staging.delete() }
        runCatching { candidateSlot.delete() }

        val copyOutcome = copyBoundedWithSha256(input, staging, VisionContract.MAX_MODEL_BYTES)
        if (copyOutcome !is BoundedCopyOutcome.Success) {
            runCatching { staging.delete() }
            val failure = copyOutcome as BoundedCopyOutcome.Failure
            return handleImportFailure(
                reason = failure.koreanMessage,
                detail = failure.code,
            )
        }

        if (expectedBytes > 0L && copyOutcome.bytesWritten != expectedBytes) {
            runCatching { staging.delete() }
            return handleImportFailure(
                reason = "기본 번들 모델 크기 불일치 (예상: $expectedBytes B, 실제: ${copyOutcome.bytesWritten} B)",
                detail = "default_model_size_mismatch",
            )
        }
        if (expectedSha256.isNotEmpty() && !copyOutcome.sha256Hex.equals(expectedSha256, ignoreCase = true)) {
            runCatching { staging.delete() }
            return handleImportFailure(
                reason = "기본 번들 모델 SHA-256 무결성 검증 실패",
                detail = "default_model_sha256_mismatch",
            )
        }

        val movedToSlot = runCatching { atomicMoveSameDirectory(staging, candidateSlot) }.getOrDefault(false)
        if (!movedToSlot || !candidateSlot.isFile) {
            runCatching { staging.delete() }
            runCatching { candidateSlot.delete() }
            return handleImportFailure(
                reason = "기본 모델 파일을 앱 전용 슬롯으로 원자적 이동하지 못했습니다.",
                detail = "atomic_move_failed",
            )
        }

        val prepareOutcome = segmenter.prepareModelSession(candidateSlot)
        if (prepareOutcome is LiteRtVisionSegmenter.PrepareOutcome.Invalid) {
            runCatching { candidateSlot.delete() }
            return handleImportFailure(
                reason = prepareOutcome.failure.koreanMessage,
                detail = prepareOutcome.failure.detail,
            )
        }

        val preparedSession = (prepareOutcome as LiteRtVisionSegmenter.PrepareOutcome.Valid).session
        if (closed.get()) {
            preparedSession.close()
            runCatching { candidateSlot.delete() }
            return VisionModelState.Missing
        }

        val metadata = VisionModelMetadata(
            displayName = safeName,
            sizeBytes = copyOutcome.bytesWritten,
            sha256 = copyOutcome.sha256Hex,
            importedAtMillis = System.currentTimeMillis(),
        )

        val prefsCommitted = runCatching { savePersistedPrefs(candidateSlot.name, metadata) }.getOrDefault(false)
        if (!prefsCommitted) {
            preparedSession.close()
            runCatching { candidateSlot.delete() }
            if (previousMetadata != null) {
                runCatching { savePersistedPrefs(previousSlot.name, previousMetadata) }
            } else {
                clearPersistedPrefs(preserveClearedFlag = false)
            }
            return handleImportFailure(
                reason = "기본 모델 메타데이터 저장에 실패했습니다.",
                detail = "prefs_commit_failed",
            )
        }

        val effectiveBackend = targetBackend ?: getPreferredBackend()
        val effectiveThreads = targetCpuThreads ?: getPreferredCpuThreads()

        val oldSessionCloseable: AutoCloseable? = try {
            segmenter.activatePreparedSession(preparedSession, effectiveBackend, effectiveThreads)
            null
        } catch (t: Throwable) {
            preparedSession.close()
            runCatching { candidateSlot.delete() }
            if (previousMetadata != null) {
                runCatching { savePersistedPrefs(previousSlot.name, previousMetadata) }
            } else {
                clearPersistedPrefs(preserveClearedFlag = false)
            }
            return handleImportFailure(
                reason = "기본 모델 활성화 중 오류가 발생했습니다 (${t.javaClass.simpleName}).",
                detail = "activation_failed",
            )
        }

        activeMetadata = metadata
        runCatching { oldSessionCloseable?.close() }
        if (previousSlot.absolutePath != candidateSlot.absolutePath) {
            runCatching { previousSlot.delete() }
        }
        val ready = VisionModelState.Ready(metadata)
        mutableState.value = ready
        return ready
    }

    fun restoreDefaultModel() {
        synchronized(stateLock) {
            if (closed.get()) return
            mutableInitialRestoreCompleted.value = false
            mutableState.value = VisionModelState.Loading(VisionContract.DEFAULT_MODEL_FILENAME)
            try {
                executor.execute {
                    try {
                        if (!closed.get()) {
                            prefs.edit().putBoolean(KEY_USER_CLEARED, false).commit()
                            provisionDefaultAssetModelSync()
                        }
                    } catch (error: Exception) {
                        publishRestoreFailure(error)
                    } finally {
                        mutableInitialRestoreCompleted.value = true
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                publishRestoreFailure(error)
                mutableInitialRestoreCompleted.value = true
            }
        }
    }

    private fun publishRestoreFailure(error: Exception) {
        mutableState.value = VisionModelState.Error(
            reason = "주변 인식을 준비하는 중 문제가 발생했습니다.",
            detail = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}",
        )
    }

    fun clearModel() {
        synchronized(stateLock) {
            if (closed.get()) return
            executor.execute {
                if (!closed.get()) {
                    clearModelSync()
                }
            }
        }
    }

    internal fun clearModelSync(): VisionModelState {
        try {
            segmenter.unloadModel()
            activeMetadata = null
            clearPersistedPrefs(preserveClearedFlag = true)
            runCatching { stagingModelFile.delete() }
            runCatching { File(visionDir, SLOT_0_FILENAME).delete() }
            runCatching { File(visionDir, SLOT_1_FILENAME).delete() }
            val missing = VisionModelState.Missing
            mutableState.value = missing
            return missing
        } finally {
            mutableInitialRestoreCompleted.value = true
        }
    }

    private fun handleImportFailure(
        reason: String,
        detail: String,
        runtimeReadyCheck: () -> Boolean = { segmenter.isReady },
    ): VisionModelState {
        val existing = activeMetadata
        return if (existing != null && activeModelFile.isFile && runtimeReadyCheck()) {
            // Preserve the existing verified model, metadata, and live runtime.
            val preserved = VisionModelState.Ready(
                metadata = existing,
                rejectedImportNotice = "새 모델 가져오기 거부됨 (기존 유효 모델 유지): $reason",
            )
            mutableState.value = preserved
            preserved
        } else {
            val err = VisionModelState.Error(reason = reason, detail = detail)
            mutableState.value = err
            err
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
        return sanitizeFileName(queried ?: uri.lastPathSegment ?: VisionContract.DEFAULT_MODEL_FILENAME)
    }

    private fun savePersistedPrefs(slotFileName: String, metadata: VisionModelMetadata): Boolean {
        return prefs.edit()
            .putString(KEY_ACTIVE_SLOT_FILE, slotFileName)
            .putString(KEY_DISPLAY_NAME, metadata.displayName)
            .putLong(KEY_SIZE_BYTES, metadata.sizeBytes)
            .putString(KEY_SHA256, metadata.sha256)
            .putLong(KEY_IMPORTED_AT, metadata.importedAtMillis)
            .putBoolean(KEY_USER_CLEARED, false)
            .commit()
    }

    private fun clearPersistedPrefs(preserveClearedFlag: Boolean = true) {
        val editor = prefs.edit().clear()
        if (preserveClearedFlag) {
            editor.putBoolean(KEY_USER_CLEARED, true)
        }
        editor.commit()
    }

    override fun close() {
        synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) return
            val scheduled = runCatching {
                executor.execute {
                    segmenter.close()
                }
                true
            }.getOrDefault(false)
            executor.shutdown()
            if (!scheduled) {
                segmenter.close()
            }
        }
    }

    internal sealed interface BoundedCopyOutcome {
        data class Success(val bytesWritten: Long, val sha256Hex: String) : BoundedCopyOutcome
        data class Failure(val code: String, val koreanMessage: String) : BoundedCopyOutcome
    }

    companion object {
        private const val PREFS_NAME = "vision_model_store"
        private const val KEY_ACTIVE_SLOT_FILE = "active_slot_file"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_SIZE_BYTES = "size_bytes"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_IMPORTED_AT = "imported_at"
        private const val KEY_USER_CLEARED = "user_cleared_model"
        private const val KEY_PREFERRED_BACKEND = "preferred_backend"
        private const val KEY_PREFERRED_CPU_THREADS = "preferred_cpu_threads"

        internal const val SLOT_0_FILENAME = "walkable_seg_aria_slot0.tflite"
        internal const val SLOT_1_FILENAME = "walkable_seg_aria_slot1.tflite"

        internal fun atomicMoveSameDirectory(source: File, destination: File): Boolean {
            return try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                true
            } catch (_: Throwable) {
                false
            }
        }

        internal fun sanitizeFileName(raw: String): String {
            val leaf = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            return leaf.takeIf { it.isNotEmpty() }?.take(80) ?: VisionContract.DEFAULT_MODEL_FILENAME
        }

        internal fun copyBoundedWithSha256(
            input: InputStream,
            destination: File,
            maxBytes: Long = VisionContract.MAX_MODEL_BYTES,
        ): BoundedCopyOutcome {
            return try {
                destination.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L
                val buffer = ByteArray(64 * 1024)
                destination.outputStream().buffered().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        totalBytes += read
                        if (totalBytes > maxBytes) {
                            return BoundedCopyOutcome.Failure(
                                code = "file_too_large",
                                koreanMessage = "모델 파일 크기가 허용 한도(${maxBytes / (1024 * 1024)} MB)를 초과했습니다.",
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
                if (totalBytes <= 0L) {
                    runCatching { destination.delete() }
                    BoundedCopyOutcome.Failure(
                        code = "empty_file",
                        koreanMessage = "선택한 모델 파일이 비어 있습니다 (0 bytes).",
                    )
                } else {
                    val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
                    BoundedCopyOutcome.Success(bytesWritten = totalBytes, sha256Hex = shaHex)
                }
            } catch (t: Throwable) {
                runCatching { destination.delete() }
                BoundedCopyOutcome.Failure(
                    code = "io_error",
                    koreanMessage = "모델 파일 복사 중 오류가 발생했습니다 (${t.javaClass.simpleName}).",
                )
            }
        }

        internal fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
