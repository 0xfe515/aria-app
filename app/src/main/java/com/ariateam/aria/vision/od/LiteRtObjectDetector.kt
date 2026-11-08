package com.ariateam.aria.vision.od

import android.content.Context
import com.ariateam.aria.vision.UprightCropRect
import com.ariateam.aria.vision.VisionContract
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * LiteRT CPU object detector for the pinned bundled EfficientDet-Lite0 int8 helper model.
 *
 * Model provenance (re-validated at construction against the packaged asset):
 * - source: `lite-model_efficientdet_lite0_detection_metadata_1.tflite` from the official TF Lite
 *   task library (`download.tensorflow.org/models/tflite/task_library/object_detection/android/`),
 *   Apache-2.0, embedded metadata author `TensorFlow`;
 * - input: `uint8 [1, 320, 320, 3]`, quantization `scale = 1/128`, `zero_point = 127`, image
 *   normalization `(pixel - 127.5) / 127.5` -> `q = clamp(round(value * 256 - 1))` for the app's
 *   `[0, 1]` RGB floats;
 * - outputs: the model's own `TFLite_Detection_PostProcess` op (built-in NMS) with the pinned
 *   declaration order `boxes [1, 25, 4]` (`ymin, xmin, ymax, xmax`, normalized),
 *   `classes [1, 25]`, `scores [1, 25]`, `count [1]`.
 *
 * The engine is created, invoked and closed on the auxiliary sidecar worker only. It uses a single
 * CPU thread (XNNPACK, `setNumThreads(1)`) and no GPU/NPU delegate. It never mutates the caller's
 * RGB tensor. Invalid caller input yields an empty list; native load/invoke failures propagate so
 * the sidecar's isolated failure handler can disable OD without touching segmentation.
 */
class LiteRtObjectDetector(context: Context) : ObjectDetectorEngine {

    private val interpreter: Interpreter
    private val modelBuffer: ByteBuffer

    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffers: Map<Int, Any>

    private lateinit var boxesBuffer: ByteBuffer
    private lateinit var classesBuffer: ByteBuffer
    private lateinit var scoresBuffer: ByteBuffer
    private lateinit var countBuffer: ByteBuffer

    private val boxesArray = FloatArray(MODEL_SLOT_COUNT * BOX_COORDS)
    private val classesArray = FloatArray(MODEL_SLOT_COUNT)
    private val scoresArray = FloatArray(MODEL_SLOT_COUNT)
    private val countArray = FloatArray(1)

    private var inputQuantScale: Float = INPUT_QUANT_SCALE
    private var inputZeroPoint: Int = INPUT_ZERO_POINT

    private val adapter = OdFrameAdapter()

    private val detectorFloat = FloatArray(DETECTOR_INPUT_SIZE * DETECTOR_INPUT_SIZE * CHANNELS)
    private val detectorQuantized = ByteArray(DETECTOR_INPUT_SIZE * DETECTOR_INPUT_SIZE * CHANNELS)

    private val labels: List<String>
    private val config: OdInferenceConfig

    @Volatile
    private var closed = false

    private val lock = Any()

