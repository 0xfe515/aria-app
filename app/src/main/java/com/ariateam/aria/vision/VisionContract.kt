package com.ariateam.aria.vision

/**
 * Verified model contract for upstream `tulg580/vision_model` (`export_for_aria.py`).
 *
 * Architecture: MobileNetV2 encoder + lightweight U-Net decoder (7-class semantic segmentation).
 * App export wrapper fixes batch_size=1 and rescales [0, 1] RGB float32 input back to [0, 255]
 * inside the graph before the MobileNetV2 [-1, 1] rescaling layer.
 */
object VisionContract {
    const val MODEL_BATCH = 1
    const val MODEL_HEIGHT = 288
    const val MODEL_WIDTH = 512
    const val MODEL_CHANNELS = 3
    const val MODEL_CLASSES = 7

    const val INPUT_PIXELS = MODEL_HEIGHT * MODEL_WIDTH
    const val INPUT_FLOATS = MODEL_BATCH * MODEL_HEIGHT * MODEL_WIDTH * MODEL_CHANNELS
    const val INPUT_BYTES = INPUT_FLOATS * Float.SIZE_BYTES

    const val OUTPUT_FLOATS = MODEL_BATCH * MODEL_HEIGHT * MODEL_WIDTH * MODEL_CLASSES
    const val OUTPUT_BYTES = OUTPUT_FLOATS * Float.SIZE_BYTES

    const val EXPECTED_DATA_TYPE = "FLOAT32"
    const val DEFAULT_MODEL_FILENAME = "walkable_seg_aria.tflite"
    const val STAGING_MODEL_FILENAME = "walkable_seg_aria.tflite.staging"

    const val DEFAULT_ASSET_MODEL_PATH = "models/walkable_seg_aria5.tflite"
    const val DEFAULT_ASSET_MODEL_NAME = "walkable_seg_aria5.tflite"
    const val DEFAULT_ASSET_MODEL_SHA256 = "3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7"
    const val DEFAULT_ASSET_MODEL_BYTES = 10_837_912L
    const val DEFAULT_ASSET_MODEL_UPSTREAM_COMMIT = "bfb11e6"

    const val OPTIMIZED_ASSET_MODEL_PATH = "models/walkable_seg_aria5_unfused.tflite"
    const val OPTIMIZED_ASSET_MODEL_NAME = "walkable_seg_aria5_unfused.tflite"
    const val OPTIMIZED_ASSET_MODEL_SHA256 = "84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847"
    const val OPTIMIZED_ASSET_MODEL_BYTES = 10_839_936L

    /**
     * Bounded import copy limit (64 MiB).
     * Upstream FP32 export is ~21 MiB and FP16 is ~10.5 MiB.
     */
    const val MAX_MODEL_BYTES = 64L * 1024L * 1024L

    const val DEFAULT_ANALYSIS_WIDTH = 640
    const val DEFAULT_ANALYSIS_HEIGHT = 480
    const val DEFAULT_CPU_THREADS = 4

    /** Bottom 45% ROI start row (288 * 0.55 = 158), matching upstream `test_video_multi.py`. */
    const val ROI_START_ROW = 158

    val EXPECTED_INPUT_SHAPE = intArrayOf(MODEL_BATCH, MODEL_HEIGHT, MODEL_WIDTH, MODEL_CHANNELS)
    val EXPECTED_OUTPUT_SHAPE = intArrayOf(MODEL_BATCH, MODEL_HEIGHT, MODEL_WIDTH, MODEL_CLASSES)

    data class ClassSpec(
        val id: Int,
        val name: String,
        val koreanLabel: String,
        val walkable: Boolean,
        val overlayArgb: Int,
    )

    /**
     * Class order and colors matching upstream `seg_multi.py` and `test_video_multi.py` (`PALETTE` BGR converted to ARGB).
     * Note: Class 3 (`crosswalk`) is learned via upstream AI Hub fine-tuning (`train_finetune.py`, IoU ~0.729).
     */
    val CLASSES: List<ClassSpec> = listOf(
        ClassSpec(0, "other", "기타·배경", walkable = false, overlayArgb = 0x00000000),
        ClassSpec(1, "road", "도로", walkable = false, overlayArgb = 0x80FF3939.toInt()),
        ClassSpec(2, "sidewalk", "인도", walkable = true, overlayArgb = 0x8039FF39.toInt()),
        ClassSpec(3, "crosswalk", "횡단보도", walkable = true, overlayArgb = 0x803939FF.toInt()),
        ClassSpec(4, "person", "사람", walkable = false, overlayArgb = 0x80FFFF39.toInt()),
        ClassSpec(5, "vehicle", "차량", walkable = false, overlayArgb = 0x80FF39FF.toInt()),
        ClassSpec(6, "pole_sign", "기둥·표지판", walkable = false, overlayArgb = 0x8039FFFF.toInt()),
    )

