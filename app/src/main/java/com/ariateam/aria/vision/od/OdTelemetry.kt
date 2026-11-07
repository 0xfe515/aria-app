package com.ariateam.aria.vision.od

/**
 * Lifecycle status of the auxiliary object-detection sidecar.
 *
 * The sidecar is strictly advisory and never part of the camera analyzer's critical path.
 */
enum class OdSidecarStatus(val koreanLabel: String) {
    /** Sidecar disabled by pipeline mode (preview-only, model not ready) or by configuration. */
    DISABLED("비활성"),

    /** Enabled and session-active, no inference in flight. */
    IDLE("대기"),

    /** One inference is in flight on the dedicated worker. */
    BUSY("처리 중"),

    /** Temporarily suspended by the pressure governor; segmentation keeps full priority. */
    SUSPENDED("부하로 일시 중단"),

    /** Engine initialization or invocation failed; segmentation is unaffected. */
    UNAVAILABLE("사용 불가"),

    /** Pipeline controller closed the sidecar; no further results are published. */
    CLOSED("종료"),
}

/** Reason the pressure governor reduced or suspended the sidecar rate. */
enum class OdBackoffReason(val koreanLabel: String) {
    NONE("없음"),
    PRIMARY_FRAME_OVER_BUDGET("세그멘테이션 프레임 예산 초과"),
    SIDECAR_OVER_BUDGET("보조 추론 예산 초과"),
    ENGINE_ERROR("엔진 오류"),
}

/**
 * Bounded, read-only sidecar telemetry.
 *
 * Surfaced through `VisionUiState.odTelemetry` and `VisionPipelineDiagnosticSnapshot.odTelemetry`
 * so existing diagnostic surfaces can consume it without any Home layout change, and mirrored to
 * logcat through [structuredLogLine].
 *
 * All latency/age values are measured from monotonic elapsed-realtime clocks only; they are never
 * derived from wall-clock time and never claim device performance.
 */
data class OdTelemetry(
    val status: OdSidecarStatus = OdSidecarStatus.DISABLED,
    val backoffReason: OdBackoffReason = OdBackoffReason.NONE,
    val rateHz: Int = 0,
    val submitted: Long = 0L,
    val completed: Long = 0L,
    val replaced: Long = 0L,
    val busy: Long = 0L,
    val rateLimited: Long = 0L,
    val staleDropped: Long = 0L,
    val orderingRejected: Long = 0L,
    val invalidDropped: Long = 0L,
    val errors: Long = 0L,
    val inferenceMs: Long = 0L,
    val endToEndMs: Long = 0L,
    val resultAgeMs: Long = 0L,
    val detectionCount: Int = 0,
    val sessionToken: Long = 0L,
    val detail: String = "",
    val followsSegmentationCadence: Boolean = false,
) {
    /** Single-line structured log used by the controller; safe to emit off the analyzer thread. */
    fun structuredLogLine(): String {
        val cadenceTag = if (followsSegmentationCadence) {
            "cadence=segmentation_frames pacing=segmentation cadence/uncapped not suspended "
        } else {
            ""
        }
        val base = "od_sidecar status=${status.name} statusKo=${status.koreanLabel} rateHz=$rateHz " +
            cadenceTag +
            "backoff=${backoffReason.name} submitted=$submitted completed=$completed replaced=$replaced " +
            "busy=$busy rateLimited=$rateLimited stale=$staleDropped orderingRejected=$orderingRejected " +
            "invalid=$invalidDropped errors=$errors inferenceMs=$inferenceMs endToEndMs=$endToEndMs " +
            "resultAgeMs=$resultAgeMs detections=$detectionCount session=$sessionToken"
        return if (detail.isEmpty()) base else "$base detail=$detail"
    }

    companion object {
        /** Neutral value used before the sidecar has run and after the pipeline stops. */
        val INACTIVE: OdTelemetry = OdTelemetry(
            status = OdSidecarStatus.DISABLED,
            rateHz = 0,
            detail = "세그멘테이션 비전 실행 중에만 보조 객체 감지가 활성화됩니다.",
            followsSegmentationCadence = false,
        )
    }
}
