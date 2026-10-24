package com.ariateam.aria.hazard

import kotlin.math.atan

/**
 * Physical lens and sensor intrinsic parameters from Camera2 metadata.
 */
data class CameraIntrinsics(
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val activeArrayWidth: Int = 0,
    val activeArrayHeight: Int = 0,
) {
    val isValid: Boolean
        get() = focalLengthMm > 0f && focalLengthMm.isFinite() &&
                sensorWidthMm > 0f && sensorWidthMm.isFinite() &&
                sensorHeightMm > 0f && sensorHeightMm.isFinite()
}

/**
 * Deterministic camera geometry and orientation-aware field of view helper.
 * Free of Android framework dependencies for 100% JVM unit testability.
 */
object CameraGeometry {

    /**
     * Derives effective upright horizontal field of view (in degrees) for a model input
     * given camera intrinsics, camera buffer dimensions, display/sensor rotation,
     * and upright crop dimensions.
     *
     * Swaps axes for 90/270 degrees (upright horizontal axis corresponds to sensor height).
     * Accounts for sensor-to-buffer aspect ratio crop and preprocessor horizontal crop scale.
     *
     * Returns null (UNKNOWN) if metadata or crop geometry is missing, non-finite, or unsupported.
     */
    fun computeEffectiveUprightHfovDegrees(
        intrinsics: CameraIntrinsics?,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        cropWidth: Int,
        uprightWidth: Int,
    ): Float? {
        if (intrinsics == null || !intrinsics.isValid) return null
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        if (rotationDegrees !in setOf(0, 90, 180, 270)) return null
        if (cropWidth <= 0 || uprightWidth <= 0 || cropWidth > uprightWidth) return null

        val f = intrinsics.focalLengthMm
        val sw = intrinsics.sensorWidthMm
        val sh = intrinsics.sensorHeightMm

        // Sensor aspect ratio
        val sensorAspect = if (intrinsics.activeArrayWidth > 0 && intrinsics.activeArrayHeight > 0) {
            intrinsics.activeArrayWidth.toDouble() / intrinsics.activeArrayHeight.toDouble()
        } else {
            sw.toDouble() / sh.toDouble()
        }
        val bufferAspect = bufferWidth.toDouble() / bufferHeight.toDouble()

        // Account for active array / buffer aspect ratio crop in Camera2
        val usedSensorWidth: Double
        val usedSensorHeight: Double
        if (bufferAspect > sensorAspect + 1e-4) {
            // Buffer is wider than sensor: sensor height is cropped
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sw.toDouble() / bufferAspect
        } else if (bufferAspect < sensorAspect - 1e-4) {
            // Buffer is taller than sensor: sensor width is cropped
            usedSensorWidth = sh.toDouble() * bufferAspect
            usedSensorHeight = sh.toDouble()
        } else {
            // Identical aspect ratio: full sensor area used
            usedSensorWidth = sw.toDouble()
            usedSensorHeight = sh.toDouble()
        }

        // Swap axes for portrait (90 or 270 deg): upright horizontal axis corresponds to sensor vertical axis
        val uprightSensorWidthMm = when (rotationDegrees) {
            90, 270 -> usedSensorHeight
            else -> usedSensorWidth
        }

        // Preprocessor horizontal crop scale
        val cropScaleX = cropWidth.toDouble() / uprightWidth.toDouble()
        val effectiveModelSensorWidthMm = uprightSensorWidthMm * cropScaleX

        // Pinhole camera FOV formula: HFOV = 2 * atan(W / (2 * f))
        val hfovRad = 2.0 * atan(effectiveModelSensorWidthMm / (2.0 * f))
        val hfovDeg = Math.toDegrees(hfovRad).toFloat()

        if (!hfovDeg.isFinite() || hfovDeg <= 5f || hfovDeg >= 170f) {
            return null
        }
        return hfovDeg
    }
}
