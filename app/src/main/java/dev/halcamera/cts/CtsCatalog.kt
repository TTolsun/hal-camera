package dev.halcamera.cts

import dev.halcamera.cts.onoff.FastOnOffRules
import dev.halcamera.cts.recording.BasicRecordingRules
import dev.halcamera.cts.switching.SwitchingRules

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
    const val FAST_ON_OFF = "fast_on_off"
    const val SWITCHING = "switching"

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
        ),
        CtsCaseSpec(
            id = FAST_ON_OFF,
            source = FastOnOffRules.SOURCE,
            title = "빠른 켜기·끄기",
            needsAudio = false,
            summary = { cameras ->
                "카메라 ${cameras}대마다 표준 열기(열기 → 프리뷰 세션 → 첫 프레임 → 닫기)와 빠른 열기(열기 직후 닫기 → 다시 열기 → 첫 프레임 → 닫기)를 " +
                    "${FastOnOffRules.ITERATIONS}회씩 번갈아 수행합니다(약 ${cameras * FastOnOffRules.ITERATIONS * 3 / 60 + 1}분). " +
                    "첫 프레임 도착과 SENSOR_TIMESTAMP·프레임 번호를 검사하고, 마지막 줄에 두 방식의 첫 프레임 중앙값을 비교합니다."
            }
        ),
        CtsCaseSpec(
            id = SWITCHING,
            source = SwitchingRules.SOURCE,
            title = "카메라 전환",
            needsAudio = true,
            summary = { cameras ->
                "카메라 ${cameras}대를 차례로 열고 첫 프레임을 받은 뒤 닫는 전환을 ${SwitchingRules.DEFAULT_ROUNDS}회 반복하고, " +
                    "끝에 카메라마다 가장 큰 CamcorderProfile로 ${SwitchingRules.RECORDING_DURATION_MS / 1000}초씩 녹화합니다(약 ${(cameras * SwitchingRules.DEFAULT_ROUNDS * 2 + cameras * 6) / 60 + 1}분). " +
                    "녹화는 파일·트랙·크기와 길이 오차 ${(SwitchingRules.DURATION_MARGIN * 100).toInt()} %만 검사하며 소리도 함께 녹음됩니다."
            }
        )
    )

    fun byId(id: String?): CtsCaseSpec? = cases.firstOrNull { it.id == id }
}
