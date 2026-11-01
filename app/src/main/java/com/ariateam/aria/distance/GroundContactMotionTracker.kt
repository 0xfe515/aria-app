package com.ariateam.aria.distance

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.ariateam.aria.hazard.Vector3D
import kotlin.math.sqrt

/**
 * Orientation snapshot for ground-plane intersection containing the camera-space UP vector.
 *
 * In upright camera coordinates (+X right, +Y down, +Z forward):
 * - Perfectly vertical phone: World UP is (0, -1, 0).
 * - Phone tilted down 30 deg: World UP has negative Z component (0, -cos(30), -sin(30)).
 */
data class GroundContactOrientation(
    val timestampNanos: Long = 0L,
    val upVector: Vector3D = Vector3D(0f, -1f, 0f),
    val isReliable: Boolean = false,
    val degradedReason: String? = null,
    val rapidMotion: Boolean = false,
)

/**
 * Standalone IMU orientation tracker for monocular ground-contact distance estimation.
 *
 * Features:
 * - Free of heading calibration dependency (works immediately from gravity).
 * - Avoids magnetometer dependency (uses accelerometer + gyro).
 * - Maps Android sensor coordinates to upright camera coordinates based on display rotation.
 * - Enforces freshness tolerance (<= 200ms) and rapid-motion angular velocity rejection (> 45 deg/s).
 * - Validates acceleration magnitude (rejects freefall < 4.5 m/s² and extreme shock > 16.5 m/s²).
 * - Bounded history ring buffer aligns exact camera frame timestamps.
 * - Per-session listener instances fence late callbacks across stop/start cycles.
 */
