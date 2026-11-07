package com.ariateam.aria.hazard

enum class AlertSeverity(val rank: Int) { DEGRADED(-1), NONE(0), CAUTION(1), WARNING(2) }

enum class HazardCause(val severity: AlertSeverity) {
    NONE(AlertSeverity.NONE),
    W1_VEHICLE_APPROACH(AlertSeverity.WARNING),
    W2_OBJECT_IMMINENT(AlertSeverity.WARNING),
    W3_ROAD_UNDERFOOT(AlertSeverity.WARNING),
    W4_STATIC_IMMINENT(AlertSeverity.WARNING),
    W5_PATH_LOST(AlertSeverity.WARNING),
    C1_OBJECT_IN_PATH(AlertSeverity.CAUTION),
    C2_VEHICLE_NEARBY(AlertSeverity.CAUTION),
    C3_ROAD_EDGE(AlertSeverity.CAUTION),
    C4_PATH_NARROW(AlertSeverity.CAUTION),
    C5_CROSSWALK_ENTRY(AlertSeverity.CAUTION),
    DEGRADED(AlertSeverity.DEGRADED),
}

enum class ObjectKind(val weight: Float, val dynamic: Boolean) {
    VEHICLE(1.0f, true), TWO_WHEELER(0.9f, true), PERSON(0.6f, true), POLE(0.5f, false), UNKNOWN(0.3f, false),
}

/** Normalized [0,1] upright 512x288 display-space box; distance fields are null when geometry is unknown. */
data class ObjectTrack(
    val id: Int,
    val kind: ObjectKind,
    val score: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val distanceMeters: Float?,
    val lateralMeters: Float?,
    val ttcSeconds: Float?,
    val lateralVelocityNorm: Float,   // positive = moving toward active corridor centre, per second
    val corridorOverlap: Float,       // fraction of box width inside active corridor
    val confirmed: Boolean,
    val segAgree: Boolean,
    val lastCaptureNanos: Long,
)

data class MaskBlob(
    val classId: Int, val pixelCount: Int,
    val minX: Int, val maxX: Int, val minY: Int, val maxY: Int,
)

data class SegmentationFeatures(
    val corridor: CorridorDirection,
    val nearWalkable: Float, val roiWalkable: Float,
    val nearRoad: Float, val sideRoadLeft: Float, val sideRoadRight: Float,
    val nearCrosswalk: Float, val otherRatio: Float,
    val dominantNearClass: Int, val dominantRoiClass: Int,
    val poleBlobs: List<MaskBlob>,
)

data class RuleDecision(
    val severity: AlertSeverity,
    val cause: HazardCause,
    val trackId: Int? = null,
    val objectKind: ObjectKind? = null,
    val distanceMeters: Float? = null,
    val direction: CorridorDirection? = null,
    val riskScore: Float = 0f,
    val messageKorean: String = "",
)
