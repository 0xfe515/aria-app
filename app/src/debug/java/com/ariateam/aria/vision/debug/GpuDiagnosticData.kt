package com.ariateam.aria.vision.debug

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf

data class GpuDiagnosticData(
    val stage: String, // "BEFORE" or "AFTER"
    val stageTitle: String, // "BEFORE: PARTIAL GPU DELEGATION" or "AFTER: FULL GPU DELEGATION"
    val stageSubtitle: String,
    val stageBadgeColor: Long, // 0xFFEF4444L or 0xFF10B981L
    val modelName: String, // "walkable_seg_aria5.tflite" vs "walkable_seg_aria5_unfused.tflite"
    val modelSizeBytes: Long,
    val modelSha256: String,
    val delegateConfig: String, // "TfLiteGpuDelegateV2 (FP32 Sustained)"
    val precisionLossAllowed: Boolean, // false
    val inferencePreference: String, // "SUSTAINED_SPEED"
    val partitionCount: Int, // 3 vs 1
    val gpuDelegatedNodes: Int, // 64 vs 210
    val totalNodes: Int, // 204 vs 210
    val cpuFallbackNodes: Int, // 140 vs 0
    val rootCauseSummary: String,
    val warmupCount: Int,
    val sampleCount: Int,
    val p50LatencyMs: Double,
    val minLatencyMs: Double,
    val maxLatencyMs: Double,
    val sampleTimingsMs: List<Double>,
    val cpu4ReferenceP50Ms: Double,
    val speedupVsCpu: Double,
    val maxDiffVsCpu: Float,
    val maeVsCpu: Float,
    val argmaxAgreementVsCpu: Float,
    val walkableAgreementVsCpu: Float,
    val thermalStatusStart: Int,
    val thermalStatusEnd: Int,
    val batteryTempStartC: Float,
    val batteryTempEndC: Float,
    val deviceModel: String, // "Google Pixel 3 (blueline)"
    val androidVersion: String, // "Android 12 (API 31)"
    val timestamp: String,
    val observedLogLines: List<String>,
    val rawReplacingLines: List<String> = emptyList(),
    val inputBitmap: Bitmap? = null,
    val maskBitmap: Bitmap? = null,
)

object GpuDiagnosticStateHolder {
    val liveData = mutableStateOf<GpuDiagnosticData?>(null)

    var currentData: GpuDiagnosticData?
        get() = liveData.value
        set(value) {
            liveData.value = value
        }
}
