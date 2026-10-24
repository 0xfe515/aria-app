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
 * Thread-safe LiteRT CPU/GPU segmentation runner with atomic session commits, strict
 * local resource disposal on failure, and single-session ownership.
 */
class LiteRtVisionSegmenter(
    private val defaultCpuThreads: Int = VisionContract.DEFAULT_CPU_THREADS,
) : AutoCloseable {

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
    var configuredBackend: VisionBackendType = VisionBackendType.CPU
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
        dup.rewind()
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
     */
    internal fun buildSession(
        mappedBuffer: MappedByteBuffer,
        requestedBackend: VisionBackendType,
        threads: Int,
        precisionLossAllowed: Boolean = false,
    ): InferenceSession {
        val cpuThreads = threads.coerceIn(1, 8)

        if (requestedBackend == VisionBackendType.GPU) {
            var delegate: GpuDelegate? = null
            var candidateInterpreter: Interpreter? = null
            try {
                val isSupported: Boolean
                val delegateOptions: GpuDelegate.Options
                CompatibilityList().use { compatList ->
                    isSupported = compatList.isDelegateSupportedOnThisDevice
                    delegateOptions = if (isSupported) compatList.bestOptionsForThisDevice else GpuDelegate.Options()
                }
                if (isSupported) {
                    delegateOptions.setPrecisionLossAllowed(precisionLossAllowed)
                    delegateOptions.setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    delegate = GpuDelegate(delegateOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(delegate)
                        setNumThreads(cpuThreads)
                    }
                    candidateInterpreter = Interpreter(mappedBuffer, options)
                    candidateInterpreter.allocateTensors()
                    val specResult = inspectInterpreterSpec(candidateInterpreter)
                    if (specResult is VisionContract.SpecValidationResult.Valid) {
                        val precisionTag = if (precisionLossAllowed) "FP16 [진단 전용]" else "FP32"
                        return InferenceSession(
                            interpreter = candidateInterpreter,
                            gpuDelegate = delegate,
                            mappedBuffer = mappedBuffer,
                            backendType = VisionBackendType.GPU,
                            cpuThreads = cpuThreads,
                            backendDescription = "LiteRT GPU $precisionTag (GpuDelegate · 잔여 CPU ${cpuThreads}스레드)",
                        )
                    } else {
                        throw IllegalArgumentException((specResult as VisionContract.SpecValidationResult.Invalid).detail)
                    }
                }
            } catch (t: Throwable) {
                // Guarantee candidate locals are cleaned up immediately on failure
                runCatching { candidateInterpreter?.close() }
                runCatching { delegate?.close() }
                // Fall back to CPU below
            }
        }

        // Build CPU Session
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
                        backendDescription = if (requestedBackend == VisionBackendType.GPU) {
                            "LiteRT CPU FP32 (XNNPACK, ${cpuThreads}스레드 · GPU 미지원 폴백)"
                        } else {
                            "LiteRT CPU FP32 (XNNPACK, ${cpuThreads}스레드)"
                        },
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
                activeBackendDescription = "N/A (모델 미적용)"
            }
        }
        // Close prior session exactly once
        runCatching { oldSession?.close() }
    }

    /**
     * Reconfigures the backend (CPU or GPU) and thread count for the active model.
     * Preserves existing session if candidate initialization fails.
     */
    fun configureBackend(
        backend: VisionBackendType,
        threads: Int = configuredCpuThreads,
        precisionLossAllowed: Boolean = false,
    ) {
        synchronized(lock) {
            configuredBackend = backend
            configuredCpuThreads = threads
        }
        runOnInferenceThread {
            val mapped = synchronized(lock) { activeSession?.mappedBuffer } ?: return@runOnInferenceThread
            try {
                val candidate = buildSession(mapped, backend, threads, precisionLossAllowed)
                commitSession(candidate)
            } catch (t: Throwable) {
                // Keep existing valid session untouched
            }
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
        synchronized(lock) {
            configuredBackend = backend
            configuredCpuThreads = threads
        }
        runOnInferenceThread {
            val candidate = buildSession(session.mappedBuffer, backend, threads, precisionLossAllowed)
            commitSession(candidate)
        }
    }

    fun loadModelFile(file: File): VisionContract.SpecValidationResult {
        return when (val outcome = prepareModelSession(file)) {
            is PrepareOutcome.Valid -> {
                activatePreparedSession(outcome.session)
                VisionContract.SpecValidationResult.Valid
            }
            is PrepareOutcome.Invalid -> outcome.failure
        }
    }

    fun runIfLoaded(
        inputByteBuffer: ByteBuffer,
        produceOverlayBitmap: Boolean = true,
    ): VisionInferenceOutput? {
        return runOnInferenceThread {
            synchronized(lock) {
                val session = activeSession ?: return@runOnInferenceThread null
                inputByteBuffer.rewind()
                outputByteBuffer.rewind()

                val invokeStart = SystemClock.elapsedRealtimeNanos()
                session.interpreter.run(inputByteBuffer, outputByteBuffer)
                val invokeDurationNanos = (SystemClock.elapsedRealtimeNanos() - invokeStart).coerceAtLeast(0L)

                val postStart = SystemClock.elapsedRealtimeNanos()
                outputAsFloatBuffer().get(outputFloatArray)
                val summary = decodeArgmaxAndMask(
                    probabilities = outputFloatArray,
                    outClassMask = reusableClassMask,
                    outOverlayArgb = if (produceOverlayBitmap) reusableOverlayArgb else null,
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
                outputByteBuffer.rewind()
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
        ): VisionSegmentationSummary {
            val pixelCount = width * height
            require(probabilities.size >= pixelCount * numClasses) {
                "Probabilities array too small: ${probabilities.size} < ${pixelCount * numClasses}"
            }
            require(outClassMask.size >= pixelCount && (outOverlayArgb == null || outOverlayArgb.size >= pixelCount)) {
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