    init {
        val modelBytes = readAsset(context, ASSET_MODEL_PATH)
        if (modelBytes.size.toLong() != MODEL_BYTE_COUNT) {
            throw IllegalStateException(
                "OD model size mismatch: expected $MODEL_BYTE_COUNT bytes, got ${modelBytes.size}",
            )
        }
        val actualSha = sha256(modelBytes)
        if (!actualSha.equals(MODEL_SHA256, ignoreCase = true)) {
            throw IllegalStateException(
                "OD model SHA-256 mismatch: expected $MODEL_SHA256, got $actualSha",
            )
        }
        if (!containsPostprocessOp(modelBytes)) {
            throw IllegalStateException(
                "OD model does not contain the expected built-in $POSTPROCESS_OP_CODE op",
            )
        }
        labels = parseLabels(readAsset(context, ASSET_LABELS_PATH))
        val labelsProblem = validateLabelMap(labels)
        if (labelsProblem != null) {
            throw IllegalStateException("OD label map is incompatible: $labelsProblem")
        }
        config = OdInferenceConfig(
            scoreThreshold = SCORE_THRESHOLD,
            maxDetections = MAX_RESULTS,
            allowedClassIds = ALLOWED_CLASS_IDS.copyOf(),
            labels = labels,
        )

        modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

        interpreter = try {
            ensureNativeRuntimeLoaded()
            Interpreter(
                modelBuffer,
                Interpreter.Options()
                    // CPU only, one thread: no GPU/NPU delegate. XNNPACK stays enabled because the
                    // reference CPU kernels are an order of magnitude slower for this model; with
                    // setNumThreads(1) the pool is bounded to one thread (host-validated CPU/wall
                    // ratio ~1.0 versus ~3.2-5.0 with 4 threads).
                    .setNumThreads(1)
                    .setUseXNNPACK(true),
            )
        } catch (t: Throwable) {
            throw IllegalStateException("OD interpreter creation failed: ${t.message}", t)
        }

        try {
            validateInputContract()
            interpreter.allocateTensors()
            validateOutputContract()
            boxesBuffer = allocateOutputBuffer(0)
            classesBuffer = allocateOutputBuffer(1)
            scoresBuffer = allocateOutputBuffer(2)
            countBuffer = allocateOutputBuffer(3)
            outputBuffers = mapOf(
                0 to boxesBuffer,
                1 to classesBuffer,
                2 to scoresBuffer,
                3 to countBuffer,
            )
            inputBuffer = ByteBuffer.allocateDirect(detectorQuantized.size).order(ByteOrder.nativeOrder())
            warmUp()
        } catch (t: Throwable) {
            runCatching { interpreter.close() }
            throw t
        }
    }

    override fun detect(inputRgb: FloatArray, transform: UprightCropRect): List<DetectedObject> {
        if (closed) throw IllegalStateException("LiteRtObjectDetector is closed")
        if (inputRgb.size < VisionContract.INPUT_FLOATS) return emptyList()
        if (!adapter.isUsable(transform)) return emptyList()

        synchronized(lock) {
            if (closed) throw IllegalStateException("LiteRtObjectDetector is closed")
            adapter.fillDetectorTensor(inputRgb, transform, detectorFloat)
            quantizeRgb(detectorFloat, detectorQuantized, inputQuantScale, inputZeroPoint)

            inputBuffer.clear()
            inputBuffer.put(detectorQuantized)
            inputBuffer.rewind()
            clearOutputBuffers(boxesBuffer, classesBuffer, scoresBuffer, countBuffer)

            // Native invoke failures intentionally propagate: the sidecar treats a thrown engine
            // failure differently from a legitimate empty detection list.
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputBuffers)

            readFloats(boxesBuffer, boxesArray)
            readFloats(classesBuffer, classesArray)
            readFloats(scoresBuffer, scoresArray)
            readFloats(countBuffer, countArray)

            return decodeDetectionOutput(
                boxes = boxesArray,
                classes = classesArray,
                scores = scoresArray,
                count = countArray[0],
                adapter = adapter,
                transform = transform,
                config = config,
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { interpreter.close() }
        }
    }

    internal fun resolvedLabelCount(): Int = labels.size

    internal fun allowedClassIds(): IntArray = config.allowedClassIds.copyOf()

    private fun validateInputContract() {
        if (interpreter.inputTensorCount != 1) {
            throw IllegalStateException("OD model must expose exactly 1 input tensor, got ${interpreter.inputTensorCount}")
        }
        val tensor = interpreter.getInputTensor(0)
        if (!tensor.shape().contentEquals(EXPECTED_INPUT_SHAPE)) {
            throw IllegalStateException(
                "OD input shape mismatch: expected ${EXPECTED_INPUT_SHAPE.joinToString()}, got ${tensor.shape().joinToString()}",
            )
        }
        if (tensor.dataType() != DataType.UINT8) {
            throw IllegalStateException("OD input dtype mismatch: expected UINT8, got ${tensor.dataType()}")
        }
        val quantization = tensor.quantizationParams()
        inputQuantScale = quantization.scale
        inputZeroPoint = quantization.zeroPoint
        if (abs(inputQuantScale - INPUT_QUANT_SCALE) > 1e-6f || inputZeroPoint != INPUT_ZERO_POINT) {
            throw IllegalStateException(
                "OD input quantization mismatch: expected scale=$INPUT_QUANT_SCALE zeroPoint=$INPUT_ZERO_POINT, " +
                    "got scale=$inputQuantScale zeroPoint=$inputZeroPoint",
            )
        }
    }

