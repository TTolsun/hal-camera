package dev.halcamera.benchmark.domain

import dev.halcamera.metrics.jsonName

/**
 * The BENCHMARK start card (docs/PLAN-BenchMarker-v0.3.md 8.2).
 *
 * [blockedReason] and [notices] are deliberately different things. A blocked card offers no START at all, because
 * the run either cannot produce the profile's streams (3.6) or would be measured on a throttled device. A notice
 * lets the run start and names the validity flag it will carry, so the developer decides before spending 45
 * seconds rather than reading `THERMAL_HIGH` off the result screen afterwards.
 */
data class StartCard(
    val titleLine: String,
    val profileLine: String,
    val verdictLine: String,
    val durationLine: String,
    val detailLine: String,
    val notices: List<String>,
    val blockedReason: String?,
    /**
     * True when the block is a property of the moment rather than of the camera. A hot device cools down, so
     * that card needs a way back to START; an unsupported profile never becomes supported here (3.6), and
     * offering to re-check would send the developer to wait for nothing.
     */
    val refreshable: Boolean
) {
    val canStart: Boolean get() = blockedReason == null
}

/** Pure so every rule of 8.2 is decided in one place and testable without a camera or a thermal service. */
object StartCardPresenter {

    /** PowerManager.THERMAL_STATUS_SEVERE, as a plain int so this file has no Android dependency. */
    const val THERMAL_SEVERE = 3

    /** The engine name [dev.halcamera.MainActivity] uses for the Camera2 path. */
    const val ENGINE_CAMERA2 = "Camera2"

    /**
     * Wall-clock length of one run. The plan's 3.3 budget estimated 25 - 30 s; the M2 device check on a Galaxy
     * S25+ measured 45 s, so the card states the measured number rather than the estimate.
     */
    const val ESTIMATED_SECONDS = 45

    fun present(
        profile: BenchmarkProfile,
        compatibility: Compatibility,
        endpointName: String,
        engineName: String,
        thermalStatus: Int?,
        powerSaveMode: Boolean?
    ): StartCard {
        val notices = ArrayList<String>()
        // 8.2: the profile is Camera2-only, so entering from a CameraX preview switches the engine and says so
        // instead of silently measuring something the LIVE screen was not showing.
        if (engineName != ENGINE_CAMERA2) {
            notices += "Camera2 전용 profile · Camera2로 전환합니다"
        }
        if (thermalStatus != null && thermalStatus in ValidityFlags.THERMAL_MODERATE until THERMAL_SEVERE) {
            notices += "THERMAL_HIGH · 비교·점수 산정에서 제외됩니다"
        }
        if (powerSaveMode == true) {
            notices += "POWER_SAVE_MODE · 비교·점수 산정에서 제외됩니다"
        }
        return StartCard(
            // No engine name: the profile is Camera2-only, so this always read "Camera2" and told the reader
            // nothing. It also collided with the camera label right after it — "Camera2 · Camera · 0 (Wide ·
            // Rear)" reads as one phrase. Entering from a CameraX preview is announced in notices instead.
            titleLine = listOf(endpointName, conditionLabel(profile), launchModeLabel(profile))
                .joinToString(" · "),
            profileLine = "Profile  ${profile.id}",
            verdictLine = verdictLine(compatibility),
            durationLine = "약 ${ESTIMATED_SECONDS}초 · 화면을 켠 채 유지 · 밝은 피사체를 향해 고정",
            detailLine = "${profile.launchIterations}× open · ${profile.observeMs / 1000}초 관측 · 사진 ${profile.stillCount}장",
            notices = notices,
            blockedReason = blockedReason(compatibility, thermalStatus),
            refreshable = compatibility.supported
        )
    }

    /**
     * Support comes first: a profile that cannot run here is never lowered to fit (3.6), so a cooler device would
     * not change the answer, and naming the thermal state instead would send the developer to wait for nothing.
     */
    fun blockedReason(compatibility: Compatibility, thermalStatus: Int?): String? = when {
        !compatibility.supported ->
            "이 profile은 이 카메라에서 실행할 수 없습니다 (${compatibility.reasons.joinToString(", ").ifEmpty { "사유 없음" }})."
        thermalStatus != null && thermalStatus >= THERMAL_SEVERE ->
            "기기 온도가 높습니다(SEVERE). 식을 때까지 자동으로 다시 확인합니다."
        else -> null
    }

    /** The preflight verdict with the method that produced it: a static-table pass is weaker evidence than an exact query. */
    fun verdictLine(compatibility: Compatibility): String =
        if (compatibility.supported) "✓ 이 카메라에서 실행 가능 (${compatibility.method})"
        else "✗ 실행할 수 없음 (${compatibility.method})"

    /** "1080p30" from the profile's preview size and fps range; the raw strings when either cannot be parsed. */
    fun conditionLabel(profile: BenchmarkProfile): String {
        val height = profile.previewSize.substringAfter('x', "").toIntOrNull()
        val fps = Regex("(\\d+)\\s*]").find(profile.fpsRange)?.groupValues?.get(1)?.toIntOrNull()
        if (height == null || fps == null) return "${profile.previewSize} ${profile.fpsRange}"
        return "${height}p$fps"
    }

    /** Same wording as the result headline (8.4) so one run reads the same on both screens. */
    fun launchModeLabel(profile: BenchmarkProfile): String = profile.launchMode.jsonName.replace('_', ' ')
}
