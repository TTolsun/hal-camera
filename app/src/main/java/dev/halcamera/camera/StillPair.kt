package dev.halcamera.camera

/** Per-request buffer correlation. Callback and image delivery may arrive in either order. */
internal class StillPair<Y, J> {
    var timestamp: Long? = null
    private val yuv = linkedMapOf<Long, Y>()
    private val jpeg = linkedMapOf<Long, J>()

    fun accepts(timestamp: Long) = this.timestamp == null || this.timestamp == timestamp
    fun yuv(timestamp: Long, value: Y) = add(yuv, timestamp, value)
    fun jpeg(timestamp: Long, value: J) = add(jpeg, timestamp, value)
    private fun <T> add(target: LinkedHashMap<Long, T>, timestamp: Long, value: T) {
        if (!accepts(timestamp)) return
        target[timestamp] = value
        while (target.size > 8) target.remove(target.keys.first())
    }
    fun complete(): Pair<Y, J>? {
        val key = timestamp ?: return null
        val first = yuv[key] ?: return null
        val second = jpeg[key] ?: return null
        return first to second
    }
}