class GroundContactMotionTracker(
    context: Context? = null,
    private val sensorDelay: Int = SensorManager.SENSOR_DELAY_GAME,
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) : AutoCloseable {

    private val sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val lock = Any()
    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var sensorsAvailable: Boolean = false
        private set

    private var activeGeneration: Long = 0L
    internal var activeListener: SessionListener? = null
    private var displayRotation: Int = Surface.ROTATION_0

    // Filtered gravity in upright camera space
    private var gravCamX = 0f
    private var gravCamY = -9.81f
    private var gravCamZ = 0f
    private var hasGravity = false

    // Fault latching
    private var accelFault = false
    private var gyroFault = false
    private var rapidTurnUntilNanos = 0L

    // Bounded history ring buffer for timestamp-aligned orientation
    internal data class HistoryEntry(
        val timestampNanos: Long,
        val upX: Float,
        val upY: Float,
        val upZ: Float,
        val isFault: Boolean,
        val isRapidMotion: Boolean,
    )

    private val historyCapacity = 128
    private val ringBuffer = Array(historyCapacity) {
        HistoryEntry(0L, 0f, -1f, 0f, isFault = true, isRapidMotion = false)
    }
    private var ringHead = 0
    private var ringCount = 0
    private var latestAccelTimestampNanos = 0L
    private var latestGyroTimestampNanos = 0L

    internal class SessionListener(
        val generation: Long,
        val tracker: GroundContactMotionTracker,
    ) : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    tracker.onAccelerometerSample(
                        timestampNanos = event.timestamp,
                        rawAx = event.values[0],
                        rawAy = event.values[1],
                        rawAz = event.values[2],
                        generation = generation,
                        listener = this,
                    )
                }
                Sensor.TYPE_GYROSCOPE -> {
                    tracker.onGyroscopeSample(
                        timestampNanos = event.timestamp,
                        rawGx = event.values[0],
                        rawGy = event.values[1],
                        rawGz = event.values[2],
                        generation = generation,
                        listener = this,
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(targetRotation: Int = Surface.ROTATION_0) {
        synchronized(lock) {
            if (isRunning) return
            activeGeneration++
            displayRotation = targetRotation
            resetFilterLocked()

            val listener = SessionListener(activeGeneration, this)
            activeListener = listener

            if (sensorManager != null && accelSensor != null) {
                val thread = HandlerThread("aria-ground-motion-tracker").apply { start() }
                handlerThread = thread
                val handler = Handler(thread.looper)
                sensorHandler = handler

                val okAccel = sensorManager.registerListener(listener, accelSensor, sensorDelay, handler)
                val okGyro = if (gyroSensor != null) {
                    sensorManager.registerListener(listener, gyroSensor, sensorDelay, handler)
                } else false

                sensorsAvailable = okAccel
                isRunning = true
            } else {
                sensorsAvailable = false
                isRunning = true
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isRunning) return
            isRunning = false
            activeGeneration++
            val listener = activeListener
            if (listener != null) {
                runCatching { sensorManager?.unregisterListener(listener) }
                activeListener = null
            }
            handlerThread?.quitSafely()
            handlerThread = null
            sensorHandler = null
            resetFilterLocked()
        }
    }

    fun setDisplayRotation(rotation: Int) {
        synchronized(lock) {
            if (displayRotation != rotation) {
                displayRotation = rotation
                hasGravity = false
                ringHead = 0
                ringCount = 0
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            resetFilterLocked()
        }
    }

    private fun resetFilterLocked() {
        gravCamX = 0f
        gravCamY = -9.81f
        gravCamZ = 0f
        hasGravity = false
        accelFault = false
        gyroFault = false
        rapidTurnUntilNanos = 0L
        ringHead = 0
        ringCount = 0
        latestAccelTimestampNanos = 0L
        latestGyroTimestampNanos = 0L
    }

    fun onAccelerometerSample(
        timestampNanos: Long,
        rawAx: Float,
        rawAy: Float,
        rawAz: Float,
        generation: Long = activeGeneration,
        listener: SensorEventListener? = null,
    ): Boolean {
        synchronized(lock) {
            if (!isRunning || generation != activeGeneration) return false
            if (listener != null && activeListener !== listener) return false
            if (timestampNanos <= 0L) return false

            if (!rawAx.isFinite() || !rawAy.isFinite() || !rawAz.isFinite()) {
                accelFault = true
                pushHistoryLocked(timestampNanos, 0f, -1f, 0f, isFault = true, isRapid = false)
                return false
            }

            // Validate acceleration magnitude (freefall or extreme dynamic acceleration)
            val rawMag = sqrt(rawAx * rawAx + rawAy * rawAy + rawAz * rawAz)
            if (rawMag < MIN_GRAVITY_MAG || rawMag > MAX_GRAVITY_MAG) {
                accelFault = true
                pushHistoryLocked(timestampNanos, 0f, -1f, 0f, isFault = true, isRapid = false)
                return false
            }

            val dtSec = if (latestAccelTimestampNanos > 0L) {
                val dt = timestampNanos - latestAccelTimestampNanos
                if (dt <= 0L) return false
                (dt / 1_000_000_000.0f).coerceIn(0.001f, 0.5f)
            } else {
                0.01f
            }
            latestAccelTimestampNanos = timestampNanos
            accelFault = false

            // Remap Android sensor coordinates for display rotation
            val (axDisp, ayDisp, azDisp) = when (displayRotation) {
                Surface.ROTATION_90 -> Triple(-rawAy, rawAx, rawAz)
                Surface.ROTATION_180 -> Triple(-rawAx, -rawAy, rawAz)
                Surface.ROTATION_270 -> Triple(rawAy, -rawAx, rawAz)
                else -> Triple(rawAx, rawAy, rawAz)
            }

            // Remap display coordinates into upright camera space:
            // Camera right: +X_cam = axDisp
            // Camera down:  +Y_cam = -ayDisp
            // Camera fwd:   +Z_cam = -azDisp
            val aCamX = axDisp
            val aCamY = -ayDisp
            val aCamZ = -azDisp

            if (!hasGravity) {
                gravCamX = aCamX
                gravCamY = aCamY
                gravCamZ = aCamZ
                hasGravity = true
            } else {
                val alpha = (dtSec / (GRAVITY_TAU_SECONDS + dtSec)).coerceIn(0.01f, 0.5f)
                gravCamX = (1f - alpha) * gravCamX + alpha * aCamX
                gravCamY = (1f - alpha) * gravCamY + alpha * aCamY
                gravCamZ = (1f - alpha) * gravCamZ + alpha * aCamZ
            }

            val isRapid = timestampNanos < rapidTurnUntilNanos
            pushHistoryLocked(timestampNanos, gravCamX, gravCamY, gravCamZ, isFault = false, isRapid = isRapid)
            return true
        }
    }

    fun onGyroscopeSample(
        timestampNanos: Long,
        rawGx: Float,
        rawGy: Float,
        rawGz: Float,
        generation: Long = activeGeneration,
        listener: SensorEventListener? = null,
    ): Boolean {
        synchronized(lock) {
            if (!isRunning || generation != activeGeneration) return false
            if (listener != null && activeListener !== listener) return false
            if (timestampNanos <= 0L) return false
            if (latestGyroTimestampNanos > 0L && timestampNanos <= latestGyroTimestampNanos) {
                return false
            }

            if (!rawGx.isFinite() || !rawGy.isFinite() || !rawGz.isFinite()) {
                gyroFault = true
                return false
            }

            latestGyroTimestampNanos = timestampNanos
            gyroFault = false
            val omegaMagRad = sqrt(rawGx * rawGx + rawGy * rawGy + rawGz * rawGz)
            val omegaMagDeg = Math.toDegrees(omegaMagRad.toDouble()).toFloat()
            if (omegaMagDeg > RAPID_MOTION_THRESHOLD_DEG_PER_SEC) {
                val newHoldUntil = timestampNanos + RAPID_MOTION_HOLD_NANOS
                if (newHoldUntil > rapidTurnUntilNanos) {
                    rapidTurnUntilNanos = newHoldUntil
                }
            }
            return true
        }
    }

    private fun pushHistoryLocked(
        timestampNanos: Long,
        gx: Float,
        gy: Float,
        gz: Float,
        isFault: Boolean,
        isRapid: Boolean,
    ) {
        val entry = HistoryEntry(
            timestampNanos = timestampNanos,
            upX = gx,
            upY = gy,
            upZ = gz,
            isFault = isFault,
            isRapidMotion = isRapid,
        )
        ringBuffer[ringHead] = entry
        ringHead = (ringHead + 1) % historyCapacity
        if (ringCount < historyCapacity) {
            ringCount++
        }
    }

    /**
     * Queries the upright camera-space UP vector aligned to [queryTimestampNanos].
     */
    fun orientationAt(queryTimestampNanos: Long): GroundContactOrientation {
        synchronized(lock) {
            if (accelFault || gyroFault) {
                return GroundContactOrientation(
                    timestampNanos = queryTimestampNanos,
                    upVector = Vector3D(0f, -1f, 0f),
                    isReliable = false,
                    degradedReason = "비정상 센서 수치(가속도 이상) 감지",
                )
            }

            if (ringCount == 0 || !hasGravity) {
                return GroundContactOrientation(
                    timestampNanos = queryTimestampNanos,
                    upVector = Vector3D(0f, -1f, 0f),
                    isReliable = false,
                    degradedReason = "센서(자세) 신호 미수신",
                )
            }

            // Find history entry closest to queryTimestampNanos
            var bestEntry: HistoryEntry? = null
            var minDiff = Long.MAX_VALUE

            for (i in 0 until ringCount) {
                val idx = (ringHead - 1 - i + historyCapacity) % historyCapacity
                val entry = ringBuffer[idx]
                val diff = kotlin.math.abs(queryTimestampNanos - entry.timestampNanos)
                if (diff < minDiff) {
                    minDiff = diff
                    bestEntry = entry
                }
            }

            if (bestEntry == null) {
                return GroundContactOrientation(
                    timestampNanos = queryTimestampNanos,
                    upVector = Vector3D(0f, -1f, 0f),
                    isReliable = false,
                    degradedReason = "센서 데이터 탐색 실패",
                )
            }

            // Enforce freshness tolerance relative to query timestamp
            if (minDiff > FRESHNESS_TOLERANCE_NANOS) {
                return GroundContactOrientation(
                    timestampNanos = queryTimestampNanos,
                    upVector = Vector3D(0f, -1f, 0f),
                    isReliable = false,
                    degradedReason = "센서 데이터 지연",
                )
            }

            if (bestEntry.isFault || accelFault || gyroFault) {
                return GroundContactOrientation(
                    timestampNanos = queryTimestampNanos,
                    upVector = Vector3D(0f, -1f, 0f),
                    isReliable = false,
                    degradedReason = "비정상 센서 수치(가속도 이상) 감지",
                )
            }

            if (bestEntry.isRapidMotion || queryTimestampNanos < rapidTurnUntilNanos) {
                val upVec = Vector3D(bestEntry.upX, bestEntry.upY, bestEntry.upZ).normalize() ?: Vector3D(0f, -1f, 0f)
                return GroundContactOrientation(
                    timestampNanos = queryTimestampNanos,
                    upVector = upVec,
                    isReliable = false,
                    degradedReason = "급격한 기기 움직임 감지",
                    rapidMotion = true,
                )
            }

            val rawVec = Vector3D(bestEntry.upX, bestEntry.upY, bestEntry.upZ)
            val upVec = rawVec.normalize()
            if (upVec == null) {
                return GroundContactOrientation(
                    timestampNanos = queryTimestampNanos,
                    upVector = Vector3D(0f, -1f, 0f),
                    isReliable = false,
                    degradedReason = "중력 벡터 크기 비정상",
                )
            }

            return GroundContactOrientation(
                timestampNanos = queryTimestampNanos,
                upVector = upVec,
                isReliable = true,
                degradedReason = null,
                rapidMotion = false,
            )
        }
    }

    override fun close() {
        stop()
    }

    companion object {
        const val FRESHNESS_TOLERANCE_NANOS = 200_000_000L // 200ms
        const val GRAVITY_TAU_SECONDS = 0.20f
        const val RAPID_MOTION_THRESHOLD_DEG_PER_SEC = 45f
        const val RAPID_MOTION_HOLD_NANOS = 200_000_000L // 200ms
        const val MIN_GRAVITY_MAG = 4.5f // Freefall threshold
        const val MAX_GRAVITY_MAG = 16.5f // Extreme acceleration threshold
    }
}
