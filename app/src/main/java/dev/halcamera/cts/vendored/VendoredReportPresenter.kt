package dev.halcamera.cts.vendored

import dev.halcamera.ctsvendor.VendoredResult
import dev.halcamera.ctsvendor.VendoredVerdict

/** Plain-text rendering of a [VendoredResult] for the screen and the clipboard. Pure Kotlin. */
object VendoredReportPresenter {
    const val DISCLAIMER = "AOSP CTS 테스트 코드를 앱 안의 JUnit으로 실행한 결과입니다. 공식 판정은 cts-tradefed의 test_result.xml만 인정됩니다."

    /**
     * A cancelled run leads with 중단됨, not FAIL: the host made the test fail by closing its camera, so the
     * failure it reports is the cancel itself. The failure text still follows so the reader can confirm that.
     */
    fun headline(result: VendoredResult): String {
        val timed = duration(result.durationMs)
        if (result.cancelled) {
            return if (result.failures.isEmpty()) "중단됨 · $timed" else "중단됨 · $timed · 실패 ${result.failures.size}건"
        }
        return when (result.verdict) {
            VendoredVerdict.PASS -> "PASS · $timed"
            VendoredVerdict.FAIL -> "FAIL · 실패 ${result.failures.size}건 · $timed"
            VendoredVerdict.SKIP -> "SKIP · 이 기기에서는 검사할 것이 없습니다 · $timed"
        }
    }

    /** "1분 23초" for anything over a minute, "23초" below, "0.8초" under a second. */
    fun duration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds >= 60 -> "${seconds / 60}분 ${seconds % 60}초"
            seconds >= 1 -> "${seconds}초"
            else -> "${ms / 100 / 10.0}초"
        }
    }

    fun fullText(device: String, build: String, app: String, result: VendoredResult): String = buildString {
        append("HAL CAM CTS vendored · ").append(result.test.source).append('\n')
        append(result.test.className).append('\n')
        append(device).append(" · ").append(build).append(" · app ").append(app).append('\n')
        append(headline(result)).append('\n')
        append(DISCLAIMER).append('\n')
        result.failures.forEachIndexed { index, failure ->
            append('\n').append("실패 ").append(index + 1).append('\n').append(failure).append('\n')
        }
    }.trimEnd()
}
