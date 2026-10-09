package dev.halcamera.camera

/** Correlates both physical buffers with the timestamps from ONE logical capture result. */
internal class DualStillPair<T> {
    private var expected: Pair<Long, Long>? = null
    private var logicalTimestamp: Long? = null
    private val buffers = listOf(linkedMapOf<Long, T>(), linkedMapOf<Long, T>())
    fun result(first: Long, second: Long, logical: Long? = null) {
        expected = first to second
        logicalTimestamp = logical
    }
    fun image(index: Int, timestamp: Long, value: T) {
        buffers[index][timestamp] = value
        while (buffers[index].size > 4) buffers[index].remove(buffers[index].keys.first())
    }
    fun ready(): Pair<T, T>? {
        val (a, b) = expected ?: return null
        return (buffers[0][a] ?: buffers[0][logicalTimestamp] ?: return null) to
            (buffers[1][b] ?: buffers[1][logicalTimestamp] ?: return null)
    }
    fun diagnostic() = "expected=$expected logical=$logicalTimestamp received=${buffers.map { it.keys }}"
}
