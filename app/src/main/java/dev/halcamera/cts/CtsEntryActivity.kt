package dev.halcamera.cts

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.cts.vendored.VendoredCtsListActivity
import dev.halcamera.ctsvendor.VendoredCts
import dev.halcamera.ui.Look

/**
 * The first CTS screen: the user chooses between the cases transcribed into Kotlin ([CtsCaseListActivity]) and
 * the CTS test methods vendored from AOSP and run by JUnit inside the app ([VendoredCtsListActivity]). The two
 * differ in what they report, so the choice is made here, before any camera is opened.
 */
class CtsEntryActivity : ComponentActivity() {
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

        body.addView(Look.titleBar(this, "CTS", 22, "카메라로 돌아가기") { finish() })
        body.addView(Look.text(this, "앱 내 검사 · 공식 CTS 인증 결과 아님", 12, Look.onDarkMuted), lp(top = 4))

        body.addView(
            choice(
                title = "커스텀 케이스",
                detail = "CTS 기반 자체 검사 · 카메라별 단계 판정·측정값",
                enabled = true,
                reason = null
            ) { startActivity(Intent(this, CtsCaseListActivity::class.java)) },
            lp(top = 16)
        )
        val vendoredSupported = Build.VERSION.SDK_INT >= VendoredCts.MIN_SDK
        body.addView(
            choice(
                title = "CTS 원문 케이스",
                detail = "AOSP CTS 원문 실행 · 메서드별 판정·실패 메시지",
                enabled = vendoredSupported,
                reason = if (vendoredSupported) null else "API ${VendoredCts.MIN_SDK}+ 필요 · 현재 API ${Build.VERSION.SDK_INT}"
            ) { startActivity(Intent(this, VendoredCtsListActivity::class.java)) },
            lp(top = 12)
        )
    }

    private fun choice(title: String, detail: String, enabled: Boolean, reason: String?, open: () -> Unit): LinearLayout {
        val card = Look.card(this, dark = true).apply {
            isClickable = enabled; isFocusable = enabled
            contentDescription = if (enabled) "$title 열기" else "$title · $reason"
            alpha = if (enabled) 1f else 0.5f
            if (enabled) setOnClickListener { open() }
        }
        card.addView(Look.text(this, title, 17, Look.onDark, bold = true))
        card.addView(Look.text(this, detail, 12, Look.onDarkMuted), lp(top = 6))
        if (reason != null) card.addView(Look.text(this, reason, 12, Look.statusWarn), lp(top = 6))
        return card
    }

    private fun dp(v: Int) = Look.dp(this, v)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); gravity = Gravity.START }
}
