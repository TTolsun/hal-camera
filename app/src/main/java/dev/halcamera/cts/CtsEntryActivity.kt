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
        setTheme(dev.halcamera.R.style.LabTheme)
        super.onCreate(savedInstanceState)
        Look.configureLabWindow(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Look.canvas) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + Look.pageMargin(view, bars.left + bars.right), bars.top + dp(16), bars.right + Look.pageMargin(view, bars.left + bars.right), bars.bottom + dp(16))
            insets
        }

        body.addView(Look.titleBar(this, "CTS", 22, "이전 화면으로 돌아가기") { finish() })
        body.addView(Look.text(this, "앱 내 검사 · 공식 CTS 인증 결과 아님", 12, Look.inkMuted), lp(top = 4))

        body.addView(
            choice(
                title = "Custom Checks",
                detail = "단계별 판정 · 측정값",
                enabled = true,
                reason = null
            ) { startActivity(Intent(this, CtsCaseListActivity::class.java)) },
            lp(top = 16)
        )
        val vendoredSupported = Build.VERSION.SDK_INT >= VendoredCts.MIN_SDK
        body.addView(
            choice(
                title = "AOSP CTS",
                detail = "AOSP 원문 실행 · 실패 메시지",
                enabled = vendoredSupported,
                reason = if (vendoredSupported) null else "API ${VendoredCts.MIN_SDK}+ 필요 · 현재 API ${Build.VERSION.SDK_INT}"
            ) { startActivity(Intent(this, VendoredCtsListActivity::class.java)) },
            lp(top = 12)
        )
    }

    private fun choice(title: String, detail: String, enabled: Boolean, reason: String?, open: () -> Unit): LinearLayout {
        val card = Look.card(this, dark = false).apply {
            isClickable = enabled; isFocusable = enabled
            contentDescription = if (enabled) "$title 열기. $detail" else "$title · $reason"
            isEnabled = enabled
            background = Look.touchBackground(this@CtsEntryActivity, Look.labSurface, Look.hairline)
            alpha = if (enabled) 1f else 0.5f
            if (enabled) setOnClickListener { open() }
        }
        val heading = Look.row(this)
        heading.addView(Look.text(this, title, 17, Look.ink), LinearLayout.LayoutParams(0, -2, 1f))
        if (enabled) heading.addView(android.widget.ImageView(this).apply {
            setImageResource(dev.halcamera.R.drawable.ic_action_next)
            imageTintList = android.content.res.ColorStateList.valueOf(Look.primary)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        card.addView(heading)
        card.addView(Look.text(this, detail, 12, Look.inkMuted), lp(top = 6))
        if (reason != null) card.addView(Look.text(this, reason, 12, Look.warningInk), lp(top = 6))
        return card
    }

    private fun dp(v: Int) = Look.dp(this, v)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); gravity = Gravity.START }
}
