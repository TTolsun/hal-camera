package dev.halcamera

import android.net.Uri
import dev.halcamera.camera.MediaCapture
import dev.halcamera.camera.liveStreamSupport
import dev.halcamera.ui.AppBokehEditor
import java.util.UUID

/** Uses the ordinary photo session; the saved JPEG is the only inference input. */
internal fun MainActivity.chooseAppBokeh() {
    if (!resumed || closing || mediaBusy() || cli.active != null || recordingVideo || stoppingRecording) return
    val id = cameraId
    io.execute {
        val selected = runCatching {
            liveStreamSupport(manager.getCameraCharacteristics(id)).defaults().copy(yuv = null).also {
                require(it.jpeg != null) { "App Bokeh requires a JPEG output on this camera" }
            }
        }
        main.post {
            if (!resumed || closing || cameraId != id || mediaBusy() || cli.active != null || recordingVideo || stoppingRecording) return@post
            selected.fold({ settings ->
                recorder.finish("app_bokeh_selected")?.let(::exportCliIncident)
                resetModeSettings()
                engineName = "Camera2"; videoMode = false; appBokehMode = true; paused = false
                streamSettings[streamKey()] = settings
                captureFeedback.showResult("App Bokeh · Capture, then tap the subject")
                updateCameraChoices(); restartCamera()
            }, { captureFeedback.showResult("App Bokeh unavailable: ${it.message}") })
        }
    }
}

internal fun MainActivity.captureAppBokeh() {
    if (!resumed || !ready || closing || mediaBusy() || cli.active != null) return
    val camera = engine as? MediaCapture ?: return
    appBokehCapturing = true
    updateMediaControls()
    camera.capturePhoto(UUID.randomUUID().toString()) { result ->
        main.post {
            appBokehCapturing = false
            result.fold({ photo ->
                val jpeg = photo.artifacts.firstOrNull { it.mime == "image/jpeg" }
                if (jpeg == null) { if (resumed) captureFeedback.showResult("App Bokeh needs a JPEG photo. Enable JPEG in Live Streams.") }
                else {
                    appBokehSource = jpeg.uri.toString()
                    if (resumed && !destroyed) openAppBokehEditor(jpeg.uri)
                }
            }, { if (resumed) captureFeedback.showResult("App Bokeh capture failed: ${it.message}") })
            if (!destroyed) updateMediaControls()
        }
    }
}

internal fun MainActivity.openAppBokehEditor(uri: Uri) {
    if (!resumed || destroyed || appBokehEditor != null) return
    appBokehSource = uri.toString()
    appBokehEditor = AppBokehEditor(this, uri) {
        appBokehEditor = null; appBokehSource = null
        if (!destroyed) updateMediaControls()
    }.also { it.show() }
    updateMediaControls()
}
