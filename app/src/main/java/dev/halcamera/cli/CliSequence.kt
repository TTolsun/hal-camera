package dev.halcamera.cli

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.halcamera.camera.*
import org.json.JSONObject

/** Sequential saves retain partial artifacts on cancellation and failure. */
class CliSequence(private val context: Context) {
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
        val evs = if (bracket) BracketPlan.evIndices(base.evIndex, support.evRange!!, support.evStep) else emptyList()
        val count = if (bracket) 3 else command.options?.values?.get("count")?.toInt() ?: 3
        cancelled = false
        run = BurstRun(command.id, count, command.options?.values?.get("interval_ms")?.toLong() ?: 0,
            SystemClock::elapsedRealtime, { delay, block -> main.postDelayed(block, delay) },
            { !camera.mediaBusy }, camera::capturePhoto,
            { progress -> CommandCoordinator.get(context).store.transition(command.id, "running") {
                it.put("progress", JSONObject().put("saved", progress.saved).put("total", count).put("phase", "capturing"))
            } },
            { summary ->
                run = null
                if (bracket) runCatching { tuning?.setControls(base) }
                val photos = summary.shots.mapNotNull { it.result.getOrNull() }
                val artifacts = photos.flatMap { photo -> photo.artifacts.ifEmpty { photo.uris.mapIndexed { i, uri -> PhotoArtifact("${photo.name}_$i.jpg", "image/jpeg", uri) } }.map { CliArtifact(it.name, it.mime, it.uri) } }.toMutableList()
                val result = JSONObject().put("saved", summary.saved).put("requested", count).put("cancelled", cancelled)
                    .put("stop_reason", summary.stopReason).put("shots", CliJson.of(summary.shots.map {
                        mapOf("request_id" to it.requestId, "saved" to it.result.isSuccess, "error" to it.result.exceptionOrNull()?.message)
                    }))
                val failure = if (cancelled) CliFailure("CANCELLED", "Stopped; saved files remain available with fetch")
                    else if (summary.saved != count) CliFailure("CAPTURE_FAILED", "Not all photos saved; use fetch to recover saved files") else null
                val sources = photos.mapNotNull(BracketFusion::pick)
                if (bracket && failure == null && sources.size == 3) {
                    val fusion = BracketFusion(context)
                    fusion.fuse(command.id, sources) { fused ->
                        fused.getOrNull()?.let { artifacts += CliArtifact(it.name, it.mime, it.uri) }
                        result.put("hdr", if (fused.isSuccess) "saved" else "failed").put("hdr_error", fused.exceptionOrNull()?.message)
                        fusion.close()
                        done(result.put("artifact_count", artifacts.size).put("cancelled", cancelled), artifacts, if (cancelled) CliFailure("CANCELLED", "Stopped after saving HDR") else failure)
                    }
                } else {
                    if (bracket) result.put("hdr", "skipped").put("hdr_reason", "Three saved JPEG sources are required")
                    done(result.put("artifact_count", artifacts.size), artifacts, failure)
                }
            },
            name = { index -> if (bracket) "bracket-${command.id}-${index + 1}-${BracketPlan.tag(evs[index], support.evStep)}" else "burst-${command.id}-${index + 1}" },
            prepare = { index, go ->
                if (bracket) { tuning!!.setControls(base.copy(evIndex = evs[index])); main.postDelayed(go, LiveBurst.SETTLE_MS) }
                else go()
            })
        run!!.start()
    }

    fun cancel() { cancelled = true; run?.cancel("Cancelled") }
}
