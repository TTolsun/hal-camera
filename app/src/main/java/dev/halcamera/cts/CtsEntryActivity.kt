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
import dev.halcamera.R
import dev.halcamera.cts.vendored.VendoredCtsListActivity
import dev.halcamera.ctsvendor.VendoredCts
import dev.halcamera.ui.IconButton
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

        val head = Look.row(this)
        head.addView(Look.text(this, "CTS", 22, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconButton(this, R.drawable.ic_action_close, "CTS 화면 닫기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(head)
        body.addView(Look.text(this, "어느 방식으로 검사할지 고릅니다. 두 방식 모두 자기 카메라를 열고 닫으며, 결과는 앱 안에서만 봅니다.", 12, Look.onDarkMuted), lp(top = 4))

        body.addView(
            choice(
                title = "커스텀 케이스",
                detail = "CTS 케이스를 이 앱의 Kotlin으로 옮겨 적은 검사입니다. 카메라마다 단계별 PASS·FAIL과 첫 프레임 시간 같은 측정값을 함께 보여 주고, 실행 중에 중단할 수 있습니다.",
                enabled = true,
                reason = null
            ) { startActivity(Intent(this, CtsCaseListActivity::class.java)) },
            lp(top = 16)
        )
        val vendoredSupported = Build.VERSION.SDK_INT >= VendoredCts.MIN_SDK
        body.addView(
            choice(
                title = "CTS 원문 케이스",
                detail = "AOSP CTS의 Java 테스트 코드를 그대로 가져와 앱 안의 JUnit으로 실행합니다. 결과는 테스트 메서드 하나에 PASS·FAIL 하나이며, 실패 메시지는 CTS가 남긴 문장 그대로입니다.",
                enabled = vendoredSupported,
                reason = if (vendoredSupported) null else "Android ${VendoredCts.MIN_SDK} 이상에서만 실행할 수 있습니다. 이 기기는 API ${Build.VERSION.SDK_INT}입니다."
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
