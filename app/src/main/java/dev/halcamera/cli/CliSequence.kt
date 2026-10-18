package dev.halcamera.cli

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.halcamera.camera.*

/** Sequential saves retain partial artifacts on cancellation and failure. */
class CliSequence(private val context: Context, private val updateRecord: (String, Map<String, Any?>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var run: BurstRun<PhotoResult>? = null
    private var cancelled = false

    fun start(command: CliCommand, camera: MediaCapture?, base: LiveControls, support: LiveControlSupport,
              done: (Map<String, Any?>, List<CliArtifact>, CliFailure?) -> Unit) {
        if (camera == null) throw CliFailure("PREFLIGHT_FAILED", "Camera is unavailable")
        val bracket = command.command == "bracket"
        val tuning = camera as? LiveTuning
        if (bracket && (support.evRange == null || tuning == null || base.manual.exposure != null || base.aeLock || base.flash != FlashMode.OFF))
            throw CliFailure("PREFLIGHT_FAILED", "AEB needs EV support, automatic exposure, AE unlocked and flash OFF")
        val count = if (bracket) 3 else command.options?.values?.get("count")?.toInt() ?: 3
        cancelled = false
        val onProgress: (BurstRun<PhotoResult>) -> Unit = { progress ->
            progress(command.id, mapOf("saved" to progress.saved, "total" to count, "phase" to "capturing"))
        }
        val onFinished: (BurstSummary<PhotoResult>) -> Unit = { summary ->
                run = null
                val photos = summary.shots.mapNotNull { it.result.getOrNull() }
                val artifacts = photos.flatMap { photo -> photo.artifacts.ifEmpty { photo.uris.mapIndexed { i, uri -> PhotoArtifact("${photo.name}_$i.jpg", "image/jpeg", uri) } }.map { CliArtifact(it.name, it.mime, it.uri) } }.toMutableList()
                val result = cliResult("saved" to summary.saved, "requested" to count, "cancelled" to cancelled,
                    "stop_reason" to summary.stopReason, "shots" to summary.shots.map {
                        mapOf("request_id" to it.requestId, "saved" to it.result.isSuccess, "error" to it.result.exceptionOrNull()?.message)
                    })
                val failure = if (cancelled) CliFailure("CANCELLED", "Stopped; saved files remain available with fetch")
                    else if (summary.saved != count) CliFailure("CAPTURE_FAILED", "Not all photos saved; use fetch to recover saved files") else null
                if (bracket) {
                    val fusion = BracketFusion(context)
                    fusion.fuseSaved(summary, started = { progress(command.id, mapOf("saved" to count, "total" to count, "phase" to "HDR")) }) { outcome ->
                        outcome.artifact?.let { artifacts += CliArtifact(it.name, it.mime, it.uri) }
                        fusion.close()
                        done(result + cliResult("hdr" to outcome.status, "hdr_error" to outcome.error, "hdr_reason" to outcome.reason,
                            "artifact_count" to artifacts.size, "cancelled" to cancelled), artifacts,
                            if (cancelled) CliFailure("CANCELLED", "Stopped; saved files remain available with fetch") else failure)
                    }
                } else {
                    done(result + ("artifact_count" to artifacts.size), artifacts, failure)
                }
        }
        val schedule: (Long, () -> Unit) -> Unit = { delay, block -> main.postDelayed(block, delay) }
        run = if (bracket) BracketCapture.create(command.id, base, support, SystemClock::elapsedRealtime, schedule,
            { !camera.mediaBusy }, camera::capturePhoto, tuning!!::setControls, progress = onProgress, finished = onFinished)
        else BurstRun(command.id, count, command.options?.values?.get("interval_ms")?.toLong() ?: 0,
            SystemClock::elapsedRealtime, schedule, { !camera.mediaBusy }, camera::capturePhoto, onProgress, onFinished,
            name = { "burst-${command.id}-${it + 1}" })
        run!!.start()
    }

    private fun progress(id: String, progress: Map<String, Any?>) {
        // Progress is advisory. Keep draining in-flight saves even if the status disk is full;
        // the final durable completion reports STORE_FAILED without abandoning saved photos.
        runCatching { updateRecord(id, mapOf("progress" to progress)) }
            .onFailure { android.util.Log.w("CliSequence", "Cannot write progress", it) }
    }

    fun cancel() { cancelled = true; run?.cancel("Cancelled") }
}
