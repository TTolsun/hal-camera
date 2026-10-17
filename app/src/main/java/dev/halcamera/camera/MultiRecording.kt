package dev.halcamera.camera

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.core.net.toUri

/** Handler-confined group transaction shared by the Camera2 and CameraX engines. */
internal class MultiRecording(
    private val context: Context,
    private val handler: Handler,
    private val compositors: Map<String, DeviceCompositor>,
    private val changed: (Boolean, ConcurrentMediaResult) -> Unit,
    private val saved: (Result<List<Uri>>) -> Unit,
) {
    var active = false
        private set
    var pending = false
        private set
    private var microphone: MultiAudio? = null
    private val startedNs = mutableMapOf<String, Long>()
    private var closing = false
    private val closeCallbacks = mutableListOf<() -> Unit>()

    fun start(audio: Boolean) {
        if (active || pending || closing) return
        pending = true
        val preparation = runCatching { if (audio) microphone = MultiAudio(context).also { microphone = it; it.start() } }
        if (preparation.isFailure) {
            microphone?.close(); microphone = null; pending = false
            changed(false, ConcurrentMediaResult("Microphone unavailable: ${preparation.exceptionOrNull()?.message}", true)); return
        }
        val name = MediaLibrary(context).name()
        val results = mutableMapOf<String, Result<Unit>>()
        compositors.forEach { (id, compositor) ->
            compositor.startVideo(name) { result -> handler.post {
                startedNs[id] = compositor.recordingStartNs
                results[id] = result
                if (results.size == compositors.size) {
                    pending = false; active = true
                    if (results.values.any { it.isFailure }) stop(false, "Could not start every camera recording")
                    else if (closing) stop()
                    else changed(true, ConcurrentMediaResult(if (audio) "Recording with microphone" else "Recording without audio"))
                }
            } }
        }
    }

    fun stop(save: Boolean = true, failure: String? = null) {
        if (!active || pending) return
        pending = true; active = false
        val audio = microphone
        val stopped = runCatching { audio?.stop() }
        val error = failure ?: stopped.exceptionOrNull()?.let { "Microphone recording failed: ${it.message}" }
        val results = mutableMapOf<String, Result<String>>()
        compositors.forEach { (id, compositor) ->
            val process: ((java.io.File) -> Unit)? = audio?.let { track -> { file -> track.addTo(file, startedNs.getValue(id)) } }
            compositor.stopVideo(save && error == null, process) { result -> handler.post {
                results[id] = result
                if (results.size == compositors.size) {
                    audio?.close(); microphone = null; pending = false
                    val uris = results.values.mapNotNull { it.getOrNull()?.let(Uri::parse) }
                    val outcome = runCatching {
                        check(error == null) { error.orEmpty() }
                        results.values.map { it.getOrThrow().toUri() }
                    }
                    // All camera files form one recording transaction. Never report silent/partial success.
                    if (outcome.isFailure) MediaLibrary(context).deleteAll(uris)
                    if (!closing) {
                        changed(false, ConcurrentMediaResult(outcome.fold(
                            { results.entries.joinToString("\n") { "Camera ${it.key}: ${it.value.getOrThrow()}" } },
                            { "Recording failed: ${it.message}" }), outcome.isFailure))
                        saved(outcome)
                    }
                    finishClose()
                }
            } }
        }
    }

    fun close(done: () -> Unit) {
        closing = true; closeCallbacks += done
        if (active && !pending) stop()
        finishClose()
    }

    private fun finishClose() {
        if (!closing || active || pending) return
        microphone?.close(); microphone = null
        closeCallbacks.toList().also { closeCallbacks.clear() }.forEach { it() }
    }
}
