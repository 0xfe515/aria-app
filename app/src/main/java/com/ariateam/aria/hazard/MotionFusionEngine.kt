package com.ariateam.aria.hazard

import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure, deterministic sensor fusion and corridor tracking engine.
 * Free of Android Sensor dependencies for 100% JVM unit testability.
 */
class MotionFusionEngine(
    private val gravityTauSeconds: Float = 0.15f,
    private val minPitchDegrees: Float = -15f,
    private val maxPitchDegrees: Float = 55f,
    private val maxRollDegrees: Float = 30f,
    private val rapidTurnThresholdDegPerSec: Float = 35f,
    private val gyroDeadbandDegPerSec: Float = 1.5f,
    private val historyCapacity: Int = 512,
    private val streamFreshnessToleranceNanos: Long = 200_000_000L, // 200ms
    defaultHfovDegrees: Float? = null,
) {
    private val lock = Any()

    // Filtered gravity state
    private var gravX = 0f
    private var gravY = 9.81f
    private var gravZ = 0f
    private var hasGravity = false

    // Stream timestamps & fault latching
    private var lastAccelTimestampNanos = 0L
    private var lastGyroTimestampNanos = 0L
    private var accelFault = false
    private var gyroFault = false
    private var accelSampleCount = 0
    private var gyroSampleCount = 0

    val accelSamplesReceived: Int get() = synchronized(lock) { accelSampleCount }
    val gyroSamplesReceived: Int get() = synchronized(lock) { gyroSampleCount }
    val lastAccelNanos: Long get() = synchronized(lock) { lastAccelTimestampNanos }
    val lastGyroNanos: Long get() = synchronized(lock) { lastGyroTimestampNanos }

    // Dynamic camera geometry & pinhole focal length
    private var activeHfovDegrees: Float? = defaultHfovDegrees
    private var halfHfovRad: Float = if (defaultHfovDegrees != null && defaultHfovDegrees > 0f) {
        Math.toRadians((defaultHfovDegrees / 2.0)).toFloat()
    } else {
        0f
    }
    private var pinholeFxPx: Float = if (halfHfovRad > 0f) 256.0f / tan(halfHfovRad) else 0f

    val effectiveHfovDegrees: Float? get() = synchronized(lock) { activeHfovDegrees }

    // Linear acceleration & activity estimation
    private var filteredLinearAccelEnergy = 0f
    private var latestLinearAccelMag = 0f

    // Heading integration
    private var integratedHeadingDeg = 0f
    private var baselineHeadingDeg = 0f
    private var isCalibrated = false
    private var calibrationTimestampNanos = 0L
    private var displayRotation = Surface.ROTATION_0

    // History ring buffer
    internal data class HistoryEntry(
        val state: MotionState,
        val accelTimestampNanos: Long,
        val gyroTimestampNanos: Long,
    )

    private val ringBuffer = Array(historyCapacity) {
        HistoryEntry(MotionState(), 0L, 0L)
    }
    private var ringHead = 0
    private var ringCount = 0

    private var latestState = MotionState()

    fun setDisplayRotation(rotation: Int) {
        synchronized(lock) {
            if (displayRotation != rotation) {
                displayRotation = rotation
                isCalibrated = false
                baselineHeadingDeg = 0f
                integratedHeadingDeg = 0f
                ringHead = 0
                ringCount = 0
                val now = maxOf(lastAccelTimestampNanos, lastGyroTimestampNanos, latestState.timestampNanos)
                val reason = if (activeHfovDegrees == null) {
                    MotionDegradedReason.CAMERA_FOV_UNKNOWN
                } else {
                    MotionDegradedReason.CALIBRATION_PENDING
                }
                latestState = latestState.copy(
                    timestampNanos = now,
                    isCalibrated = false,
                    confidence = 0f,
                    degradedReason = reason,
                )
                pushSnapshotLocked(latestState, lastAccelTimestampNanos, lastGyroTimestampNanos)
            }
        }
    }

    /**
     * Updates the camera geometry with the orientation-aware effective horizontal FOV (degrees).
     * If the FOV changes or is invalid/unknown (null), existing calibration is invalidated
     * and historical snapshots are cleared so old calibrated frames cannot be returned.
     * Updating with the same geometry preserves current calibration.
     */
    fun updateCameraGeometry(effectiveHfovDegrees: Float?) {
        synchronized(lock) {
            val hfov = if (effectiveHfovDegrees != null && effectiveHfovDegrees.isFinite() && effectiveHfovDegrees in 5f..170f) {
                effectiveHfovDegrees
            } else {
                null
            }
            if (activeHfovDegrees != hfov) {
                activeHfovDegrees = hfov
                if (hfov != null) {
                    halfHfovRad = Math.toRadians((hfov / 2.0)).toFloat()
                    pinholeFxPx = 256.0f / tan(halfHfovRad)
                } else {
                    halfHfovRad = 0f
                    pinholeFxPx = 0f
                }
                isCalibrated = false
                baselineHeadingDeg = 0f
                integratedHeadingDeg = 0f

                // Clear history ring buffer so old calibrated snapshots cannot be returned
                ringHead = 0
                ringCount = 0

                val now = maxOf(lastAccelTimestampNanos, lastGyroTimestampNanos, latestState.timestampNanos)
                val initialReason = if (hfov == null) {
                    MotionDegradedReason.CAMERA_FOV_UNKNOWN
                } else {
                    MotionDegradedReason.CALIBRATION_PENDING
                }
                latestState = latestState.copy(
                    timestampNanos = now,
                    isCalibrated = false,
                    confidence = 0f,
                    degradedReason = initialReason,
                )
                pushSnapshotLocked(latestState, lastAccelTimestampNanos, lastGyroTimestampNanos)
            }
        }
    }

    fun calibrateForward(realtimeElapsedNanos: Long): Boolean {
        if (realtimeElapsedNanos <= 0L) return false
        synchronized(lock) {
            if (activeHfovDegrees == null) {
                return false
            }
            val accelAge = realtimeElapsedNanos - lastAccelTimestampNanos
            val gyroAge = realtimeElapsedNanos - lastGyroTimestampNanos
            if (accelAge < 0L || gyroAge < 0L) {
                // Reject future calibration timestamp / negative age
                return false
            }
            if (accelSampleCount < 5 || gyroSampleCount < 5 ||
                accelAge > streamFreshnessToleranceNanos || gyroAge > streamFreshnessToleranceNanos ||
                accelFault || gyroFault) {
                return false
            }

            val pitchRad = atan2(gravZ.toDouble(), gravY.toDouble())
            val pitchDeg = Math.toDegrees(pitchRad).toFloat()
            val rollRad = atan2(gravX.toDouble(), sqrt((gravY * gravY + gravZ * gravZ).toDouble()))
            val rollDeg = Math.toDegrees(rollRad).toFloat()

            if (pitchDeg < minPitchDegrees || pitchDeg > maxPitchDegrees || abs(rollDeg) > maxRollDegrees) {
                return false
            }
            if (filteredLinearAccelEnergy > 0.45f) {
                return false
            }

            baselineHeadingDeg = integratedHeadingDeg
            isCalibrated = true
            calibrationTimestampNanos = realtimeElapsedNanos
            updateLatestStateLocked(realtimeElapsedNanos)
            return true
        }
    }

    fun reset() {
        synchronized(lock) {
            gravX = 0f
            gravY = 9.81f
            gravZ = 0f
            hasGravity = false
            lastGyroTimestampNanos = 0L
            lastAccelTimestampNanos = 0L
            accelFault = false
            gyroFault = false
            accelSampleCount = 0
            gyroSampleCount = 0
            filteredLinearAccelEnergy = 0f
            latestLinearAccelMag = 0f
            integratedHeadingDeg = 0f
            baselineHeadingDeg = 0f
            isCalibrated = false
            calibrationTimestampNanos = 0L
            ringHead = 0
            ringCount = 0
            latestState = MotionState()
        }
    }

    fun onAccelerometerSample(
        timestampNanos: Long,
        rawAx: Float,
        rawAy: Float,
        rawAz: Float,
    ): Boolean {
        if (timestampNanos <= 0L) return false
        if (!rawAx.isFinite() || !rawAy.isFinite() || !rawAz.isFinite()) {
            synchronized(lock) {
                accelFault = true
                recordDegradedSnapshotLocked(timestampNanos, MotionDegradedReason.NON_FINITE_DATA)
            }
            return false
        }

        synchronized(lock) {
            if (lastAccelTimestampNanos > 0L) {
                val dtNanos = timestampNanos - lastAccelTimestampNanos
                if (dtNanos <= 0L) return false
                if (dtNanos > streamFreshnessToleranceNanos) {
                    lastAccelTimestampNanos = timestampNanos
                    isCalibrated = false
                    updateLatestStateLocked(timestampNanos)
                    return true
                }
            }
            val dtSec = if (lastAccelTimestampNanos > 0L) {
                (timestampNanos - lastAccelTimestampNanos) / 1_000_000_000.0f
            } else {
                0.01f
            }
            lastAccelTimestampNanos = timestampNanos
            accelFault = false
            accelSampleCount++

            val (ax, ay, az) = remapSensorAxes(rawAx, rawAy, rawAz, displayRotation)

            if (!hasGravity) {
                gravX = ax
                gravY = ay
                gravZ = az
                hasGravity = true
            } else {
                val alpha = (dtSec / (gravityTauSeconds + dtSec)).coerceIn(0.01f, 0.5f)
                gravX = (1f - alpha) * gravX + alpha * ax
                gravY = (1f - alpha) * gravY + alpha * ay
                gravZ = (1f - alpha) * gravZ + alpha * az
            }

            val linX = ax - gravX
            val linY = ay - gravY
            val linZ = az - gravZ
            val linMag = sqrt(linX * linX + linY * linY + linZ * linZ)
            latestLinearAccelMag = linMag

            val beta = 0.10f
            filteredLinearAccelEnergy = (1f - beta) * filteredLinearAccelEnergy + beta * linMag

            updateLatestStateLocked(timestampNanos)
            return true
        }
    }

    fun onGyroscopeSample(
        timestampNanos: Long,
        rawGx: Float,
        rawGy: Float,
        rawGz: Float,
    ): Boolean {
        if (timestampNanos <= 0L) return false
        if (!rawGx.isFinite() || !rawGy.isFinite() || !rawGz.isFinite()) {
            synchronized(lock) {
                gyroFault = true
                recordDegradedSnapshotLocked(timestampNanos, MotionDegradedReason.NON_FINITE_DATA)
            }
            return false
        }

        synchronized(lock) {
            if (lastGyroTimestampNanos > 0L) {
                val dtNanos = timestampNanos - lastGyroTimestampNanos
                if (dtNanos <= 0L) return false
                if (dtNanos > streamFreshnessToleranceNanos) {
                    // Stream gap detected: do NOT integrate across gap, invalidate calibration
                    lastGyroTimestampNanos = timestampNanos
                    isCalibrated = false
                    updateLatestStateLocked(timestampNanos)
                    return true
                }
            }
            val dtSec = if (lastGyroTimestampNanos > 0L) {
                (timestampNanos - lastGyroTimestampNanos) / 1_000_000_000.0f
            } else {
                0.01f
            }
            lastGyroTimestampNanos = timestampNanos
            gyroFault = false
            gyroSampleCount++

            val (gx, gy, gz) = remapSensorAxes(rawGx, rawGy, rawGz, displayRotation)

            // Upward vertical unit vector u_hat = g_vector / |g_vector|
            val gNorm = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
            val yawRateDegPerSec = if (gNorm > 1e-4f) {
                val dot = (gx * gravX + gy * gravY + gz * gravZ) / gNorm
                Math.toDegrees(dot.toDouble()).toFloat()
            } else {
                Math.toDegrees(gy.toDouble()).toFloat()
            }

            if (abs(yawRateDegPerSec) > gyroDeadbandDegPerSec) {
                integratedHeadingDeg += yawRateDegPerSec * dtSec
            }

            updateLatestStateLocked(timestampNanos, yawRateDegPerSec)
            return true
        }
    }

    private fun updateLatestStateLocked(
        timestampNanos: Long,
        currentYawRateDegPerSec: Float? = null,
    ) {
        val yawRate = currentYawRateDegPerSec ?: latestState.yawRateDegPerSec

        val pitchRad = atan2(gravZ.toDouble(), gravY.toDouble())
        val pitchDeg = Math.toDegrees(pitchRad).toFloat()
        val rollRad = atan2(gravX.toDouble(), sqrt((gravY * gravY + gravZ * gravZ).toDouble()))
        val rollDeg = Math.toDegrees(rollRad).toFloat()

        val accelAge = timestampNanos - lastAccelTimestampNanos
        val gyroAge = timestampNanos - lastGyroTimestampNanos
        val isStreamGapped = (accelAge > streamFreshnessToleranceNanos || gyroAge > streamFreshnessToleranceNanos)

        var degradedReason: MotionDegradedReason? = null
        var confidence = 1.0f

        if (activeHfovDegrees == null) {
            degradedReason = MotionDegradedReason.CAMERA_FOV_UNKNOWN
            confidence = 0f
        } else if (accelFault || gyroFault) {
            degradedReason = MotionDegradedReason.NON_FINITE_DATA
            confidence = 0f
        } else if (isStreamGapped && (accelSampleCount > 0 || gyroSampleCount > 0)) {
            degradedReason = MotionDegradedReason.SAMPLES_STALE
            confidence = 0f
        } else if (!isCalibrated) {
            degradedReason = MotionDegradedReason.CALIBRATION_PENDING
            confidence = 0f
        } else if (pitchDeg < minPitchDegrees || pitchDeg > maxPitchDegrees || abs(rollDeg) > maxRollDegrees) {
            degradedReason = MotionDegradedReason.CAMERA_TILT_INVALID
            confidence = (1f - (abs(rollDeg) / 60f)).coerceIn(0.1f, 0.4f)
        } else if (abs(yawRate) > rapidTurnThresholdDegPerSec) {
            degradedReason = MotionDegradedReason.RAPID_TURN_TRANSIENT
            confidence = 0.2f
        }

        val relHeading = integratedHeadingDeg - baselineHeadingDeg

        val calibAgeNanos = timestampNanos - calibrationTimestampNanos
        if (isCalibrated && calibAgeNanos > 60_000_000_000L && degradedReason == null) {
            // Gyroscope yaw integration drifts over extended periods (>60s).
            // Linearly decay confidence from 1.0 down to 0.4 between 60s and 120s.
            val decay = ((calibAgeNanos - 60_000_000_000L) / 60_000_000_000.0f).coerceIn(0f, 0.6f)
            confidence = (confidence - decay).coerceAtLeast(0.4f)
        }

        // Pinhole camera field of view projection:
        // When phone yaws left (positive relHeading), forward target moves to the RIGHT in camera view!
        val targetAngleRad = Math.toRadians(relHeading.toDouble())
        val isOutOfFov = if (halfHfovRad > 0f) {
            abs(targetAngleRad) > halfHfovRad.toDouble()
        } else {
            true
        }

        if (isOutOfFov && degradedReason == null) {
            degradedReason = MotionDegradedReason.EXCESSIVE_DRIFT
            confidence = 0.2f
        }

        val projectedColPx = if (!isOutOfFov && pinholeFxPx > 0f) {
            (256.0 + pinholeFxPx * tan(targetAngleRad)).toFloat().coerceIn(0f, 511f)
        } else if (targetAngleRad < 0) {
            0f
        } else {
            511f
        }

        val corridor = when {
            projectedColPx < 171f -> CorridorDirection.LEFT
            projectedColPx > 341f -> CorridorDirection.RIGHT
            else -> CorridorDirection.CENTER
        }

        val activity = when {
            filteredLinearAccelEnergy > 0.35f -> UserMovementActivity.WALKING
            accelSampleCount >= 5 -> UserMovementActivity.STATIONARY
            else -> UserMovementActivity.UNKNOWN
        }

        val state = MotionState(
            timestampNanos = timestampNanos,
            isCalibrated = isCalibrated,
            pitchDegrees = pitchDeg,
            rollDegrees = rollDeg,
            yawRateDegPerSec = yawRate,
            relativeHeadingDeg = relHeading,
            activeCorridor = corridor,
            continuousCorridorCenterPx = projectedColPx,
            activity = activity,
            linearAccelMag = latestLinearAccelMag,
            isCameraForwardWalk = isCalibrated && degradedReason == null,
            confidence = confidence,
            degradedReason = degradedReason,
        )

        latestState = state
        pushSnapshotLocked(state, lastAccelTimestampNanos, lastGyroTimestampNanos)
    }

    private fun recordDegradedSnapshotLocked(timestampNanos: Long, reason: MotionDegradedReason) {
        val state = latestState.copy(
            timestampNanos = timestampNanos,
            confidence = 0f,
            degradedReason = reason,
        )
        latestState = state
        pushSnapshotLocked(state, lastAccelTimestampNanos, lastGyroTimestampNanos)
    }

    private fun pushSnapshotLocked(state: MotionState, accelNanos: Long, gyroNanos: Long) {
        ringBuffer[ringHead] = HistoryEntry(state, accelNanos, gyroNanos)
        ringHead = (ringHead + 1) % historyCapacity
        if (ringCount < historyCapacity) {
            ringCount++
        }
    }

    fun stateAt(queryTimestampNanos: Long): MotionState {
        synchronized(lock) {
            if (ringCount == 0) {
                return MotionState(
                    timestampNanos = queryTimestampNanos,
                    confidence = 0f,
                    degradedReason = if (activeHfovDegrees == null) {
                        MotionDegradedReason.CAMERA_FOV_UNKNOWN
                    } else {
                        MotionDegradedReason.SENSORS_UNAVAILABLE
                    },
                )
            }

            var bestEntry: HistoryEntry? = null
            var bestTimestamp = Long.MIN_VALUE

            val startIdx = (ringHead - ringCount + historyCapacity) % historyCapacity
            for (offset in 0 until ringCount) {
                val entry = ringBuffer[(startIdx + offset) % historyCapacity]
                val t = entry.state.timestampNanos
                if (t in bestTimestamp..queryTimestampNanos &&
                    entry.accelTimestampNanos <= queryTimestampNanos &&
                    entry.gyroTimestampNanos <= queryTimestampNanos
                ) {
                    bestTimestamp = t
                    bestEntry = entry
                }
            }

            if (bestEntry == null) {
                return MotionState(
                    timestampNanos = queryTimestampNanos,
                    isCalibrated = isCalibrated,
                    confidence = 0f,
                    degradedReason = MotionDegradedReason.SAMPLES_STALE,
                )
            }

            // Latched sensor stream fault check
            if (accelFault || gyroFault) {
                return bestEntry.state.copy(
                    timestampNanos = queryTimestampNanos,
                    confidence = 0f,
                    degradedReason = MotionDegradedReason.NON_FINITE_DATA,
                )
            }

            val accelAge = queryTimestampNanos - bestEntry.accelTimestampNanos
            val gyroAge = queryTimestampNanos - bestEntry.gyroTimestampNanos
            if (accelAge > streamFreshnessToleranceNanos || gyroAge > streamFreshnessToleranceNanos) {
                return bestEntry.state.copy(
                    timestampNanos = queryTimestampNanos,
                    confidence = 0f,
                    degradedReason = MotionDegradedReason.SAMPLES_STALE,
                )
            }

            return bestEntry.state
        }
    }

    companion object {
        fun remapSensorAxes(
            x: Float,
            y: Float,
            z: Float,
            displayRotation: Int,
        ): Triple<Float, Float, Float> {
            return when (displayRotation) {
                Surface.ROTATION_90 -> Triple(-y, x, z)
                Surface.ROTATION_180 -> Triple(-x, -y, z)
                Surface.ROTATION_270 -> Triple(y, -x, z)
                else -> Triple(x, y, z)
            }
        }
    }
}
