package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import dev.halcamera.R

/**
 * App identity: name, version, one-line purpose and the author's contact. A dialog rather than an Activity so it
 * needs no manifest entry, no docs element and can be opened from any screen with [show].
 */
object AboutSheet {
    const val AUTHOR = "KH"
    const val EMAIL = "kh_87.kim@samsung.com"
    private const val TAGLINE = "HAL instrumentation & benchmark"
    private const val STACKS = "Camera2 · CameraX"
    private const val EASTER_EGG_TAPS = 5

    fun show(context: Context) {
        val d = Look.dp(context, 16)
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("?")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d * 3 / 2, d * 3 / 2, d * 3 / 2, d)
        }

        // Header: launcher icon, app name, version. Sizes follow Material headlineSmall / bodyMedium.
        root.addView(Look.row(context).apply {
            addView(ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = null // decorative
            }, LinearLayout.LayoutParams(Look.dp(context, 48), Look.dp(context, 48)).apply { marginEnd = d })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Look.text(context, "HAL CAM", 24, Look.ink, bold = true))
                addView(Look.text(context, version, 13, Look.inkMuted, mono = true).apply {
                    // Settings-app convention: tapping the version repeatedly reveals the author card.
                    var taps = 0
                    setOnClickListener {
                        if (++taps >= EASTER_EGG_TAPS) {
                            taps = 0
                            Toast.makeText(context, "made by $AUTHOR", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            })
        })

        root.addView(
            Look.text(context, TAGLINE, 14, Look.ink),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d }
        )
        root.addView(Look.text(context, STACKS, 13, Look.inkMuted))

        // Signature block under a hairline: name, then the address in mono. Tapping it opens the mail client.
        root.addView(View(context).apply { setBackgroundColor(Look.hairline) },
            LinearLayout.LayoutParams(-1, Look.dp(context, 1)).apply { topMargin = d })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Email $AUTHOR"
            addView(Look.text(context, AUTHOR, 14, Look.ink, bold = true).apply { letterSpacing = 0.08f })
            addView(Look.text(context, EMAIL, 13, Look.inkMuted, mono = true))
            setOnClickListener { open(context, "mailto:$EMAIL") }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d * 3 / 4 })

        // The system dialog theme follows dark mode; the sheet uses Look.ink on Look.card, so pin the surface.
        val dialog = AlertDialog.Builder(context).setView(ScrollView(context).apply { addView(root) }).create()
        dialog.window?.setBackgroundDrawable(Look.cardBackground(context))

        root.addView(Look.row(context).apply {
            gravity = Gravity.END
            addView(Look.ghostButton(context, "Close") { dialog.dismiss() })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d })

        dialog.show()
    }

    private fun open(context: Context, uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            .onFailure { Toast.makeText(context, uri.removePrefix("mailto:"), Toast.LENGTH_LONG).show() }
    }
}
