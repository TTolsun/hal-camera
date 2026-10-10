package dev.halcamera

import android.content.Intent
import android.hardware.camera2.CameraManager
import dev.halcamera.camera.*
import dev.halcamera.cli.LiveController

/** Activity-side adapter; keeps CLI preparation and handover separate from screen layout. */
internal fun MainActivity.createLiveCli(): LiveController = LiveController(cli, object : LiveController.Driver {
    override fun busy() = recordingVideo || stoppingRecording || pendingPermissionAction != null || mediaBusy()
    override fun prepare(command: dev.halcamera.cli.CliCommand, ready: () -> Unit) {
        // Capability work can initialize CameraX; keep it off the main thread.
        io.execute {
            val selected = runCatching {
                val id = requireNotNull(command.camera)
                if (command.streams == null) null else {
                    var support = liveStreamSupport(getSystemService(CameraManager::class.java).getCameraCharacteristics(id))
                    if (command.engine == "CameraX") support = cameraXStreamSupport(this@createLiveCli, id, support)
                    command.streams.resolve(support)
                }
            }
            main.post {
                if (cli.active?.id != command.id || !resumed) return@post
                selected.fold({ settings ->
                    if (command.command in setOf("capture", "burst", "bracket") && settings?.canCapture == false) {
                        cli.fail(command.id, "PREFLIGHT_FAILED", "Capture requires YUV or JPEG output")
                        return@fold
                    }
                    captureFeedback.clearResult()
                    showCallbacks(false)
                    val modeChanged = engineName != (command.engine ?: "Camera2") || videoMode != (command.command == "record.start")
                    cameraId = requireNotNull(command.camera); engineName = command.engine ?: "Camera2"
                    videoMode = command.command == "record.start"
                    if (modeChanged) resetModeSettings()
                    paused = false; zoomRatio = 1f
                    if (settings == null) streamSettings.remove(streamKey()) else streamSettings[streamKey()] = settings
                    resetControls()
                    try {
                        command.options?.let { options ->
                            val controls = options.controls(controlBar.support, manualCapabilities, videoMode)
                            val zoom = options.values["zoom"]?.toFloat() ?: 1f
                            val range = zoomRange(manager, cameraId)
                            if (zoom !in range.first..range.second) throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Zoom outside supported range")
                            zoomRatio = zoom
                            controlBar.applyRequested(controls)
                        }
                        updateCameraChoices(); restartCamera(); ready()
                    } catch (e: Exception) { cli.fail(command.id, (e as? dev.halcamera.cli.CliFailure)?.code ?: "PREFLIGHT_FAILED", e.message ?: "Invalid controls") }
                }, { cli.fail(command.id, (it as? dev.halcamera.cli.CliFailure)?.code ?: "PREFLIGHT_FAILED", it.message ?: "Invalid stream settings") })
            }
        }
    }
    override fun streamInfo(): Map<String, Any?> =
        (telemetry.sessions[sessionId]?.get("negotiatedStreams") as? Map<*, *>)?.entries
            ?.associate { it.key.toString() to it.value }.orEmpty()
    override fun photoLabels(): List<String> {
        val settings = streamSettings[streamKey()]
        return listOfNotNull("YUV".takeIf { settings == null || settings.yuv != null },
            "JPEG".takeIf { settings == null || settings.jpeg != null })
    }
    override fun capture(id: String, done: (Result<PhotoResult>) -> Unit) {
        val camera = engine as? MediaCapture
        if (camera == null) done(Result.failure(IllegalStateException("Media capture unavailable; camera not ready"))) else camera.capturePhoto(id, done)
        updateMediaControls()
    }
    override fun tune(options: dev.halcamera.cli.CliOptions?, reset: Boolean): org.json.JSONObject {
        if ((!ready && !recordingVideo) || closing || stoppingRecording) throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Start preview or recording first")
        val requested = if (reset) LiveControls() else options!!.controls(controlBar.support, manualCapabilities, videoMode, controlBar.controls)
        val zoom = if (reset) 1f else options!!.values["zoom"]?.toFloat() ?: zoomRatio
        val range = zoomRange(manager, cameraId)
        if (zoom !in range.first..range.second) throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Unsupported zoom")
        controlBar.applyRequested(requested)
        zoomRatio = zoom; engine?.setZoom(zoom)
        return dev.halcamera.cli.CliJson.envelope().put("completed", true).put("zoom", zoom).put("requested_controls", requested.toString())
    }
    override fun sequence(command: dev.halcamera.cli.CliCommand, done: (org.json.JSONObject, List<dev.halcamera.cli.CliArtifact>, dev.halcamera.cli.CliFailure?) -> Unit) {
        cliSequence.start(command, engine as? MediaCapture, controlBar.controls, controlBar.support) { result, artifacts, failure ->
            if (resumed) captureFeedback.showResult("${result.optInt("saved")}/${result.optInt("requested")} saved" +
                result.optString("hdr").takeIf { it.isNotEmpty() }.let { if (it == null) "" else " · HDR $it" })
            done(result, artifacts, failure)
        }
    }
    override fun cancelSequence() { cliSequence.cancel() }
    override fun cancelEvents() { recorder.finish("cancelled")?.let(::exportCliIncident) }
    override fun inspect(command: dev.halcamera.cli.CliCommand) {
        when (command.command) {
            "live.info" -> cli.complete(command.id, dev.halcamera.cli.CliJson.of(mapOf("camera_id" to cameraId, "engine" to engineName,
                "ready" to ready, "zoom" to zoomRatio, "streams" to streamInfo(), "controls" to controlBar.controls.toString(),
                "events" to recorder.snapshot(1_000_000_000L).takeLast(100).map { mapOf("kind" to it.kind, "at_ns" to it.atNs.toString(), "values" to it.values) })) as org.json.JSONObject)
            "events" -> {
                if (!recorder.trigger(command.id)) throw dev.halcamera.cli.CliFailure("BUSY", "Events are already being saved")
                cli.state(command.id, "running")
            }
            "meter" -> {
                val v = previewHost.getChildAt(0) ?: throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Start preview first")
                val opts = command.options!!.values
                val accepted = (engine as? TouchMetering)?.meterAt(opts.getValue("x").toFloat() * v.width,
                    opts.getValue("y").toFloat() * v.height, opts["meter"] == "exposure") { } == true
                if (!accepted) throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Metering unavailable. Start preview with automatic focus/exposure.")
                cli.complete(command.id, org.json.JSONObject().put("submitted", true).put("note", "Metering requested; inspect live.info for capture results"))
            }
        }
    }
    override fun dual(command: dev.halcamera.cli.CliCommand) = handOver(command) {
        Intent(this@createLiveCli, DualPreviewActivity::class.java)
            .putExtra(DualPreviewActivity.EXTRA_ENGINE, command.engine ?: "Camera2")
            .putExtra(DualPreviewActivity.EXTRA_VIDEO, command.command == "dual.record")
    }
    override fun snapshot(done: (Result<PhotoResult>) -> Unit) {
        (engine as? MediaCapture)?.captureSnapshot(done) ?: done(Result.failure(IllegalStateException("Camera unavailable")))
    }
    override fun record(audio: Boolean, started: () -> Unit, done: (Result<android.net.Uri>) -> Unit) {
        videoMode = true
        val camera = engine as? MediaCapture
        if (camera == null) done(Result.failure(IllegalStateException("Media capture unavailable; camera not ready")))
        else camera.startRecording(audio, started, done)
        updateMediaControls()
    }
    override fun stopRecording() {
        stoppingRecording = true
        (engine as? MediaCapture)?.stopRecording()
        updateMediaControls()
    }
    override fun stopPreview(done: () -> Unit) {
        bursts.close()
        paused = true; ready = false
        val old = engine; engine = null
        closing = old != null
        val closed = {
            closing = false
            setStatus("Preview paused · Reconnect Camera in Lab.", false)
            done()
        }
        if (old == null) closed() else old.close(closed)
    }
    override fun cts(command: dev.halcamera.cli.CliCommand) = handOver(command) {
        Intent(this@createLiveCli, dev.halcamera.cts.suite.CtsSuiteRunActivity::class.java)
            .putExtra(dev.halcamera.cts.suite.CtsSuiteRunActivity.EXTRA_KEYS, command.cases.orEmpty().toTypedArray())
    }
    override fun benchmark(command: dev.halcamera.cli.CliCommand) = handOver(command) {
        Intent(this@createLiveCli, dev.halcamera.benchmark.BenchmarkActivity::class.java)
            .putExtra(dev.halcamera.benchmark.BenchmarkActivity.EXTRA_CAMERA_ID, command.camera)
    }
    /** Closes the Live camera first: the next screen must open a free camera, and close(done) is the only way to know. */
    private fun handOver(command: dev.halcamera.cli.CliCommand, intent: () -> Intent) {
        bursts.close()
        val old = engine; engine = null; closing = true; ready = false
        val open = {
            closing = false
            if (resumed && cli.active?.id == command.id) startActivity(intent().putExtra("cli_request_id", command.id))
            else cli.fail(command.id, "APP_NOT_FOREGROUND", "App left foreground before ${command.command}")
        }
        if (old == null) open() else old.close { open() }
    }
    override fun stopPreparing() { paused = true; restartCamera() }
})
