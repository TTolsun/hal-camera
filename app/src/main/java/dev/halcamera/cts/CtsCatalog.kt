package dev.halcamera.cts

import dev.halcamera.cts.combination.StillPreviewCombinationRules
import dev.halcamera.cts.onoff.FastOnOffRules
import dev.halcamera.cts.sizes.AllSizeOnOffRules
import dev.halcamera.cts.snapshot.VideoSnapshotRules
import dev.halcamera.cts.switching.SwitchingRules

/**
 * One entry of the CTS case list. [id] travels in the Intent, [source] is the CTS class#method the case
 * mirrors (or `custom#Name` for a case of our own in the CTS style), and [summary] tells the reader what one
 * run does on a device with [cameras] cameras, including a rough duration. [estimateSeconds] is that same
 * rough duration as a number, so the suite screen can add the estimates of several cases up. Pure Kotlin so
 * the list and its texts are testable; the runner behind each id is chosen by [CtsRunners] on the device.
 */
data class CtsCaseSpec(
    val id: String,
    val source: String,
    val title: String,
    val needsAudio: Boolean,
    val summary: (cameras: Int) -> String,
    val estimateSeconds: (cameras: Int) -> Int
)

/**
 * The cases the custom CTS screen offers, in list order. Every id is unique and stable. testBasicRecording is
 * not transcribed here: the vendored CTS path runs the original RecordingTest#testBasicRecording, and
 * [dev.halcamera.cts.recording.BasicRecordingRules] stays because Switching and VideoSnapshot share its rules.
 */
object CtsCatalog {
    const val FAST_ON_OFF = "fast_on_off"
    const val SWITCHING = "switching"
    const val ALL_SIZE_ON_OFF = "all_size_on_off"
    const val STILL_PREVIEW_COMBINATION = "still_preview_combination"
    const val VIDEO_SNAPSHOT = "video_snapshot"

    val cases: List<CtsCaseSpec> = listOf(
        CtsCaseSpec(
            id = FAST_ON_OFF,
            source = FastOnOffRules.SOURCE,
            title = "빠른 켜기·끄기",
            needsAudio = false,
            summary = { cameras ->
                "카메라 ${cameras}대마다 표준 열기(열기 → 프리뷰 세션 → 첫 프레임 → 닫기)와 빠른 열기(열기 직후 닫기 → 다시 열기 → 첫 프레임 → 닫기)를 " +
                    "${FastOnOffRules.ITERATIONS}회씩 번갈아 수행합니다(약 ${cameras * FastOnOffRules.ITERATIONS * 3 / 60 + 1}분). " +
                    "첫 프레임 도착과 SENSOR_TIMESTAMP·프레임 번호를 검사하고, 마지막 줄에 두 방식의 첫 프레임 중앙값을 비교합니다."
            },
            estimateSeconds = { cameras -> cameras * FastOnOffRules.ITERATIONS * 3 }
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
            },
            estimateSeconds = { cameras -> cameras * SwitchingRules.DEFAULT_ROUNDS * 2 + cameras * 6 }
        ),
        CtsCaseSpec(
            id = ALL_SIZE_ON_OFF,
            source = AllSizeOnOffRules.SOURCE,
            title = "모든 크기 켜기·끄기",
            needsAudio = false,
            summary = { cameras ->
                "카메라 ${cameras}대마다 SurfaceHolder로 보고하는 모든 프리뷰 크기를 큰 것부터 하나씩 열어(열기 → 그 크기의 프리뷰 세션 → 첫 프레임 → 닫기) 확인합니다" +
                    "(크기당 약 2초, 카메라마다 크기 수만큼). 1080p 상한 없이 카메라가 광고한 크기 전부를 대상으로 합니다."
            },
            // The size count is only known once a camera is opened; 25 sizes per camera is what a 2025 flagship reports.
            estimateSeconds = { cameras -> cameras * 25 * 2 }
        ),
        CtsCaseSpec(
            id = STILL_PREVIEW_COMBINATION,
            source = StillPreviewCombinationRules.SOURCE,
            title = "정지 영상 × 프리뷰 조합",
            needsAudio = false,
            summary = { cameras ->
                "카메라 ${cameras}대마다 JPEG 크기 전부와 프리뷰 크기(1080p 이하) 전부의 조합을 하나씩 구성해 프리뷰 첫 프레임 뒤 정지 영상을 한 장 찍고, " +
                    "요청한 크기의 디코딩 가능한 JPEG가 돌아오는지 검사합니다(조합당 약 1초, 카메라마다 수백 조합). " +
                    "CTS StillCaptureTest#testStillPreviewCombination의 순서와 QCIF 예외를 따르되 AE·AF 수렴은 기다리지 않습니다. 사진은 저장하지 않습니다."
            },
            // About 300 combinations per camera at just over a second each: 29 minutes for four cameras on a Galaxy S25+.
            estimateSeconds = { cameras -> cameras * 420 }
        ),
        CtsCaseSpec(
            id = VIDEO_SNAPSHOT,
            source = VideoSnapshotRules.SOURCE,
            title = "동영상 스냅샷",
            needsAudio = true,
            summary = { cameras ->
                "카메라 ${cameras}대마다 가장 큰 CamcorderProfile로 ${VideoSnapshotRules.RECORDING_DURATION_MS / 1000}초를 녹화하면서 " +
                    "${VideoSnapshotRules.SNAPSHOT_EARLIEST_MS / 1000}~${VideoSnapshotRules.SNAPSHOT_LATEST_MS / 1000}초 사이의 무작위 시점에 같은 세션으로 JPEG 스냅샷을 한 장 찍습니다" +
                    "(약 ${cameras * 30 / 60 + 1}분). 동영상은 길이 오차 20 %와 프레임 드롭률 ${VideoSnapshotRules.FRAMEDROP_TOLERANCE.toInt()} % 미만, 스냅샷은 요청한 크기의 디코딩 가능한 JPEG인지 검사하며 소리도 함께 녹음됩니다. 사진과 동영상은 저장하지 않습니다."
            },
            estimateSeconds = { cameras -> cameras * 30 }
        )
    )

    fun byId(id: String?): CtsCaseSpec? = cases.firstOrNull { it.id == id }
}
