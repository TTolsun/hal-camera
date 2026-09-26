package dev.halcamera.benchmark.domain

/**
 * Which stored runs an over-limit directory should delete (Settings → "Data limit").
 *
 * Pure so the two rules live in one testable place: the newest [limit] runs stay, and a baseline run is never
 * deleted, because removing it would silently change what every later run is measured against. A protected run
 * still counts toward the limit — the cap is "how much history the developer wants around", not "how many
 * deletable files exist".
 */
object RunRetention {

    const val UNLIMITED = 0
    const val DEFAULT_LIMIT = UNLIMITED

    /** Distance between neighbouring stops, in runs. */
    const val STEP = 10
    const val MAX_LIMIT = 100

    /**
     * The stops the Settings slider offers: 10, 20 … 100, then [UNLIMITED].
     *
     * Every step is the same [STEP] runs, so moving the thumb one notch always means the same thing. An
     * earlier 10 / 20 / 50 / 100 list put unequal jumps at equal distances, which is exactly the promise a
     * slider makes and breaks. Unlimited is not a number and cannot sit on the scale, so it is the one stop
     * past the end, labelled ∞.
     */
    val OPTIONS: List<Int> = (STEP..MAX_LIMIT step STEP).toList() + UNLIMITED

    fun label(limit: Int): String = if (limit == UNLIMITED) "무제한" else limit.toString()

    /** The value in the Settings dialog, with its unit: "10" alone did not say ten of what. */
    fun valueLabel(limit: Int): String = if (limit == UNLIMITED) "무제한" else "${limit}개"

    /** The ends under the slider. Only the two ends are labelled, so the word fits where ∞ used to stand. */
    fun tickLabel(limit: Int): String = label(limit)

    /**
     * What a limit would do, beside the value: how many runs are stored now and how many applying it deletes.
     * The dialog asked for a number without the one fact the choice depends on.
     */
    fun impactLine(stored: Int, deleting: Int): String =
        if (deleting == 0) "현재 ${stored}개" else "현재 ${stored}개 · ${deleting}개 삭제"

    /** Run ids to delete, oldest first. [runIdsNewestFirst] is the store's name order, which is time order. */
    fun toDelete(runIdsNewestFirst: List<String>, protected: Set<String>, limit: Int): List<String> {
        if (limit == UNLIMITED || runIdsNewestFirst.size <= limit) return emptyList()
        return runIdsNewestFirst.drop(limit).filterNot { it in protected }.reversed()
    }
}
