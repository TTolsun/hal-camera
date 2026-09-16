package dev.halcamera.cts

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.R
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look

/**
 * The CTS case list: one card per [CtsCatalog] entry, tapped to open [CtsCaseActivity] for that case. Nothing
 * here opens a camera; LIVE has already closed its session before this screen starts, and the case screen opens
 * its own when the user presses 실행.
 */
class CtsCaseListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Look.expertTile) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }

        val head = Look.row(this)
        head.addView(Look.text(this, "CTS 케이스", 22, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconButton(this, R.drawable.ic_action_close, "CTS 케이스 목록 닫기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(head)
        body.addView(Look.text(this, "케이스 하나를 골라 실행합니다. 각 케이스는 자기 카메라를 열고 닫으며, 실행 중에 중단할 수 있습니다.", 12, Look.onDarkMuted), lp(top = 4))
        body.addView(Look.text(this, CaseReportPresenter.DISCLAIMER, 11, Look.onDarkMuted), lp(top = 4))

        val cameras = runCatching { getSystemService(CameraManager::class.java).cameraIdList.size }.getOrDefault(0)
        CtsCatalog.cases.forEach { spec ->
            val card = Look.card(this, dark = true).apply {
                isClickable = true; isFocusable = true
                contentDescription = "${spec.title} 케이스 열기"
                setOnClickListener { open(spec) }
            }
            card.addView(Look.text(this, spec.title, 16, Look.onDark, bold = true))
            card.addView(Look.text(this, spec.source, 12, Look.onDarkMuted, mono = true), lp(top = 4))
            card.addView(Look.text(this, spec.summary(cameras), 12, Look.onDarkMuted), lp(top = 8))
            body.addView(card, lp(top = 12))
        }
    }

    private fun open(spec: CtsCaseSpec) {
        startActivity(Intent(this, CtsCaseActivity::class.java).putExtra(CtsCaseActivity.EXTRA_CASE_ID, spec.id))
    }

    private fun dp(v: Int) = Look.dp(this, v)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); gravity = Gravity.START }
}
