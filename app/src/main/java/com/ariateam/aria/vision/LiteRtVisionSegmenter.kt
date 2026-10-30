package com.ariateam.aria.vision

import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
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
}

/**
 * Policy governing vision inference execution.
 * Production enforces GPU-only inference; CPU reference is isolated to explicit diagnostic testing.
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
    open fun isGpuSupported(): Boolean {
        return runCatching {
            CompatibilityList().use { it.isDelegateSupportedOnThisDevice }
        }.getOrDefault(false)
    }

    open fun createDelegate(precisionLossAllowed: Boolean): GpuDelegate {
        val delegateOptions = CompatibilityList().use { it.bestOptionsForThisDevice }
        delegateOptions.setPrecisionLossAllowed(precisionLossAllowed)
        delegateOptions.setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
        return GpuDelegate(delegateOptions)
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

/**
 * Thread-safe LiteRT GPU segmentation runner with atomic session commits, strict
 * local resource disposal on failure, and single-session ownership.
 *
 * Production enforces GPU-only FP32 model inference. CPU reference execution is isolated
 * to explicitly injected [inferencePolicy] for diagnostic parity testing.
 */
class LiteRtVisionSegmenter internal constructor(
    private val defaultCpuThreads: Int = VisionContract.DEFAULT_CPU_THREADS,
    internal val inferencePolicy: VisionInferencePolicy = ProductionGpuOnlyPolicy,
    internal val gpuEnvironment: GpuEnvironment = GpuEnvironment(),
) : AutoCloseable {
    constructor() : this(VisionContract.DEFAULT_CPU_THREADS, ProductionGpuOnlyPolicy, GpuEnvironment())
    constructor(defaultCpuThreads: Int) : this(defaultCpuThreads, ProductionGpuOnlyPolicy, GpuEnvironment())

    private val lock = Any()

    @Volatile
    private var inferenceThread: Thread? = null

    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AriaInferenceThread").also { t ->
            t.isDaemon = true
            inferenceThread = t
        }
    }

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
        val interpreter: Interpreter,
        val gpuDelegate: GpuDelegate?,
        val mappedBuffer: MappedByteBuffer,
        val backendType: VisionBackendType,
        val cpuThreads: Int,
        val backendDescription: String,
    ) : AutoCloseable {
        override fun close() {
            runCatching { interpreter.close() }
            runCatching { gpuDelegate?.close() }
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
    var isReady: Boolean = false
        private set

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

    class PreparedModelSession internal constructor(
        internal val mappedBuffer: MappedByteBuffer,
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
     * Guarantees all candidate locals are closed if building throws, preventing GPU or native leaks.
     *
     * Production enforces GPU-only inference (FP32 sustained speed).
     * If GPU is unsupported or fails to initialize, throws an exception immediately without falling back to CPU.
     * CPU inference is only allowed when explicitly enabled via [inferencePolicy.allowCpuReferenceForTesting].
     */
    internal fun buildSession(
        mappedBuffer: MappedByteBuffer,
        requestedBackend: VisionBackendType,
        threads: Int,
        precisionLossAllowed: Boolean = false,
    ): InferenceSession {
        val cpuThreads = threads.coerceIn(1, 8)

        if (requestedBackend == VisionBackendType.CPU) {
            if (!inferencePolicy.allowCpuReferenceForTesting) {
                throw UnsupportedOperationException(
                    "CPU inference is disabled in production. Aria enforces LiteRT GPU-only inference."
                )
            }
            var candidateInterpreter: Interpreter? = null
            try {
                val options = Interpreter.Options().apply {
                    setNumThreads(cpuThreads)
                    setUseXNNPACK(true)
                }
                candidateInterpreter = Interpreter(mappedBuffer, options)
                candidateInterpreter.allocateTensors()
                when (val specResult = inspectInterpreterSpec(candidateInterpreter)) {
                    is VisionContract.SpecValidationResult.Valid -> {
                        return InferenceSession(
                            interpreter = candidateInterpreter,
                            gpuDelegate = null,
                            mappedBuffer = mappedBuffer,
                            backendType = VisionBackendType.CPU,
                            cpuThreads = cpuThreads,
                            backendDescription = "LiteRT CPU FP32 [진단 전용] (XNNPACK, ${cpuThreads}스레드)",
                        )
                    }
                    is VisionContract.SpecValidationResult.Invalid -> {
                        throw IllegalArgumentException(specResult.detail)
                    }
                }
            } catch (t: Throwable) {
                runCatching { candidateInterpreter?.close() }
                throw t
            }
        }

        // Requested GPU
        if (!gpuEnvironment.isGpuSupported()) {
            throw IllegalStateException("LiteRT GPU delegate is not supported on this device.")
        }

        val effectivePrecisionLoss = if (inferencePolicy.allowCpuReferenceForTesting) {
            precisionLossAllowed
        } else {
            false
        }

        var delegate: GpuDelegate? = null
        var candidateInterpreter: Interpreter? = null
        try {
            delegate = gpuEnvironment.createDelegate(effectivePrecisionLoss)
            val options = Interpreter.Options().apply {
                addDelegate(delegate)
                setNumThreads(cpuThreads)
            }
            candidateInterpreter = Interpreter(mappedBuffer, options)
            candidateInterpreter.allocateTensors()
            when (val specResult = inspectInterpreterSpec(candidateInterpreter)) {
                is VisionContract.SpecValidationResult.Valid -> {
                    val precisionTag = if (effectivePrecisionLoss) "FP16 [진단 전용]" else "FP32"
                    return InferenceSession(
                        interpreter = candidateInterpreter,
                        gpuDelegate = delegate,
                        mappedBuffer = mappedBuffer,
                        backendType = VisionBackendType.GPU,
                        cpuThreads = cpuThreads,
                        backendDescription = "LiteRT GPU $precisionTag (GpuDelegate · 잔여 CPU ${cpuThreads}스레드)",
                    )
                }
                is VisionContract.SpecValidationResult.Invalid -> {
                    throw IllegalArgumentException(specResult.detail)
                }
            }
        } catch (t: Throwable) {
            // Guarantee candidate locals are cleaned up immediately on failure
            runCatching { candidateInterpreter?.close() }
            runCatching { delegate?.close() }
            throw t
        }
    }

    /**
     * Atomically swaps in [newSession] and closes any prior session exactly once.
     */
    internal fun commitSession(newSession: InferenceSession?) {
        val oldSession: InferenceSession?
        synchronized(lock) {
            oldSession = activeSession
            activeSession = newSession
            isReady = newSession != null
            if (newSession != null) {
                configuredBackend = newSession.backendType
                configuredCpuThreads = newSession.cpuThreads
                activeBackendDescription = newSession.backendDescription
            } else {
                configuredBackend = VisionBackendType.GPU
                activeBackendDescription = "N/A (모델 미적용)"
            }
        }
        // Close prior session exactly once
        runCatching { oldSession?.close() }
    }

    /**
     * Reconfigures the backend (GPU only in production, or CPU if explicitly allowed for testing).
     * Preserves existing valid session if candidate initialization fails and propagates diagnostic failure.
     */
    fun configureBackend(
        backend: VisionBackendType,
        threads: Int = configuredCpuThreads,
        precisionLossAllowed: Boolean = false,
    ) {
        if (backend == VisionBackendType.CPU && !inferencePolicy.allowCpuReferenceForTesting) {
            throw IllegalArgumentException(
                "CPU backend is disabled in production. Aria enforces LiteRT GPU-only inference."
            )
        }
        val targetThreads = threads.coerceIn(1, 8)
        runOnInferenceThread {
            val mapped = synchronized(lock) {
                if (activeSession == null) {
                    configuredBackend = backend
                    configuredCpuThreads = targetThreads
                    return@runOnInferenceThread
                }
                activeSession?.mappedBuffer
            } ?: return@runOnInferenceThread
            val candidate = buildSession(mapped, backend, targetThreads, precisionLossAllowed)
            commitSession(candidate)
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
        var candidate: Interpreter? = null
        return try {
            val mapped = mapReadOnly(file)
            val options = Interpreter.Options().apply {
                setNumThreads(1)
                setUseXNNPACK(true)
            }
            candidate = Interpreter(mapped, options)
            candidate.allocateTensors()
            when (val specResult = inspectInterpreterSpec(candidate)) {
                is VisionContract.SpecValidationResult.Valid -> {
                    PrepareOutcome.Valid(PreparedModelSession(mapped))
                }
                is VisionContract.SpecValidationResult.Invalid -> {
                    PrepareOutcome.Invalid(specResult)
                }
            }
        } catch (t: Throwable) {
            PrepareOutcome.Invalid(
                VisionContract.SpecValidationResult.Invalid(
                    code = "interpreter_init_failed",
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}",
                    koreanMessage = "유효한 LiteRT/TFLite 세그멘테이션 모델이 아닙니다 (${t.javaClass.simpleName}).",
                )
            )
        } finally {
            runCatching { candidate?.close() }
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
                "CPU backend is disabled in production. Aria enforces LiteRT GPU-only inference."
            )
        }
        val targetThreads = threads.coerceIn(1, 8)
        runOnInferenceThread {
            val candidate = buildSession(session.mappedBuffer, backend, targetThreads, precisionLossAllowed)
            commitSession(candidate)
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
                        koreanMessage = "GPU 가속 초기화에 실패했습니다 (${t.javaClass.simpleName}).",
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
                inputByteBuffer.rewind()
                outputByteBuffer.clear()

                val invokeStart = SystemClock.elapsedRealtimeNanos()
                session.interpreter.run(inputByteBuffer, outputByteBuffer)
                val invokeDurationNanos = (SystemClock.elapsedRealtimeNanos() - invokeStart).coerceAtLeast(0L)

                val postStart = SystemClock.elapsedRealtimeNanos()
                outputAsFloatBuffer().get(outputFloatArray)
                val summary = decodeArgmaxAndMask(
                    probabilities = outputFloatArray,
                    outClassMask = reusableClassMask,
                    outOverlayArgb = if (produceOverlayBitmap) reusableOverlayArgb else null,
                    width = VisionContract.MODEL_WIDTH,
                    height = VisionContract.MODEL_HEIGHT,
                    numClasses = VisionContract.MODEL_CLASSES,
                    roiStartRow = VisionContract.ROI_START_ROW,
                    transform = transform,
                    modelWidth = VisionContract.MODEL_INPUT_WIDTH,
                    modelHeight = VisionContract.MODEL_INPUT_HEIGHT,
                )
                val bitmap = if (produceOverlayBitmap) {
                    Bitmap.createBitmap(
                        reusableOverlayArgb,
                        VisionContract.MODEL_WIDTH,
                        VisionContract.MODEL_HEIGHT,
                        Bitmap.Config.ARGB_8888,
                    )
                } else {
                    null
                }
                val postDurationNanos = (SystemClock.elapsedRealtimeNanos() - postStart).coerceAtLeast(0L)

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
                inputByteBuffer.rewind()
                outputByteBuffer.clear()
                val start = SystemClock.elapsedRealtimeNanos()
                session.interpreter.run(inputByteBuffer, outputByteBuffer)
                val duration = (SystemClock.elapsedRealtimeNanos() - start).coerceAtLeast(0L)
                outputAsFloatBuffer().get(destinationArray, 0, VisionContract.OUTPUT_FLOATS)
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
