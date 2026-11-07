package com.ariateam.aria.hazard

import com.ariateam.aria.distance.BodyMountPosition
import com.ariateam.aria.distance.BodyMountProfile
import com.ariateam.aria.distance.GroundContactOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class GroundPointProjectorTest {

    private val defaultProfile = BodyMountProfile(
        statureCm = 170f,
        position = BodyMountPosition.SHOULDER,
    )

    private val defaultFov = EffectiveFov(
        hfovDegrees = 65f,
        vfovDegrees = 50f,
    )

    private val defaultModel = FullUprightCameraModel(
        focalLengthMm = 4.44f,
        uprightSensorWidthMm = 4.29f,
        uprightSensorHeightMm = 5.76f,
        fullHfovDegrees = 51.5f,
        fullVfovDegrees = 66.0f,
    )

    @Test
    fun project_downwardPitch_computesAnalyticallyExpectedDistance() {
        val pitchRad = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(pitchRad), -sin(pitchRad))
        val orientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = upVector,
            isReliable = true,
        )

        val heightMeters = defaultProfile.estimatedCameraHeightMeters!!
        val expectedForwardDistance = heightMeters / tan(pitchRad)

        val intersection = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = defaultProfile,
            orientation = orientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )

        assertNotNull("Intersection must not be null for downward ray", intersection)
        assertEquals(expectedForwardDistance, intersection!!.forwardDistanceMeters, 0.05f)
        assertEquals(0f, intersection.lateralDistanceMeters, 0.05f)
    }

    @Test
    fun project_fullCameraModel_projectsValidGroundIntersection() {
        val pitchRad = Math.toRadians(25.0).toFloat()
        val upVector = Vector3D(0f, -cos(pitchRad), -sin(pitchRad))
        val orientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = upVector,
            isReliable = true,
        )

        val intersection = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = defaultProfile,
            orientation = orientation,
            effectiveFov = null,
            fullCameraModel = defaultModel,
            cropLeftNorm = 0.1f,
            cropTopNorm = 0.2f,
            cropWidthNorm = 0.8f,
            cropHeightNorm = 0.6f,
        )

        assertNotNull(intersection)
        val fwd = intersection!!.forwardDistanceMeters
        assertTrue("Forward distance must be > 0.1m", fwd > 0.1f)
        assertTrue("Forward distance must be < 35m", fwd < 35f)
    }

    @Test
    fun project_returnsNullForRayAboveOrParallelToHorizon() {
        val orientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = Vector3D(0f, -1f, 0f),
            isReliable = true,
        )

        val intersection = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = defaultProfile,
            orientation = orientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )
        assertNull("Ray parallel to ground must return null", intersection)

        val intersectionTop = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.05f,
            profile = defaultProfile,
            orientation = orientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )
        assertNull("Ray pointing above horizon must return null", intersectionTop)
    }

    @Test
    fun project_returnsNullForUnreliableOrientation() {
        val pitchRad = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(pitchRad), -sin(pitchRad))

        val unreliableOrientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = upVector,
            isReliable = false,
        )

        val res1 = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = defaultProfile,
            orientation = unreliableOrientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )
        assertNull("Unreliable orientation must yield null", res1)

        val res2 = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = defaultProfile,
            orientation = null,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )
        assertNull("Null orientation must yield null", res2)
    }

    @Test
    fun project_returnsNullForInvalidOrNullProfile() {
        val pitchRad = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(pitchRad), -sin(pitchRad))
        val orientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = upVector,
            isReliable = true,
        )

        val invalidProfile = BodyMountProfile(
            statureCm = 80f,
            position = BodyMountPosition.SHOULDER,
        )

        val res1 = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = invalidProfile,
            orientation = orientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )
        assertNull("Invalid stature profile must yield null", res1)

        val res2 = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = null,
            orientation = orientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )
        assertNull("Null profile must yield null", res2)
    }

    @Test
    fun project_returnsNullWhenBothFovAndCameraModelAreNull() {
        val pitchRad = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(pitchRad), -sin(pitchRad))
        val orientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = upVector,
            isReliable = true,
        )

        val res = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = defaultProfile,
            orientation = orientation,
            effectiveFov = null,
            fullCameraModel = null,
        )
        assertNull("Missing FOV and camera model must yield null", res)
    }

    @Test
    fun project_returnsNullForNonFiniteAndOutOfBoundsInputs() {
        val pitchRad = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(pitchRad), -sin(pitchRad))
        val orientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = upVector,
            isReliable = true,
        )

        assertNull(GroundPointProjector.project(-0.01f, 0.5f, defaultProfile, orientation, defaultFov, null))
        assertNull(GroundPointProjector.project(1.01f, 0.5f, defaultProfile, orientation, defaultFov, null))
        assertNull(GroundPointProjector.project(Float.NaN, 0.5f, defaultProfile, orientation, defaultFov, null))
        assertNull(GroundPointProjector.project(0.5f, -0.01f, defaultProfile, orientation, defaultFov, null))
        assertNull(GroundPointProjector.project(0.5f, 1.01f, defaultProfile, orientation, defaultFov, null))
        assertNull(GroundPointProjector.project(0.5f, Float.POSITIVE_INFINITY, defaultProfile, orientation, defaultFov, null))
        assertNull(GroundPointProjector.project(0.5f, 0.5f, defaultProfile, orientation, defaultFov, null, cropWidthNorm = 0f))
        assertNull(GroundPointProjector.project(0.5f, 0.5f, defaultProfile, orientation, defaultFov, null, cropWidthNorm = -1f))
        assertNull(GroundPointProjector.project(0.5f, 0.5f, defaultProfile, orientation, defaultFov, null, cropHeightNorm = Float.NaN))
        assertNull(GroundPointProjector.project(0.5f, 0.5f, defaultProfile, orientation, defaultFov, null, cropLeftNorm = -0.1f))
        assertNull(GroundPointProjector.project(0.5f, 0.5f, defaultProfile, orientation, defaultFov, null, cropTopNorm = 1.1f))
    }

    @Test
    fun bind_factoryMatchesProjectCall() {
        val pitchRad = Math.toRadians(30.0).toFloat()
        val upVector = Vector3D(0f, -cos(pitchRad), -sin(pitchRad))
        val orientation = GroundContactOrientation(
            timestampNanos = 100_000_000L,
            upVector = upVector,
            isReliable = true,
        )

        val projector = GroundPointProjector.bind(
            profile = defaultProfile,
            orientation = orientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )

        val expected = GroundPointProjector.project(
            xNorm = 0.5f,
            yNorm = 0.5f,
            profile = defaultProfile,
            orientation = orientation,
            effectiveFov = defaultFov,
            fullCameraModel = null,
        )

        val actual = projector.project(0.5f, 0.5f)
        assertNotNull(expected)
        assertNotNull(actual)
        assertEquals(expected!!.forwardDistanceMeters, actual!!.forwardDistanceMeters, 1e-4f)
        assertEquals(expected.lateralDistanceMeters, actual.lateralDistanceMeters, 1e-4f)
    }
}
