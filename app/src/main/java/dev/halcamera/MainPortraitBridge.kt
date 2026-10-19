package dev.halcamera

import android.os.Build
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import dev.halcamera.camera.*
import dev.halcamera.ui.*

/** The Depth menu opens this OEM resolution picker. */
internal fun MainActivity.choosePortrait(support: PortraitSupport) {
    if (!resumed || closing || cli.active != null || recordingVideo || stoppingRecording || mediaBusy() ||
        pendingPermissionAction != null || cameraId.isEmpty()) return
    val configs = support.jpegs.mapNotNull(support::config)
    val id = cameraId
    val choices = configs.map { LiveChoiceSheet.Choice("JPEG ${it.jpeg}", "Manufacturer Bokeh · Preview ${it.preview}") }
    LiveChoiceSheet.show(this, "Portrait · Manufacturer Bokeh", choices, if (ready) configs.indexOf(portraitConfig) else -1,
        content = Look.text(this, "Choose a JPEG resolution. Photos only; RAW, PIP and manual controls are unavailable.", 12, Look.onDarkMuted)) { index ->
        if (!resumed || closing || cameraId != id || cli.active != null || recordingVideo || stoppingRecording ||
            pendingPermissionAction != null || mediaBusy()) return@show
        val chosen = configs.getOrNull(index) ?: return@show
        recorder.finish("portrait_changed")?.let(::exportCliIncident)
        captureFeedback.clearResult()
        engineName = "Camera2"
        videoMode = false
        resetModeSettings()
        portraitConfig = chosen
        paused = false
        updateCameraChoices(); restartCamera()
    }
}

internal fun MainActivity.createPortraitEngine(released: String?, status: (String, Boolean) -> Unit): CameraEngine? {
    if (Build.VERSION.SDK_INT < 31) { setStatus("Portrait requires Android 12 or newer.", false); return null }
    val thisSession = sessionId
    val view = TextureView(this)
    previewHost.addView(view, FrameLayout.LayoutParams(-1, -1))
    return PortraitCameraEngine(this, view, cameraId, requireNotNull(portraitConfig), sessionId, telemetry, released,
        { if (thisSession == sessionId && resumed && !closing && !paused) previewFrameArrived() }, status)
}

internal fun MainActivity.updatePortraitControls() {
    if (portraitConfig == null) return
    liveIndicator.bindStabilization(LiveEisStatus(0), false)
    physicalPipButton.isEnabled = false; physicalPipButton.alpha = .4f
    zoomControl.isEnabled = false
    controlBar.bind(false, false, false)
    manualPanel.close()
    metrics.visibility = View.GONE
    mediaButton.contentDescription = if (mediaBusy()) "Saving Portrait photo" else "Take a manufacturer Portrait photo"
}
