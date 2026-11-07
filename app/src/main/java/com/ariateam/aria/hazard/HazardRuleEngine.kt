package com.ariateam.aria.hazard

import com.ariateam.aria.vision.VisionContract
import java.util.Locale
import kotlin.math.abs

data class RuleConfig(
    val reactionSeconds: Float = 1.5f,
    val marginMeters: Float = 0.5f,
    val defaultWalkSpeedMps: Float = 1.2f,
    val vehicleWarnMeters: Float = 4.0f,
    val vehicleWarnTtcS: Float = 3.0f,
    val crosswalkVehicleWarnMeters: Float = 6.0f,
    val crosswalkVehicleWarnTtcS: Float = 4.0f,
    val imminentTtcS: Float = 2.0f,
    val staticWarnMeters: Float = 1.2f,
    val staticWarnBoxBottom: Float = 0.92f,
    val nearRoadWarn: Float = 0.5f,
    val pathLostWalkable: Float = 0.2f,
    val pathNarrowNearWalkable: Float = 0.5f,
    val pathNarrowRoiWalkable: Float = 0.65f,
    val sideRoadCaution: Float = 0.3f,
    val vehicleNearbyMeters: Float = 6.0f,
    val inPathOverlap: Float = 0.5f,
    val crosswalkEntry: Float = 0.3f,
    val degradedOtherRatio: Float = 0.6f,
    val poleCautionBoxBottom: Float = 0.80f,
)

/**
 * Deterministic rule engine evaluating spatial segmentation features and object tracks to produce
 * prioritized walking hazard decisions (Warning / Caution / None / Degraded).
 */
