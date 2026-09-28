package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.*
import dev.halcamera.camera.*

/** A draft is committed only by Apply; dismissing a selector or this panel never changes the camera. */
object LiveStreamDialog {
    fun show(context: Context, support: LiveStreamSupport, current: LiveStreamSettings,
             actual: String, restore: (() -> Unit)?, apply: (LiveStreamSettings) -> Unit) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Look.dp(context, 20), Look.dp(context, 8), Look.dp(context, 20), Look.dp(context, 8))
            setBackgroundColor(Look.cameraCard)
        }
        fun text(value: String) = TextView(context).apply {
            text = value; textSize = 14f; setTextColor(Look.onDarkMuted)
            setPadding(0, Look.dp(context, 8), 0, Look.dp(context, 8)); content.addView(this)
        }
        text("적용 상태: $actual")
        text("Preview는 항상 켜집니다. YUV 사진은 JPEG으로 변환해 저장합니다. 개별 크기 지원과 출력 조합 지원은 다릅니다.")
        fun <T> choice(label: String, values: List<T>, selected: T, display: (T) -> String): () -> T {
            var value = selected
            val button = Button(context).apply {
                isAllCaps = false; minHeight = Look.dp(context, 48)
                setTextColor(Look.onDark); background = Look.touchBackground(context, Look.cameraCard, Look.cameraOutline)
                text = "$label: ${display(value)}"; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            button.setOnClickListener {
                AlertDialog.Builder(context).setTitle(label).setSingleChoiceItems(values.map(display).toTypedArray(), values.indexOf(value)) { dialog, index ->
                    value = values[index]; button.text = "$label: ${display(value)}"; dialog.dismiss()
                }.setNegativeButton("취소", null).show()
            }
            content.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Look.dp(context, 6) })
            return { value }
        }
        val preview = choice("Preview", support.preview, current.preview) { it.toString() }
        val yuv = choice("YUV", listOf(null) + support.yuv, current.yuv) { it?.toString() ?: "Off" }
        val jpeg = choice("JPEG", listOf(null) + support.jpeg, current.jpeg) { it?.toString() ?: "Off" }
        val fps = choice("프리뷰 FPS", listOf(null) + support.fps, current.fps) { it?.toString() ?: "Auto" }
        text("녹화는 Preview + Encoder 조합으로 전환합니다. 실제 구성 성공 여부는 녹화 시작 시 확인합니다. 아래 FPS는 일반 세션 설정이며 고속 세션은 제공하지 않습니다.")
        val video = choice("녹화 설정", listOf(null) + support.videos, current.video) { it?.toString() ?: "기본 설정 · H264 / 30 fps" }
        val scroll = ScrollView(context).apply { addView(content) }
        val builder = AlertDialog.Builder(context).setTitle("Live 스트림 · Camera2").setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("적용") { _, _ -> apply(LiveStreamSettings(preview(), yuv(), jpeg(), fps(), video())) }
        if (restore != null) builder.setNeutralButton("직전 정상 구성") { _, _ -> restore() }
        builder.show()
    }
}
