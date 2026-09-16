package dev.halcamera.cts.vendored

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.halcamera.R
import dev.halcamera.ctsvendor.VendoredCatalog
import dev.halcamera.ctsvendor.VendoredCts
import dev.halcamera.ctsvendor.VendoredTest
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look

/**
 * The vendored CTS list: one card per `@Test` method of every class in [VendoredCatalog], grouped by class and
 * tapped to open [VendoredCaseActivity]. Nothing here opens a camera.
 */
class VendoredCtsListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VendoredCts.install(this)
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
        head.addView(Look.text(this, "CTS 원문 케이스", 22, Look.onDark, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconButton(this, R.drawable.ic_action_close, "CTS 원문 케이스 목록 닫기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(head)
        body.addView(Look.text(this, "테스트 메서드 하나를 골라 실행합니다. 각 메서드는 카메라 전부를 차례로 검사하며, 중단은 카메라를 닫아 테스트를 실패시키는 방식입니다.", 12, Look.onDarkMuted), lp(top = 4))
        body.addView(Look.text(this, VendoredReportPresenter.DISCLAIMER, 11, Look.onDarkMuted), lp(top = 4))

        VendoredCatalog.tests().groupBy { it.className }.forEach { (className, tests) ->
            body.addView(Look.text(this, className, 12, Look.onDarkMuted, mono = true), lp(top = 18))
            tests.forEach { test ->
                val card = Look.card(this, dark = true).apply {
                    isClickable = true; isFocusable = true
                    contentDescription = "${test.source} 열기"
                    setOnClickListener { open(test) }
                }
                card.addView(Look.text(this, test.method, 16, Look.onDark, bold = true))
                body.addView(card, lp(top = 8))
            }
        }
    }

    private fun open(test: VendoredTest) {
        startActivity(Intent(this, VendoredCaseActivity::class.java).putExtra(VendoredCaseActivity.EXTRA_TEST_ID, test.id))
    }

    private fun dp(v: Int) = Look.dp(this, v)
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); gravity = Gravity.START }
}
