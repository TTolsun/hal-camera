package dev.halcamera.camera

/** Wait for every source, including late failures, before exposing or cleaning up a photo group. */
internal fun <P> capturePipBatch(captures: List<((Result<P>) -> Unit) -> Unit>,
    discard: (List<P>, () -> Unit) -> Unit, done: (Result<List<P>>) -> Unit) {
    require(captures.isNotEmpty())
    val lock = Any()
    val results = MutableList<Result<P>?>(captures.size) { null }
    var completed = false
    fun receive(index: Int, result: Result<P>) {
        val settled = synchronized(lock) {
            if (results[index] != null || completed) return
            results[index] = result
            if (results.any { it == null }) return
            completed = true
            results.map { checkNotNull(it) }
        }
        val saved = settled.mapNotNull { it.getOrNull() }
        val failure = settled.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure == null) done(Result.success(saved))
        else discard(saved) { done(Result.failure(failure)) }
    }
    captures.forEachIndexed { index, capture ->
        try { capture { receive(index, it) } }
        catch (e: Exception) { receive(index, Result.failure(e)) }
    }
}
