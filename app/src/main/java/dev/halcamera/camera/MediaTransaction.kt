package dev.halcamera.camera

/** Publishes a group only after writing succeeds; all owned entries are removed on failure. */
internal class MediaTransaction<T>(private val publish: (T) -> Unit, private val delete: (T) -> Unit) {
    private val entries = mutableListOf<T>()
    fun own(entry: T): T = entry.also { entries += it }

    fun <R> run(write: MediaTransaction<T>.() -> R): R = try {
        val result = write()
        entries.forEach(publish)
        result
    } catch (e: Exception) {
        entries.forEach { runCatching { delete(it) } }
        throw e
    }
}
