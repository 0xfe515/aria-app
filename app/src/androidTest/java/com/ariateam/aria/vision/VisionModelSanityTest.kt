package com.ariateam.aria.vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

@RunWith(AndroidJUnit4::class)
class VisionModelSanityTest {

    @Test
    fun defaultShippedAssetModel_hasExactHashAndValidTensors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            assertTrue("Default model asset must exist", store.hasDefaultAssetModel())
            val state = store.provisionDefaultAssetModelSync()
            assertTrue("State must be Ready, but was $state", state is VisionModelState.Ready)
            val ready = state as VisionModelState.Ready
            assertEquals(VisionContract.DEFAULT_ASSET_MODEL_NAME, ready.metadata.displayName)
            assertEquals(VisionContract.DEFAULT_ASSET_MODEL_BYTES, ready.metadata.sizeBytes)
            assertEquals(VisionContract.DEFAULT_ASSET_MODEL_SHA256, ready.metadata.sha256)
            assertTrue(store.segmenter.isReady)
            assertTrue(store.activeModelFile.isFile)
        } finally {
            store.close()
        }
    }

    @Test
    fun defaultProvisioning_enforcesHashAndSizeAndRollback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            store.clearModelSync()
            assertEquals(VisionModelState.Missing, store.state.value)

            // 1. Corrupted bytes with wrong size/hash
            val fakeBytes = "corrupted-default-model-data".toByteArray(Charsets.UTF_8)
            val outcomeWrongSize = store.provisionDefaultAssetStreamSync(
                input = ByteArrayInputStream(fakeBytes),
                expectedBytes = VisionContract.DEFAULT_ASSET_MODEL_BYTES,
                expectedSha256 = VisionContract.DEFAULT_ASSET_MODEL_SHA256,
            )
            assertTrue("Wrong size must produce Error", outcomeWrongSize is VisionModelState.Error)
            assertEquals(
                "default_model_size_mismatch",
                (outcomeWrongSize as VisionModelState.Error).detail,
            )
            assertFalse(store.segmenter.isReady)
            assertFalse(store.activeModelFile.exists())

            // 2. Corrupted bytes matching declared size but wrong hash
            val wrongHashOutcome = store.provisionDefaultAssetStreamSync(
                input = ByteArrayInputStream(fakeBytes),
                expectedBytes = fakeBytes.size.toLong(),
                expectedSha256 = VisionContract.DEFAULT_ASSET_MODEL_SHA256, // mismatch
            )
            assertTrue("Wrong hash must produce Error", wrongHashOutcome is VisionModelState.Error)
            assertEquals(
                "default_model_sha256_mismatch",
                (wrongHashOutcome as VisionModelState.Error).detail,
            )
            assertFalse(store.segmenter.isReady)
            assertFalse(store.activeModelFile.exists())
        } finally {
            store.close()
        }
    }

    @Test
    fun userClearAndRestoreDefault_preservesIntentionalMissingUntilRestored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            // Provision default
            val provisioned = store.provisionDefaultAssetModelSync()
            assertTrue(provisioned is VisionModelState.Ready)

            // User clears model
            val cleared = store.clearModelSync()
            assertEquals(VisionModelState.Missing, cleared)
            assertFalse(store.segmenter.isReady)

            // Re-instantiate store with restoreOnInit=false: must remain Missing because user cleared it
            val restoredStore = VisionModelStore(context, restoreOnInit = false)
            try {
                val stateAfterReboot = restoredStore.restorePersistedModelSync()
                assertEquals("User cleared choice must remain Missing across restarts", VisionModelState.Missing, stateAfterReboot)

                // Now restore default model explicitly
                val reRestored = restoredStore.provisionDefaultAssetModelSync()
                assertTrue("Restoring default explicitly must return Ready", reRestored is VisionModelState.Ready)
                assertTrue(restoredStore.segmenter.isReady)
            } finally {
                restoredStore.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun defaultShippedModel_rawProbabilityTensor_finiteSoftmaxSumAndDeterministicParity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            val state = store.provisionDefaultAssetModelSync()
            assertTrue(state is VisionModelState.Ready)

            val inputBuffer = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES)
                .order(ByteOrder.nativeOrder())
            val floatBuf = inputBuffer.asFloatBuffer()

            // Generate deterministic fixed seeded synthetic input [0.0f, 1.0f]
            val rng = Random(42L)
            floatBuf.rewind()
            for (i in 0 until VisionContract.INPUT_FLOATS) {
                floatBuf.put(rng.nextFloat())
            }

            val rawOutputRun1 = FloatArray(VisionContract.OUTPUT_FLOATS)
            val durationNanos1 = store.segmenter.runDirectRawProbabilities(inputBuffer, rawOutputRun1)
            assertTrue("Invoke duration > 0 ($durationNanos1 ns)", durationNanos1 > 0L)

            // 1. Assert every probability float is finite, non-negative, and <= 1.0 (with slight fp16 tolerance)
            val numClasses = VisionContract.MODEL_CLASSES
            val numPixels = VisionContract.INPUT_PIXELS
            var offset = 0
            for (p in 0 until numPixels) {
                var sum = 0.0f
                for (c in 0 until numClasses) {
                    val prob = rawOutputRun1[offset + c]
                    assertFalse("Probability must not be NaN at pixel $p class $c", prob.isNaN())
                    assertFalse("Probability must not be Infinite at pixel $p class $c", prob.isInfinite())
                    assertTrue("Probability $prob must be >= -1e-4f", prob >= -1e-4f)
                    assertTrue("Probability $prob must be <= 1.0001f", prob <= 1.0001f)
                    sum += prob
                }
                assertTrue(
                    "Softmax sum across $numClasses classes at pixel $p must be ~1.0, was $sum",
                    sum in 0.98f..1.02f,
                )
                offset += numClasses
            }

            // 2. Repeat inference with identical input to verify deterministic parity
            val rawOutputRun2 = FloatArray(VisionContract.OUTPUT_FLOATS)
            inputBuffer.rewind()
            val durationNanos2 = store.segmenter.runDirectRawProbabilities(inputBuffer, rawOutputRun2)
            assertTrue(durationNanos2 > 0L)

            for (i in 0 until VisionContract.OUTPUT_FLOATS) {
                val diff = kotlin.math.abs(rawOutputRun1[i] - rawOutputRun2[i])
                assertTrue("Non-deterministic output detected at index $i: diff=$diff", diff < 1e-6f)
            }

            // 3. Test end-to-end decode on this validated tensor
            val outMask = ByteArray(VisionContract.INPUT_PIXELS)
            val outArgb = IntArray(VisionContract.INPUT_PIXELS)
            val summary = LiteRtVisionSegmenter.decodeArgmaxAndMask(
                probabilities = rawOutputRun1,
                outClassMask = outMask,
                outOverlayArgb = outArgb,
            )
            var totalCount = 0
            for (count in summary.classPixelCounts) totalCount += count
            assertEquals(VisionContract.INPUT_PIXELS, totalCount)
            assertTrue(summary.roiLeftWalkableRatio in 0.0f..1.0f)
            assertTrue(summary.roiCenterWalkableRatio in 0.0f..1.0f)
            assertTrue(summary.roiRightWalkableRatio in 0.0f..1.0f)
        } finally {
            store.close()
        }
    }

    @Test
    fun productionSegmenter_gpuDefaultPrecisionAndCrossThreadLifecycleAndPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VisionModelStore(context, restoreOnInit = false)
        try {
            store.clearModelSync()
            val ready = store.provisionOptimizedAssetModelSync()
            assertTrue("Optimized model must be ready", ready is VisionModelState.Ready)

            val isGpuSupported = org.tensorflow.lite.gpu.CompatibilityList().use { it.isDelegateSupportedOnThisDevice }
            if (isGpuSupported) {
                assertEquals(VisionBackendType.GPU, store.segmenter.configuredBackend)
                assertTrue(
                    "Backend description must explicitly state FP32, was ${store.segmenter.activeBackendDescription}",
                    store.segmenter.activeBackendDescription.contains("LiteRT GPU FP32"),
                )
            }

            // Cross-thread execution
            val inputBuf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
            val fb = inputBuf.asFloatBuffer()
            val rng = Random(42L)
            for (i in 0 until VisionContract.INPUT_FLOATS) fb.put(rng.nextFloat())

            var thread1Error: Throwable? = null
            var crossThreadOutput: VisionInferenceOutput? = null
            val thread1 = Thread {
                try {
                    crossThreadOutput = store.segmenter.runIfLoaded(inputBuf, produceOverlayBitmap = true)
                } catch (t: Throwable) {
                    thread1Error = t
                }
            }
            thread1.start()
            thread1.join(5000L)
            assertFalse("Thread 1 must complete within 5s", thread1.isAlive)
            thread1Error?.let { throw AssertionError("Thread 1 failed with exception", it) }
            assertNotNull("Cross-thread inference must produce output", crossThreadOutput)
            assertTrue(crossThreadOutput!!.invokeDurationNanos > 0L)
            assertNotNull(crossThreadOutput!!.overlayBitmap)

            // Reconfigure backend across threads
            var thread2Error: Throwable? = null
            val thread2 = Thread {
                try {
                    store.setPreferredBackend(VisionBackendType.CPU, 2)
                } catch (t: Throwable) {
                    thread2Error = t
                }
            }
            thread2.start()
            thread2.join(5000L)
            assertFalse("Thread 2 must complete within 5s", thread2.isAlive)
            thread2Error?.let { throw AssertionError("Thread 2 failed with exception", it) }
            assertEquals(VisionBackendType.CPU, store.segmenter.configuredBackend)
            assertEquals(2, store.segmenter.configuredCpuThreads)
            assertTrue(
                "CPU backend description must state FP32, was ${store.segmenter.activeBackendDescription}",
                store.segmenter.activeBackendDescription.contains("LiteRT CPU FP32"),
            )

            // Re-run inference on CPU 2T
            val cpuOutput = store.segmenter.runIfLoaded(inputBuf, produceOverlayBitmap = false)
            assertNotNull(cpuOutput)
            assertTrue(cpuOutput!!.invokeDurationNanos > 0L)

            // If GPU is supported, verify raw numerical parity between production GPU and CPU across 3 synthetic fixtures
            if (isGpuSupported) {
                // 1. Prepare 4 synthetic test fixtures (blank_zeros, constant_0.5, gradient_ramp, seeded_random)
                class Fixture(val name: String, val generate: () -> ByteBuffer)
                val fixtures = listOf(
                    Fixture("blank_zeros") {
                        ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                    },
                    Fixture("constant_0.5") {
                        val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                        val bfb = buf.asFloatBuffer()
                        for (i in 0 until VisionContract.INPUT_FLOATS) bfb.put(0.5f)
                        buf.rewind()
                        buf
                    },
                    Fixture("gradient_ramp") {
                        val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                        val bfb = buf.asFloatBuffer()
                        for (y in 0 until VisionContract.MODEL_HEIGHT) {
                            val yNorm = y.toFloat() / VisionContract.MODEL_HEIGHT
                            for (x in 0 until VisionContract.MODEL_WIDTH) {
                                val xNorm = x.toFloat() / VisionContract.MODEL_WIDTH
                                for (c in 0 until VisionContract.MODEL_CHANNELS) {
                                    val v = ((xNorm + yNorm + c * 0.25f) / 2.25f).coerceIn(0f, 1f)
                                    bfb.put(v)
                                }
                            }
                        }
                        buf.rewind()
                        buf
                    },
                    Fixture("seeded_random") {
                        val buf = ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder())
                        val bfb = buf.asFloatBuffer()
                        val r = Random(42L)
                        for (i in 0 until VisionContract.INPUT_FLOATS) bfb.put(r.nextFloat())
                        buf.rewind()
                        buf
                    },
                )

                // Run on GPU FP32
                store.setPreferredBackend(VisionBackendType.GPU, VisionContract.DEFAULT_CPU_THREADS)
                val gpuFixtureOutputs = fixtures.map { fixture ->
                    val out = FloatArray(VisionContract.OUTPUT_FLOATS)
                    val inBuf = fixture.generate()
                    store.segmenter.runDirectRawProbabilities(inBuf, out)
                    out
                }

                // Run on CPU 4T
                store.setPreferredBackend(VisionBackendType.CPU, 4)
                val cpuFixtureOutputs = fixtures.map { fixture ->
                    val out = FloatArray(VisionContract.OUTPUT_FLOATS)
                    val inBuf = fixture.generate()
                    store.segmenter.runDirectRawProbabilities(inBuf, out)
                    out
                }

                // Verify numeric parity on each fixture
                for (idx in fixtures.indices) {
                    val fixture = fixtures[idx]
                    val gpuFloats = gpuFixtureOutputs[idx]
                    val cpuFloats = cpuFixtureOutputs[idx]

                    var maxDiff = 0f
                    var sumDiff = 0.0
                    var matchingArgmax = 0
                    var matchingWalkable = 0

                    val numPixels = VisionContract.MODEL_WIDTH * VisionContract.MODEL_HEIGHT
                    val numClasses = VisionContract.MODEL_CLASSES
                    var offset = 0
                    for (p in 0 until numPixels) {
                        var bestCpuClass = 0
                        var bestCpuProb = cpuFloats[offset]
                        var bestGpuClass = 0
                        var bestGpuProb = gpuFloats[offset]
                        var cpuSum = 0f
                        var gpuSum = 0f

                        for (c in 0 until numClasses) {
                            val cp = cpuFloats[offset + c]
                            val gp = gpuFloats[offset + c]
                            assertTrue("CPU float must be finite", cp.isFinite())
                            assertTrue("GPU float must be finite", gp.isFinite())
                            val diff = kotlin.math.abs(cp - gp)
                            if (diff > maxDiff) maxDiff = diff
                            sumDiff += diff
                            cpuSum += cp
                            gpuSum += gp

                            if (cp > bestCpuProb) {
                                bestCpuProb = cp
                                bestCpuClass = c
                            }
                            if (gp > bestGpuProb) {
                                bestGpuProb = gp
                                bestGpuClass = c
                            }
                        }

                        assertEquals("CPU softmax sum near 1.0", 1.0f, cpuSum, 0.05f)
                        assertEquals("GPU softmax sum near 1.0", 1.0f, gpuSum, 0.05f)

                        if (bestCpuClass == bestGpuClass) matchingArgmax++
                        val cpuWalkable = VisionContract.CLASS_WALKABLE[bestCpuClass]
                        val gpuWalkable = VisionContract.CLASS_WALKABLE[bestGpuClass]
                        if (cpuWalkable == gpuWalkable) matchingWalkable++

                        offset += numClasses
                    }

                    val mae = sumDiff / VisionContract.OUTPUT_FLOATS
                    val argmaxRatio = matchingArgmax.toDouble() / numPixels
                    val walkableRatio = matchingWalkable.toDouble() / numPixels
                    android.util.Log.i("AriaVisionBackend", "Fixture ${fixture.name}: matchingArgmax=$matchingArgmax/$numPixels (${argmaxRatio * 100}%), matchingWalkable=$matchingWalkable/$numPixels (${walkableRatio * 100}%), maxDiff=$maxDiff, MAE=$mae")

                    assertTrue("Fixture ${fixture.name}: maxDiff must be < 0.001, was $maxDiff", maxDiff < 0.001f)
                    assertTrue("Fixture ${fixture.name}: MAE must be < 1e-4, was $mae", mae < 1e-4)
                    assertTrue("Fixture ${fixture.name}: argmax parity must be >= 99.99%, was $argmaxRatio", argmaxRatio >= 0.9999)
                    assertTrue("Fixture ${fixture.name}: walkable parity must be >= 99.99%, was $walkableRatio", walkableRatio >= 0.9999)
                }
            }

            // Restore GPU backend
            store.setPreferredBackend(VisionBackendType.GPU, VisionContract.DEFAULT_CPU_THREADS)
            if (isGpuSupported) {
                assertEquals(VisionBackendType.GPU, store.segmenter.configuredBackend)
                assertTrue(store.segmenter.activeBackendDescription.contains("LiteRT GPU FP32"))
            }

            // Test persistence across store reload
            store.close()

            val reloadedStore = VisionModelStore(context, restoreOnInit = false)
            try {
                val restoredState = reloadedStore.restorePersistedModelSync()
                assertTrue(restoredState is VisionModelState.Ready)
                if (isGpuSupported) {
                    assertEquals(VisionBackendType.GPU, reloadedStore.getPreferredBackend())
                    assertEquals(VisionBackendType.GPU, reloadedStore.segmenter.configuredBackend)
                    assertTrue(
                        "Reloaded store must retain GPU FP32, was ${reloadedStore.segmenter.activeBackendDescription}",
                        reloadedStore.segmenter.activeBackendDescription.contains("LiteRT GPU FP32"),
                    )
                }
                val reloadedOutput = reloadedStore.segmenter.runIfLoaded(inputBuf, produceOverlayBitmap = false)
                assertNotNull(reloadedOutput)
                assertTrue(reloadedOutput!!.invokeDurationNanos > 0L)
            } finally {
                reloadedStore.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun motionTracker_hardwareSensorSmokeTest_deliversFiniteSamplesAndCleansUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracker = com.ariateam.aria.hazard.MotionTracker(context)
        try {
            val session1StartNanos = SystemClock.elapsedRealtimeNanos()
            tracker.start(android.view.Surface.ROTATION_0)
            assertTrue("MotionTracker must be running after start", tracker.isRunning)
            assertTrue("Pixel 3 hardware sensors (BMI160 accel + gyro) must be available", tracker.sensorsAvailable)

            val startMs = android.os.SystemClock.elapsedRealtime()
            var gotSamples = false
            while (android.os.SystemClock.elapsedRealtime() - startMs < 5000L) {
                if (tracker.engine.accelSamplesReceived >= 5 && tracker.engine.gyroSamplesReceived >= 5) {
                    gotSamples = true
                    break
                }
                Thread.sleep(50L)
            }
            assertTrue("Expected at least 5 accel and 5 gyro samples within 5s", gotSamples)

            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val lastAccel = tracker.engine.lastAccelNanos
            val lastGyro = tracker.engine.lastGyroNanos

            assertTrue("lastAccelNanos ($lastAccel) must be >= session start ($session1StartNanos)", lastAccel >= session1StartNanos)
            assertTrue("lastAccelNanos ($lastAccel) must be <= elapsedRealtimeNanos ($nowNanos)", lastAccel <= nowNanos)
            assertTrue("lastGyroNanos ($lastGyro) must be >= session start ($session1StartNanos)", lastGyro >= session1StartNanos)
            assertTrue("lastGyroNanos ($lastGyro) must be <= elapsedRealtimeNanos ($nowNanos)", lastGyro <= nowNanos)

            val accelFreshnessMs = (nowNanos - lastAccel) / 1_000_000L
            val gyroFreshnessMs = (nowNanos - lastGyro) / 1_000_000L
            assertTrue("Accel freshness must be <= 200ms, was ${accelFreshnessMs}ms", accelFreshnessMs <= 200L)
            assertTrue("Gyro freshness must be <= 200ms, was ${gyroFreshnessMs}ms", gyroFreshnessMs <= 200L)

            val state = tracker.stateAt(nowNanos)
            assertTrue("Pitch must be finite", state.pitchDegrees.isFinite())
            assertTrue("Roll must be finite", state.rollDegrees.isFinite())
            assertTrue("Yaw rate must be finite", state.yawRateDegPerSec.isFinite())
            assertTrue("Linear accel mag must be finite", state.linearAccelMag.isFinite())
            assertTrue("Confidence in [0, 1]", state.confidence in 0f..1f)

            // Stop tracker: assert counters reset to 0, running false, sensorsAvailable false
            tracker.stop()
            assertFalse("MotionTracker must not be running after stop", tracker.isRunning)
            assertFalse("sensorsAvailable must be false after stop", tracker.sensorsAvailable)
            assertEquals("Accel counter must be reset to 0 after stop", 0, tracker.engine.accelSamplesReceived)
            assertEquals("Gyro counter must be reset to 0 after stop", 0, tracker.engine.gyroSamplesReceived)

            val stoppedState = tracker.stateAt(SystemClock.elapsedRealtimeNanos())
            assertEquals(
                com.ariateam.aria.hazard.MotionDegradedReason.SENSORS_UNAVAILABLE,
                stoppedState.degradedReason,
            )

            // Restart tracker: verify new generation receives new samples
            val session2StartNanos = SystemClock.elapsedRealtimeNanos()
            tracker.start(android.view.Surface.ROTATION_0)
            assertTrue("MotionTracker must restart cleanly", tracker.isRunning)
            assertTrue("sensorsAvailable must be true after restart", tracker.sensorsAvailable)

            val restartStartMs = android.os.SystemClock.elapsedRealtime()
            var restartGotSamples = false
            while (android.os.SystemClock.elapsedRealtime() - restartStartMs < 5000L) {
                if (tracker.engine.accelSamplesReceived >= 5 && tracker.engine.gyroSamplesReceived >= 5) {
                    restartGotSamples = true
                    break
                }
                Thread.sleep(50L)
            }
            assertTrue("Expected samples after restart within 5s", restartGotSamples)
            assertTrue(tracker.engine.lastAccelNanos >= session2StartNanos)
            assertTrue(tracker.engine.lastGyroNanos >= session2StartNanos)
        } finally {
            tracker.close()
        }
    }

    @Test
    fun cameraGeometry_actualPixel3Metadata_derivesOrientationAwareHfovAcrossAllRotations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.first()

        val chars = cm.getCameraCharacteristics(cameraId)
        val fArray = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = fArray?.firstOrNull() ?: 0f
        val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        assertNotNull("Pixel 3 back camera must have focal length", fArray)
        assertNotNull("Pixel 3 back camera must have physical sensor size", sensorPhysicalSize)
        assertTrue(focalLength > 0f)

        val intrinsics = com.ariateam.aria.hazard.CameraIntrinsics(
            focalLengthMm = focalLength,
            sensorWidthMm = sensorPhysicalSize!!.width,
            sensorHeightMm = sensorPhysicalSize.height,
            activeArrayWidth = sensorActiveArray?.width() ?: 0,
            activeArrayHeight = sensorActiveArray?.height() ?: 0,
        )
        assertTrue(intrinsics.isValid)

        // Portrait 90 deg: buffer 640x480 -> upright 480x640 -> crop 480x270 (full upright width 480)
        // Upright horizontal axis maps to sensor height (~4.29mm on Pixel 3)
        val portraitHfov = com.ariateam.aria.hazard.CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            cropWidth = 480,
            uprightWidth = 480,
        )
        assertNotNull(portraitHfov)
        assertEquals("Pixel 3 portrait HFOV must be ~51.5 deg", 51.5f, portraitHfov!!, 1.5f)

        // Reverse portrait 270 deg: also uses sensor height
        val revPortraitHfov = com.ariateam.aria.hazard.CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 270,
            cropWidth = 480,
            uprightWidth = 480,
        )
        assertNotNull(revPortraitHfov)
        assertEquals("Pixel 3 reverse portrait HFOV must match 90 deg", portraitHfov, revPortraitHfov!!, 0.01f)

        // Landscape 0 deg: buffer 640x480 -> upright 640x480 -> crop 640x360 (full upright width 640)
        // Upright horizontal axis maps to sensor width (~5.76mm on Pixel 3)
        val landscapeHfov = com.ariateam.aria.hazard.CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 0,
            cropWidth = 640,
            uprightWidth = 640,
        )
        assertNotNull(landscapeHfov)
        assertEquals("Pixel 3 landscape HFOV must be ~65.9 deg", 65.9f, landscapeHfov!!, 1.5f)
        assertTrue("Portrait HFOV must be strictly narrower than landscape HFOV", portraitHfov < landscapeHfov)

        // Reverse landscape 180 deg: also uses sensor width
        val revLandscapeHfov = com.ariateam.aria.hazard.CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 180,
            cropWidth = 640,
            uprightWidth = 640,
        )
        assertNotNull(revLandscapeHfov)
        assertEquals("Pixel 3 reverse landscape HFOV must match 0 deg", landscapeHfov, revLandscapeHfov!!, 0.01f)
    }
}
