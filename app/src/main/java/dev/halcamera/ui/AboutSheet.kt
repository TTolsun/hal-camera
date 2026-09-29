package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import dev.halcamera.R

/** Runway-inspired app identity, retaining the original robot artwork and contact actions. */
object AboutSheet {
    const val AUTHOR = "KH"
    const val EMAIL = "kh_87.kim@samsung.com"
    private const val TAGLINE = "Camera inspection, CTS checks, and benchmarks."
    private const val EASTER_EGG_TAPS = 5

    fun show(context: Context) {
        val themed = ContextThemeWrapper(context, R.style.LabDialogTheme)
        fun dp(value: Int) = Look.dp(themed, value)
        fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("?")
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(24))
        }
        root.addView(Look.text(themed, "ABOUT / HAL CAMERA", 11, Look.runwayMuted).apply { letterSpacing = 0.08f })
        root.addView(ImageView(themed).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { topMargin = dp(24) })
        root.addView(Look.text(themed, "HAL CAM", 36, Look.runwayInk).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, params(20))
        root.addView(Look.text(themed, TAGLINE, 17, Look.runwayInk), params(8))
        root.addView(Look.text(themed, "Camera2 · CameraX", 13, Look.runwayMuted), params(16))
        root.addView(View(themed).apply { setBackgroundColor(Look.hairline) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(24); bottomMargin = dp(12) })
        root.addView(Look.text(themed, "Version $version", 13, Look.runwayMuted, mono = true).apply {
            minimumHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            var taps = 0
            setOnClickListener {
                if (++taps >= EASTER_EGG_TAPS) {
                    taps = 0
                    Toast.makeText(context, "made by $AUTHOR", Toast.LENGTH_SHORT).show()
                }
            }
        }, params())
        root.addView(Look.ghostButton(themed, "$AUTHOR\n$EMAIL") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:$EMAIL"))) }
                .onFailure { Toast.makeText(context, EMAIL, Toast.LENGTH_LONG).show() }
        }.apply {
            setTextColor(Look.runwayInk)
            textSize = 13f
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            background = RippleDrawable(ColorStateList.valueOf(0x18000000), null, Look.pill(themed, Color.WHITE))
            contentDescription = "Email $AUTHOR, $EMAIL"
        }, params())
        val dialog = AlertDialog.Builder(themed).create().apply {
            setView(ScrollView(themed).apply { addView(root) }, 0, 0, 0, 0)
        }
        root.addView(Look.primaryButton(themed, "Close") { dialog.dismiss() }.apply {
            background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), Look.pill(themed, Color.BLACK), Look.pill(themed, Color.WHITE))
        }, params(24))
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(8).toFloat()
            })
            setLayout(minOf(dp(480), themed.resources.displayMetrics.widthPixels - dp(32)), -2)
        }
    }
}