class HazardRuleEngine(
    private val config: RuleConfig = RuleConfig(),
    private val width: Int = VisionContract.MODEL_WIDTH,
    private val height: Int = VisionContract.MODEL_HEIGHT,
) {
    private var wasOnCrosswalk: Boolean = false

    fun reset() {
        wasOnCrosswalk = false
    }

    fun evaluate(
        features: SegmentationFeatures,
        tracks: List<ObjectTrack>,
        motion: MotionState,
        timestampNanos: Long,
    ): RuleDecision {
        // Degraded first: invalid motion or obscured segmentation
        if (!motion.isReliable || motion.degradedReason != null || features.otherRatio > config.degradedOtherRatio) {
            wasOnCrosswalk = false
            val message = when {
                features.otherRatio > config.degradedOtherRatio -> "주변 인식이 어렵습니다"
                motion.degradedReason != null -> "방향·자세 확인 필요: ${motion.degradedReason.koreanMessage}"
                else -> "방향 신뢰도 부족"
            }
            return RuleDecision(
                severity = AlertSeverity.DEGRADED,
                cause = HazardCause.DEGRADED,
                direction = features.corridor,
                riskScore = 0f,
                messageKorean = message,
            )
        }

        val walking = motion.activity == UserMovementActivity.WALKING || motion.activity == UserMovementActivity.UNKNOWN
        val onCrosswalk = features.nearCrosswalk >= config.crosswalkEntry
        val dReact = config.defaultWalkSpeedMps * config.reactionSeconds + config.marginMeters

        // Edge detection for crosswalk entry
        val crosswalkRisingEdge = onCrosswalk && !wasOnCrosswalk
        wasOnCrosswalk = onCrosswalk

        val candidates = ArrayList<RuleCandidate>()

        // 1. Process Object Tracks (only confirmed tracks participate)
        val validTracks = ArrayList<ObjectTrack>()
        for (track in tracks) {
            if (track.confirmed) {
                validTracks.add(track)
            }
        }

        // 2. Convert segmentation poleBlobs into synthetic static candidates
        for (i in features.poleBlobs.indices) {
            val blob = features.poleBlobs[i]
            val blobWidth = (blob.maxX - blob.minX + 1).coerceAtLeast(1)
            val overlapStart = maxOf(blob.minX, features.corridor.startCol)
            val overlapEnd = minOf(blob.maxX, features.corridor.endCol)
            val overlapPixels = if (overlapEnd >= overlapStart) (overlapEnd - overlapStart + 1) else 0
            val overlap = overlapPixels.toFloat() / blobWidth

            val syntheticTrack = ObjectTrack(
                id = -100 - i,
                kind = ObjectKind.POLE,
                score = 1.0f,
                left = blob.minX.toFloat() / width,
                top = blob.minY.toFloat() / height,
                right = (blob.maxX + 1).toFloat() / width,
                bottom = (blob.maxY + 1).toFloat() / height,
                distanceMeters = null,
                lateralMeters = null,
                ttcSeconds = null,
                lateralVelocityNorm = 0f,
                corridorOverlap = overlap,
                confirmed = true,
                segAgree = true,
                lastCaptureNanos = timestampNanos,
            )
            validTracks.add(syntheticTrack)
        }

        // Evaluate track-based rules
        for (track in validTracks) {
            val inPath = track.corridorOverlap >= config.inPathOverlap
            val approachingLaterally = track.lateralVelocityNorm > 0f
            val isVehicle = track.kind == ObjectKind.VEHICLE || track.kind == ObjectKind.TWO_WHEELER
            val isStatic = !track.kind.dynamic

            val trackDirection = determineTrackDirection(track)
            val riskScore = calculateTrackRiskScore(track, dReact)

            // W1: Vehicle / Two-wheeler approach
            val vehicleWarnDist = if (onCrosswalk) config.crosswalkVehicleWarnMeters else config.vehicleWarnMeters
            val vehicleWarnTtc = if (onCrosswalk) config.crosswalkVehicleWarnTtcS else config.vehicleWarnTtcS
            val distTriggerW1 = track.distanceMeters != null && track.distanceMeters < vehicleWarnDist
            val ttcTriggerW1 = track.ttcSeconds != null && track.ttcSeconds < vehicleWarnTtc
            if (isVehicle && (inPath || approachingLaterally) && (distTriggerW1 || ttcTriggerW1)) {
                candidates.add(
                    RuleCandidate(
                        severity = AlertSeverity.WARNING,
                        cause = HazardCause.W1_VEHICLE_APPROACH,
                        trackId = track.id,
                        objectKind = track.kind,
                        distanceMeters = track.distanceMeters,
                        direction = trackDirection,
                        riskScore = riskScore,
                        messageKorean = createMessage(
                            cause = HazardCause.W1_VEHICLE_APPROACH,
                            direction = trackDirection,
                            distanceMeters = track.distanceMeters,
                            kind = track.kind,
                        ),
                    )
                )
            }

            // W2: Imminent dynamic track in path
            if (track.kind.dynamic && inPath && track.ttcSeconds != null && track.ttcSeconds < config.imminentTtcS) {
                candidates.add(
                    RuleCandidate(
                        severity = AlertSeverity.WARNING,
                        cause = HazardCause.W2_OBJECT_IMMINENT,
                        trackId = track.id,
                        objectKind = track.kind,
                        distanceMeters = track.distanceMeters,
                        direction = trackDirection,
                        riskScore = riskScore,
                        messageKorean = createMessage(
                            cause = HazardCause.W2_OBJECT_IMMINENT,
                            direction = trackDirection,
                            distanceMeters = track.distanceMeters,
                            kind = track.kind,
                        ),
                    )
                )
            }

            // W4: Static obstacle imminent in path while walking
            val staticDistW4 = track.distanceMeters != null && track.distanceMeters < config.staticWarnMeters
            val staticBottomW4 = track.distanceMeters == null && track.bottom >= config.staticWarnBoxBottom
            if (walking && isStatic && inPath && (staticDistW4 || staticBottomW4)) {
                candidates.add(
                    RuleCandidate(
                        severity = AlertSeverity.WARNING,
                        cause = HazardCause.W4_STATIC_IMMINENT,
                        trackId = track.id,
                        objectKind = track.kind,
                        distanceMeters = track.distanceMeters,
                        direction = trackDirection,
                        riskScore = riskScore,
                        messageKorean = createMessage(
                            cause = HazardCause.W4_STATIC_IMMINENT,
                            direction = trackDirection,
                            distanceMeters = track.distanceMeters,
                            kind = track.kind,
                        ),
                    )
                )
            }

            // C1: Obstacle in path (caution zone)
            val c1Dist = track.distanceMeters != null &&
                track.distanceMeters >= config.staticWarnMeters &&
                track.distanceMeters < (dReact + 1.5f)
            val c1Bottom = track.distanceMeters == null && track.bottom >= config.poleCautionBoxBottom
            if (inPath && (!isStatic || walking) && (c1Dist || c1Bottom)) {
                candidates.add(
                    RuleCandidate(
                        severity = AlertSeverity.CAUTION,
                        cause = HazardCause.C1_OBJECT_IN_PATH,
                        trackId = track.id,
                        objectKind = track.kind,
                        distanceMeters = track.distanceMeters,
                        direction = trackDirection,
                        riskScore = riskScore,
                        messageKorean = createMessage(
                            cause = HazardCause.C1_OBJECT_IN_PATH,
                            direction = trackDirection,
                            distanceMeters = track.distanceMeters,
                            kind = track.kind,
                        ),
                    )
                )
            }

            // C2: Vehicle nearby but not directly in path
            if (isVehicle && !inPath && track.distanceMeters != null && track.distanceMeters < config.vehicleNearbyMeters) {
                candidates.add(
                    RuleCandidate(
                        severity = AlertSeverity.CAUTION,
                        cause = HazardCause.C2_VEHICLE_NEARBY,
                        trackId = track.id,
                        objectKind = track.kind,
                        distanceMeters = track.distanceMeters,
                        direction = trackDirection,
                        riskScore = riskScore,
                        messageKorean = createMessage(
                            cause = HazardCause.C2_VEHICLE_NEARBY,
                            direction = trackDirection,
                            distanceMeters = track.distanceMeters,
                            kind = track.kind,
                        ),
                    )
                )
            }
        }

        // Zone rules (riskScore = 1.0f)
        // W3: Road underfoot while walking off-crosswalk
        if (walking && !onCrosswalk && features.nearRoad > config.nearRoadWarn) {
            candidates.add(
                RuleCandidate(
                    severity = AlertSeverity.WARNING,
                    cause = HazardCause.W3_ROAD_UNDERFOOT,
                    trackId = null,
                    objectKind = null,
                    distanceMeters = null,
                    direction = features.corridor,
                    riskScore = 1.0f,
                    messageKorean = createMessage(
                        cause = HazardCause.W3_ROAD_UNDERFOOT,
                        direction = features.corridor,
                        distanceMeters = null,
                        kind = null,
                    ),
                )
            )
        }

        // W5: Path lost (near walkable fraction below threshold)
        if (walking && features.nearWalkable < config.pathLostWalkable) {
            candidates.add(
                RuleCandidate(
                    severity = AlertSeverity.WARNING,
                    cause = HazardCause.W5_PATH_LOST,
                    trackId = null,
                    objectKind = null,
                    distanceMeters = null,
                    direction = features.corridor,
                    riskScore = 1.0f,
                    messageKorean = createMessage(
                        cause = HazardCause.W5_PATH_LOST,
                        direction = features.corridor,
                        distanceMeters = null,
                        kind = null,
                    ),
                )
            )
        }

        // C3: Road on side band
        val maxSideRoad = maxOf(features.sideRoadLeft, features.sideRoadRight)
        if (maxSideRoad > config.sideRoadCaution) {
            val sideDirection = if (features.sideRoadLeft >= features.sideRoadRight) {
                CorridorDirection.LEFT
            } else {
                CorridorDirection.RIGHT
            }
            candidates.add(
                RuleCandidate(
                    severity = AlertSeverity.CAUTION,
                    cause = HazardCause.C3_ROAD_EDGE,
                    trackId = null,
                    objectKind = null,
                    distanceMeters = null,
                    direction = sideDirection,
                    riskScore = 1.0f,
                    messageKorean = createMessage(
                        cause = HazardCause.C3_ROAD_EDGE,
                        direction = sideDirection,
                        distanceMeters = null,
                        kind = null,
                    ),
                )
            )
        }

        // C4: Path narrowing ahead
        if (walking && (features.nearWalkable < config.pathNarrowNearWalkable || features.roiWalkable < config.pathNarrowRoiWalkable)) {
            candidates.add(
                RuleCandidate(
                    severity = AlertSeverity.CAUTION,
                    cause = HazardCause.C4_PATH_NARROW,
                    trackId = null,
                    objectKind = null,
                    distanceMeters = null,
                    direction = features.corridor,
                    riskScore = 1.0f,
                    messageKorean = createMessage(
                        cause = HazardCause.C4_PATH_NARROW,
                        direction = features.corridor,
                        distanceMeters = null,
                        kind = null,
                    ),
                )
            )
        }

        // C5: Crosswalk entry rising edge
        if (crosswalkRisingEdge) {
            candidates.add(
                RuleCandidate(
                    severity = AlertSeverity.CAUTION,
                    cause = HazardCause.C5_CROSSWALK_ENTRY,
                    trackId = null,
                    objectKind = null,
                    distanceMeters = null,
                    direction = features.corridor,
                    riskScore = 1.0f,
                    messageKorean = createMessage(
                        cause = HazardCause.C5_CROSSWALK_ENTRY,
                        direction = features.corridor,
                        distanceMeters = null,
                        kind = null,
                    ),
                )
            )
        }

        if (candidates.isEmpty()) {
            return RuleDecision(
                severity = AlertSeverity.NONE,
                cause = HazardCause.NONE,
                trackId = null,
                objectKind = null,
                distanceMeters = null,
                direction = null,
                riskScore = 0f,
                messageKorean = "",
            )
        }

        // Deterministic selection: highest severity rank -> highest riskScore -> lowest cause ordinal -> lowest trackId
        var best = candidates[0]
        for (i in 1 until candidates.size) {
            val curr = candidates[i]
            if (isBetter(curr, best)) {
                best = curr
            }
        }

        return RuleDecision(
            severity = best.severity,
            cause = best.cause,
            trackId = best.trackId,
            objectKind = best.objectKind,
            distanceMeters = best.distanceMeters,
            direction = best.direction,
            riskScore = best.riskScore,
            messageKorean = best.messageKorean,
        )
    }

    private fun isBetter(candidate: RuleCandidate, currentBest: RuleCandidate): Boolean {
        if (candidate.severity.rank != currentBest.severity.rank) {
            return candidate.severity.rank > currentBest.severity.rank
        }
        val scoreDiff = candidate.riskScore - currentBest.riskScore
        if (abs(scoreDiff) > 1e-6f) {
            return scoreDiff > 0f
        }
        if (candidate.cause.ordinal != currentBest.cause.ordinal) {
            return candidate.cause.ordinal < currentBest.cause.ordinal
        }
        val candidateId = candidate.trackId ?: Int.MAX_VALUE
        val bestId = currentBest.trackId ?: Int.MAX_VALUE
        return candidateId < bestId
    }

    private fun determineTrackDirection(track: ObjectTrack): CorridorDirection {
        val centerX = (track.left + track.right) / 2f
        return when {
            centerX < 171f / 512f -> CorridorDirection.LEFT
            centerX > 341f / 512f -> CorridorDirection.RIGHT
            else -> CorridorDirection.CENTER
        }
    }

    private fun calculateTrackRiskScore(track: ObjectTrack, dReact: Float): Float {
        val proximity = if (track.distanceMeters != null) {
            if (track.distanceMeters <= 0f) 2.0f else (dReact / track.distanceMeters).coerceIn(0f, 2f)
        } else {
            1.0f
        }
        val segWeight = if (track.segAgree) 1.0f else 0.7f
        val approach = if (track.ttcSeconds != null) {
            if (track.ttcSeconds <= 0f) 2.0f else (1.0f + (1.0f / track.ttcSeconds).coerceIn(0f, 1f))
        } else {
            1.0f
        }
        return track.kind.weight * proximity * track.corridorOverlap * track.score * segWeight * approach
    }

    private fun formatDistance(distanceMeters: Float?): String {
        return if (distanceMeters != null) {
            String.format(Locale.US, "%.1f미터 ", distanceMeters)
        } else {
            ""
        }
    }

    private fun formatDirection(direction: CorridorDirection?): String {
        return when (direction) {
            CorridorDirection.LEFT -> "왼쪽"
            CorridorDirection.CENTER -> "정면"
            CorridorDirection.RIGHT -> "오른쪽"
            null -> "전방"
        }
    }

    private fun formatKind(kind: ObjectKind?): String {
        return when (kind) {
            ObjectKind.VEHICLE -> "차량"
            ObjectKind.TWO_WHEELER -> "이륜차"
            ObjectKind.PERSON -> "사람"
            ObjectKind.POLE -> "기둥"
            ObjectKind.UNKNOWN, null -> "장애물"
        }
    }

    private fun createMessage(
        cause: HazardCause,
        direction: CorridorDirection?,
        distanceMeters: Float?,
        kind: ObjectKind?,
    ): String {
        val dirStr = formatDirection(direction)
        val distStr = formatDistance(distanceMeters)
        val kindStr = formatKind(kind)

        return when (cause) {
            HazardCause.W1_VEHICLE_APPROACH -> {
                "정지. $dirStr ${distStr}$kindStr".replace("  ", " ").trim()
            }
            HazardCause.W2_OBJECT_IMMINENT -> {
                "$dirStr ${distStr}$kindStr".replace("  ", " ").trim()
            }
            HazardCause.W3_ROAD_UNDERFOOT -> {
                "차도입니다. 멈추세요"
            }
            HazardCause.W4_STATIC_IMMINENT -> {
                if (distanceMeters != null) {
                    "정지. $dirStr ${distStr}$kindStr".replace("  ", " ").trim()
                } else {
                    "정지. $dirStr $kindStr".replace("  ", " ").trim()
                }
            }
            HazardCause.W5_PATH_LOST -> {
                "앞길이 막혔습니다"
            }
            HazardCause.C1_OBJECT_IN_PATH -> {
                "$dirStr ${distStr}$kindStr".replace("  ", " ").trim()
            }
            HazardCause.C2_VEHICLE_NEARBY -> {
                "$dirStr ${distStr}$kindStr 주의".replace("  ", " ").trim()
            }
            HazardCause.C3_ROAD_EDGE -> {
                "$dirStr${if (dirStr.endsWith("쪽")) "이" else "가"} 차도입니다"
            }
            HazardCause.C4_PATH_NARROW -> {
                "길이 좁아집니다"
            }
            HazardCause.C5_CROSSWALK_ENTRY -> {
                "횡단보도 앞입니다. 신호는 확인할 수 없습니다"
            }
            HazardCause.DEGRADED -> {
                "주변 인식이 어렵습니다"
            }
            HazardCause.NONE -> ""
        }
    }

    private data class RuleCandidate(
        val severity: AlertSeverity,
        val cause: HazardCause,
        val trackId: Int?,
        val objectKind: ObjectKind?,
        val distanceMeters: Float?,
        val direction: CorridorDirection?,
        val riskScore: Float,
        val messageKorean: String,
    )
}
