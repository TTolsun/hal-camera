package dev.halcamera.benchmark.platform

import android.content.Context
import dev.halcamera.benchmark.domain.RunRetention

/**
 * Benchmark settings that outlive the screen (Settings → "Profiling data limit"). Which runs a limit deletes is
 * decided by [RunRetention]; this only remembers the number the developer picked.
 */
class BenchmarkPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var runLimit: Int
        get() = prefs.getInt(KEY_RUN_LIMIT, RunRetention.DEFAULT_LIMIT)
        set(value) = prefs.edit().putInt(KEY_RUN_LIMIT, value).apply()

    companion object {
        private const val NAME = "benchmark_settings"
        private const val KEY_RUN_LIMIT = "run_limit"
    }
}
