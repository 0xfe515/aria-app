package com.ariateam.aria.hazard

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraGeometryTest {

    // Pixel 3 rear camera parameters (Sony IMX363: 1/2.55", 12.2MP, 4032x3024 active array)
    private val pixel3Intrinsics = CameraIntrinsics(
        focalLengthMm = 4.44f,
        sensorWidthMm = 5.76f,
        sensorHeightMm = 4.29f,
        activeArrayWidth = 4032,
        activeArrayHeight = 3024,
    )

    @Test
    fun pixel3_640x480_portrait_derivesNarrowerUprightHfov() {
        // Buffer 640x480 at 90 deg rotation -> upright is 480x640.
        // Fused preprocessor 512:288 crop yields width=480, height=270 (full width, bottom-cropped height).
        // Since rotation is 90 deg, upright horizontal axis maps to sensor HEIGHT (4.29mm).
        // HFOV = 2 * atan(4.29 / (2 * 4.44)) = ~51.54 deg.
        val hfov = CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = pixel3Intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            cropWidth = 480,
            uprightWidth = 480,
        )
        assertNotNull(hfov)
        assertEquals(51.54f, hfov!!, 0.5f)
        assertTrue("Portrait HFOV (~51.5 deg) must be strictly narrower than landscape (~66 deg)", hfov < 60f)
    }

    @Test
    fun pixel3_640x480_landscape_derivesSensorWidthHfov() {
        // Buffer 640x480 at 0 deg rotation -> upright is 640x480.
        // Fused preprocessor 512:288 crop yields width=640, height=360 (full width, bottom-cropped height).
        // Since rotation is 0 deg, upright horizontal axis maps to sensor WIDTH (5.76mm).
        // HFOV = 2 * atan(5.76 / (2 * 4.44)) = ~65.95 deg.
        val hfov = CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = pixel3Intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 0,
            cropWidth = 640,
            uprightWidth = 640,
        )
        assertNotNull(hfov)
        assertEquals(65.95f, hfov!!, 0.5f)
    }

    @Test
    fun cropScaleNarrowing_scalesHorizontalFovCorrectly() {
        // If preprocessor horizontally cropped from 480 down to 360:
        val fullWidthHfov = CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = pixel3Intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            cropWidth = 480,
            uprightWidth = 480,
        )!!

        val croppedHfov = CameraGeometry.computeEffectiveUprightHfovDegrees(
            intrinsics = pixel3Intrinsics,
            bufferWidth = 640,
            bufferHeight = 480,
            rotationDegrees = 90,
            cropWidth = 360,
            uprightWidth = 480,
        )!!

        assertTrue("Horizontally cropped FOV must be narrower than full width", croppedHfov < fullWidthHfov)
        // 4.29 * (360 / 480) = 3.2175mm -> 2 * atan(3.2175 / (2 * 4.44)) = ~39.8 deg
        assertEquals(39.8f, croppedHfov, 0.5f)
    }

    @Test
    fun invalidOrMissingMetadata_returnsNullUnknown() {
        // Missing intrinsics
        assertNull(
            CameraGeometry.computeEffectiveUprightHfovDegrees(
                intrinsics = null,
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = 90,
                cropWidth = 480,
                uprightWidth = 480,
            )
        )

        // Non-positive focal length
        assertNull(
            CameraGeometry.computeEffectiveUprightHfovDegrees(
                intrinsics = pixel3Intrinsics.copy(focalLengthMm = 0f),
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = 90,
                cropWidth = 480,
                uprightWidth = 480,
            )
        )

        // Non-finite focal length
        assertNull(
            CameraGeometry.computeEffectiveUprightHfovDegrees(
                intrinsics = pixel3Intrinsics.copy(focalLengthMm = Float.NaN),
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = 90,
                cropWidth = 480,
                uprightWidth = 480,
            )
        )

        // Non-positive sensor dimensions
        assertNull(
            CameraGeometry.computeEffectiveUprightHfovDegrees(
                intrinsics = pixel3Intrinsics.copy(sensorWidthMm = -1f),
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = 90,
                cropWidth = 480,
                uprightWidth = 480,
            )
        )

        // Invalid rotation
        assertNull(
            CameraGeometry.computeEffectiveUprightHfovDegrees(
                intrinsics = pixel3Intrinsics,
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = 45,
                cropWidth = 480,
                uprightWidth = 480,
            )
        )

        // Invalid crop geometry (crop > upright)
        assertNull(
            CameraGeometry.computeEffectiveUprightHfovDegrees(
                intrinsics = pixel3Intrinsics,
                bufferWidth = 640,
                bufferHeight = 480,
                rotationDegrees = 90,
                cropWidth = 500,
                uprightWidth = 480,
            )
        )
    }

    @Test
    fun motionFusionEngine_whenFovIsUnknown_marksStateDegradedAndRejectsCalibration() {
        val engine = MotionFusionEngine(defaultHfovDegrees = null)
        val initial = engine.stateAt(1_000_000_000L)
        assertEquals(MotionDegradedReason.CAMERA_FOV_UNKNOWN, initial.degradedReason)
        assertEquals(0f, initial.confidence, 1e-4f)

        // Feed valid sensor samples
        var t = 1_000_000_000L
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }

        // Calibration must fail because camera geometry is unknown
        val calibOk = engine.calibrateForward(t)
        assertFalse("Calibration must be rejected when FOV is unknown", calibOk)

        val uncalibState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.CAMERA_FOV_UNKNOWN, uncalibState.degradedReason)
        assertFalse(uncalibState.isReliable)

        // Now update camera geometry with Pixel 3 portrait FOV (~51.5 deg)
        engine.updateCameraGeometry(51.54f)
        assertEquals(51.54f, engine.effectiveHfovDegrees!!, 0.01f)

        // State moves to CALIBRATION_PENDING
        val pendingState = engine.stateAt(t)
        assertEquals(MotionDegradedReason.CALIBRATION_PENDING, pendingState.degradedReason)

        // Now calibration succeeds
        val calibSuccess = engine.calibrateForward(t)
        assertTrue("Calibration must succeed once geometry is established", calibSuccess)
        val readyState = engine.stateAt(t)
        assertTrue(readyState.isReliable)
        assertNull(readyState.degradedReason)
    }

    @Test
    fun geometryInvalidation_fencesHistoricalCalibration() {
        // 1. Start with initial valid geometry and calibrate
        val engine = MotionFusionEngine(defaultHfovDegrees = 51.5f)
        var t = 1_000_000_000L
        for (i in 0 until 30) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        assertTrue(engine.calibrateForward(t))
        val calibratedState = engine.stateAt(t)
        assertTrue(calibratedState.isReliable)
        assertTrue(calibratedState.isCalibrated)

        // 2. Updating with the SAME geometry should preserve calibration
        engine.updateCameraGeometry(51.5f)
        val sameGeomState = engine.stateAt(t)
        assertTrue("Updating with same geometry must preserve calibration", sameGeomState.isCalibrated)
        assertTrue(sameGeomState.isReliable)

        // 3. Updating to new geometry (e.g. landscape 65.5f) must invalidate calibration and fence historical snapshots
        engine.updateCameraGeometry(65.5f)
        // Querying at or before current capture MUST NOT return the old calibrated snapshot
        val queryAfterChange = engine.stateAt(t)
        assertFalse("Old calibrated snapshot must be fenced after geometry change", queryAfterChange.isCalibrated)
        assertFalse(queryAfterChange.isReliable)
        assertEquals(MotionDegradedReason.CALIBRATION_PENDING, queryAfterChange.degradedReason)

        // 4. Updating to unknown/null geometry
        engine.updateCameraGeometry(null)
        val queryUnknown = engine.stateAt(t)
        assertFalse(queryUnknown.isCalibrated)
        assertFalse(queryUnknown.isReliable)
        assertEquals(MotionDegradedReason.CAMERA_FOV_UNKNOWN, queryUnknown.degradedReason)

        // 5. Must require explicit new calibration after restoring geometry
        engine.updateCameraGeometry(65.5f)
        t += 10_000_000L
        for (i in 0 until 10) {
            engine.onAccelerometerSample(t, 0f, 9.81f, 0f)
            engine.onGyroscopeSample(t, 0f, 0f, 0f)
            t += 10_000_000L
        }
        assertTrue(engine.calibrateForward(t))
        val reCalibrated = engine.stateAt(t)
        assertTrue(reCalibrated.isCalibrated)
        assertTrue(reCalibrated.isReliable)
    }
}
