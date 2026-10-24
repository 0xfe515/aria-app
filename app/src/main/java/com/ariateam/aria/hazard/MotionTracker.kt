package com.ariateam.aria.hazard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface

/**
 * Real-time IMU motion and phone orientation tracker combining accelerometer and gyroscope.
 * Delegates sensor fusion, history buffering, and corridor steering to [MotionFusionEngine].
 */
class MotionTracker(
    context: Context? = null,
    private val sensorDelay: Int = SensorManager.SENSOR_DELAY_GAME,
    val engine: MotionFusionEngine = MotionFusionEngine(),
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) : AutoCloseable {

    private val sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val lifecycleLock = Any()
    private val startGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var activeGeneration: Long = 0L
    private var activeListener: SensorEventListener? = null
    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var sensorsAvailable: Boolean = false
        private set

    internal class SessionListener(
        val sessionGen: Long,
        val sessionStartNanos: Long,
        private val tracker: MotionTracker,
    ) : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            tracker.dispatchSensorEvent(
                listener = this,
                sessionGen = sessionGen,
                sessionStartNanos = sessionStartNanos,
                sensorType = event.sensor.type,
                timestamp = event.timestamp,
                v0 = event.values[0],
                v1 = event.values[1],
                v2 = event.values[2],
            )
        }

        fun dispatchTestSample(sensorType: Int, timestamp: Long, v0: Float, v1: Float, v2: Float) {
            tracker.dispatchSensorEvent(
                listener = this,
                sessionGen = sessionGen,
                sessionStartNanos = sessionStartNanos,
                sensorType = sensorType,
                timestamp = timestamp,
                v0 = v0,
                v1 = v1,
                v2 = v2,
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    internal fun startTestSession(generation: Long, startNanos: Long): SessionListener {
        synchronized(lifecycleLock) {
            activeGeneration = generation
            isRunning = true
            sensorsAvailable = true
            val listener = SessionListener(generation, startNanos, this)
            activeListener = listener
            return listener
        }
    }

    internal fun dispatchSensorEvent(
        listener: SensorEventListener,
        sessionGen: Long,
        sessionStartNanos: Long,
        sensorType: Int,
        timestamp: Long,
        v0: Float,
        v1: Float,
        v2: Float,
    ) {
        synchronized(lifecycleLock) {
            if (!isRunning || activeListener !== listener || activeGeneration != sessionGen) {
                return
            }
            if (timestamp < sessionStartNanos) {
                return
            }
            when (sensorType) {
                Sensor.TYPE_ACCELEROMETER -> {
                    engine.onAccelerometerSample(
                        timestampNanos = timestamp,
                        rawAx = v0,
                        rawAy = v1,
                        rawAz = v2,
                    )
                }
                Sensor.TYPE_GYROSCOPE -> {
                    engine.onGyroscopeSample(
                        timestampNanos = timestamp,
                        rawGx = v0,
                        rawGy = v1,
                        rawGz = v2,
                    )
                }
            }
        }
    }

    fun start(displayRotation: Int = Surface.ROTATION_0) {
        synchronized(lifecycleLock) {
            if (isRunning) return
            val sessionGen = startGeneration.incrementAndGet()
            activeGeneration = sessionGen
            val sessionStartNanos = clock()
            engine.reset()
            engine.setDisplayRotation(displayRotation)

            val thread = HandlerThread("aria-imu-motion").apply { start() }
            val handler = Handler(thread.looper)
            handlerThread = thread
            sensorHandler = handler

            if (sensorManager == null || accelSensor == null || gyroSensor == null) {
                thread.quitSafely()
                handlerThread = null
                sensorHandler = null
                sensorsAvailable = false
                isRunning = false
                return
            }

            val listener = SessionListener(sessionGen, sessionStartNanos, this)
            activeListener = listener

            val accelOk = sensorManager.registerListener(listener, accelSensor, sensorDelay, handler)
            val gyroOk = sensorManager.registerListener(listener, gyroSensor, sensorDelay, handler)

            if (!accelOk || !gyroOk) {
                sensorManager.unregisterListener(listener)
                activeListener = null
                thread.quitSafely()
                handlerThread = null
                sensorHandler = null
                sensorsAvailable = false
                isRunning = false
                return
            }

            sensorsAvailable = true
            isRunning = true
        }
    }

    fun setDisplayRotation(rotation: Int) {
        synchronized(lifecycleLock) {
            engine.setDisplayRotation(rotation)
        }
    }

    fun updateCameraGeometry(effectiveHfovDegrees: Float?) {
        synchronized(lifecycleLock) {
            engine.updateCameraGeometry(effectiveHfovDegrees)
        }
    }

    fun calibrateForward(realtimeElapsedNanos: Long = clock()): Boolean {
        synchronized(lifecycleLock) {
            if (!isRunning) return false
            return engine.calibrateForward(realtimeElapsedNanos)
        }
    }

    fun stateAt(queryTimestampNanos: Long): MotionState {
        synchronized(lifecycleLock) {
            if (!isRunning || !sensorsAvailable) {
                return MotionState(
                    timestampNanos = queryTimestampNanos,
                    confidence = 0f,
                    degradedReason = MotionDegradedReason.SENSORS_UNAVAILABLE,
                )
            }
            return engine.stateAt(queryTimestampNanos)
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            startGeneration.incrementAndGet()
            activeGeneration = 0L
            isRunning = false
            sensorsAvailable = false
            activeListener?.let { listener ->
                sensorManager?.unregisterListener(listener)
            }
            activeListener = null
            handlerThread?.quitSafely()
            handlerThread = null
            sensorHandler = null
            engine.reset()
        }
    }

    override fun close() {
        stop()
    }
}