    val CLASS_OVERLAY_ARGB = IntArray(CLASSES.size) { CLASSES[it].overlayArgb }
    val CLASS_WALKABLE = BooleanArray(CLASSES.size) { CLASSES[it].walkable }

    sealed interface SpecValidationResult {
        data object Valid : SpecValidationResult
        data class Invalid(
            val code: String,
            val detail: String,
            val koreanMessage: String,
        ) : SpecValidationResult
    }

    fun validateModelSpec(
        inputTensorCount: Int,
        inputShape: IntArray,
        inputDataType: String,
        outputTensorCount: Int,
        outputShape: IntArray,
        outputDataType: String,
    ): SpecValidationResult {
        if (inputTensorCount != 1) {
            return SpecValidationResult.Invalid(
                code = "input_tensor_count",
                detail = "Expected 1 input tensor, got $inputTensorCount",
                koreanMessage = "입력 텐서 개수 불일치: 1개 필요 (감지됨: ${inputTensorCount}개)",
            )
        }
        if (outputTensorCount != 1) {
            return SpecValidationResult.Invalid(
                code = "output_tensor_count",
                detail = "Expected 1 output tensor, got $outputTensorCount",
                koreanMessage = "출력 텐서 개수 불일치: 1개 필요 (감지됨: ${outputTensorCount}개)",
            )
        }
        if (!inputDataType.equals(EXPECTED_DATA_TYPE, ignoreCase = true)) {
            return SpecValidationResult.Invalid(
                code = "input_dtype",
                detail = "Expected input dtype $EXPECTED_DATA_TYPE, got $inputDataType",
                koreanMessage = "입력 텐서 타입 불일치: FLOAT32 필요 (감지됨: $inputDataType)",
            )
        }
        if (!outputDataType.equals(EXPECTED_DATA_TYPE, ignoreCase = true)) {
            return SpecValidationResult.Invalid(
                code = "output_dtype",
                detail = "Expected output dtype $EXPECTED_DATA_TYPE, got $outputDataType",
                koreanMessage = "출력 텐서 타입 불일치: FLOAT32 필요 (감지됨: $outputDataType)",
            )
        }
        if (!inputShape.contentEquals(EXPECTED_INPUT_SHAPE)) {
            val actual = inputShape.joinToString(prefix = "[", postfix = "]")
            val expected = EXPECTED_INPUT_SHAPE.joinToString(prefix = "[", postfix = "]")
            return SpecValidationResult.Invalid(
                code = "input_shape",
                detail = "Expected input shape $expected, got $actual",
                koreanMessage = "입력 텐서 크기 불일치: $expected 필요 (감지됨: $actual)",
            )
        }
        if (!outputShape.contentEquals(EXPECTED_OUTPUT_SHAPE)) {
            val actual = outputShape.joinToString(prefix = "[", postfix = "]")
            val expected = EXPECTED_OUTPUT_SHAPE.joinToString(prefix = "[", postfix = "]")
            return SpecValidationResult.Invalid(
                code = "output_shape",
                detail = "Expected output shape $expected, got $actual",
                koreanMessage = "출력 텐서 크기 불일치: $expected 필요 (감지됨: $actual)",
            )
        }
        return SpecValidationResult.Valid
    }
}

data class VisionModelMetadata(
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAtMillis: Long,
    val backendDescription: String = "LiteRT CPU (XNNPACK, ${VisionContract.DEFAULT_CPU_THREADS}스레드)",
)

sealed interface VisionModelState {
    data object Missing : VisionModelState
    data class Loading(val displayName: String) : VisionModelState
    data class Ready(
        val metadata: VisionModelMetadata,
        val rejectedImportNotice: String? = null,
    ) : VisionModelState
    data class Error(
        val reason: String,
        val detail: String,
    ) : VisionModelState
}
