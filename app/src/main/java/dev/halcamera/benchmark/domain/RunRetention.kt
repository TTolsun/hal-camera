package dev.halcamera.benchmark.domain

/**
 * Which stored runs an over-limit directory should delete (Settings → "Profiling data limit").
 *
 * Pure so the two rules live in one testable place: the newest [limit] runs stay, and a baseline run is never
 * deleted, because removing it would silently change what every later run is measured against. A protected run
 * still counts toward the limit — the cap is "how much history the developer wants around", not "how many
 * deletable files exist".
 */
object RunRetention {

    /** The choices Settings offers. [UNLIMITED] keeps everything, which was the only behaviour before v0.3. */
    val OPTIONS = listOf(10, 20, 50, 100, UNLIMITED)
    const val UNLIMITED = 0
    const val DEFAULT_LIMIT = UNLIMITED

    fun label(limit: Int): String = if (limit == UNLIMITED) "Unlimited" else limit.toString()

    /** Run ids to delete, oldest first. [runIdsNewestFirst] is the store's name order, which is time order. */
    fun toDelete(runIdsNewestFirst: List<String>, protected: Set<String>, limit: Int): List<String> {
        if (limit == UNLIMITED || runIdsNewestFirst.size <= limit) return emptyList()
        return runIdsNewestFirst.drop(limit).filterNot { it in protected }.reversed()
    }
}
