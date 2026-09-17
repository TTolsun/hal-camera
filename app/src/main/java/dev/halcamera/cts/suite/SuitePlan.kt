package dev.halcamera.cts.suite

import dev.halcamera.cts.CtsCaseSpec
import dev.halcamera.ctsvendor.VendoredTest

/**
 * One line of the CTS suite checklist: either a case transcribed into Kotlin or a vendored CTS test method.
 * [key] is what the checklist stores and hands to the run screen; it carries the kind so the two id spaces
 * never collide. Pure Kotlin so the ordering, the keys and the estimates are testable.
 */
sealed class SuiteItem {
    abstract val key: String
    abstract val title: String
    /** The CTS class#method the item mirrors or runs. */
    abstract val source: String
    abstract val needsAudio: Boolean
    /** A rough duration for [cameras] cameras, or null when nobody has measured the item yet. */
    abstract fun estimateSeconds(cameras: Int): Int?

    data class Custom(val spec: CtsCaseSpec) : SuiteItem() {
        override val key: String get() = CUSTOM_PREFIX + spec.id
        override val title: String get() = spec.title
        override val source: String get() = spec.source
        override val needsAudio: Boolean get() = spec.needsAudio
        override fun estimateSeconds(cameras: Int): Int = spec.estimateSeconds(cameras)
    }

    data class Vendored(val test: VendoredTest) : SuiteItem() {
        override val key: String get() = VENDORED_PREFIX + test.id
        override val title: String get() = test.method
        override val source: String get() = test.source
        /** Every RecordingTest method may record; the permission is asked once for the whole run. */
        override val needsAudio: Boolean get() = true
        override fun estimateSeconds(cameras: Int): Int? = SuitePlan.vendoredSecondsPerCamera[test.method]?.let { it * cameras }
    }

    companion object {
        const val CUSTOM_PREFIX = "custom:"
        const val VENDORED_PREFIX = "vendored:"
    }
}

/** How much of the suite is estimated: the seconds of the items with an estimate and the count of those without. */
data class SuiteEstimate(val knownSeconds: Int, val unknown: Int)

/**
 * The checklist order, the selection resolved back to items, and the estimate line under the list. The run
 * order is the checklist order, custom cases first, whatever order the user ticked the boxes in.
 */
object SuitePlan {
    /**
     * Per-camera seconds of the vendored methods that have run on a device. testBasicRecording took 1 min 58 s
     * for four cameras on a Galaxy S25+; the other methods are listed without a number until they are measured.
     */
    val vendoredSecondsPerCamera: Map<String, Int> = mapOf("testBasicRecording" to 30)

    fun items(custom: List<CtsCaseSpec>, vendored: List<VendoredTest>): List<SuiteItem> =
        custom.map { SuiteItem.Custom(it) } + vendored.map { SuiteItem.Vendored(it) }

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
