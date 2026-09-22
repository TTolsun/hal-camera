package dev.halcamera.benchmark.platform

import android.content.Context
import java.util.UUID

/**
 * Optional raw provenance stored in every run JSON: installation-scoped, stable across app updates, reset by
 * app-data deletion. It says whether two runs came from the same install, which is weaker evidence than a
 * hardware identifier and is never treated as one.
 */
object DeviceInstance {
    @Synchronized fun id(context: Context): String {
        val preferences = context.getSharedPreferences("profile-device", Context.MODE_PRIVATE)
        preferences.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString("id", id).commit()) { "기기 설치 ID를 저장하지 못했습니다." }
        return id
    }
}
