package dev.halcamera.camera

import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one video snapshot from submission through storage. A capture deadline or session teardown may end
 * WAITING, but cannot cancel SAVING: the image has already arrived and storage owns its final result.
 * Late camera callbacks cannot answer a request twice or start a second save. Free of Android dependencies.
 */
internal class SnapshotRequest<T>(private val complete: (Result<T>) -> Unit) {
    private enum class Phase { WAITING, SAVING, DONE }
    private val phase = AtomicReference(Phase.WAITING)

    fun acceptImage(): Boolean = phase.compareAndSet(Phase.WAITING, Phase.SAVING)

    fun failCapture(error: Exception) {
        if (phase.compareAndSet(Phase.WAITING, Phase.DONE)) complete(Result.failure(error))
    }

    fun finishSave(result: Result<T>) {
        if (phase.compareAndSet(Phase.SAVING, Phase.DONE)) complete(result)
    }
}
