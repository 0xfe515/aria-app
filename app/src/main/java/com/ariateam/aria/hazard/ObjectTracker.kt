package com.ariateam.aria.hazard

import com.ariateam.aria.vision.od.DetectedObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM multi-object tracker for auxiliary object detections.
 *
 * Converts per-frame detected bounding boxes into confirmed temporal tracks with metric ground distance,
 * time-to-contact (TTC) from bounding-box growth, lateral approach velocity toward the active corridor,
 * walking corridor overlap, and semantic segmentation agreement.
 */
class ObjectTracker(
    val minIou: Float = 0.3f,
    val maxGapNanos: Long = 700_000_000L,
    val minScore: Float = 0.5f,
    val ttcMinObservations: Int = 3,
    val ttcEmaAlpha: Float = 0.5f,
    val maxTracks: Int = 16,
    val frameWidth: Int = 512,
) {

    private data class TrackInternal(
        val id: Int,
        val kind: ObjectKind,
        var score: Float,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
        var observations: Int,
        var prevHeight: Float,
        var prevCentreX: Float,
        var prevCaptureNanos: Long,
        var lastCaptureNanos: Long,
        var smoothedTtc: Float?,
        var lastEmittedTrack: ObjectTrack,
    )

    private data class MatchCandidate(
        val trackIndex: Int,
        val detectionIndex: Int,
        val iou: Float,
    )

    private var activeTracks = mutableListOf<TrackInternal>()
    private var currentTracks: List<ObjectTrack> = emptyList()
    private var lastAcceptedCaptureNanos: Long = 0L
    private var nextTrackId: Int = 1

    /**
     * Ingests a frame's object detections and updates confirmed tracks.
     *
     * @param detections Raw detected objects in upright crop coordinates [0f, 1f].
     * @param captureNanos Monotonic frame timestamp in nanoseconds.
     * @param corridor Direction of the active walking corridor.
     * @param classMask Optional segmentation class mask buffer for agreement check.
     * @param maskWidth Width in pixels of [classMask].
     * @param maskHeight Height in pixels of [classMask].
     * @param projector Optional ground contact point projector.
     * @return Track list ordered by track ID ascending.
     */
    fun update(
        detections: List<DetectedObject>,
        captureNanos: Long,
        corridor: CorridorDirection,
        classMask: ByteArray?,
        maskWidth: Int = 512,
        maskHeight: Int = 288,
        projector: PointProjector? = null,
    ): List<ObjectTrack> {
        if (captureNanos <= 0L) {
            return emptyList()
        }

        if (lastAcceptedCaptureNanos > 0L) {
            if (captureNanos < lastAcceptedCaptureNanos) {
                // Out-of-order timestamp: ignore update and return current tracks unchanged
                return currentTracks
            }
            if (captureNanos == lastAcceptedCaptureNanos) {
                // Snapshot reuse: return current tracks without counting a new observation
                return currentTracks
            }
            if ((captureNanos - lastAcceptedCaptureNanos) > maxGapNanos) {
                // Excessive gap: drop all previous tracks before matching
                activeTracks.clear()
            }
        }

        // Filter and map detections from supported COCO categories
        val validDetections = mutableListOf<Pair<DetectedObject, ObjectKind>>()
        for (det in detections) {
            if (!det.hasValidGeometry || det.score < minScore) continue
            val kind = when (det.classId) {
                0 -> ObjectKind.PERSON
                1, 3 -> ObjectKind.TWO_WHEELER
                2, 5, 7 -> ObjectKind.VEHICLE
                else -> null
            } ?: continue
            validDetections.add(det to kind)
        }

        // Greedy matching by descending IoU (same kind only, IoU >= minIou)
        val candidates = mutableListOf<MatchCandidate>()
        for ((tIdx, track) in activeTracks.withIndex()) {
            for ((dIdx, detPair) in validDetections.withIndex()) {
                val (det, detKind) = detPair
                if (track.kind != detKind) continue
                val iou = computeIoU(
                    track.left, track.top, track.right, track.bottom,
                    det.left, det.top, det.right, det.bottom,
                )
                if (iou >= minIou) {
                    candidates.add(MatchCandidate(tIdx, dIdx, iou))
                }
            }
        }
        candidates.sortByDescending { it.iou }

        val matchedTrackIndices = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()
        val survivingTracks = mutableListOf<TrackInternal>()

        val corridorCentreX = (corridor.startCol + corridor.endCol + 1).toFloat() / (2f * frameWidth.toFloat())
        val corridorLeft = corridor.startCol.toFloat() / frameWidth.toFloat()
        val corridorRight = (corridor.endCol + 1).toFloat() / frameWidth.toFloat()

        for (candidate in candidates) {
            if (candidate.trackIndex in matchedTrackIndices || candidate.detectionIndex in matchedDetectionIndices) {
                continue
            }
            matchedTrackIndices.add(candidate.trackIndex)
            matchedDetectionIndices.add(candidate.detectionIndex)

            val track = activeTracks[candidate.trackIndex]
            val (det, _) = validDetections[candidate.detectionIndex]

            val dtNanos = captureNanos - track.lastCaptureNanos
            val dtSeconds = if (dtNanos > 0L) dtNanos.toFloat() / 1_000_000_000f else 0f
            val currHeight = det.bottom - det.top
            val currCentreX = (det.left + det.right) / 2f
            val dh = currHeight - track.prevHeight

            if (dh > 0f && dtSeconds > 0f) {
                val dhDt = dh / dtSeconds
                val instTtc = currHeight / dhDt
                if (instTtc.isFinite()) {
                    val prevSmoothed = track.smoothedTtc
                    track.smoothedTtc = if (prevSmoothed == null) {
                        instTtc
                    } else {
                        ttcEmaAlpha * instTtc + (1f - ttcEmaAlpha) * prevSmoothed
                    }
                } else {
                    track.smoothedTtc = null
                }
            } else {
                track.smoothedTtc = null
            }

            val newObservations = track.observations + 1
            val confirmed = newObservations >= 2

            val ttcSeconds: Float? = if (newObservations >= ttcMinObservations && track.smoothedTtc != null) {
                val s = track.smoothedTtc!!
                if (s.isFinite()) s.coerceIn(0.1f, 30f) else null
            } else {
                null
            }

            val vx = if (dtSeconds > 0f) (currCentreX - track.prevCentreX) / dtSeconds else 0f
            val lateralVelocityNorm = when {
                currCentreX < corridorCentreX -> vx
                currCentreX > corridorCentreX -> -vx
                track.prevCentreX < corridorCentreX -> vx
                track.prevCentreX > corridorCentreX -> -vx
                else -> 0f
            }

            val boxWidth = det.right - det.left
            val interLeft = max(det.left, corridorLeft)
            val interRight = min(det.right, corridorRight)
            val interWidth = max(0f, interRight - interLeft)
            val corridorOverlap = if (boxWidth > 0f) (interWidth / boxWidth).coerceIn(0f, 1f) else 0f

            val bottomCentreX = (det.left + det.right) / 2f
            val bottomCenterY = det.bottom
            val intersection = projector?.project(bottomCentreX, bottomCenterY)
            val distanceMeters = intersection?.forwardDistanceMeters
            val lateralMeters = intersection?.lateralDistanceMeters

            val segAgree = computeSegAgree(
                kind = track.kind,
                left = det.left,
                top = det.top,
                right = det.right,
                bottom = det.bottom,
                classMask = classMask,
                maskWidth = maskWidth,
                maskHeight = maskHeight,
            )

            val emitted = ObjectTrack(
                id = track.id,
                kind = track.kind,
                score = det.score,
                left = det.left,
                top = det.top,
                right = det.right,
                bottom = det.bottom,
                distanceMeters = distanceMeters,
                lateralMeters = lateralMeters,
                ttcSeconds = ttcSeconds,
                lateralVelocityNorm = lateralVelocityNorm,
                corridorOverlap = corridorOverlap,
                confirmed = confirmed,
                segAgree = segAgree,
                lastCaptureNanos = captureNanos,
            )

            track.score = det.score
            track.left = det.left
            track.top = det.top
            track.right = det.right
            track.bottom = det.bottom
            track.observations = newObservations
            track.prevHeight = currHeight
            track.prevCentreX = currCentreX
            track.prevCaptureNanos = track.lastCaptureNanos
            track.lastCaptureNanos = captureNanos
            track.lastEmittedTrack = emitted

            survivingTracks.add(track)
        }

        // Unmatched detections become new tracks
        for ((dIdx, detPair) in validDetections.withIndex()) {
            if (dIdx in matchedDetectionIndices) continue
            val (det, kind) = detPair

            val currHeight = det.bottom - det.top
            val currCentreX = (det.left + det.right) / 2f
            val boxWidth = det.right - det.left
            val interLeft = max(det.left, corridorLeft)
            val interRight = min(det.right, corridorRight)
            val interWidth = max(0f, interRight - interLeft)
            val corridorOverlap = if (boxWidth > 0f) (interWidth / boxWidth).coerceIn(0f, 1f) else 0f

            val bottomCentreX = (det.left + det.right) / 2f
            val bottomCenterY = det.bottom
            val intersection = projector?.project(bottomCentreX, bottomCenterY)
            val distanceMeters = intersection?.forwardDistanceMeters
            val lateralMeters = intersection?.lateralDistanceMeters

            val segAgree = computeSegAgree(
                kind = kind,
                left = det.left,
                top = det.top,
                right = det.right,
                bottom = det.bottom,
                classMask = classMask,
                maskWidth = maskWidth,
                maskHeight = maskHeight,
            )

            val trackId = nextTrackId++
            val emitted = ObjectTrack(
                id = trackId,
                kind = kind,
                score = det.score,
                left = det.left,
                top = det.top,
                right = det.right,
                bottom = det.bottom,
                distanceMeters = distanceMeters,
                lateralMeters = lateralMeters,
                ttcSeconds = null,
                lateralVelocityNorm = 0f,
                corridorOverlap = corridorOverlap,
                confirmed = false,
                segAgree = segAgree,
                lastCaptureNanos = captureNanos,
            )

            val newTrack = TrackInternal(
                id = trackId,
                kind = kind,
                score = det.score,
                left = det.left,
                top = det.top,
                right = det.right,
                bottom = det.bottom,
                observations = 1,
                prevHeight = currHeight,
                prevCentreX = currCentreX,
                prevCaptureNanos = captureNanos,
                lastCaptureNanos = captureNanos,
                smoothedTtc = null,
                lastEmittedTrack = emitted,
            )
            survivingTracks.add(newTrack)
        }

        // Cap surviving tracks at maxTracks by score (ties broken deterministically by ID)
        val cappedTracks = if (survivingTracks.size > maxTracks) {
            survivingTracks.sortedWith(
                compareByDescending<TrackInternal> { it.score }.thenBy { it.id }
            ).take(maxTracks)
        } else {
            survivingTracks
        }

        activeTracks = cappedTracks.toMutableList()
        val resultTracks = cappedTracks.map { it.lastEmittedTrack }.sortedBy { it.id }
        currentTracks = resultTracks
        lastAcceptedCaptureNanos = captureNanos
        return resultTracks
    }

    /**
     * Resets all internal tracking state and re-initializes track ID generation.
     */
    fun reset() {
        activeTracks.clear()
        currentTracks = emptyList()
        lastAcceptedCaptureNanos = 0L
        nextTrackId = 1
    }

    private fun computeIoU(
        l1: Float, t1: Float, r1: Float, b1: Float,
        l2: Float, t2: Float, r2: Float, b2: Float,
    ): Float {
        val interLeft = max(l1, l2)
        val interTop = max(t1, t2)
        val interRight = min(r1, r2)
        val interBottom = min(b1, b2)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val area1 = (r1 - l1) * (b1 - t1)
        val area2 = (r2 - l2) * (b2 - t2)
        val unionArea = area1 + area2 - interArea
        return if (unionArea > 0f) (interArea / unionArea).coerceIn(0f, 1f) else 0f
    }

    private fun computeSegAgree(
        kind: ObjectKind,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        classMask: ByteArray?,
        maskWidth: Int,
        maskHeight: Int,
    ): Boolean {
        if (classMask == null || classMask.size < maskWidth * maskHeight) return false
        if (maskWidth <= 0 || maskHeight <= 0) return false

        val targetClass = when (kind) {
            ObjectKind.PERSON -> 4
            ObjectKind.VEHICLE, ObjectKind.TWO_WHEELER -> 5
            else -> return false
        }

        val height = bottom - top
        if (height <= 0f) return false

        val bottom10Top = bottom - 0.1f * height
        val bottom10Bottom = bottom

        val rowStart = (bottom10Top * maskHeight).toInt().coerceIn(0, maskHeight - 1)
        val rowEnd = (bottom10Bottom * maskHeight).toInt().coerceIn(0, maskHeight - 1)
        val colStart = (left * maskWidth).toInt().coerceIn(0, maskWidth - 1)
        val colEnd = (right * maskWidth).toInt().coerceIn(0, maskWidth - 1)

        if (rowStart > rowEnd || colStart > colEnd) return false

        var totalPixels = 0
        var matchPixels = 0

        for (r in rowStart..rowEnd) {
            val rowOffset = r * maskWidth
            for (c in colStart..colEnd) {
                totalPixels++
                val maskVal = classMask[rowOffset + c].toInt() and 0xFF
                if (maskVal == targetClass) {
                    matchPixels++
                }
            }
        }

        return totalPixels > 0 && ((matchPixels.toFloat() / totalPixels.toFloat()) >= 0.2f)
    }
}
