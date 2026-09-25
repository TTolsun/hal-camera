package dev.halcamera.benchmark.platform

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dev.halcamera.benchmark.domain.AppInfo
import dev.halcamera.benchmark.domain.DeviceInfo

/**
 * Reads the run environment and build identity that every run JSON records (docs/PLAN-BenchMarker-v0.3.md 6):
 * battery, charging, power save, thermal status, rotation, and the device and app identity blocks. This is a
 * platform adapter on purpose: the system-service and Build lookups live here so BenchmarkActivity only wires
 * screens, and the values cross into the domain as plain maps and value types.
 */
class EnvironmentProbe(private val activity: Activity) {

    /** The environment block recorded at run start and run end; the validity flags compare the two. */
    fun environment(): Map<String, Any?> = mapOf(
        "battery_pct" to batteryPercent(),
        "charging" to activity.getSystemService(BatteryManager::class.java)?.isCharging,
        "power_save" to activity.getSystemService(PowerManager::class.java)?.isPowerSaveMode,
        "rotation" to rotation()
    )

    fun thermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= 29) activity.getSystemService(PowerManager::class.java)?.currentThermalStatus else null

    fun powerSaveMode(): Boolean? = activity.getSystemService(PowerManager::class.java)?.isPowerSaveMode

    fun batteryPercent(): Int? =
        activity.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it > 0 }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: 0 else activity.windowManager.defaultDisplay.rotation

    fun deviceInfo(cameraId: String): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER, model = Build.MODEL, buildDisplay = Build.DISPLAY,
        buildIncremental = Build.VERSION.INCREMENTAL, fingerprint = Build.FINGERPRINT,
        vendorFingerprint = systemProperty("ro.vendor.build.fingerprint"),
        sdk = Build.VERSION.SDK_INT,
        securityPatch = if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else null,
        cameraInfoVersion = cameraInfoVersion(cameraId)
    )

    fun appInfo(): AppInfo {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        // Without this every run stores app.debuggable = null, which drops DEBUGGABLE_BUILD from the validity
        // flags and leaves BuildIdentity.sameAppBuild permanently unknown.
        val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return AppInfo(info.versionName ?: "", code, debuggable)
    }

    private fun cameraInfoVersion(cameraId: String): String? = if (Build.VERSION.SDK_INT < 28) null else try {
        activity.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)[CameraCharacteristics.INFO_VERSION]
    } catch (_: Exception) { null }

    /** getprop through a subprocess: SystemProperties is not public API and the vendor fingerprint has no getter. */
    private fun systemProperty(key: String): String? = try {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readLine() }?.trim()
        process.waitFor()
        value?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}
