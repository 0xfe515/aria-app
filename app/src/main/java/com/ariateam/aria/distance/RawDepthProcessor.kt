package com.ariateam.aria.distance

import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Extracts, unprojects, and transforms raw depth pixels into a camera-relative,
 * gravity-aligned horizontal-forward coordinate frame for obstacle estimation.
 *
 * Coordinates:
 * - xMeters: Horizontal right (meters)
 * - yMeters: Vertical up against gravity (meters)
 * - zMeters: Horizontal forward along walking heading (meters)
 */
class RawDepthProcessor(
    private val pixelSamplingStep: Int = 4,
    private val minConfidence: Float = 0.50f,
    private val maxProcessingRangeMeters: Float = 6.0f,
) {
    data class ProcessResult(
        val samples: List<DepthSample>,
        val timestampNanos: Long,
        val isFresh: Boolean,
        val isDuplicate: Boolean,
        val rawWidth: Int = 0,
        val rawHeight: Int = 0,
    )

    private var lastDepthTimestampNanos: Long = 0L

    /**
     * Acquires raw depth & confidence images from [frame], verifies camera-domain freshness
     * and matching confidence, and transforms valid depth points into gravity-aligned [DepthSample]s.
     */
    fun processFrame(frame: Frame): ProcessResult {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            return ProcessResult(emptyList(), frame.timestamp, isFresh = false, isDuplicate = false)
        }

        var depthImage: Image? = null
        var confImage: Image? = null
        try {
            depthImage = frame.acquireRawDepthImage16Bits()
            confImage = try {
                frame.acquireRawDepthConfidenceImage()
            } catch (_: NotYetAvailableException) {
                null
            }

            // Confidence image is mandatory for Raw Depth; never fabricate synthetic confidence
            if (confImage == null) {
                return ProcessResult(emptyList(), frame.timestamp, isFresh = false, isDuplicate = false)
            }

            if (depthImage.width != confImage.width || depthImage.height != confImage.height) {
                return ProcessResult(emptyList(), frame.timestamp, isFresh = false, isDuplicate = false)
            }

            // Verify depth and confidence images belong to the exact same sensor frame
            if (depthImage.timestamp != confImage.timestamp) {
                return ProcessResult(emptyList(), frame.timestamp, isFresh = false, isDuplicate = false)
            }

            val depthTimestampNanos = depthImage.timestamp
            val frameTimestampNanos = frame.timestamp

            // Camera-domain freshness: depth image timestamp must be near camera frame timestamp
            val ageInCameraDomain = frameTimestampNanos - depthTimestampNanos
            if (ageInCameraDomain < 0L || ageInCameraDomain > 500_000_000L) {
                return ProcessResult(emptyList(), depthTimestampNanos, isFresh = false, isDuplicate = false)
            }

            // Check if this depth frame was already processed on a previous camera frame
            if (depthTimestampNanos == lastDepthTimestampNanos) {
                return ProcessResult(
                    samples = emptyList(),
                    timestampNanos = depthTimestampNanos,
                    isFresh = false,
                    isDuplicate = true,
                    rawWidth = depthImage.width,
                    rawHeight = depthImage.height,
                )
            }

            // Reject out-of-order timestamps
            if (depthTimestampNanos < lastDepthTimestampNanos) {
                return ProcessResult(emptyList(), depthTimestampNanos, isFresh = false, isDuplicate = false)
            }

            lastDepthTimestampNanos = depthTimestampNanos

            val samples = extractSamples(
                depthImage = depthImage,
                confidenceImage = confImage,
                frame = frame,
            )

            return ProcessResult(
                samples = samples,
                timestampNanos = depthTimestampNanos,
                isFresh = true,
                isDuplicate = false,
                rawWidth = depthImage.width,
                rawHeight = depthImage.height,
            )
        } catch (_: NotYetAvailableException) {
            // Normal readiness state when depth frame is not yet generated
            return ProcessResult(emptyList(), frame.timestamp, isFresh = false, isDuplicate = false)
        } catch (_: Throwable) {
            return ProcessResult(emptyList(), frame.timestamp, isFresh = false, isDuplicate = false)
        } finally {
            try {
                depthImage?.close()
            } catch (_: Throwable) {
            }
            try {
                confImage?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun extractSamples(
        depthImage: Image,
        confidenceImage: Image,
        frame: Frame,
    ): List<DepthSample> {
        val width = depthImage.width
        val height = depthImage.height
        if (width <= 0 || height <= 0) return emptyList()

        val depthPlane = depthImage.planes[0]
        val depthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val depthRowStride = depthPlane.rowStride
        val depthPixelStride = depthPlane.pixelStride

        val confPlane = confidenceImage.planes[0]
        val confBuffer = confPlane.buffer
        val confRowStride = confPlane.rowStride
        val confPixelStride = confPlane.pixelStride

        val intrinsics = frame.camera.imageIntrinsics
        val focalLength = intrinsics.focalLength
        val principalPoint = intrinsics.principalPoint
        val dims = intrinsics.imageDimensions

        val scaleX = width.toFloat() / dims[0].coerceAtLeast(1)
        val scaleY = height.toFloat() / dims[1].coerceAtLeast(1)

        val fx = focalLength[0] * scaleX
        val fy = focalLength[1] * scaleY
        val cx = principalPoint[0] * scaleX
        val cy = principalPoint[1] * scaleY

        val cameraPose = frame.camera.pose
        val fwdVec = FloatArray(3)
        // Camera optical axis in ARCore OpenGL camera coordinate space is (0, 0, -1)
        cameraPose.rotateVector(floatArrayOf(0f, 0f, -1f), 0, fwdVec, 0)

        // Project camera forward vector onto horizontal plane (gravity is +Y up in ARCore world)
        val horizLen = sqrt(fwdVec[0] * fwdVec[0] + fwdVec[2] * fwdVec[2])
        if (horizLen < 0.15f) {
            // Camera pointed almost directly at ceiling or floor
            return emptyList()
        }

        val fxHoriz = fwdVec[0] / horizLen
        val fzHoriz = fwdVec[2] / horizLen

        // Horizontal right = Forward x WorldUp = (fxHoriz, 0, fzHoriz) x (0, 1, 0) = (-fzHoriz, 0, fxHoriz)
        val rxHoriz = -fzHoriz
        val rzHoriz = fxHoriz

        val camX = cameraPose.tx()
        val camY = cameraPose.ty()
        val camZ = cameraPose.tz()

        val samples = ArrayList<DepthSample>(width * height / (pixelSamplingStep * pixelSamplingStep))
        val pointCam = FloatArray(3)
        val pointWorld = FloatArray(3)

        for (v in 0 until height step pixelSamplingStep) {
            val depthRowOffset = v * depthRowStride
            val confRowOffset = v * confRowStride

            for (u in 0 until width step pixelSamplingStep) {
                val depthOffset = depthRowOffset + u * depthPixelStride
                val depthMm = depthBuffer.getShort(depthOffset).toInt() and 0xFFFF
                if (depthMm == 0) continue

                val depthMeters = depthMm / 1000f
                if (depthMeters <= 0.15f || depthMeters > maxProcessingRangeMeters) continue

                val confOffset = confRowOffset + u * confPixelStride
                val confByte = confBuffer.get(confOffset).toInt() and 0xFF
                val confidence = confByte / 255f

                if (confidence < minConfidence) continue

                // Unproject 2D pixel to 3D camera coordinate space
                // ARCore camera space: +X right, +Y up, -Z forward (optical axis)
                val xCam = (u - cx) * depthMeters / fx
                val yCam = -(v - cy) * depthMeters / fy
                val zCam = -depthMeters

                pointCam[0] = xCam
                pointCam[1] = yCam
                pointCam[2] = zCam

                cameraPose.transformPoint(pointCam, 0, pointWorld, 0)

                // Offset relative to camera in world coordinates
                val dx = pointWorld[0] - camX
                val dy = pointWorld[1] - camY
                val dz = pointWorld[2] - camZ

                // Transform to gravity-aligned horizontal-forward frame
                val xSample = dx * rxHoriz + dz * rzHoriz
                val ySample = dy // Gravity aligned (+Y is up)
                val zSample = dx * fxHoriz + dz * fzHoriz

                samples.add(
                    DepthSample(
                        xMeters = xSample,
                        yMeters = ySample,
                        zMeters = zSample,
                        confidence = confidence,
                    )
                )
            }
        }

        return samples
    }

    fun reset() {
        lastDepthTimestampNanos = 0L
    }
}