    /**
     * Pins the postprocess output indices to the verified artifact contract:
     * `0 = boxes [1, 25, 4]`, `1 = classes [1, 25]`, `2 = scores [1, 25]`, `3 = count [1]`.
     *
     * Shapes, dtypes and the artifact's declared tensor names must match; anything else fails
     * closed rather than being guessed from values at runtime.
     */
    private fun validateOutputContract() {
        if (interpreter.outputTensorCount != EXPECTED_OUTPUT_COUNT) {
            throw IllegalStateException(
                "OD model must expose exactly $EXPECTED_OUTPUT_COUNT output tensors, got ${interpreter.outputTensorCount}",
            )
        }
        val expectedShapes = listOf(EXPECTED_BOXES_SHAPE, EXPECTED_CLASS_SCORE_SHAPE, EXPECTED_CLASS_SCORE_SHAPE, intArrayOf(1))
        val expectedNames = listOf(EXPECTED_BOXES_NAME, EXPECTED_CLASSES_NAME, EXPECTED_SCORES_NAME, EXPECTED_COUNT_NAME)
        for (index in 0 until EXPECTED_OUTPUT_COUNT) {
            val tensor = interpreter.getOutputTensor(index)
            if (tensor.dataType() != DataType.FLOAT32) {
                throw IllegalStateException(
                    "OD output $index dtype mismatch: expected FLOAT32, got ${tensor.dataType()}",
                )
            }
            if (!tensor.shape().contentEquals(expectedShapes[index])) {
                throw IllegalStateException(
                    "OD output $index shape mismatch: expected ${expectedShapes[index].joinToString()}, " +
                        "got ${tensor.shape().joinToString()}",
                )
            }
            if (tensor.name() != expectedNames[index]) {
                throw IllegalStateException(
                    "OD output $index name mismatch: expected '${expectedNames[index]}', got '${tensor.name()}'",
                )
            }
        }
    }

