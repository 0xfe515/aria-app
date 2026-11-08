package com.ariateam.aria.distance

import com.ariateam.aria.hazard.CameraGeometry
import com.ariateam.aria.hazard.CameraIntrinsics
import com.ariateam.aria.hazard.EffectiveFov
import com.ariateam.aria.hazard.FullUprightCameraModel
import com.ariateam.aria.hazard.Vector3D
import com.ariateam.aria.vision.od.DetectedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GroundContactDistanceEstimatorTest {

    private val pixel3Intrinsics = CameraIntrinsics(
        focalLengthMm = 4.44f,
        sensorWidthMm = 5.76f,
        sensorHeightMm = 4.29f,
        activeArrayWidth = 4032,
        activeArrayHeight = 3024,
    )

    private val standardPortraitFov = EffectiveFov(
        hfovDegrees = 51.54f,
        vfovDegrees = 38.65f,
    )

    private val pixel3PortraitFullCameraModel = FullUprightCameraModel(
        focalLengthMm = 4.44f,
        uprightSensorWidthMm = 4.29f,
        uprightSensorHeightMm = 5.76f,
        fullHfovDegrees = 51.54f,
        fullVfovDegrees = 65.95f,
    )

    private val shoulderProfile170 = BodyMountProfile(
        statureCm = 170f,
        position = BodyMountPosition.SHOULDER,
    )

    private val chestProfile170 = BodyMountProfile(
        statureCm = 170f,
        position = BodyMountPosition.CHEST,
    )

    @Test
    fun fixedPose_uprightVerticalPhone_estimatesExactFloorDistance() {
        // Upright vertical phone: World UP is -Y (0, -1, 0) in camera coordinates.
        val upVector = Vector3D(0f, -1f, 0f)
        val cameraHeight = shoulderProfile170.estimatedCameraHeightMeters!! // ~1.366m

        // Target pixel in lower screen (uNorm = 0.5, vNorm = 0.75, below center)
        val ray = CameraGeometry.computeCameraRay(
            uNorm = 0.5f,
            vNorm = 0.75f,
            hfovDegrees = standardPortraitFov.hfovDegrees,
            vfovDegrees = standardPortraitFov.vfovDegrees,
        )

        // Ray must point down in camera space (y > 0)
        assertTrue("Ray must point downward (+Y)", ray.y > 0f)
        assertEquals("Horizontal ray component must be zero on center column", 0f, ray.x, 1e-4f)

        val intersection = CameraGeometry.intersectGroundPlane(
            ray = ray,
            cameraHeightMeters = cameraHeight,
            upVector = upVector,
        )
        assertNotNull(intersection)
        assertTrue(intersection!!.forwardDistanceMeters > 0f)
        assertEquals(0f, intersection.lateralDistanceMeters, 1e-3f)

        // Theoretical scalar check:
        // tan(VFOV / 2) = tan(38.65 / 2) = 0.35069
        // y / z = (0.75 - 0.5) * 2 * 0.35069 = 0.175345
        // Forward distance = h / (y / z) = 1.3660228 / 0.175345 = ~7.79m
        assertEquals(7.79f, intersection.forwardDistanceMeters, 0.2f)
    }

    @Test
    fun fixedPose_thirtyDegreeDownwardTilt_estimatesDistanceAtCenterPixel() {
        // When tilted 30 deg forward/down, the back camera optical axis (0, 0, 1) points 30 deg below horizon.
        // Android +Z (front screen) tilts upward towards sky, so world UP has negative Z in camera space:
        // upVector = (0, -cos(30 deg), -sin(30 deg))
        val rad30 = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(rad30), -sin(rad30))
        val cameraHeight = shoulderProfile170.estimatedCameraHeightMeters!! // ~1.3660228m

        // Center pixel (uNorm = 0.5, vNorm = 0.5) has optical axis ray (0, 0, 1)
        val centerRay = CameraGeometry.computeCameraRay(
            uNorm = 0.5f,
            vNorm = 0.5f,
            hfovDegrees = standardPortraitFov.hfovDegrees,
            vfovDegrees = standardPortraitFov.vfovDegrees,
        )
        assertEquals(0f, centerRay.x, 1e-4f)
        assertEquals(0f, centerRay.y, 1e-4f)
        assertEquals(1f, centerRay.z, 1e-4f)

        val intersection = CameraGeometry.intersectGroundPlane(
            ray = centerRay,
            cameraHeightMeters = cameraHeight,
            upVector = upVector,
        )
        assertNotNull(intersection)

        // Exact geometric formula for optical axis at angle theta: d = h / tan(theta)
        // d = 1.3660228 / tan(30 deg) = 1.3660228 / 0.577350269 = 2.3660m
        val expectedDistance = (cameraHeight / kotlin.math.tan(rad30))
        assertEquals(2.366f, expectedDistance, 0.01f)
        assertEquals(expectedDistance, intersection!!.forwardDistanceMeters, 0.05f)
        assertEquals(0f, intersection.lateralDistanceMeters, 1e-3f)
    }

    @Test
    fun angleSign_tiltedUpward_rejectsAboveHorizon() {
        // Tilted 30 deg upward towards ceiling/sky:
        // upVector in camera coordinates has positive Z:
        val rad30 = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(rad30), sin(rad30))
        val cameraHeight = shoulderProfile170.estimatedCameraHeightMeters!!

        val centerRay = CameraGeometry.computeCameraRay(
            uNorm = 0.5f,
            vNorm = 0.5f,
            hfovDegrees = standardPortraitFov.hfovDegrees,
            vfovDegrees = standardPortraitFov.vfovDegrees,
        )

        val intersection = CameraGeometry.intersectGroundPlane(
            ray = centerRay,
            cameraHeightMeters = cameraHeight,
            upVector = upVector,
        )
        assertNull("Ray pointing above horizon must return null intersection", intersection)
    }

    @Test
    fun heightScale_scalesDistanceStrictlyProportionalToMountHeight() {
        val rad30 = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(rad30), -sin(rad30))

        val ray = CameraGeometry.computeCameraRay(
            uNorm = 0.5f,
            vNorm = 0.5f,
            hfovDegrees = standardPortraitFov.hfovDegrees,
            vfovDegrees = standardPortraitFov.vfovDegrees,
        )

        val shoulderHeight = shoulderProfile170.estimatedCameraHeightMeters!!
        val chestHeight = chestProfile170.estimatedCameraHeightMeters!!

        val shoulderIntersection = CameraGeometry.intersectGroundPlane(ray, shoulderHeight, upVector)!!
        val chestIntersection = CameraGeometry.intersectGroundPlane(ray, chestHeight, upVector)!!

        val ratioActual = shoulderIntersection.forwardDistanceMeters / chestIntersection.forwardDistanceMeters
        val ratioExpected = BodyMountHeightModel.SHOULDER_RATIO / BodyMountHeightModel.CHEST_RATIO

        assertEquals(ratioExpected, ratioActual, 1e-4f)
        assertTrue(
            "Shoulder distance must be strictly greater than chest distance",
            shoulderIntersection.forwardDistanceMeters > chestIntersection.forwardDistanceMeters
        )
    }

    @Test
    fun legacyCrop_asymmetricBottomAlign_mappedCorrectlyWithoutDistortion() {
        // In LEGACY_CROP for portrait 480x640:
        // Crop dimensions are 480x270, bottom-aligned: cropTop = 640 - 270 = 370.
        // cropLeftNorm = 0, cropTopNorm = 370 / 640 = 0.578125, cropWidthNorm = 1.0, cropHeightNorm = 270 / 640 = 0.421875.
        // The optical center (fullVNorm = 0.5) is ABOVE the crop.
        // All pixels in this bottom crop have fullVNorm >= 0.578125, so rays point downward (y > 0) in upright camera coords!
        val topCropPixelRay = CameraGeometry.computeRayFromFullUprightNorm(
            fullUNorm = 0.5f,
            fullVNorm = 0.578125f,
            cameraModel = pixel3PortraitFullCameraModel,
        )
        assertNotNull(topCropPixelRay)
        assertTrue("Top row of bottom crop must still point downward (+Y)", topCropPixelRay!!.y > 0f)

        val bottomCropPixelRay = CameraGeometry.computeRayFromFullUprightNorm(
            fullUNorm = 0.5f,
            fullVNorm = 1.0f,
            cameraModel = pixel3PortraitFullCameraModel,
        )
        assertNotNull(bottomCropPixelRay)
        assertTrue("Bottom row of crop must point steeply downward", bottomCropPixelRay!!.y > topCropPixelRay.y)
    }

    @Test
    fun vectorSafety_rejectsNonFiniteAndZeroLength() {
        val nanVec = Vector3D(Float.NaN, 1f, 1f)
        assertFalse(nanVec.isFinite)
        assertNull("Non-finite vector normalization must return null", nanVec.normalize())

        val zeroVec = Vector3D(0f, 0f, 0f)
        assertNull("Zero length vector normalization must return null", zeroVec.normalize())

        val ray = Vector3D(0f, 0.5f, 1f).normalize()!!
        val badUp = Vector3D(0f, Float.NaN, 0f)
        assertNull(CameraGeometry.intersectGroundPlane(ray, 1.36f, badUp))
    }

    @Test
    fun strictCorridor_rejectsOutsideLateralObstacle() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Draw a vehicle (class 5) far to the right (x=450..470, well outside +/-0.45m walking corridor)
        for (y in 160..180) {
            for (x in 450..470) {
                mask[y * width + x] = 5.toByte()
            }
        }
        // Road (class 1) supporting the vehicle
        for (y in 181..184) {
            for (x in 450..470) {
                mask[y * width + x] = 1.toByte()
            }
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val estimator = GroundContactDistanceEstimator(corridorHalfWidthMeters = 0.45f)
        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
        )

        assertNull("Obstacle outside forward corridor must not produce distance in user's walking path", result.distanceMeters)
        assertEquals("전방 보행 경로 장애물 미감지", result.message)
    }

    @Test
    fun unsupportedFloatingObject_inUpperThird_rejected() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Draw a pole/sign (class 6) suspended high in the sky (y=30..60, < 35% height) with no ground support
        for (y in 30..60) {
            for (x in 250..260) {
                mask[y * width + x] = 6.toByte()
            }
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val estimator = GroundContactDistanceEstimator()
        val candidates = estimator.extractCandidates(mask, width, height)
        assertEquals(1, candidates.size)
        assertTrue("Object high in the air must be marked as floating unsupported", candidates[0].isFloatingUnsupported)

        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
        )
        assertNull(result.distanceMeters)
        assertEquals("지면 접점 미확인 (공중 객체)", result.message)
    }

    @Test
    fun edgeClippedMask_touchesBottomBoundary_rejected() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Draw a person mask whose bottom touches the very bottom row (height - 1 = 287)
        for (y in 260 until height) {
            for (x in 250..260) {
                mask[y * width + x] = 4.toByte()
            }
        }

        val estimator = GroundContactDistanceEstimator(minClusterPixels = 20)
        val candidates = estimator.extractCandidates(mask, width, height)

        assertEquals(1, candidates.size)
        assertTrue("Candidate touching frame bottom must be flagged as clipped", candidates[0].isBottomClipped)

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
        )

        assertNull("Frame-bottom clipped mask must not produce distance", result.distanceMeters)
        assertEquals("접점 화면 경계 잘림 (거리 추정 불가)", result.message)
    }

    @Test
    fun endToEnd_validObstacle_computesDistanceAndFormatsMessage() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Place a person (class 4) right in center
        for (y in 140..170) {
            for (x in 250..262) {
                mask[y * width + x] = 4.toByte()
            }
        }
        // Sidewalk (class 2) supporting the person
        for (y in 171..174) {
            for (x in 250..262) {
                mask[y * width + x] = 2.toByte()
            }
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val estimator = GroundContactDistanceEstimator()
        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
        )

        assertNotNull(result.distanceMeters)
        assertTrue(result.distanceMeters!! > 0f)
        assertTrue("Message must use '추정 거리'", result.message.startsWith("추정 거리"))
        assertTrue("Message must contain 'm'", result.message.endsWith("m"))
        assertEquals(1_000_000_000L, result.timestampNanos)
        assertNotNull(result.contactXNormalized)
        assertNotNull(result.contactYNormalized)
    }

    @Test
    fun identicalBboxInLowerScreen_withSupportingGround_estimatesDistance() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Pole/sign (class 6) located in lower screen (y=150..180, well below 35% height = 100)
        for (y in 150..180) {
            for (x in 250..262) {
                mask[y * width + x] = 6.toByte()
            }
        }
        // Supporting ground: sidewalk (class 2) immediately below the pole base (y=181..184)
        for (y in 181..184) {
            for (x in 250..262) {
                mask[y * width + x] = 2.toByte()
            }
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val estimator = GroundContactDistanceEstimator()
        val candidates = estimator.extractCandidates(mask, width, height)
        assertEquals(1, candidates.size)
        assertFalse("Obstacle with ground support must not be marked floating", candidates[0].isFloatingUnsupported)
        assertTrue("Ground support pixels must be detected", candidates[0].groundSupportPixels >= 3)

        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
        )

        assertNotNull("Supported obstacle must produce valid distance", result.distanceMeters)
        assertTrue(result.distanceMeters!! > 0f)
        assertTrue(result.message.startsWith("추정 거리"))
    }

    @Test
    fun identicalBboxInLowerScreen_withoutSupportingGround_rejectedAsFloating() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Identical bounding box: pole/sign (class 6) at y=150..180, x=250..262
        for (y in 150..180) {
            for (x in 250..262) {
                mask[y * width + x] = 6.toByte()
            }
        }
        // No supporting ground: rows 181..184 remain 0 (other/air/wall)

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val estimator = GroundContactDistanceEstimator()
        val candidates = estimator.extractCandidates(mask, width, height)
        assertEquals(1, candidates.size)
        assertTrue("Obstacle without ground support must be flagged as floating", candidates[0].isFloatingUnsupported)
        assertEquals(0, candidates[0].groundSupportPixels)

        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
        )

        assertNull("Unsupported obstacle must produce null distance", result.distanceMeters)
        assertEquals("지면 접점 미확인 (공중 객체)", result.message)
    }

    @Test
    fun unsupportedHangingSign_inLowerScreen_rejectedAsFloating() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Overhead sign hanging across the street in lower screen (y=140..170, x=240..272)
        for (y in 140..170) {
            for (x in 240..272) {
                mask[y * width + x] = 6.toByte() // pole_sign
            }
        }
        // Beneath the hanging sign is empty air (class 0) - not road/sidewalk/crosswalk

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val estimator = GroundContactDistanceEstimator()
        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
        )

        assertNull("Hanging sign without ground contact must not produce distance", result.distanceMeters)
        assertEquals("지면 접점 미확인 (공중 객체)", result.message)
    }

    @Test
    fun estimate_withAcceptedOd_usesMaskContactNotBboxBottom_andCalculatesMetricDistance() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Mask person from y=140 to y=170 at x=250..262 (contactY = 170)
        for (y in 140..170) {
            for (x in 250..262) {
                mask[y * width + x] = 4.toByte()
            }
        }
        // Supporting sidewalk
        for (y in 171..174) {
            for (x in 250..262) {
                mask[y * width + x] = 2.toByte()
            }
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        // Detection bbox bottom at y=175 (different from mask bottom 170)
        val bboxBottomNorm = 175f / height
        val detection = DetectedObject(
            classId = 0,
            label = "person",
            score = 0.88f,
            left = 245f / width,
            top = 135f / height,
            right = 268f / width,
            bottom = bboxBottomNorm,
        )

        val estimator = GroundContactDistanceEstimator()
        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = listOf(detection),
        )

        assertNotNull(result.distanceMeters)
        assertTrue(result.distanceMeters!! > 0f)
        assertTrue(result.message.startsWith("추정 거리"))

        // Assert observable contact coordinates come from the MASK, NOT the bounding box bottom
        val contactY = result.contactYNormalized
        assertNotNull(contactY)
        val expectedMaskYNorm = (170f + 0.5f) / height
        assertEquals("contactYNormalized must match mask contact", expectedMaskYNorm, contactY!!, 1e-3f)
        assertTrue(
            "contactYNormalized must differ observably from bbox bottom",
            abs(contactY - bboxBottomNorm) >= 0.01f
        )
    }

    @Test
    fun estimate_withAcceptedOd_nearestForwardCandidateSelected() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Person 1 (farther): y=140..170, x=250..260
        for (y in 140..170) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }
        for (y in 171..174) {
            for (x in 250..260) mask[y * width + x] = 2.toByte()
        }

        // Person 2 (closer): y=200..230, x=250..260
        for (y in 200..230) {
            for (x in 250..260) mask[y * width + x] = 4.toByte()
        }
        for (y in 231..234) {
            for (x in 250..260) mask[y * width + x] = 2.toByte()
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val det1 = DetectedObject(classId = 0, label = "person", score = 0.85f, left = 245f / width, top = 135f / height, right = 265f / width, bottom = 171f / height)
        val det2 = DetectedObject(classId = 0, label = "person", score = 0.90f, left = 245f / width, top = 195f / height, right = 265f / width, bottom = 231f / height)

        val estimator = GroundContactDistanceEstimator()
        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = listOf(det1, det2),
        )

        assertNotNull(result.distanceMeters)
        // Person 2 is lower in frame, thus closer
        val expectedCloserYNorm = (230f + 0.5f) / height
        assertEquals("Nearest forward obstacle must be selected", expectedCloserYNorm, result.contactYNormalized!!, 1e-3f)
    }

    @Test
    fun estimate_withAcceptedOd_outsideLateralCorridor_rejected() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        // Vehicle (class 5) placed at far right edge (x=450..470)
        for (y in 160..180) {
            for (x in 450..470) mask[y * width + x] = 5.toByte()
        }
        for (y in 181..184) {
            for (x in 450..470) mask[y * width + x] = 1.toByte()
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val carDet = DetectedObject(
            classId = 2,
            label = "car",
            score = 0.85f,
            left = 445f / width,
            top = 155f / height,
            right = 475f / width,
            bottom = 181f / height,
        )

        val estimator = GroundContactDistanceEstimator(corridorHalfWidthMeters = 0.45f)
        val result = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = listOf(carDet),
        )

        assertNull("Lateral obstacle outside walking corridor must not produce distance", result.distanceMeters)
        assertEquals("전방 보행 경로 장애물 미감지", result.message)
    }

    @Test
    fun estimate_withAcceptedOd_temporalSmoothingAndReset() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        for (y in 150..175) {
            for (x in 250..262) mask[y * width + x] = 4.toByte()
        }
        for (y in 176..179) {
            for (x in 250..262) mask[y * width + x] = 2.toByte()
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val det = DetectedObject(classId = 0, label = "person", score = 0.85f, left = 245f / width, top = 145f / height, right = 265f / width, bottom = 176f / height)

        val estimator = GroundContactDistanceEstimator()
        val r1 = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = listOf(det),
        )
        assertNotNull(r1.distanceMeters)

        // Consecutive frame: smoothing applies
        val r2 = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_066_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = listOf(det),
        )
        assertNotNull(r2.distanceMeters)
        assertEquals(r1.distanceMeters!!, r2.distanceMeters!!, 1e-4f)

        // Reset clears temporal filter
        estimator.reset()
        val r3 = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_133_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = listOf(det),
        )
        val d1 = requireNotNull(r1.distanceMeters)
        val d3 = requireNotNull(r3.distanceMeters)
        assertEquals(d1, d3, 1e-4f)
    }

    @Test
    fun estimate_fallbackSegmentationOnly_whenOdNullEmptyOrLowConfidence() {
        val width = 512
        val height = 288
        val mask = ByteArray(width * height)

        for (y in 150..175) {
            for (x in 250..262) mask[y * width + x] = 4.toByte()
        }
        for (y in 176..179) {
            for (x in 250..262) mask[y * width + x] = 2.toByte()
        }

        val rad30 = Math.toRadians(30.0).toFloat()
        val orientation = GroundContactOrientation(
            timestampNanos = 1_000_000_000L,
            upVector = Vector3D(0f, -cos(rad30), -sin(rad30)),
            isReliable = true,
        )

        val estimator = GroundContactDistanceEstimator()

        // 1. detections = null (e.g. gate rejected or OD disabled)
        val rNull = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = null,
        )
        assertNotNull("Missing OD must fall back to segmentation distance", rNull.distanceMeters)

        // 2. detections = emptyList()
        val rEmpty = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = emptyList(),
        )
        assertNotNull("Empty OD must fall back to segmentation distance", rEmpty.distanceMeters)

        // 3. low score (< 0.5f)
        val rLow = estimator.estimate(
            classMask = mask,
            width = width,
            height = height,
            profile = shoulderProfile170,
            effectiveFov = standardPortraitFov,
            orientation = orientation,
            timestampNanos = 1_000_000_000L,
            isClockRealtime = true,
            fullCameraModel = pixel3PortraitFullCameraModel,
            detections = listOf(DetectedObject(classId = 0, label = "p", score = 0.3f, left = 0.4f, top = 0.4f, right = 0.6f, bottom = 0.7f)),
        )
        assertNotNull("Low confidence OD must fall back to segmentation distance", rLow.distanceMeters)
    }
}
