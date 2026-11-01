package com.ariateam.aria.distance

/**
 * Physical body mount position options for wearable camera height estimation.
 *
 * References:
 * Makiko Kouchi and Masaaki Mochimaru, 2006: Japanese 3-D body shape and dimensions data 2003,
 * National Institute of Advanced Industrial Science and Technology (AIST), H18PRO-503.
 */
enum class BodyMountPosition(val koreanLabel: String) {
    SHOULDER("어깨"),
    CHEST("가슴"),
}

/**
 * User-configured body mount profile consisting of total stature in cm and mount position.
 */
data class BodyMountProfile(
    val statureCm: Float,
    val position: BodyMountPosition,
) {
    val isValid: Boolean
        get() = statureCm.isFinite() &&
            statureCm in BodyMountHeightModel.MIN_STATURE_CM..BodyMountHeightModel.MAX_STATURE_CM

    val estimatedCameraHeightMeters: Float?
        get() = BodyMountHeightModel.estimateHeightMeters(statureCm, position)
}

/**
 * Deterministic height model translating stature and mount position into estimated lens height.
 *
 * Ratios derived from sample-weighted aggregate averages of AIST 2003 adult statistics:
 * Total aggregate stature sum D = 49 * 1696.7 + 48 * 1578.3 = 158896.7 mm
 * Acromiale (shoulder) height sum S = 49 * 1363.5 + 48 * 1268.1 = 127680.3 mm -> r_shoulder = S / D = 0.8035428048537193
 * Chest height sum C = 49 * 1217.3 + 48 * 1129.1 = 113844.5 mm -> r_chest = C / D = 0.7164686239550601
 */
object BodyMountHeightModel {
    const val MIN_STATURE_CM = 120f
    const val MAX_STATURE_CM = 220f

    const val SHOULDER_RATIO = 0.8035428f
    const val CHEST_RATIO = 0.7164686f

    fun estimateHeightMeters(statureCm: Float, position: BodyMountPosition): Float? {
        if (!statureCm.isFinite() || statureCm !in MIN_STATURE_CM..MAX_STATURE_CM) {
            return null
        }
        val ratio = when (position) {
            BodyMountPosition.SHOULDER -> SHOULDER_RATIO
            BodyMountPosition.CHEST -> CHEST_RATIO
        }
        val heightMeters = (statureCm / 100f) * ratio
        return if (heightMeters.isFinite() && heightMeters > 0f) heightMeters else null
    }
}
