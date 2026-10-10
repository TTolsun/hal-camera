package dev.halcamera.camera

/** Serializes media work and drains it before the owning camera session disposes its surfaces. */
internal class PipMedia<P, V>(
    private val dispatch: (() -> Unit) -> Unit,
    private val available: () -> Boolean,
    private val capturePhoto: (String?, (Result<P>) -> Unit) -> Unit,
    private val startVideo: (Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val stopVideo: ((Result<V>) -> Unit) -> Unit,
    private val recordingChanged: (Boolean) -> Unit,
    private val notice: (String) -> Unit,
) {
    private enum class State { IDLE, CAPTURING, STARTING, RECORDING, STOPPING }
    @Volatile private var state = State.IDLE
    @Volatile private var closing = false
    val busy get() = closing || state != State.IDLE
    private var videoDone: ((Result<V>) -> Unit)? = null
    private val closeCallbacks = mutableListOf<() -> Unit>()

    fun capture(requestId: String?, done: (Result<P>) -> Unit) = dispatch {
        if (busy || !available()) done(Result.failure(IllegalStateException("Camera busy")))
        else {
            state = State.CAPTURING
            capturePhoto(requestId) { result -> dispatch {
                state = State.IDLE
                done(result)
                drain()
            } }
        }
    }

    fun start(audio: Boolean, started: () -> Unit, done: ((Result<V>) -> Unit)?) = dispatch {
        if (busy || !available()) done?.invoke(Result.failure(IllegalStateException("Camera busy")))
        else {
            state = State.STARTING
            videoDone = done
            startVideo(audio) { result -> dispatch {
                if (result.isSuccess) {
                    state = State.RECORDING
                    if (closing) stopRecording() else { recordingChanged(true); started() }
                } else {
                    state = State.IDLE
                    val completion = videoDone; videoDone = null
                    completion?.invoke(Result.failure(result.exceptionOrNull()!!))
                    if (!closing) notice("Video not saved")
                    drain()
                }
            } }
        }
    }

    fun stop() = dispatch { if (state == State.RECORDING) stopRecording() }

    private fun stopRecording() {
        state = State.STOPPING
        stopVideo { result -> dispatch {
            state = State.IDLE
            val completion = videoDone; videoDone = null
            recordingChanged(false)
            completion?.invoke(result)
            if (!closing) notice(if (result.isSuccess) "Saved video" else "Video not saved")
            drain()
        } }
    }

    fun close(done: () -> Unit) = dispatch {
        closing = true
        closeCallbacks += done
        if (state == State.RECORDING) stopRecording()
        drain()
    }

    private fun drain() {
        if (closing && state == State.IDLE) {
            closeCallbacks.toList().also { closeCallbacks.clear() }.forEach { it() }
        }
    }
}
