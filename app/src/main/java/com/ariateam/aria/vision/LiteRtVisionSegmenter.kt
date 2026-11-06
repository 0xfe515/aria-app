package com.ariateam.aria.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.NpuAcceleratorProvider
import com.google.ai.edge.litert.NpuCompatibilityChecker
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.google.ai.edge.litert.support.metadata.MetadataExtractor
import org.tensorflow.lite.schema.TensorType as SchemaTensorType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class VisionBackendType(val label: String) {
    CPU("LiteRT CPU"),
    GPU("LiteRT GPU"),
    NPU("LiteRT NPU"),
}

/**
 * Policy governing vision inference execution.
 * Production prefers GPU and permits CPU only as an explicitly reported GPU-startup fallback;
 * diagnostic CPU reference sessions remain isolated to explicit testing.
 */
internal interface VisionInferencePolicy {
    val allowCpuReferenceForTesting: Boolean
        get() = false
}

internal object ProductionGpuOnlyPolicy : VisionInferencePolicy {
    override val allowCpuReferenceForTesting: Boolean = false
}

internal data class DiagnosticInferencePolicy(
    override val allowCpuReferenceForTesting: Boolean = true,
) : VisionInferencePolicy

/**
 * Encapsulates LiteRT GPU delegate compatibility detection and construction.
 * Open for test injection to deterministically simulate GPU unsupported or init failure cases.
 */
internal open class GpuEnvironment {
    open fun isGpuSupported(): Boolean = true

    open fun createDelegate(precisionLossAllowed: Boolean): AutoCloseable? = null
}

/**
 * Resolves GPU execution precision and formatting labels.
 *
 * Production enforces FP32 precision regardless of caller requests.
 * Diagnostic sessions may explicitly opt into FP16 precision loss for evaluation.
 */
internal object GpuPrecisionPolicy {
    fun resolveEffectivePrecision(
        isDiagnosticPolicy: Boolean,
        precisionLossAllowed: Boolean,
    ): CompiledModel.GpuOptions.Precision {
        return if (isDiagnosticPolicy && precisionLossAllowed) {
            CompiledModel.GpuOptions.Precision.FP16
        } else {
            CompiledModel.GpuOptions.Precision.FP32
        }
    }

    fun buildGpuOptions(
        isDiagnosticPolicy: Boolean,
        precisionLossAllowed: Boolean,
    ): CompiledModel.GpuOptions {
        val precision = resolveEffectivePrecision(isDiagnosticPolicy, precisionLossAllowed)
        return CompiledModel.GpuOptions(precision = precision)
    }

    fun buildGpuBackendDescription(
        isDiagnosticPolicy: Boolean,
        precisionLossAllowed: Boolean,
    ): String {
        val precision = resolveEffectivePrecision(isDiagnosticPolicy, precisionLossAllowed)
        return buildGpuBackendDescription(precision)
    }

    fun buildGpuBackendDescription(
        effectivePrecision: CompiledModel.GpuOptions.Precision,
    ): String {
        val precisionTag = when (effectivePrecision) {
            CompiledModel.GpuOptions.Precision.FP16 -> "FP16 [진단 전용]"
            CompiledModel.GpuOptions.Precision.FP32 -> "FP32"
            else -> effectivePrecision.name
        }
        return "LiteRT GPU $precisionTag (CompiledModel)"
    }
}

enum class SupportedNpuVendor {
    QUALCOMM,
    MEDIATEK,
    GOOGLE_TENSOR,
    UNKNOWN,
}

/**
 * Encapsulates LiteRT NPU accelerator availability detection and provider creation.
 */
