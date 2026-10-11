package dev.halcamera.cli

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.halcamera.camera.*
import org.json.JSONObject

/** Sequential saves retain partial artifacts on cancellation and failure. */
class CliSequence(private val context: Context, private val updateRecord: (String, (JSONObject) -> Unit) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var run: BurstRun<PhotoResult>? = null
    private var cancelled = false

    fun start(command: CliCommand, camera: MediaCapture?, base: LiveControls, support: LiveControlSupport,
              done: (JSONObject, List<CliArtifact>, CliFailure?) -> Unit) {
        if (camera == null) throw CliFailure("PREFLIGHT_FAILED", "Camera is unavailable")
        val bracket = command.command == "bracket"
        val tuning = camera as? LiveTuning
        if (bracket && (support.evRange == null || tuning == null || base.manual.exposure != null || base.aeLock || base.flash != FlashMode.OFF))
            throw CliFailure("PREFLIGHT_FAILED", "AEB needs EV support, automatic exposure, AE unlocked and flash OFF")
        val count = if (bracket) 3 else command.options?.values?.get("count")?.toInt() ?: 3
        cancelled = false
        val onProgress: (BurstRun<PhotoResult>) -> Unit = { progress -> progress(command.id) {
                it.put("progress", JSONObject().put("saved", progress.saved).put("total", count).put("phase", "capturing"))
            } }
        val onFinished: (BurstSummary<PhotoResult>) -> Unit = { summary ->
                run = null
                val photos = summary.shots.mapNotNull { it.result.getOrNull() }
                val artifacts = photos.flatMap { photo -> photo.artifacts.ifEmpty { photo.uris.mapIndexed { i, uri -> PhotoArtifact("${photo.name}_$i.jpg", "image/jpeg", uri) } }.map { CliArtifact(it.name, it.mime, it.uri) } }.toMutableList()
                val result = JSONObject().put("saved", summary.saved).put("requested", count).put("cancelled", cancelled)
                    .put("stop_reason", summary.stopReason).put("shots", CliJson.of(summary.shots.map {
                        mapOf("request_id" to it.requestId, "saved" to it.result.isSuccess, "error" to it.result.exceptionOrNull()?.message)
                    }))
                val failure = if (cancelled) CliFailure("CANCELLED", "Stopped; saved files remain available with fetch")
                    else if (summary.saved != count) CliFailure("CAPTURE_FAILED", "Not all photos saved; use fetch to recover saved files") else null
                if (bracket) {
                    val fusion = BracketFusion(context)
                    fusion.fuseSaved(summary, started = { progress(command.id) {
                        it.put("progress", JSONObject().put("saved", count).put("total", count).put("phase", "HDR"))
                    } }) { outcome ->
                        outcome.artifact?.let { artifacts += CliArtifact(it.name, it.mime, it.uri) }
                        result.put("hdr", outcome.status).put("hdr_error", outcome.error).put("hdr_reason", outcome.reason)
                        fusion.close()
                        done(result.put("artifact_count", artifacts.size).put("cancelled", cancelled), artifacts, if (cancelled) CliFailure("CANCELLED", "Stopped; saved files remain available with fetch") else failure)
                    }
                } else {
                    done(result.put("artifact_count", artifacts.size), artifacts, failure)
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

    private fun progress(id: String, update: (JSONObject) -> Unit) {
        // Progress is advisory. Keep draining in-flight saves even if the status disk is full;
        // the final durable completion reports STORE_FAILED without abandoning saved photos.
        runCatching { updateRecord(id, update) }
            .onFailure { android.util.Log.w("CliSequence", "Cannot write progress", it) }
    }

    fun cancel() { cancelled = true; run?.cancel("Cancelled") }
}
