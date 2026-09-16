package dev.halcamera.cts

import dev.halcamera.cts.recording.BasicRecordingRules

/**
 * One entry of the CTS case list. [id] travels in the Intent, [source] is the CTS class#method the case
 * mirrors (or `custom#Name` for a case of our own in the CTS style), and [summary] tells the reader what one
 * run does on a device with [cameras] cameras, including a rough duration. Pure Kotlin so the list and its
 * texts are testable; the runner behind each id is chosen by [CtsRunners] on the device.
 */
data class CtsCaseSpec(
    val id: String,
    val source: String,
    val title: String,
    val needsAudio: Boolean,
    val summary: (cameras: Int) -> String
)

/** The cases the CTS screen offers, in list order. Every id is unique and stable. */
object CtsCatalog {
    const val BASIC_RECORDING = "basic_recording"

    val cases: List<CtsCaseSpec> = listOf(
        CtsCaseSpec(
            id = BASIC_RECORDING,
            source = BasicRecordingRules.SOURCE,
            title = "기본 녹화",
            needsAudio = true,
            summary = { cameras ->
                "카메라 ${cameras}대 × CamcorderProfile 최대 ${BasicRecordingRules.PROFILE_ORDER.size}개를 각각 " +
                    "${BasicRecordingRules.RECORDING_DURATION_MS / 1000}초씩 녹화합니다(약 ${cameras * BasicRecordingRules.PROFILE_ORDER.size * 4 / 60 + 1}분). " +
                    "길이 오차 ${(BasicRecordingRules.DURATION_MARGIN * 100).toInt()} %, 프레임 드롭률 ${BasicRecordingRules.FRMDRP_RATE_TOLERANCE.toInt()} % 미만을 검사하고 소리도 함께 녹음됩니다."
            }
        )
    )

    fun byId(id: String?): CtsCaseSpec? = cases.firstOrNull { it.id == id }
}