    /** Single warm-up invocation; verifies the pinned layout produces a decodable result. */
    private fun warmUp() {
        val warm = FloatArray(VisionContract.INPUT_FLOATS)
        adapter.fillDetectorTensor(
            warm,
            UprightCropRect(
                bufferWidth = VisionContract.MODEL_INPUT_WIDTH,
                bufferHeight = VisionContract.MODEL_INPUT_HEIGHT,
                rotationDegrees = 0,
                uprightWidth = VisionContract.MODEL_INPUT_WIDTH,
                uprightHeight = VisionContract.MODEL_INPUT_HEIGHT,
                left = 0,
                top = 0,
                width = VisionContract.MODEL_INPUT_WIDTH,
                height = VisionContract.MODEL_INPUT_HEIGHT,
            ),
            detectorFloat,
        )
        quantizeRgb(detectorFloat, detectorQuantized, inputQuantScale, inputZeroPoint)
        inputBuffer.clear()
        inputBuffer.put(detectorQuantized)
        inputBuffer.rewind()
        clearOutputBuffers(boxesBuffer, classesBuffer, scoresBuffer, countBuffer)
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputBuffers)
        readFloats(countBuffer, countArray)
        clearOutputBuffers(boxesBuffer, classesBuffer, scoresBuffer, countBuffer)
        val count = countArray[0]
        if (!count.isFinite() || count < 0f || count > MODEL_SLOT_COUNT.toFloat()) {
            throw IllegalStateException("OD warm-up produced an invalid detection count: $count")
        }
    }

    private fun readFloats(buffer: ByteBuffer, destination: FloatArray) {
        val duplicate = buffer.duplicate().order(ByteOrder.nativeOrder())
        duplicate.rewind()
        val floats = duplicate.asFloatBuffer()
        val available = minOf(floats.remaining(), destination.size)
        if (available > 0) floats.get(destination, 0, available)
    }

    private fun allocateOutputBuffer(index: Int): ByteBuffer {
        val numElements = interpreter.getOutputTensor(index).numElements()
        if (numElements <= 0) {
            throw IllegalStateException(
                "OD output $index has non-positive element count ($numElements); ensure allocateTensors() ran first",
            )
        }
        val bytes = numElements * Float.SIZE_BYTES
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    companion object {
        internal const val NATIVE_LIBRARY_NAME = "LiteRt"
        private val nativeRuntimeLock = Any()

        @Volatile
        private var nativeRuntimeLoaded = false

        /**
         * Explicitly loads `libLiteRt.so` before [Interpreter] triggers `TensorFlowLite.init()`.
         *
         * The packaged `com.google.ai.edge.litert:litert:2.3.2` artifact ships `libLiteRt.so`
         * (which exports all 53 `Java_org_tensorflow_lite_*` JNI entry points, including
         * `Java_org_tensorflow_lite_TensorFlowLite_nativeDoNothing` and
         * `Java_org_tensorflow_lite_NativeInterpreterWrapper_*`), not `libtensorflowlite_jni.so`.
         * Under instrumentation (`androidTest`), transitive `org.tensorflow:tensorflow-lite-api`
         * classes precede `litert-api` on the test classloader and their static initializer only
         * probes `tensorflowlite_jni*`; pre-loading `LiteRt` ensures `TensorFlowLite.init()`'s
         * `nativeDoNothing()` probe succeeds both in standalone device tests and in production
         * before segmentation initializes.
         */
        internal fun ensureNativeRuntimeLoaded() {
            if (nativeRuntimeLoaded) return
            synchronized(nativeRuntimeLock) {
                if (nativeRuntimeLoaded) return
                System.loadLibrary(NATIVE_LIBRARY_NAME)
                nativeRuntimeLoaded = true
            }
        }

        /**
         * Resets direct output [ByteBuffer] cursors before `Interpreter.runForMultipleInputsOutputs`.
         *
         * LiteRT's `TensorImpl.copyTo(Buffer)` writes into `((ByteBuffer) dst).put(tensorBuffer)` at
         * the buffer's current `position`, advancing `position` to `limit` after each inference
         * (including [warmUp]). Failing to clear the output buffers before the next invocation
         * throws [java.nio.BufferOverflowException] on the first post-warm-up [detect] call.
         */
        internal fun clearOutputBuffers(vararg buffers: ByteBuffer) {
            for (buffer in buffers) {
                buffer.clear()
            }
        }
        const val ASSET_MODEL_PATH = "models/efficientdet_lite0_int8.tflite"
        const val ASSET_LABELS_PATH = "models/efficientdet_lite0_labels.txt"

        const val MODEL_SOURCE_URL =
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite"
        const val MODEL_SHA256 = "2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b"
        const val MODEL_BYTE_COUNT = 4_563_519L
        const val MODEL_LICENSE = "Apache-2.0"

        internal const val POSTPROCESS_OP_CODE = "TFLite_Detection_PostProcess"

        const val DETECTOR_INPUT_SIZE = 320
        const val CHANNELS = 3
        /** Number of detection slots the built-in postprocess writes (fixed by the artifact). */
        const val MODEL_SLOT_COUNT = 25
        /** Maximum detections returned to the sidecar. */
        const val MAX_RESULTS = 12
        const val BOX_COORDS = 4
        const val CLASS_COUNT = 90

        /** Image normalization read from the packaged model metadata (mean/std in `[0, 255]`). */
        const val INPUT_NORMALIZATION_MEAN = 127.5f
        const val INPUT_NORMALIZATION_STD = 127.5f
        const val INPUT_QUANT_SCALE = 0.0078125f
        const val INPUT_ZERO_POINT = 127

        const val SCORE_THRESHOLD = 0.5f

        const val TRAFFIC_LIGHT_CLASS_ID = 9

        /**
         * COCO-90 category ids of the classes this helper may report:
         * 0 (person), 1 (bicycle), 2 (car), 3 (motorcycle), 5 (bus), 7 (truck), and
         * 9 (traffic light - display position only, excluded from hazard fusion).
         */
        val ALLOWED_CLASS_IDS = intArrayOf(0, 1, 2, 3, 5, 7, TRAFFIC_LIGHT_CLASS_ID)

        val EXPECTED_INPUT_SHAPE = intArrayOf(1, DETECTOR_INPUT_SIZE, DETECTOR_INPUT_SIZE, CHANNELS)
        val EXPECTED_BOXES_SHAPE = intArrayOf(1, MODEL_SLOT_COUNT, BOX_COORDS)
        val EXPECTED_CLASS_SCORE_SHAPE = intArrayOf(1, MODEL_SLOT_COUNT)
        const val EXPECTED_OUTPUT_COUNT = 4

        // Names of the pinned artifact's postprocess outputs (SHA-pinned, verified on real bytes).
        const val EXPECTED_BOXES_NAME = "StatefulPartitionedCall:3"
        const val EXPECTED_CLASSES_NAME = "StatefulPartitionedCall:2"
        const val EXPECTED_SCORES_NAME = "StatefulPartitionedCall:1"
        const val EXPECTED_COUNT_NAME = "StatefulPartitionedCall:0"

        internal fun quantizeRgb(
            source: FloatArray,
            destination: ByteArray,
            scale: Float,
            zeroPoint: Int,
        ) {
            val size = minOf(source.size, destination.size)
            for (i in 0 until size) {
                val value = source[i]
                if (!value.isFinite()) {
                    destination[i] = 0
                    continue
                }
                // App tensor is [0, 1] RGB; model metadata normalizes 0..255 pixels by mean/std
                // 127.5 -> [-1, 1], then the tensor's own affine quantization applies.
                val pixel = (value * 255f).coerceIn(0f, 255f)
                val normalized = (pixel - INPUT_NORMALIZATION_MEAN) / INPUT_NORMALIZATION_STD
                val quantized = (normalized / scale).roundToInt() + zeroPoint
                destination[i] = quantized.coerceIn(0, 255).toByte()
            }
        }

        /**
         * Decodes the pinned postprocess output.
         *
         * Boxes are `[ymin, xmin, ymax, xmax]` (index 0/1/2/3), so display `left` comes from
         * index 1, `top` from 0, `right` from 3 and `bottom` from 2.
         *
         * Every declared slot is examined before the class/score filter and before the
         * [OdInferenceConfig.maxDetections] result cap, so an allowed object can never be hidden
         * behind a higher-scoring, disallowed slot.
         *
         * The declared count is clamped to the model's [MODEL_SLOT_COUNT] (25) slots because that is
         * the real tensor capacity; a malformed count above it cannot widen the output buffers and
         * never adds detections.
         */
        internal fun decodeDetectionOutput(
            boxes: FloatArray,
            classes: FloatArray,
            scores: FloatArray,
            count: Float,
            adapter: OdFrameAdapter,
            transform: UprightCropRect,
            config: OdInferenceConfig,
        ): List<DetectedObject> {
            if (!count.isFinite()) return emptyList()
            val declared = count.roundToInt().coerceIn(0, MODEL_SLOT_COUNT)
            if (declared == 0) return emptyList()

            val results = ArrayList<DetectedObject>(declared)
            val slots = minOf(declared, MODEL_SLOT_COUNT, scores.size, classes.size)
            for (i in 0 until slots) {
                val score = scores[i]
                if (!score.isFinite() || score < config.scoreThreshold || score > 1f) continue
                val rawClass = classes[i]
                if (!rawClass.isFinite()) continue
                val roundedClass = rawClass.roundToInt()
                if (abs(rawClass - roundedClass) > CLASS_ID_TOLERANCE) continue
                if (!config.isAllowed(roundedClass)) continue
                val label = config.labels.getOrNull(roundedClass) ?: continue
                val boxOffset = i * BOX_COORDS
                if (boxOffset + BOX_COORDS > boxes.size) break
                val yMin = boxes[boxOffset]
                val xMin = boxes[boxOffset + 1]
                val yMax = boxes[boxOffset + 2]
                val xMax = boxes[boxOffset + 3]
                val mapped = adapter.mapDetectorBoxToCropNorm(
                    left = xMin,
                    top = yMin,
                    right = xMax,
                    bottom = yMax,
                    transform = transform,
                ) ?: continue
                val detection = DetectedObject.createOrNull(
                    classId = roundedClass,
                    label = label,
                    score = score,
                    left = mapped[0],
                    top = mapped[1],
                    right = mapped[2],
                    bottom = mapped[3],
                ) ?: continue
                results.add(detection)
            }
            if (results.size <= config.maxDetections) {
                results.sortWith(compareByDescending { it.score })
                return results
            }

            // When results exceed maxDetections, prioritize obstacle classes (0,1,2,3,5,7)
            // by descending score so display-only extras (traffic light 9) never crowd out
            // genuine obstacle candidates from hazard fusion and the UI overlay.
            val obstacles = ArrayList<DetectedObject>(results.size)
            val displayExtras = ArrayList<DetectedObject>(results.size)
            for (detection in results) {
                if (detection.classId != TRAFFIC_LIGHT_CLASS_ID) {
                    obstacles.add(detection)
                } else {
                    displayExtras.add(detection)
                }
            }
            obstacles.sortWith(compareByDescending { it.score })
            displayExtras.sortWith(compareByDescending { it.score })

            val selectedObstacles = if (obstacles.size > config.maxDetections) {
                obstacles.subList(0, config.maxDetections)
            } else {
                obstacles
            }
            val remainingSlots = config.maxDetections - selectedObstacles.size
            val selectedExtras = if (remainingSlots > 0 && displayExtras.isNotEmpty()) {
                displayExtras.take(remainingSlots)
            } else {
                emptyList()
            }

            val retained = ArrayList<DetectedObject>(selectedObstacles.size + selectedExtras.size)
            retained.addAll(selectedObstacles)
            retained.addAll(selectedExtras)
            // Sort retained subset by descending score to preserve the engine's public ordering contract.
            retained.sortWith(compareByDescending { it.score })
            return retained
        }

        internal const val CLASS_ID_TOLERANCE = 1e-3f

        private fun containsPostprocessOp(modelBytes: ByteArray): Boolean {
            val needle = POSTPROCESS_OP_CODE.toByteArray(Charsets.US_ASCII)
            if (needle.isEmpty() || modelBytes.size < needle.size) return false
            outer@ for (start in 0..(modelBytes.size - needle.size)) {
                for (index in needle.indices) {
                    if (modelBytes[start + index] != needle[index]) continue@outer
                }
                return true
            }
            return false
        }

        private fun parseLabels(bytes: ByteArray): List<String> =
            String(bytes, Charsets.UTF_8)
                .lineSequence()
                .map { it.trim('\r', '\n', ' ') }
                .toList()
                .dropLastWhile { it.isEmpty() }

        private fun validateLabelMap(labels: List<String>): String? {
            if (labels.size < CLASS_COUNT) {
                return "expected at least $CLASS_COUNT categories, got ${labels.size}"
            }
            val expected = mapOf(
                0 to "person",
                1 to "bicycle",
                2 to "car",
                3 to "motorcycle",
                5 to "bus",
                7 to "truck",
                9 to "traffic light",
            )
            for ((id, name) in expected) {
                if (!labels[id].equals(name, ignoreCase = true)) {
                    return "category $id expected '$name' but was '${labels[id]}'"
                }
            }
            return null
        }

        private fun readAsset(context: Context, path: String): ByteArray {
            context.assets.open(path).use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                return buffer.toByteArray()
            }
        }

        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val builder = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                if (value < 0x10) builder.append('0')
                builder.append(Integer.toHexString(value))
            }
            return builder.toString()
        }
    }
}

/** Immutable inference configuration resolved from the packaged label map. */
internal data class OdInferenceConfig(
    val scoreThreshold: Float,
    val maxDetections: Int,
    val allowedClassIds: IntArray,
    val labels: List<String>,
) {
    fun isAllowed(classId: Int): Boolean = allowedClassIds.contains(classId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OdInferenceConfig) return false
        return scoreThreshold == other.scoreThreshold &&
            maxDetections == other.maxDetections &&
            allowedClassIds.contentEquals(other.allowedClassIds) &&
            labels == other.labels
    }

    override fun hashCode(): Int {
        var result = scoreThreshold.hashCode()
        result = 31 * result + maxDetections
        result = 31 * result + allowedClassIds.contentHashCode()
        result = 31 * result + labels.hashCode()
        return result
    }
}
