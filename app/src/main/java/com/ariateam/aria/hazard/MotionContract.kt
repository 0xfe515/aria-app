package com.ariateam.aria.hazard

/**
 * Directional corridor within the 512x288 camera/segmentation field of view.
 */
enum class CorridorDirection(val koreanLabel: String, val startCol: Int, val endCol: Int) {
    LEFT("좌측", 0, 170),
    CENTER("중앙", 171, 341),
    RIGHT("우측", 342, 511),
}

/**
 * Reasons why the motion estimate is degraded and cannot guarantee walking forward guidance.
 */
enum class MotionDegradedReason(val koreanMessage: String) {
    CALIBRATION_PENDING("보행 정면 캘리브레이션 대기 중"),
    SENSORS_UNAVAILABLE("센서(가속도·자이로) 미지원 또는 비활성"),
    SAMPLES_STALE("센서 샘플 지연 (200ms 초과)"),
    SAMPLES_GAPPED("센서 샘플 시간 간격 불안정"),
    NON_FINITE_DATA("비정상 센서 수치(NaN/Inf) 감지"),
    CAMERA_TILT_INVALID("카메라 상향/하향 각도 과도 (보행 시야 이탈)"),
    RAPID_TURN_TRANSIENT("급격한 회전 중 (화면 모션 블러)"),
    EXCESSIVE_DRIFT("경로 편차 과도 (재보정 필요)"),
    CAMERA_FOV_UNKNOWN("카메라 시야각(FOV) 기하구조 미확인"),
}

enum class UserMovementActivity(val koreanLabel: String) {
    STATIONARY("정지"),
    WALKING("보행 중"),
    UNKNOWN("감지 중"),
}

data class MotionState(
    val timestampNanos: Long = 0L,
    val isCalibrated: Boolean = false,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val yawRateDegPerSec: Float = 0f,
    val relativeHeadingDeg: Float = 0f,
    val activeCorridor: CorridorDirection = CorridorDirection.CENTER,
    val continuousCorridorCenterPx: Float = 256f,
    val activity: UserMovementActivity = UserMovementActivity.UNKNOWN,
    val linearAccelMag: Float = 0f,
    val isCameraForwardWalk: Boolean = false,
    val confidence: Float = 0f,
    val degradedReason: MotionDegradedReason? = MotionDegradedReason.CALIBRATION_PENDING,
) {
    val isReliable: Boolean get() = isCalibrated && degradedReason == null && confidence >= 0.5f
}

enum class HazardLevel(val priority: Int, val koreanLabel: String) {
    CLEAR_UNWARNED(0, "경고 미감지 (보행 영역 탐지)"),
    PATH_CAUTION(1, "주의 (진행 경로 협소)"),
    PATH_BLOCKED(2, "위험 (진행 경로 차단)"),
    NEAR_PATH_HAZARD(3, "주의 (근접 경로 장애물 추정)"),
    UNKNOWN_DEGRADED(-1, "방향·자세 미확인"),
}

data class HazardAssessment(
    val timestampNanos: Long = 0L,
    val level: HazardLevel = HazardLevel.UNKNOWN_DEGRADED,
    val activeCorridor: CorridorDirection = CorridorDirection.CENTER,
    val dominantNonWalkableClass: Int = -1,
    val dominantNearClass: Int = -1,
    val corridorWalkableRatio: Float = 0f,
    val nearObstacleOccupancy: Float = 0f,
    val motionConfidence: Float = 0f,
    val messageKorean: String = "방향·자세 확인 필요 (보행 정면 캘리브레이션 대기)",
    val isAlertCandidate: Boolean = false,
)