internal open class NpuEnvironment(
    private val context: Context? = null,
    private val vendorDetector: () -> SupportedNpuVendor = { detectVendor(context) },
) {
    companion object {
        fun detectVendor(context: Context?): SupportedNpuVendor {
            if (context == null) return SupportedNpuVendor.UNKNOWN
            return try {
                when {
                    NpuCompatibilityChecker.Qualcomm.isDeviceSupported() -> SupportedNpuVendor.QUALCOMM
                    NpuCompatibilityChecker.GoogleTensor.isDeviceSupported() -> SupportedNpuVendor.GOOGLE_TENSOR
                    NpuCompatibilityChecker.Mediatek.isDeviceSupported() -> SupportedNpuVendor.MEDIATEK
                    else -> SupportedNpuVendor.UNKNOWN
                }
            } catch (_: Throwable) {
                SupportedNpuVendor.UNKNOWN
            }
        }

        /**
         * Pure file-check helper that verifies plugin+dispatch matching pair and vendor runtime readiness
         * for a given vendor within [nativeDir] (and optional system/vendor lib lookup for runtime).
         */
        fun isVendorPairAndRuntimeReady(
            vendor: SupportedNpuVendor,
            nativeDir: File,
            systemLibDirs: List<File> = listOf(File("/vendor/lib64"), File("/vendor/dsp/cdsp")),
        ): Boolean {
            if (!nativeDir.isDirectory) return false
            return when (vendor) {
                SupportedNpuVendor.QUALCOMM -> {
                    val hasPlugin = File(nativeDir, "libLiteRtCompilerPlugin_Qualcomm.so").isFile
                    val hasDispatch = File(nativeDir, "libLiteRtDispatch_Qualcomm.so").isFile
                    if (!hasPlugin || !hasDispatch) return false

                    // QnnSystem alone cannot establish HTP readiness; require actual HTP runtime
                    val hasHtpInNative = File(nativeDir, "libQnnHtp.so").isFile
                    val hasHtpInSystem = systemLibDirs.any { File(it, "libQnnHtp.so").isFile }
                    hasHtpInNative || hasHtpInSystem
                }
                SupportedNpuVendor.MEDIATEK -> {
                    val hasPlugin = File(nativeDir, "libLiteRtCompilerPlugin_MediaTek.so").isFile
                    val hasDispatch = File(nativeDir, "libLiteRtDispatch_MediaTek.so").isFile
                    if (!hasPlugin || !hasDispatch) return false
                    // MediaTek Neuron vendor runtime readiness is unestablished in this packaging; conservatively disabled.
                    false
                }
                SupportedNpuVendor.GOOGLE_TENSOR -> {
                    val hasPlugin = File(nativeDir, "libLiteRtCompilerPlugin_google_tensor.so").isFile
                    val hasDispatch = File(nativeDir, "libLiteRtDispatch_GoogleTensor.so").isFile
                    if (!hasPlugin || !hasDispatch) return false
                    // Google Tensor NPU vendor runtime readiness is unestablished in this packaging; conservatively disabled.
                    false
                }
                SupportedNpuVendor.UNKNOWN -> false
            }
        }
    }

    open fun isNpuSupported(): Boolean {
        val ctx = context ?: return false
        val vendor = vendorDetector()
        if (vendor == SupportedNpuVendor.UNKNOWN) return false
        return try {
            val checker = when (vendor) {
                SupportedNpuVendor.QUALCOMM -> NpuCompatibilityChecker.Qualcomm
                SupportedNpuVendor.MEDIATEK -> NpuCompatibilityChecker.Mediatek
                SupportedNpuVendor.GOOGLE_TENSOR -> NpuCompatibilityChecker.GoogleTensor
                SupportedNpuVendor.UNKNOWN -> return false
            }
            if (!checker.isDeviceSupported()) return false
            isVendorRuntimeReady(ctx)
        } catch (_: Throwable) {
            false
        }
    }

    open fun isVendorRuntimeReady(context: Context): Boolean {
        val nativeDir = try {
            File(context.applicationInfo.nativeLibraryDir)
        } catch (_: Throwable) {
            return false
        }
        val vendor = vendorDetector()
        return isVendorPairAndRuntimeReady(vendor, nativeDir)
    }

    open fun createProvider(context: Context): NpuAcceleratorProvider? {
        val vendor = vendorDetector()
        val checker = when (vendor) {
            SupportedNpuVendor.QUALCOMM -> NpuCompatibilityChecker.Qualcomm
            SupportedNpuVendor.MEDIATEK -> NpuCompatibilityChecker.Mediatek
            SupportedNpuVendor.GOOGLE_TENSOR -> NpuCompatibilityChecker.GoogleTensor
            else -> NpuCompatibilityChecker.Default
        }
        return try {
            BuiltinNpuAcceleratorProvider(context, checker)
        } catch (_: Throwable) {
            try {
                BuiltinNpuAcceleratorProvider(context)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * Informational segmentation summary from a single 512x288 model output frame.
 * Does NOT make hazard or obstacle alert decisions.
 */
data class VisionSegmentationSummary(
    val classPixelCounts: IntArray,
    val roiLeftWalkableRatio: Float,
    val roiCenterWalkableRatio: Float,
    val roiRightWalkableRatio: Float,
    val dominantNonWalkableCenterClassId: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisionSegmentationSummary) return false
        return classPixelCounts.contentEquals(other.classPixelCounts) &&
            roiLeftWalkableRatio == other.roiLeftWalkableRatio &&
            roiCenterWalkableRatio == other.roiCenterWalkableRatio &&
            roiRightWalkableRatio == other.roiRightWalkableRatio &&
            dominantNonWalkableCenterClassId == other.dominantNonWalkableCenterClassId
    }

    override fun hashCode(): Int {
        var result = classPixelCounts.contentHashCode()
        result = 31 * result + roiLeftWalkableRatio.hashCode()
        result = 31 * result + roiCenterWalkableRatio.hashCode()
        result = 31 * result + roiRightWalkableRatio.hashCode()
        result = 31 * result + dominantNonWalkableCenterClassId
        return result
    }
}

data class VisionInferenceOutput(
    val invokeDurationNanos: Long,
    val postprocessDurationNanos: Long,
    val summary: VisionSegmentationSummary,
    val overlayBitmap: Bitmap?,
)

internal interface OverlayBitmapFactory {
    fun create(colors: IntArray, width: Int, height: Int): Bitmap?
}

internal object DefaultOverlayBitmapFactory : OverlayBitmapFactory {
    override fun create(colors: IntArray, width: Int, height: Int): Bitmap {
        val targetBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        targetBitmap.setPixels(colors, 0, width, 0, 0, width, height)
        return targetBitmap
    }
}

/**
 * Thread-safe LiteRT vision segmentation runner with CompiledModel execution,
 * atomic session commits, strict local resource disposal on failure, and single-session ownership.
 *
 * Production prefers GPU/NPU inference and reports a degraded CPU fallback when GPU/NPU startup fails.
 * Diagnostic CPU reference configuration remains isolated to explicitly injected [inferencePolicy].
 */
class LiteRtVisionSegmenter internal constructor(
    private val defaultCpuThreads: Int = VisionContract.DEFAULT_CPU_THREADS,
    internal val inferencePolicy: VisionInferencePolicy = ProductionGpuOnlyPolicy,
    internal val gpuEnvironment: GpuEnvironment = GpuEnvironment(),
    internal val npuEnvironment: NpuEnvironment = NpuEnvironment(),
    private val sessionBuilderForTesting: ((
        MappedByteBuffer,
        VisionBackendType,
        Int,
        Boolean,
    ) -> InferenceSession)? = null,
    internal var bitmapFactory: OverlayBitmapFactory = DefaultOverlayBitmapFactory,
    private val monotonicClockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    val context: Context? = null,
) : AutoCloseable {
    constructor() : this(VisionContract.DEFAULT_CPU_THREADS, ProductionGpuOnlyPolicy, GpuEnvironment(), NpuEnvironment(null), context = null)
    constructor(context: Context) : this(VisionContract.DEFAULT_CPU_THREADS, ProductionGpuOnlyPolicy, GpuEnvironment(), NpuEnvironment(context), context = context)
    constructor(defaultCpuThreads: Int) : this(defaultCpuThreads, ProductionGpuOnlyPolicy, GpuEnvironment(), NpuEnvironment(null), context = null)
    internal constructor(defaultCpuThreads: Int, inferencePolicy: VisionInferencePolicy, gpuEnvironment: GpuEnvironment) :
        this(defaultCpuThreads, inferencePolicy, gpuEnvironment, NpuEnvironment(null), context = null)
    internal constructor(defaultCpuThreads: Int, inferencePolicy: VisionInferencePolicy, gpuEnvironment: GpuEnvironment, npuEnvironment: NpuEnvironment) :
        this(defaultCpuThreads, inferencePolicy, gpuEnvironment, npuEnvironment, context = null)

    internal fun resolveGpuOptions(precisionLossAllowed: Boolean): CompiledModel.GpuOptions {
        return GpuPrecisionPolicy.buildGpuOptions(
            isDiagnosticPolicy = inferencePolicy.allowCpuReferenceForTesting,
            precisionLossAllowed = precisionLossAllowed,
        )
    }

    internal fun resolveGpuBackendDescription(precisionLossAllowed: Boolean): String {
        return GpuPrecisionPolicy.buildGpuBackendDescription(
            isDiagnosticPolicy = inferencePolicy.allowCpuReferenceForTesting,
            precisionLossAllowed = precisionLossAllowed,
        )
    }

    private val lock = Any()

    @Volatile
    private var inferenceThread: Thread? = null

    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AriaInferenceThread").also { t ->
            t.isDaemon = true
            inferenceThread = t
        }
    }

    val pipelineExecutor: ExecutorService
        get() = inferenceExecutor

    val executionThread: Thread?
        get() = inferenceThread

    private fun <T> runOnInferenceThread(block: () -> T): T {
        if (inferenceExecutor.isShutdown) {
            throw IllegalStateException("LiteRtVisionSegmenter has been closed")
        }
        val targetThread = inferenceThread
        return if (targetThread != null && Thread.currentThread() === targetThread) {
            block()
        } else {
            try {
                inferenceExecutor.submit(Callable { block() }).get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    internal class InferenceSession(
        val compiledModel: CompiledModel? = null,
        val inputBuffers: List<TensorBuffer>? = null,
        val outputBuffers: List<TensorBuffer>? = null,
        val environment: Environment? = null,
        val mappedBuffer: MappedByteBuffer,
        val backendType: VisionBackendType,
        val cpuThreads: Int,
        backendDescription: String,
        private val runInferenceForTesting: ((ByteBuffer, ByteBuffer) -> Unit)? = null,
        private val closeForTesting: (() -> Unit)? = null,
        val interpreter: Interpreter? = null,
        val gpuDelegate: AutoCloseable? = null,
        val modelFile: File? = null,
        internal val ownedTempFile: File? = null,
    ) : AutoCloseable {
        constructor(
            interpreter: Interpreter?,
            gpuDelegate: AutoCloseable?,
            mappedBuffer: MappedByteBuffer,
            backendType: VisionBackendType,
            cpuThreads: Int,
            backendDescription: String,
            runInferenceForTesting: ((ByteBuffer, ByteBuffer) -> Unit)? = null,
            closeForTesting: (() -> Unit)? = null,
            modelFile: File? = null,
            ownedTempFile: File? = null,
        ) : this(
            compiledModel = null,
            inputBuffers = null,
            outputBuffers = null,
            environment = null,
            mappedBuffer = mappedBuffer,
            backendType = backendType,
            cpuThreads = cpuThreads,
            backendDescription = backendDescription,
            runInferenceForTesting = runInferenceForTesting,
            closeForTesting = closeForTesting,
            interpreter = interpreter,
            gpuDelegate = gpuDelegate,
            modelFile = modelFile,
            ownedTempFile = ownedTempFile,
        )

        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val isClosed: Boolean get() = closed.get()

        var backendDescription: String = backendDescription
            private set
        var gpuFallbackCause: Throwable? = null
            private set
        var npuFallbackCause: Throwable? = null
            private set
        var isInvocationReady: Boolean = false
            internal set

        internal val inputFloatArray: FloatArray by lazy {
            FloatArray(VisionContract.INPUT_FLOATS)
        }

        fun run(input: ByteBuffer, output: ByteBuffer) {
            check(!closed.get()) { "Session has been closed" }
            val model = compiledModel
            val inBufs = inputBuffers
            val outBufs = outputBuffers
            if (model != null && inBufs != null && outBufs != null) {
                input.rewind()
                val floatBuf = input.asFloatBuffer()
                floatBuf.get(inputFloatArray)
                inBufs[0].writeFloat(inputFloatArray)
                model.run(inBufs, outBufs)
                val outFloats = outBufs[0].readFloat()
                output.clear()
                output.asFloatBuffer().put(outFloats)
                output.rewind()
            } else if (interpreter != null) {
                interpreter.run(input, output)
            } else {
                checkNotNull(runInferenceForTesting) { "Session has no inference runtime" }(input, output)
            }
        }

        internal fun computeTelemetry(): VisionComputeTelemetry {
            if (isClosed) return VisionComputeTelemetry.INACTIVE
            val invocationReady = isInvocationReady
            return VisionComputeTelemetry.build(
                isCameraRunning = true,
                pipelineMode = VisionPipelineMode.PREPROCESS_OR_INFERENCE,
                isModelReady = invocationReady,
                modelStateDescription = if (invocationReady) "모델 준비 완료" else "모델 워밍업 대기",
                configuredBackend = backendType,
                configuredCpuThreads = cpuThreads,
                activeBackend = backendType.takeIf { invocationReady },
                activeBackendDescription = backendDescription,
                isInvocationReady = invocationReady,
                gpuFallbackCause = gpuFallbackCause,
                npuFallbackCause = npuFallbackCause,
            )
        }

        fun warmup() {
            if (isInvocationReady || closed.get()) return
            val input = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
            val output = ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES).order(ByteOrder.nativeOrder())
            try {
                run(input, output)
                isInvocationReady = true
            } catch (t: Throwable) {
                close()
                throw t
            }
        }

        fun markNpuFallback(cause: Throwable) {
            npuFallbackCause = cause
            val detail = "${cause.cause?.javaClass?.simpleName ?: cause.javaClass.simpleName}: " +
                (cause.cause?.message ?: cause.message.orEmpty()).take(160)
            if (backendType == VisionBackendType.GPU) {
                backendDescription = "LiteRT GPU FP32 [NPU 초기화 실패 폴백: ($detail)]"
            } else if (backendType == VisionBackendType.CPU) {
                val gpuDetail = gpuFallbackCause?.let { gCause ->
                    val gMsg = "${gCause.cause?.javaClass?.simpleName ?: gCause.javaClass.simpleName}: " +
                        (gCause.cause?.message ?: gCause.message.orEmpty()).take(120)
                    ", GPU 초기화 실패 ($gMsg)"
                } ?: ""
                backendDescription = "LiteRT CPU FP32 [저성능 폴백: NPU 초기화 실패 ($detail)$gpuDetail, ${cpuThreads}스레드]"
            }
        }

        fun markGpuFallback(cause: Throwable) {
            gpuFallbackCause = cause
            val detail = "${cause.cause?.javaClass?.simpleName ?: cause.javaClass.simpleName}: " +
                (cause.cause?.message ?: cause.message.orEmpty()).take(160)
            val npuDetail = npuFallbackCause?.let { nCause ->
                val nMsg = "${nCause.cause?.javaClass?.simpleName ?: nCause.javaClass.simpleName}: " +
                    (nCause.cause?.message ?: nCause.message.orEmpty()).take(120)
                "NPU 초기화 실패 ($nMsg), "
            } ?: ""
            backendDescription = "LiteRT CPU FP32 [저성능 폴백: ${npuDetail}GPU 초기화 실패 ($detail), ${cpuThreads}스레드]"
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { closeForTesting?.invoke() }
            runCatching { inputBuffers?.forEach { it.close() } }
            runCatching { outputBuffers?.forEach { it.close() } }
            runCatching { compiledModel?.close() }
            runCatching { environment?.close() }
            runCatching { interpreter?.close() }
            runCatching { gpuDelegate?.close() }
            runCatching { ownedTempFile?.delete() }
        }
    }

    internal class GpuInitializationException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    internal class NpuInitializationException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    internal class ModelValidationException(message: String) : IllegalArgumentException(message)

    internal class CpuFallbackException(
        val gpuFailure: Throwable,
        val cpuFailure: Throwable,
    ) : IllegalStateException(
        "LiteRT GPU/NPU initialization failed and the low-performance CPU fallback could not be initialized.",
        cpuFailure,
    ) {
        init {
            addSuppressed(gpuFailure)
        }
    }

    private var activeSession: InferenceSession? = null

    @Volatile
    var configuredBackend: VisionBackendType = VisionBackendType.GPU
        private set

    @Volatile
    var configuredCpuThreads: Int = defaultCpuThreads.coerceIn(1, 8)
        private set

    @Volatile
    var activeBackendDescription: String = "N/A (모델 미적용)"
        private set

    @Volatile
    var activeBackendType: VisionBackendType? = null
        private set

    @Volatile
    var gpuFallbackCause: Throwable? = null
        private set

    @Volatile
    var npuFallbackCause: Throwable? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    val isInvocationReady: Boolean
        get() = synchronized(lock) { activeSession?.isInvocationReady == true }

    internal fun currentSessionSnapshot(): VisionSessionSnapshot = synchronized(lock) {
        val session = activeSession?.takeUnless { it.isClosed }
        VisionSessionSnapshot(
            configuredBackend = configuredBackend,
            configuredCpuThreads = configuredCpuThreads,
            activeBackendType = session?.backendType,
            activeBackendDescription = session?.backendDescription ?: "N/A (모델 미적용)",
            isInvocationReady = session?.isInvocationReady == true,
            gpuFallbackCause = session?.gpuFallbackCause,
            npuFallbackCause = session?.npuFallbackCause,
        )
    }

    internal fun currentComputeTelemetry(
        isCameraRunning: Boolean,
        pipelineMode: VisionPipelineMode,
        modelState: VisionModelState,
    ): VisionComputeTelemetry {
        val snapshot = currentSessionSnapshot()
        return VisionComputeTelemetry.build(
            isCameraRunning = isCameraRunning,
            pipelineMode = pipelineMode,
            modelState = modelState,
            configuredBackend = snapshot.configuredBackend,
            configuredCpuThreads = snapshot.configuredCpuThreads,
            activeBackend = snapshot.activeBackendType,
            activeBackendDescription = snapshot.activeBackendDescription,
            isInvocationReady = snapshot.isInvocationReady,
            gpuFallbackCause = snapshot.gpuFallbackCause,
            npuFallbackCause = snapshot.npuFallbackCause,
        )
    }

    private val outputByteBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(VisionContract.OUTPUT_BYTES).order(ByteOrder.nativeOrder())
    }
    private val outputFloatArray: FloatArray by lazy {
        FloatArray(VisionContract.OUTPUT_FLOATS)
    }

    private fun outputAsFloatBuffer(): FloatBuffer {
        val dup = outputByteBuffer.duplicate().order(ByteOrder.nativeOrder())
        dup.clear()
        return dup.asFloatBuffer()
    }

    val reusableClassMask: ByteArray by lazy {
        ByteArray(VisionContract.INPUT_PIXELS)
    }
    val reusableOverlayArgb: IntArray by lazy {
        IntArray(VisionContract.INPUT_PIXELS)
    }
    private val overlayComponentFilter = OverlayComponentFilter()

    class PreparedModelSession internal constructor(
        internal val mappedBuffer: MappedByteBuffer,
        internal val modelFile: File? = null,
    ) : AutoCloseable {
        override fun close() {
            // Buffer will be garbage collected or unmapped
        }
    }

    sealed interface PrepareOutcome {
        data class Valid(val session: PreparedModelSession) : PrepareOutcome
        data class Invalid(val failure: VisionContract.SpecValidationResult.Invalid) : PrepareOutcome
    }

    /**
     * Builds and validates a candidate session off the lock without touching any active session.
     * Guarantees all candidate locals are closed if building throws, preventing GPU/NPU or native leaks.
     *
     * GPU/NPU remains preferred. Production falls back to explicitly degraded CPU inference only when
     * hardware acceleration fails; model contract validation errors are never treated as backend failures.
     */
    internal fun buildSession(
        mappedBuffer: MappedByteBuffer,
        requestedBackend: VisionBackendType,
        threads: Int,
        precisionLossAllowed: Boolean = false,
        modelFile: File? = null,
    ): InferenceSession {
        val cpuThreads = threads.coerceIn(1, 8)

        if (requestedBackend == VisionBackendType.CPU) {
            if (!inferencePolicy.allowCpuReferenceForTesting) {
                throw UnsupportedOperationException(
                    "Manual CPU backend configuration is disabled in production; CPU is reserved for GPU-startup fallback."
                )
            }
            val session = createRequestedSession(mappedBuffer, requestedBackend, cpuThreads, precisionLossAllowed, modelFile = modelFile)
            try {
                session.warmup()
            } catch (error: java.lang.Error) {
                session.close()
                throw error
            } catch (t: Throwable) {
                session.close()
                throw t
            }
            return session
        }

        if (requestedBackend == VisionBackendType.NPU) {
            return try {
                val session = createRequestedSession(mappedBuffer, VisionBackendType.NPU, cpuThreads, precisionLossAllowed, modelFile = modelFile)
                try {
                    session.warmup()
                } catch (error: java.lang.Error) {
                    session.close()
                    throw error
                } catch (warmupError: Exception) {
                    session.close()
                    throw NpuInitializationException("LiteRT NPU session warmup failed.", warmupError)
                }
                session
            } catch (failure: ModelValidationException) {
                throw failure
            } catch (error: java.lang.Error) {
                throw error
            } catch (npuFailure: NpuInitializationException) {
                if (inferencePolicy.allowCpuReferenceForTesting) throw npuFailure
                // Attempt GPU fallback next
                try {
                    val gpuSession = createRequestedSession(mappedBuffer, VisionBackendType.GPU, cpuThreads, precisionLossAllowed, modelFile = modelFile)
                    try {
                        gpuSession.warmup()
                    } catch (error: java.lang.Error) {
                        gpuSession.close()
                        throw error
                    } catch (warmupError: Exception) {
                        gpuSession.close()
                        throw GpuInitializationException("LiteRT GPU session warmup failed.", warmupError)
                    }
                    gpuSession.markNpuFallback(npuFailure)
                    gpuSession
                } catch (failure: ModelValidationException) {
                    throw failure
                } catch (error: java.lang.Error) {
                    throw error
                } catch (gpuFailure: GpuInitializationException) {
                    try {
                        val cpuSession = createCpuFallbackSession(
                            mappedBuffer = mappedBuffer,
                            cpuThreads = cpuThreads,
                            gpuFailure = gpuFailure,
                            modelFile = modelFile,
                        )
                        cpuSession.markNpuFallback(npuFailure)
                        cpuSession
                    } catch (failure: ModelValidationException) {
                        throw failure
                    } catch (error: java.lang.Error) {
                        throw error
                    } catch (cpuFailure: Exception) {
                        val fallbackException = CpuFallbackException(gpuFailure, cpuFailure)
                        fallbackException.addSuppressed(npuFailure)
                        throw fallbackException
                    }
                }
            }
        }

        return try {
            val session = createRequestedSession(mappedBuffer, VisionBackendType.GPU, cpuThreads, precisionLossAllowed, modelFile = modelFile)
            try {
                session.warmup()
            } catch (error: java.lang.Error) {
                session.close()
                throw error
            } catch (warmupError: Exception) {
                session.close()
                throw GpuInitializationException("LiteRT GPU session warmup failed.", warmupError)
            }
            session
        } catch (failure: ModelValidationException) {
            throw failure
        } catch (error: java.lang.Error) {
            throw error
        } catch (gpuFailure: GpuInitializationException) {
            if (inferencePolicy.allowCpuReferenceForTesting) throw gpuFailure
            try {
                createCpuFallbackSession(mappedBuffer, cpuThreads, gpuFailure, modelFile)
            } catch (failure: ModelValidationException) {
                throw failure
            } catch (error: java.lang.Error) {
                throw error
            } catch (cpuFailure: Exception) {
                throw CpuFallbackException(gpuFailure, cpuFailure)
            }
        }
    }

    private fun createCpuFallbackSession(
        mappedBuffer: MappedByteBuffer,
        cpuThreads: Int,
        gpuFailure: GpuInitializationException,
        modelFile: File? = null,
    ): InferenceSession {
        val session = createRequestedSession(
            mappedBuffer = mappedBuffer,
            requestedBackend = VisionBackendType.CPU,
            threads = cpuThreads,
            precisionLossAllowed = false,
            allowProductionCpuFallback = true,
            modelFile = modelFile,
        )
        if (session.backendType != VisionBackendType.CPU) {
            session.close()
            throw IllegalStateException("CPU fallback builder returned ${session.backendType}, not CPU.")
        }
        try {
            session.warmup()
        } catch (error: java.lang.Error) {
            session.close()
            throw error
        } catch (t: Exception) {
            session.close()
            throw t
        }
        session.markGpuFallback(gpuFailure)
        return session
    }

    private fun createRequestedSession(
        mappedBuffer: MappedByteBuffer,
        requestedBackend: VisionBackendType,
        threads: Int,
        precisionLossAllowed: Boolean,
        allowProductionCpuFallback: Boolean = false,
        modelFile: File? = null,
    ): InferenceSession {
        if (requestedBackend == VisionBackendType.CPU &&
            !inferencePolicy.allowCpuReferenceForTesting &&
            !allowProductionCpuFallback
        ) {
            throw UnsupportedOperationException(
                "CPU inference is disabled in production. Aria permits CPU only as a GPU-failure fallback."
            )
        }

        if (requestedBackend == VisionBackendType.NPU) {
            val npuSupported = try {
                npuEnvironment.isNpuSupported()
            } catch (error: java.lang.Error) {
                throw error
            } catch (cause: Throwable) {
                throw NpuInitializationException("Unable to query LiteRT NPU support.", cause)
            }
            if (!npuSupported) {
                throw NpuInitializationException(
                    "LiteRT NPU accelerator is not supported or vendor runtime libraries are not ready on this device.",
                    UnsupportedOperationException("NPU accelerator unsupported or vendor runtime not ready"),
                )
            }
        }

        if (requestedBackend == VisionBackendType.GPU) {
            val supported = try {
                gpuEnvironment.isGpuSupported()
            } catch (error: java.lang.Error) {
                throw error
            } catch (cause: Exception) {
                throw GpuInitializationException("Unable to query LiteRT GPU support.", cause)
            }
            if (!supported) {
                throw GpuInitializationException(
                    "LiteRT GPU delegate is not supported on this device.",
                    UnsupportedOperationException("GPU delegate unsupported on this device"),
                )
            }
            try {
                gpuEnvironment.createDelegate(precisionLossAllowed)?.close()
            } catch (error: java.lang.Error) {
                throw error
            } catch (t: Exception) {
                throw GpuInitializationException("LiteRT GPU session initialization failed.", t)
            }
        }

        return try {
            sessionBuilderForTesting?.invoke(mappedBuffer, requestedBackend, threads, precisionLossAllowed)
                ?: buildRuntimeSession(mappedBuffer, requestedBackend, threads, precisionLossAllowed, modelFile)
        } catch (failure: ModelValidationException) {
            throw failure
        } catch (failure: GpuInitializationException) {
            throw failure
        } catch (failure: NpuInitializationException) {
            throw failure
        } catch (error: java.lang.Error) {
            throw error
        } catch (failure: Exception) {
            if (requestedBackend == VisionBackendType.NPU) {
                throw NpuInitializationException("LiteRT NPU session initialization failed.", failure)
            }
            if (requestedBackend == VisionBackendType.GPU) {
                throw GpuInitializationException("LiteRT GPU session initialization failed.", failure)
            }
            throw failure
        }
    }

    private fun buildRuntimeSession(
        mappedBuffer: MappedByteBuffer,
        requestedBackend: VisionBackendType,
        cpuThreads: Int,
        precisionLossAllowed: Boolean,
        modelFile: File? = null,
    ): InferenceSession {
        val (effectiveFile, isTempOwned) = if (modelFile != null && modelFile.isFile) {
            modelFile to false
        } else {
            val temp = File.createTempFile("litert_model_", ".tflite")
            val bytes = ByteArray(mappedBuffer.remaining())
            val dup = mappedBuffer.duplicate()
            dup.get(bytes)
            temp.writeBytes(bytes)
            temp to true
        }

        var candidateModel: CompiledModel? = null
        var candidateEnv: Environment? = null
        var candidateInputBuffers: List<TensorBuffer>? = null
        var candidateOutputBuffers: List<TensorBuffer>? = null

        try {
            val env = if (requestedBackend == VisionBackendType.NPU) {
                val provider = context?.let { npuEnvironment.createProvider(it) }
                if (context != null && provider != null) {
                    Environment.create(context, provider)
                } else if (provider != null) {
                    Environment.create(provider)
                } else if (context != null) {
                    Environment.create(context)
                } else {
                    Environment.create()
                }
            } else if (context != null) {
                Environment.create(context)
            } else {
                Environment.create()
            }
            candidateEnv = env

            val options = when (requestedBackend) {
                VisionBackendType.CPU -> {
                    val opts = CompiledModel.Options(Accelerator.CPU)
                    opts.cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads)
                    opts
                }
                VisionBackendType.GPU -> {
                    val opts = CompiledModel.Options(Accelerator.GPU)
                    opts.gpuOptions = resolveGpuOptions(precisionLossAllowed)
                    opts
                }
                VisionBackendType.NPU -> {
                    CompiledModel.Options(Accelerator.NPU, Accelerator.CPU)
                }
            }

            candidateModel = CompiledModel.create(effectiveFile.absolutePath, options, env)
            val inBufs = candidateModel.createInputBuffers()
            candidateInputBuffers = inBufs
            val outBufs = candidateModel.createOutputBuffers()
            candidateOutputBuffers = outBufs

            if (inBufs.size != 1 || outBufs.size != 1) {
                throw ModelValidationException(
                    "Expected 1 input and 1 output buffer, but got ${inBufs.size} inputs and ${outBufs.size} outputs"
                )
            }

            val backendDesc = when (requestedBackend) {
                VisionBackendType.CPU -> {
                    val purpose = if (inferencePolicy.allowCpuReferenceForTesting) "[진단 전용]" else "[저성능 GPU 폴백]"
                    "LiteRT CPU FP32 $purpose (CompiledModel, ${cpuThreads}스레드)"
                }
                VisionBackendType.GPU -> {
                    resolveGpuBackendDescription(precisionLossAllowed)
                }
                VisionBackendType.NPU -> {
                    "LiteRT NPU (CompiledModel · NPU/CPU 가속기 [요청됨/혼합 미검증])"
                }
            }

            return InferenceSession(
                compiledModel = candidateModel,
                inputBuffers = inBufs,
                outputBuffers = outBufs,
                environment = env,
                mappedBuffer = mappedBuffer,
                backendType = requestedBackend,
                cpuThreads = cpuThreads,
                backendDescription = backendDesc,
                modelFile = if (!isTempOwned) effectiveFile else null,
                ownedTempFile = if (isTempOwned) effectiveFile else null,
            )
        } catch (t: Throwable) {
            runCatching { candidateInputBuffers?.forEach { it.close() } }
            runCatching { candidateOutputBuffers?.forEach { it.close() } }
            runCatching { candidateModel?.close() }
            runCatching { candidateEnv?.close() }
            if (isTempOwned) {
                runCatching { effectiveFile.delete() }
            }
            throw t
        }
    }

    /**
     * Atomically swaps in [newSession] and closes any prior session exactly once.
     */
    internal fun commitSession(
        newSession: InferenceSession?,
        preferredBackend: VisionBackendType = newSession?.backendType ?: VisionBackendType.GPU,
        preferredThreads: Int = newSession?.cpuThreads ?: defaultCpuThreads.coerceIn(1, 8),
    ) {
        val oldSession: InferenceSession?
        synchronized(lock) {
            oldSession = activeSession
            activeSession = newSession
            isReady = newSession != null && newSession.isInvocationReady
            configuredBackend = preferredBackend
            configuredCpuThreads = preferredThreads
            if (newSession != null) {
                activeBackendType = newSession.backendType
                activeBackendDescription = newSession.backendDescription
                gpuFallbackCause = newSession.gpuFallbackCause
                npuFallbackCause = newSession.npuFallbackCause
            } else {
                activeBackendType = null
                activeBackendDescription = "N/A (모델 미적용)"
                gpuFallbackCause = null
                npuFallbackCause = null
            }
        }
        // Close prior session exactly once
        runCatching { oldSession?.close() }
    }

    /**
     * Reconfigures the backend (GPU/NPU in production, or CPU if explicitly allowed for testing).
     * Preserves existing valid session if candidate initialization fails and propagates diagnostic failure.
     */
    fun configureBackend(
        backend: VisionBackendType,
        threads: Int = configuredCpuThreads,
        precisionLossAllowed: Boolean = false,
    ) {
        if (backend == VisionBackendType.CPU && !inferencePolicy.allowCpuReferenceForTesting) {
            throw IllegalArgumentException(
                "Manual CPU backend configuration is disabled in production; CPU is reserved for GPU-startup fallback."
            )
        }
        val targetThreads = threads.coerceIn(1, 8)
        runOnInferenceThread {
            val (mapped, file) = synchronized(lock) {
                if (activeSession == null) {
                    configuredBackend = backend
                    configuredCpuThreads = targetThreads
                    return@runOnInferenceThread
                }
                activeSession?.mappedBuffer to activeSession?.modelFile
            }
            if (mapped == null) return@runOnInferenceThread
            val candidate = buildSession(mapped, backend, targetThreads, precisionLossAllowed, file)
            commitSession(candidate, backend, targetThreads)
        }
    }

    /**
     * Validates a model file's tensor contract without touching the currently active session.
     */
    fun prepareModelSession(file: File): PrepareOutcome {
        if (!file.isFile || file.length() <= 0L) {
            return PrepareOutcome.Invalid(
                VisionContract.SpecValidationResult.Invalid(
                    code = "missing_or_empty",
                    detail = "Model file missing or empty",
                    koreanMessage = "모델 파일이 비어 있거나 존재하지 않습니다.",
                )
            )
        }
        val mapped = try {
            mapReadOnly(file)
        } catch (t: Throwable) {
            return PrepareOutcome.Invalid(
                VisionContract.SpecValidationResult.Invalid(
                    code = "mmap_failed",
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                    koreanMessage = "모델 파일 매핑 실패: ${t.javaClass.simpleName}",
                )
            )
        }
        // Strict tensor contract validation (input [1, 512, 288, 3] and output [1, 512, 288, 7], float32 dtype)
        // using pure-Java LiteRT MetadataExtractor without legacy native JNI dependencies.
        val specResult = inspectMetadataSpec(mapped)

        return when (specResult) {
            is VisionContract.SpecValidationResult.Valid -> PrepareOutcome.Valid(PreparedModelSession(mapped, file))
            is VisionContract.SpecValidationResult.Invalid -> PrepareOutcome.Invalid(specResult)
        }
    }

    /**
     * Activates a validated [PreparedModelSession], committing it atomically.
     */
    fun activatePreparedSession(
        session: PreparedModelSession,
        backend: VisionBackendType = configuredBackend,
        threads: Int = configuredCpuThreads,
        precisionLossAllowed: Boolean = false,
    ) {
        if (backend == VisionBackendType.CPU && !inferencePolicy.allowCpuReferenceForTesting) {
            throw IllegalArgumentException(
                "Manual CPU backend configuration is disabled in production; CPU is reserved for GPU-startup fallback."
            )
        }
        val targetThreads = threads.coerceIn(1, 8)
        runOnInferenceThread {
            val candidate = buildSession(session.mappedBuffer, backend, targetThreads, precisionLossAllowed, session.modelFile)
            commitSession(candidate, backend, targetThreads)
        }
    }

    fun loadModelFile(file: File): VisionContract.SpecValidationResult {
        return when (val outcome = prepareModelSession(file)) {
            is PrepareOutcome.Valid -> {
                try {
                    activatePreparedSession(outcome.session)
                    VisionContract.SpecValidationResult.Valid
                } catch (t: Throwable) {
                    VisionContract.SpecValidationResult.Invalid(
                        code = "gpu_activation_failed",
                        detail = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}",
                        koreanMessage = "가속기 초기화에 실패했습니다 (${t.javaClass.simpleName}).",
                    )
                }
            }
            is PrepareOutcome.Invalid -> outcome.failure
        }
    }

    fun runIfLoaded(
        inputByteBuffer: ByteBuffer,
        produceOverlayBitmap: Boolean = true,
        transform: UprightCropRect? = null,
    ): VisionInferenceOutput? {
        return runOnInferenceThread {
            synchronized(lock) {
                val session = activeSession ?: return@runOnInferenceThread null
                if (!session.isInvocationReady) return@runOnInferenceThread null

                val invokeStart = monotonicClockNanos()
                val outFloats: FloatArray
                val model = session.compiledModel
                val inBufs = session.inputBuffers
                val outBufs = session.outputBuffers
                if (model != null && inBufs != null && outBufs != null) {
                    inputByteBuffer.rewind()
                    inputByteBuffer.asFloatBuffer().get(session.inputFloatArray)
                    inBufs[0].writeFloat(session.inputFloatArray)
                    model.run(inBufs, outBufs)
                    outFloats = outBufs[0].readFloat()
                } else {
                    inputByteBuffer.rewind()
                    outputByteBuffer.clear()
                    session.run(inputByteBuffer, outputByteBuffer)
                    outputAsFloatBuffer().get(outputFloatArray)
                    outFloats = outputFloatArray
                }
                val invokeDurationNanos = (monotonicClockNanos() - invokeStart).coerceAtLeast(0L)

                val postStart = monotonicClockNanos()
                val summary = decodeArgmaxAndMaskInternal(
                    probabilities = outFloats,
                    outClassMask = reusableClassMask,
                    outOverlayArgb = if (produceOverlayBitmap) reusableOverlayArgb else null,
                    width = VisionContract.MODEL_WIDTH,
                    height = VisionContract.MODEL_HEIGHT,
                    numClasses = VisionContract.MODEL_CLASSES,
                    roiStartRow = VisionContract.ROI_START_ROW,
                    transform = transform,
                    modelWidth = VisionContract.MODEL_INPUT_WIDTH,
                    modelHeight = VisionContract.MODEL_INPUT_HEIGHT,
                    overlayComponentFilter = overlayComponentFilter,
                )
                val bitmap = if (produceOverlayBitmap) {
                    bitmapFactory.create(reusableOverlayArgb, VisionContract.MODEL_WIDTH, VisionContract.MODEL_HEIGHT)
                } else {
                    null
                }
                val postDurationNanos = (monotonicClockNanos() - postStart).coerceAtLeast(0L)

                VisionInferenceOutput(
                    invokeDurationNanos = invokeDurationNanos,
                    postprocessDurationNanos = postDurationNanos,
                    summary = summary,
                    overlayBitmap = bitmap,
                )
            }
        }
    }

    fun runDirectRawProbabilities(
        inputByteBuffer: ByteBuffer,
        destinationArray: FloatArray,
    ): Long {
        require(destinationArray.size >= VisionContract.OUTPUT_FLOATS) {
            "Destination array must hold at least ${VisionContract.OUTPUT_FLOATS} floats"
        }
        return runOnInferenceThread {
            synchronized(lock) {
                val session = checkNotNull(activeSession) { "Session not loaded" }
                check(session.isInvocationReady) { "Session not invocation ready" }
                val start = monotonicClockNanos()
                val model = session.compiledModel
                val inBufs = session.inputBuffers
                val outBufs = session.outputBuffers
                val duration: Long
                if (model != null && inBufs != null && outBufs != null) {
                    inputByteBuffer.rewind()
                    inputByteBuffer.asFloatBuffer().get(session.inputFloatArray)
                    inBufs[0].writeFloat(session.inputFloatArray)
                    model.run(inBufs, outBufs)
                    val outFloats = outBufs[0].readFloat()
                    duration = (monotonicClockNanos() - start).coerceAtLeast(0L)
                    System.arraycopy(outFloats, 0, destinationArray, 0, VisionContract.OUTPUT_FLOATS)
                } else {
                    inputByteBuffer.rewind()
                    outputByteBuffer.clear()
                    session.run(inputByteBuffer, outputByteBuffer)
                    duration = (monotonicClockNanos() - start).coerceAtLeast(0L)
                    outputAsFloatBuffer().get(destinationArray, 0, VisionContract.OUTPUT_FLOATS)
                }
                duration
            }
        }
    }

    fun unloadModel() {
        if (!inferenceExecutor.isShutdown) {
            runOnInferenceThread {
                commitSession(null)
            }
        } else {
            commitSession(null)
        }
    }

    override fun close() {
        if (!inferenceExecutor.isShutdown) {
            runCatching {
                runOnInferenceThread {
                    commitSession(null)
                }
            }
            inferenceExecutor.shutdown()
        }
    }

    companion object {
        fun mapReadOnly(file: File): MappedByteBuffer {
            RandomAccessFile(file, "r").use { raf ->
                raf.channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
                }
            }
        }

        fun inspectCompiledModelSpec(model: CompiledModel): VisionContract.SpecValidationResult {
            return try {
                val inBufs = model.createInputBuffers()
                val outBufs = model.createOutputBuffers()
                try {
                    if (inBufs.size != 1 || outBufs.size != 1) {
                        return VisionContract.SpecValidationResult.Invalid(
                            code = "tensor_count_mismatch",
                            detail = "Expected 1 input and 1 output buffer, but got ${inBufs.size} inputs and ${outBufs.size} outputs",
                            koreanMessage = "모델 입력/출력 텐서 개수가 일치하지 않습니다.",
                        )
                    }
                    VisionContract.SpecValidationResult.Valid
                } finally {
                    inBufs.forEach { runCatching { it.close() } }
                    outBufs.forEach { runCatching { it.close() } }
                }
            } catch (t: Throwable) {
                VisionContract.SpecValidationResult.Invalid(
                    code = "spec_inspection_failed",
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                    koreanMessage = "모델 텐서 규격 확인 실패 (${t.javaClass.simpleName}).",
                )
            }
        }

        /**
         * Inspects tensor metadata directly from the TFLite FlatBuffer without initializing native JNI runtimes.
         * Enforces strict portrait segmentation contract (input [1, 512, 288, 3] and output [1, 512, 288, 7], float32).
         */
        fun inspectMetadataSpec(buffer: ByteBuffer): VisionContract.SpecValidationResult {
            val readOnlyBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val extractor = try {
                MetadataExtractor(readOnlyBuffer)
            } catch (t: Throwable) {
                if (t is Error) throw t
                return VisionContract.SpecValidationResult.Invalid(
                    code = "model_init_failed",
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}",
                    koreanMessage = "유효한 LiteRT/TFLite 세그멘테이션 모델이 아닙니다 (${t.javaClass.simpleName}).",
                )
            }

            val inputCount = try {
                extractor.inputTensorCount
            } catch (t: Throwable) {
                if (t is Error) throw t
                return VisionContract.SpecValidationResult.Invalid(
                    code = "model_init_failed",
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}",
                    koreanMessage = "유효한 LiteRT/TFLite 세그멘테이션 모델이 아닙니다 (${t.javaClass.simpleName}).",
                )
            }

            val outputCount = try {
                extractor.outputTensorCount
            } catch (t: Throwable) {
                if (t is Error) throw t
                return VisionContract.SpecValidationResult.Invalid(
                    code = "model_init_failed",
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}",
                    koreanMessage = "유효한 LiteRT/TFLite 세그멘테이션 모델이 아닙니다 (${t.javaClass.simpleName}).",
                )
            }

            val inputShape = if (inputCount > 0) {
                try {
                    extractor.getInputTensorShape(0) ?: IntArray(0)
                } catch (t: Throwable) {
                    if (t is Error) throw t
                    IntArray(0)
                }
            } else {
                IntArray(0)
            }

            val inputType = if (inputCount > 0) {
                try {
                    resolveTensorTypeName(extractor.getInputTensorType(0))
                } catch (t: Throwable) {
                    if (t is Error) throw t
                    "NONE"
                }
            } else {
                "NONE"
            }

            val outputShape = if (outputCount > 0) {
                try {
                    extractor.getOutputTensorShape(0) ?: IntArray(0)
                } catch (t: Throwable) {
                    if (t is Error) throw t
                    IntArray(0)
                }
            } else {
                IntArray(0)
            }

            val outputType = if (outputCount > 0) {
                try {
                    resolveTensorTypeName(extractor.getOutputTensorType(0))
                } catch (t: Throwable) {
                    if (t is Error) throw t
                    "NONE"
                }
            } else {
                "NONE"
            }

            return VisionContract.validateModelSpec(
                inputTensorCount = inputCount,
                inputShape = inputShape,
                inputDataType = inputType,
                outputTensorCount = outputCount,
                outputShape = outputShape,
                outputDataType = outputType,
            )
        }

        fun resolveTensorTypeName(typeByte: Byte): String {
            val typeInt = typeByte.toInt() and 0xFF
            return if (typeInt < SchemaTensorType.names.size) {
                SchemaTensorType.name(typeInt)
            } else {
                "UNKNOWN($typeByte)"
            }
        }

        /**
         * Legacy diagnostic helper for inspecting spec via a legacy Interpreter instance.
         * Isolated for backward compatibility; production model validation uses [inspectMetadataSpec].
         */
        @Deprecated("Use inspectMetadataSpec which does not require legacy native JNI", ReplaceWith("inspectMetadataSpec(buffer)"))
        fun inspectInterpreterSpec(interpreter: Interpreter): VisionContract.SpecValidationResult {
            val inputCount = interpreter.inputTensorCount
            val outputCount = interpreter.outputTensorCount
            val inputShape = if (inputCount > 0) interpreter.getInputTensor(0).shape() else IntArray(0)
            val inputType = if (inputCount > 0) interpreter.getInputTensor(0).dataType().name else "NONE"
            val outputShape = if (outputCount > 0) interpreter.getOutputTensor(0).shape() else IntArray(0)
            val outputType = if (outputCount > 0) interpreter.getOutputTensor(0).dataType().name else "NONE"
            return VisionContract.validateModelSpec(
                inputTensorCount = inputCount,
                inputShape = inputShape,
                inputDataType = inputType,
                outputTensorCount = outputCount,
                outputShape = outputShape,
                outputDataType = outputType,
            )
        }


        fun decodeArgmaxAndMask(
            probabilities: FloatArray,
            outClassMask: ByteArray,
            outOverlayArgb: IntArray? = null,
            width: Int = VisionContract.MODEL_WIDTH,
            height: Int = VisionContract.MODEL_HEIGHT,
            numClasses: Int = VisionContract.MODEL_CLASSES,
            roiStartRow: Int = VisionContract.ROI_START_ROW,
            transform: UprightCropRect? = null,
            modelWidth: Int = width,
            modelHeight: Int = height,
        ): VisionSegmentationSummary = decodeArgmaxAndMaskInternal(
            probabilities = probabilities,
            outClassMask = outClassMask,
            outOverlayArgb = outOverlayArgb,
            width = width,
            height = height,
            numClasses = numClasses,
            roiStartRow = roiStartRow,
            transform = transform,
            modelWidth = modelWidth,
            modelHeight = modelHeight,
            overlayComponentFilter = OverlayComponentFilter(),
            minimumOverlayComponentPixels = OverlayComponentFilter.DEFAULT_MINIMUM_COMPONENT_PIXELS,
        )

        internal fun decodeArgmaxAndMaskInternal(
            probabilities: FloatArray,
            outClassMask: ByteArray,
            outOverlayArgb: IntArray?,
            width: Int,
            height: Int,
            numClasses: Int,
            roiStartRow: Int,
            transform: UprightCropRect?,
            modelWidth: Int,
            modelHeight: Int,
            overlayComponentFilter: OverlayComponentFilter,
            minimumOverlayComponentPixels: Int = OverlayComponentFilter.DEFAULT_MINIMUM_COMPONENT_PIXELS,
        ): VisionSegmentationSummary {
            require(width > 0 && height > 0 && modelWidth > 0 && modelHeight > 0 && numClasses > 0) {
                "Dimensions must be positive: width=$width, height=$height, modelWidth=$modelWidth, modelHeight=$modelHeight, numClasses=$numClasses"
            }
            val modelPixelCount = modelWidth * modelHeight
            require(probabilities.size >= modelPixelCount * numClasses) {
                "Probabilities array too small: ${probabilities.size} < ${modelPixelCount * numClasses}"
            }
            val outputPixelCount = width * height
            require(outClassMask.size >= outputPixelCount && (outOverlayArgb == null || outOverlayArgb.size >= outputPixelCount)) {
                "Output mask arrays too small for ${width}x${height}"
            }

            val colors = VisionContract.CLASS_OVERLAY_ARGB
            val walkableFlags = VisionContract.CLASS_WALKABLE
            val classCounts = IntArray(numClasses)
            val centerBlockedCounts = IntArray(numClasses)

            val thirdWidth = width / 3
            val twoThirdWidth = thirdWidth * 2
            var leftWalkable = 0
            var leftTotal = 0
            var centerWalkable = 0
            var centerTotal = 0
            var rightWalkable = 0
            var rightTotal = 0

            val isInverseMapping = (transform != null && transform.strategy != VisionPreprocessingStrategy.LEGACY_CROP) ||
                modelWidth != width || modelHeight != height

            if (!isInverseMapping) {
                var probOffset = 0
                var pixelIndex = 0
                for (y in 0 until height) {
                    val inRoi = y >= roiStartRow
                    for (x in 0 until width) {
                        var bestClass = 0
                        var bestProb = probabilities[probOffset]
                        for (c in 1 until numClasses) {
                            val p = probabilities[probOffset + c]
                            if (p > bestProb) {
                                bestProb = p
                                bestClass = c
                            }
                        }
                        probOffset += numClasses

                        outClassMask[pixelIndex] = bestClass.toByte()
                        outOverlayArgb?.set(pixelIndex, colors[bestClass])
                        classCounts[bestClass]++

                        if (inRoi) {
                            val isWalkable = walkableFlags[bestClass]
                            when {
                                x < thirdWidth -> {
                                    leftTotal++
                                    if (isWalkable) leftWalkable++
                                }
                                x < twoThirdWidth -> {
                                    centerTotal++
                                    if (isWalkable) {
                                        centerWalkable++
                                    } else {
                                        centerBlockedCounts[bestClass]++
                                    }
                                }
                                else -> {
                                    rightTotal++
                                    if (isWalkable) rightWalkable++
                                }
                            }
                        }
                        pixelIndex++
                    }
                }
            } else {
                // Inverse-map model tensor probabilities into the canonical upright coordinate space (width x height).
                // Each upright grid pixel (ux, uy) represents an exact point in the unpadded camera frame.
                // Model tensor has dimensions (modelWidth x modelHeight), with row stride = modelWidth.
                // Implemented with zero per-pixel heap allocations (no Pair or boxed Float instances).
                var pixelIndex = 0
                val cLeft: Int
                val cTop: Int
                val cWidth: Float
                val cHeight: Float
                val isRot: Boolean

                if (transform != null) {
                    cLeft = transform.contentLeft.coerceIn(0, modelWidth - 1)
                    cTop = transform.contentTop.coerceIn(0, modelHeight - 1)
                    cWidth = transform.contentWidth.toFloat().coerceIn(1f, (modelWidth - cLeft).toFloat())
                    cHeight = transform.contentHeight.toFloat().coerceIn(1f, (modelHeight - cTop).toFloat())
                    isRot = transform.isRotatedClockwise
                } else {
                    cLeft = 0
                    cTop = 0
                    cWidth = modelWidth.toFloat()
                    cHeight = modelHeight.toFloat()
                    isRot = false
                }

                val cRight = (cLeft + cWidth.toInt() - 1).coerceIn(cLeft, modelWidth - 1)
                val cBottom = (cTop + cHeight.toInt() - 1).coerceIn(cTop, modelHeight - 1)
                val invWidth = 1.0f / width
                val invHeight = 1.0f / height

                for (uy in 0 until height) {
                    val inRoi = uy >= roiStartRow
                    val vNorm = (uy + 0.5f) * invHeight
                    val rowMCol = if (isRot) {
                        (cLeft + (1.0f - vNorm).coerceIn(0f, 1f) * cWidth).toInt().coerceIn(cLeft, cRight)
                    } else 0
                    val rowMRow = if (!isRot) {
                        (cTop + vNorm.coerceIn(0f, 1f) * cHeight).toInt().coerceIn(cTop, cBottom)
                    } else 0

                    for (ux in 0 until width) {
                        val uNorm = (ux + 0.5f) * invWidth
                        val mCol: Int
                        val mRow: Int
                        if (isRot) {
                            mCol = rowMCol
                            mRow = (cTop + uNorm.coerceIn(0f, 1f) * cHeight).toInt().coerceIn(cTop, cBottom)
                        } else {
                            mCol = (cLeft + uNorm.coerceIn(0f, 1f) * cWidth).toInt().coerceIn(cLeft, cRight)
                            mRow = rowMRow
                        }
                        val safeCol = mCol.coerceIn(0, modelWidth - 1)
                        val safeRow = mRow.coerceIn(0, modelHeight - 1)
                        val modelProbOffset = (safeRow * modelWidth + safeCol) * numClasses

                        var bestClass = 0
                        var bestProb = probabilities[modelProbOffset]
                        for (c in 1 until numClasses) {
                            val p = probabilities[modelProbOffset + c]
                            if (p > bestProb) {
                                bestProb = p
                                bestClass = c
                            }
                        }

                        outClassMask[pixelIndex] = bestClass.toByte()
                        outOverlayArgb?.set(pixelIndex, colors[bestClass])
                        classCounts[bestClass]++

                        if (inRoi) {
                            val isWalkable = walkableFlags[bestClass]
                            when {
                                ux < thirdWidth -> {
                                    leftTotal++
                                    if (isWalkable) leftWalkable++
                                }
                                ux < twoThirdWidth -> {
                                    centerTotal++
                                    if (isWalkable) {
                                        centerWalkable++
                                    } else {
                                        centerBlockedCounts[bestClass]++
                                    }
                                }
                                else -> {
                                    rightTotal++
                                    if (isWalkable) rightWalkable++
                                }
                            }
                        }
                        pixelIndex++
                    }
                }
            }

            if (outOverlayArgb != null) {
                overlayComponentFilter.suppressSmallComponents(
                    classMask = outClassMask,
                    overlayArgb = outOverlayArgb,
                    width = width,
                    height = height,
                    minimumComponentPixels = minimumOverlayComponentPixels,
                )
            }

            var dominantCenterNonWalkable = 0
            var dominantCenterCount = 0
            for (c in 0 until numClasses) {
                if (centerBlockedCounts[c] > dominantCenterCount) {
                    dominantCenterCount = centerBlockedCounts[c]
                    dominantCenterNonWalkable = c
                }
            }

            return VisionSegmentationSummary(
                classPixelCounts = classCounts,
                roiLeftWalkableRatio = if (leftTotal > 0) leftWalkable.toFloat() / leftTotal else 0f,
                roiCenterWalkableRatio = if (centerTotal > 0) centerWalkable.toFloat() / centerTotal else 0f,
                roiRightWalkableRatio = if (rightTotal > 0) rightWalkable.toFloat() / rightTotal else 0f,
                dominantNonWalkableCenterClassId = if (dominantCenterCount > 0) dominantCenterNonWalkable else -1,
            )
        }
    }
}
