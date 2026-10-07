package dev.halcamera.camera

/** Per-request buffer correlation. Callback and image delivery may arrive in either order. */
internal class StillPair<Y, J>(private val capacity: Int = 8) {
    var timestamp: Long? = null
        set(value) {
            field = value
            if (value != null) {
                yuv.keys.retainAll(setOf(value))
                jpeg.keys.retainAll(setOf(value))
            }
        }
    private val yuv = linkedMapOf<Long, Y>()
    private val jpeg = linkedMapOf<Long, J>()

    fun accepts(timestamp: Long) = this.timestamp == null || this.timestamp == timestamp
    fun clear() { yuv.clear(); jpeg.clear() }
    fun yuv(timestamp: Long, value: Y) = add(yuv, timestamp, value)
    fun jpeg(timestamp: Long, value: J) = add(jpeg, timestamp, value)
    private fun <T> add(target: LinkedHashMap<Long, T>, timestamp: Long, value: T) {
        if (!accepts(timestamp)) return
        target[timestamp] = value
        while (target.size > capacity) target.remove(target.keys.first())
    }
    fun complete(): Pair<Y, J>? {
        val key = timestamp ?: return null
        val first = yuv[key] ?: return null
        val second = jpeg[key] ?: return null
        return first to second
    }
    /** A disabled output is not awaited. Enabled outputs must match the capture's sensor timestamp. */
    fun selected(requireYuv: Boolean, requireJpeg: Boolean): Pair<Y?, J?>? {
        if (!requireYuv && !requireJpeg) return null
        val key = timestamp ?: return null
        val first = yuv[key]
        val second = jpeg[key]
        if ((requireYuv && first == null) || (requireJpeg && second == null)) return null
        return (if (requireYuv) first else null) to (if (requireJpeg) second else null)
    }
}
