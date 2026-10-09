package dev.halcamera

import dev.halcamera.camera.*

/** Activity-side adapter for Dual controls, diagnostics and request lifecycle. */
internal fun DualPreviewActivity.createDualCliHost(): dev.halcamera.cli.CliHost = object : dev.halcamera.cli.CliHost {
override val screen = "dual"
override fun isBusy() = closing || recording || recordPending || photoPending
override fun tune(options: dev.halcamera.cli.CliOptions?, reset: Boolean): org.json.JSONObject {
    if (session == null || streamingSize == null || closing || recordPending || photoPending)
        throw dev.halcamera.cli.CliFailure("BUSY", "Wait for Dual preview to be ready")
    val controls = if (reset) LiveControls() else options!!.controls(mainControls?.support ?: LiveControlSupport.NONE,
        mainControls?.manual ?: ManualSupport(camera2 = false), videoMode, controlBar.controls)
    val zoom = if (reset) 1f else options!!.values["zoom"]?.toFloat() ?: zoomRatio
    if (zoom !in cliZoomRange.first..cliZoomRange.second)
        throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Dual zoom is outside the supported range")
    controlBar.applyRequested(controls); session?.setControls(controls)
    zoomRatio = zoom; session?.setZoom(zoom); zoomControl.setChoices(zoomPresets(cliZoomRange), zoom)
    return dev.halcamera.cli.CliJson.envelope().put("completed", true).put("zoom", zoom).put("controls", controls.toString())
}
override fun execute(command: dev.halcamera.cli.CliCommand) {
    when (command.command) {
        "live.info" -> {
            cli.complete(command.id, org.json.JSONObject().put("camera_id", logicalId).put("engine", engineName)
                .put("first", pair?.first).put("second", pair?.second).put("ready", streamingSize != null)
                .put("size", streamingSize?.toString()).put("zoom", zoomRatio).put("controls", controlBar.controls.toString())
                .put("report", dualReport()).put("events", dev.halcamera.cli.CliJson.of(recorder.snapshot(1_000_000_000L).takeLast(100)
                    .map { mapOf("kind" to it.kind, "at_ns" to it.atNs.toString(), "values" to it.values) })))
            return
        }
        "events" -> {
            if (!recorder.trigger(command.id)) throw dev.halcamera.cli.CliFailure("BUSY", "Events are already being saved")
            cli.state(command.id, "running"); return
        }
        "meter" -> {
            val view = views[0] ?: throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Start Dual preview first")
            val values = command.options!!.values
            val point = view.naturalPoint(values.getValue("x").toFloat() * view.width, values.getValue("y").toFloat() * view.height)
                ?: throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Wait for a visible Dual preview")
            val accepted = (session as? DualPreviewSession)?.meterAt(point.first, point.second, values["meter"] == "exposure") { } == true
            if (!accepted) throw dev.halcamera.cli.CliFailure("PREFLIGHT_FAILED", "Metering unavailable on this Dual configuration")
            cli.complete(command.id, org.json.JSONObject().put("submitted", true)); return
        }
        "preview.stop" -> {
            cliStopping = true
            closeSession { cli.complete(command.id, org.json.JSONObject().put("camera_ready", false)) }; return
        }
    }
    cliRequest = command
    cliFrames.fill(false)
    logicalId = command.camera
    pair = command.options!!.values.getValue("first") to command.options.values.getValue("second")
    if (command.command == "dual.capture" && engineName == "CameraX") {
        cli.fail(command.id, "PREFLIGHT_FAILED", "Dual photos require Camera2"); return
    }
    if (cameras.isNotEmpty()) { render(); startIfReady() }
}
override fun cancel(command: dev.halcamera.cli.CliCommand) {
    if (command.command == "events") { recorder.finish("cancelled")?.let(::exportIncident); return }
    cliCancelled = true
    cliStopping = true
    closeSession { if (cli.active?.id == command.id) cli.complete(command.id,
        org.json.JSONObject().put("cancelled", true), cliArtifacts.toList(), dev.halcamera.cli.CliFailure("CANCELLED", "Dual operation stopped")) }
}
override fun stopRecording(command: dev.halcamera.cli.CliCommand) {
    cliStopping = true
    closeSession { if (cli.active?.id == command.id && cliArtifacts.isEmpty()) cli.fail(command.id, "RECORDING_FAILED", "No videos were saved") }
}
    }
