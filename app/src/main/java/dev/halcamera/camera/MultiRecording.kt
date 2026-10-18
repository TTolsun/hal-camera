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
    private val outcomes: (Map<String, Result<Uri>>) -> Unit = {},
) {
    var active = false
        private set
    var pending = false
        private set
    private var microphone: MultiAudio? = null
    private val audioIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var closing = false
    private val closeCallbacks = mutableListOf<() -> Unit>()

    fun start(audio: Boolean) {
        if (active || pending || closing) return
        pending = true
        audioIo.execute {
            val preparation = runCatching {
                if (!audio) null else MultiAudio(context).also { track ->
                    try { track.start() } catch (error: Throwable) { track.close(); throw error }
                }
            }
            handler.post {
                microphone = preparation.getOrNull()
                if (preparation.isFailure) {
                    pending = false
                    if (!closing) changed(false, ConcurrentMediaResult("Microphone unavailable: ${preparation.exceptionOrNull()?.message}", true))
                    finishClose()
                } else if (closing) {
                    val prepared = microphone
                    audioIo.execute {
                        prepared?.close()
                        handler.post { microphone = null; pending = false; finishClose() }
                    }
                } else startCameras(audio)
            }
        }
    }

    private fun startCameras(audio: Boolean) {
        val name = MediaLibrary(context).name()
        val results = mutableMapOf<String, Result<Unit>>()
        compositors.forEach { (id, compositor) ->
            compositor.startVideo(name) { result -> handler.post {
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
        val inputs = mutableMapOf<String, Result<Unit>>()
        compositors.forEach { (id, compositor) -> compositor.endVideoInput { result -> handler.post {
            inputs[id] = result
            if (inputs.size == compositors.size) finishRecording(save,
                failure ?: inputs.values.firstNotNullOfOrNull { it.exceptionOrNull()?.let { error -> error.message ?: "Video input shutdown failed" } })
        } } }
    }

    private fun finishRecording(save: Boolean, failure: String?) {
        val audio = microphone
        audioIo.execute {
            val stopped = runCatching { audio?.stop() }
            val error = failure ?: stopped.exceptionOrNull()?.let { "Microphone recording failed: ${it.message}" }
            handler.post { publish(save,audio,error) }
        }
    }

    private fun publish(save: Boolean, audio: MultiAudio?, error: String?) {
        val results = mutableMapOf<String, Result<String>>()
        compositors.forEach { (id, compositor) ->
            val process: ((java.io.File, Long) -> Unit)? = audio?.let { track -> { file, origin -> track.addTo(file,origin) } }
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
                    outcomes(results.mapValues { (_, result) -> outcome.exceptionOrNull()?.let { Result.failure(it) }
                        ?: result.map { it.toUri() } })
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
        audioIo.shutdown()
        closeCallbacks.toList().also { closeCallbacks.clear() }.forEach { it() }
    }
}
