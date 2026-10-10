package dev.halcamera.ui

import android.content.Context
import dev.halcamera.camera.PipRect
import dev.halcamera.camera.PipScene

/** Position survives source changes within a mode; mode entry clears its scope. */
internal class PipPositionStore(context: Context, private val scope: String) {
    private val prefs = context.getSharedPreferences("pip_positions", Context.MODE_PRIVATE)

    fun read(parent: String, fallback: PipRect): PipRect = PipScene.restore(
        fallback, prefs.getFloat("$scope.$parent.x", fallback.x), prefs.getFloat("$scope.$parent.y", fallback.y))

    fun save(parent: String, rect: PipRect) {
        prefs.edit().putFloat("$scope.$parent.x", rect.x).putFloat("$scope.$parent.y", rect.y).apply()
    }

    fun clear() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("$scope.") }.forEach(::remove) }.apply()
    }
}
