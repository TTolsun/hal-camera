package dev.halcamera.cts.suite

import dev.halcamera.ctsvendor.VendoredTest

/**
 * One line of the CTS checklist: a CTS test method vendored from AOSP. [key] is what the checklist stores,
 * the run screen receives and the CLI's `cts.run` takes; it keeps the `vendored:` prefix so a key never
 * reads as a bare method name. Pure Kotlin so the ordering, the keys and the estimates are testable.
 */
data class SuiteItem(val test: VendoredTest) {
    val key: String get() = PREFIX + test.id
    val title: String get() = test.method
    /** The CTS class#method the item runs. */
    val source: String get() = test.source
    /** Only RecordingTest methods record; the permission is asked once for the whole run when any item needs it. */
    val needsAudio: Boolean get() = test.simpleClass == "RecordingTest"
    /** A rough duration for [cameras] cameras, or null when nobody has measured the method yet. */
    fun estimateSeconds(cameras: Int): Int? = SuitePlan.vendoredSecondsPerCamera[test.method]?.let { it * cameras }

    companion object {
        const val PREFIX = "vendored:"
    }
}

/** How much of the suite is estimated: the seconds of the items with an estimate and the count of those without. */
data class SuiteEstimate(val knownSeconds: Int, val unknown: Int)

/**
 * The checklist order, the selection resolved back to items, and the estimate line under the list. The run
 * order is the checklist order, whatever order the user ticked the boxes in.
 */
object SuitePlan {
    /**
     * Per-camera seconds of the vendored methods that have run on a device. testBasicRecording took 1 min 58 s
     * for four cameras on a Galaxy S25+; the other methods are listed without a number until they are measured.
     */
    val vendoredSecondsPerCamera: Map<String, Int> = mapOf("testBasicRecording" to 30)

    fun items(vendored: List<VendoredTest>): List<SuiteItem> = vendored.map { SuiteItem(it) }

    /** The items of [all] whose key is in [keys], in the order of [all]. Unknown keys are dropped. */
    fun select(all: List<SuiteItem>, keys: Collection<String>): List<SuiteItem> = all.filter { it.key in keys }

    fun estimate(items: List<SuiteItem>, cameras: Int): SuiteEstimate {
        var known = 0
        var unknown = 0
        items.forEach { item ->
            val seconds = item.estimateSeconds(cameras)
            if (seconds == null) unknown++ else known += seconds
        }
        return SuiteEstimate(known, unknown)
    }

    /** "약 3분" for one item; "시간 미상" when nobody has measured it. */
    fun estimateLabel(item: SuiteItem, cameras: Int): String {
        val seconds = item.estimateSeconds(cameras) ?: return "시간 미상"
        return "약 ${minutes(seconds)}분"
    }

    /** The bottom-bar line: "선택 4개 · 약 10분", with " + 미상 2개" when some items carry no estimate. */
    fun summaryLine(items: List<SuiteItem>, cameras: Int): String {
        if (items.isEmpty()) return "선택한 항목이 없습니다"
        val estimate = estimate(items, cameras)
        val head = "선택 ${items.size}개"
        return when {
            estimate.unknown == 0 -> "$head · 약 ${minutes(estimate.knownSeconds)}분"
            estimate.knownSeconds == 0 -> "$head · 시간 미상"
            else -> "$head · 약 ${minutes(estimate.knownSeconds)}분 + 미상 ${estimate.unknown}개"
        }
    }

    /** Whole minutes, rounded up, never below one: a 20-second case still reads as "약 1분". */
    fun minutes(seconds: Int): Int = maxOf(1, (seconds + 59) / 60)
}
