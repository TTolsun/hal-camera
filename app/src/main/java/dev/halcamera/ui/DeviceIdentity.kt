package dev.halcamera.ui

import android.content.Context
import android.os.Build

/** Local bench label, never a hardware identifier or part of the measurement contract. */
object DeviceIdentity {
    private fun prefs(context: Context) = context.getSharedPreferences("workbench", Context.MODE_PRIVATE)

    fun alias(context: Context): String = prefs(context).getString("device_alias", "").orEmpty()

    fun setAlias(context: Context, value: String) {
        prefs(context).edit().putString("device_alias", value.trim()).apply()
    }

    fun label(context: Context): String = alias(context).takeIf { it.isNotBlank() }
        ?.let { "$it · ${Build.MODEL}" } ?: Build.MODEL

    fun platform(): String = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"

    fun chipset(): String = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }
            .joinToString(" · ").ifBlank { Build.HARDWARE }
    } else Build.HARDWARE

    fun report(context: Context): String = """
        Device: ${label(context)}
        Platform: ${platform()}
        SoC: ${chipset()}
        Product: ${Build.PRODUCT}
        Board: ${Build.BOARD}
        Build: ${Build.DISPLAY}
        Fingerprint: ${Build.FINGERPRINT}
    """.trimIndent()
}
