package dev.halcamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.halcamera.R

/**
 * LIVE's 진단 panel: frame and 3A readouts, the interval strip, the 3A oscilloscope, the callback timeline, system
 * load, the ADB CLI switch and the incident ZIP actions.
 *
 * This class only lays the panel out and hands back the views LIVE keeps updating. What each button does stays with
 * MainActivity through [Actions], because it owns the camera, the recorder and the CLI. The panel was split out so
 * MainActivity stays inside the 60,000-character input the docs scan can read.
 *
 * [cameraButton] and [pauseButton] are built by LIVE, which also changes their text and icon, and are placed here.
 */
class DiagnosticsPanel(
    context: Context,
    private val w: CameraWidgets,
    cameraButton: View,
    pauseButton: View,
    markLabel: String,
    actions: Actions,
) {
    interface Actions {
        fun close()
        fun stopRecording()
        fun about()
        fun shareLatest()
        fun incidents()
        fun retryPermission()
        fun notes()
        var cliEnabled: Boolean
    }

    private val muted = Look.onDarkMuted
    private val card = Look.cameraCard

    /** Detailed tools and graphs use flat, outlined cards; core readings stay on the preview. */
    val view = ScrollView(context).apply { setBackgroundColor(Look.cameraSurface); visibility = View.GONE; isFillViewport = true; isClickable = true }
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(w.dp(18), w.dp(12), w.dp(18), w.dp(24)) }

    /**
     * The panel hides the capture controls, which is where the stop button and the elapsed time live. A recording
     * that cannot be stopped without first closing a panel is the wrong trade for looking at the frame numbers while
     * it runs, so both follow the recording up here.
     */
    val recordingTime = w.label("● REC  00:00", 13, Look.statusFail, true).apply { typeface = Look.mono; visibility = View.GONE }
    val stopButton = IconButton(context, R.drawable.ic_action_stop, "녹화 정지") { actions.stopRecording() }.apply { visibility = View.GONE }

    /** 8.1: raw numbers only. BENCHMARK, not this panel, names a cause. */
    val readoutCard = w.label("프레임을 기다리는 중…", 12, Color.WHITE).apply { typeface = Look.mono; setPadding(w.dp(12), w.dp(14), w.dp(12), w.dp(14)); background = w.rounded(card) }
    val strip = StripView(context).apply { contentDescription = "최근 10초 센서 프레임 간격. 실선은 기준, 점선은 1.5배 임계" }
    val stripText = w.label("Partial —   Buffer — ms", 12, muted).apply { gravity = Gravity.CENTER; typeface = Look.mono }
    val scope = ScopeView(context).apply { background = w.rounded(card); contentDescription = "AE, AF, AWB 상태와 노출, ISO, 센서 프레임 간격 그래프" }
    val timelineView = TimelineView(context).apply { background = w.rounded(card); contentDescription = "최근 프레임과 세션 평균의 Start, Partial, Buffer 도착 시각 비교" }
    val timeline = w.label("프레임 콜백을 기다리는 중…", 12, Color.WHITE).apply { typeface = Look.mono; setPadding(w.dp(12), w.dp(14), w.dp(12), w.dp(14)); background = w.rounded(card) }

    /**
     * The CPU value carries its own scale: a footnote mark pointed at a dialog nobody had opened, and 150% reads as a
     * fault unless the line itself says one core is 100%. With the scale the three values no longer fit one line on a
     * phone, so the break is placed here instead of leaving one word to wrap on its own.
     */
    val system = w.label("App CPU — (1코어=100%)\nPSS —  ·  Thermal —", 11, muted)
    val recorderText = w.label("30s 순환 버퍼", 11, muted)
    val shareButton: Button = w.button("최근 ZIP 공유") { actions.shareLatest() }

    init {
        view.addView(body)
        val head = w.row().apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(head)
        head.addView(w.label("진단", 22, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(recordingTime, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = w.dp(8) })
        head.addView(stopButton, LinearLayout.LayoutParams(w.dp(48), w.dp(48)).apply { marginEnd = w.dp(4) })
        head.addView(IconButton(context, R.drawable.ic_action_info, "앱 정보 보기") { actions.about() }, LinearLayout.LayoutParams(w.dp(48), w.dp(48)))
        head.addView(IconButton(context, R.drawable.ic_action_close, "진단 패널 닫기") { actions.close() }, LinearLayout.LayoutParams(w.dp(48), w.dp(48)).apply { marginStart = w.dp(4) })
        val controls = w.row()
        cameraButton.minimumHeight = w.dp(48)
        controls.addView(cameraButton, Look.buttonParams(0, 1f))
        controls.addView(pauseButton, LinearLayout.LayoutParams(w.dp(48), w.dp(48)).apply { marginStart = w.dp(8) })
        body.addView(controls, w.lp(top = 12))

        val readoutDetails = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        readoutDetails.addView(w.label("직전 프레임 · ref p50: 최근 1.5초를 제외한 세션 표본", 12, muted), w.lp(top = 4))
        readoutDetails.addView(readoutCard, w.lp(top = 10))
        val stripBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = w.rounded(card); setPadding(w.dp(12), w.dp(12), w.dp(12), w.dp(12)) }
        stripBox.addView(w.label("Frame interval · 최근 10초", 12, muted, true), w.lp())
        stripBox.addView(strip, w.lp(height = 40, top = 8))
        stripBox.addView(stripText, w.lp(top = 8))
        body.addView(stripBox, w.lp(top = 10))
        body.addView(Look.disclosure(context, "프레임·3A 수치", readoutDetails), w.lp(top = 8))
        body.addView(w.label("3A oscilloscope", 14, muted, true), w.lp(top = 18))
        body.addView(w.label("최근 10초 · 3A 상태는 단계값, 연속값 그래프는 자동 스케일", 10, muted), w.lp(top = 4))
        body.addView(scope, w.lp(height = 342, top = 10))
        body.addView(w.label("Frame callback timeline", 14, muted, true), w.lp(top = 20))
        body.addView(timelineView, w.lp(height = 96, top = 10))
        body.addView(timeline, w.lp(top = 8))
        body.addView(system, w.lp(top = 12))

        val cliDetails = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        cliDetails.addView(cliSwitch(context, actions), w.lp())
        cliDetails.addView(w.label("승인된 ADB 연결에서 프리뷰·사진·동영상 제어", 12, muted), w.lp(top = 4))
        body.addView(Look.disclosure(context, "ADB CLI 설정", cliDetails), w.lp(top = 12))

        body.addView(w.label("Incident ZIP", 14, muted, true), w.lp(top = 20))
        body.addView(w.label("$markLabel: 직전 10초 + 이후 5초의 이벤트를 저장합니다.", 12, Color.WHITE), w.lp(top = 8))
        body.addView(recorderText, w.lp(top = 8))
        val exports = w.row()
        body.addView(exports, w.lp(top = 8))
        exports.addView(shareButton.apply { minimumHeight = w.dp(48) }, Look.buttonParams(0, 1f))
        exports.addView(w.button("ZIP 기록") { actions.incidents() }.apply { minimumHeight = w.dp(48) }, Look.buttonParams(0, 1f))
        val tools = w.row()
        body.addView(tools, w.lp(top = 8))
        tools.addView(w.button("카메라 다시 연결") { actions.retryPermission() }.apply { minimumHeight = w.dp(48) }, Look.buttonParams(0, 1f))
        tools.addView(w.button("측정 안내") { actions.notes() }.apply { minimumHeight = w.dp(48) }, Look.buttonParams(0, 1f))
        // The baseline reset that used to sit here cleared the Auto Check store. The benchmark baseline is a pointer
        // to one run and is cleared from the result screen, where the run it points at is on the screen.
        body.addView(w.label("사진 · 동영상: DCIM/HALCamera\nIncident ZIP에는 이미지 픽셀이 포함되지 않습니다", 10, muted), w.lp(top = 18))
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun cliSwitch(context: Context, actions: Actions) = Switch(context).apply {
        text = "ADB CLI 허용"
        textSize = 14f
        setTextColor(Look.onDark)
        minHeight = w.dp(48)
        isChecked = actions.cliEnabled
        setOnCheckedChangeListener { _, checked -> actions.cliEnabled = checked }
    }
}
