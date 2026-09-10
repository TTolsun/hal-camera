package dev.halcamera.benchmark

import android.content.Context

/**
 * Remembers the last subject label so the start card can pre-fill it (docs/PLAN-BenchMarker-v0.3.md 8.2).
 *
 * The values are kept here rather than read back from the newest run file: a run JSON carries its full event
 * list and is close to a megabyte, and opening the start card must not wait on that. The note is intentionally
 * not remembered, because it describes one measurement ("어두운 방", "케이스 벗김") and carrying it into the next
 * run would label that run with a condition nobody re-checked.
 */
class SubjectPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun last(): SubjectLabel = SubjectLabel(
        subjectBuildLabel = prefs.getString(KEY_BUILD, null),
        subjectCommit = prefs.getString(KEY_COMMIT, null),
        subjectBranch = prefs.getString(KEY_BRANCH, null),
        note = null
    )

    fun save(label: SubjectLabel) {
        prefs.edit()
            .putString(KEY_BUILD, label.subjectBuildLabel)
            .putString(KEY_COMMIT, label.subjectCommit)
            .putString(KEY_BRANCH, label.subjectBranch)
            .apply()
    }

    companion object {
        private const val NAME = "benchmark_subject"
        private const val KEY_BUILD = "build_label"
        private const val KEY_COMMIT = "commit"
        private const val KEY_BRANCH = "branch"
    }
}
