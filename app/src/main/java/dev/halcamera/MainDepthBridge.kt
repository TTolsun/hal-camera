package dev.halcamera

import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*
import dev.halcamera.ui.*

/** LIVE depth UI; MainActivity owns the camera. */
internal fun MainActivity.chooseDepth() {
    if (!resumed || closing || cli.active != null || recordingVideo || stoppingRecording || mediaBusy() ||
        pendingPermissionAction != null || cameraId.isEmpty()) return
    val id = cameraId
    io.execute {
        val portrait = portraitSupport(manager, id)
        val depth = runCatching { depthSupport(manager.getCameraCharacteristics(id)) }.getOrNull()
        main.post {
            if (!resumed || closing || cameraId != id || cli.active != null || mediaBusy() || recordingVideo ||
                stoppingRecording || pendingPermissionAction != null) return@post
            val choices = listOf(LiveChoiceSheet.Choice("Off", "Return to Photo"),
                LiveChoiceSheet.Choice("Portrait (Bokeh)", portraitConfig?.let { "Active · JPEG ${it.jpeg} · Change resolution" }
                    ?: if (portrait.available) "Manufacturer background blur · Photos" else portrait.unavailableReason.orEmpty(), portrait.available),
                LiveChoiceSheet.Choice("Depth Map", depthSize?.let { "Active · DEPTH16 $it · Change resolution" }
                    ?: if (depth?.available == true) "View sensor DEPTH16 · No photo capture" else depth?.unavailableReason ?: "Cannot read Depth capabilities.", depth?.available == true))
            LiveChoiceSheet.show(this, "Depth · Portrait", choices,
                if (depthSize == null && portraitConfig == null && !videoMode) 0 else -1) { index ->
                if (!resumed || closing || cameraId != id || cli.active != null || mediaBusy() || recordingVideo ||
                    stoppingRecording || pendingPermissionAction != null) return@show
                when (index) {
                    0 -> selectMode(false)
                    1 -> choosePortrait(portrait)
                    2 -> depth?.let { chooseDepthMap(it) }
                }
            }
        }
    }
}

private fun MainActivity.chooseDepthMap(support: DepthSupport) {
    val id = cameraId
    val sizes = support.sizes.takeIf { support.available }.orEmpty()
    val choices = sizes.map { LiveChoiceSheet.Choice("DEPTH16 · $it", "Camera2 · Depth-only view") }
    LiveChoiceSheet.show(this, "Depth Map", choices, if (ready) sizes.indexOf(depthSize) else -1,
        content = Look.text(this, "${support.colorSummary}\nNear: bright · Far: dark · Black: invalid\nView only; photos and video are disabled.", 12, Look.onDarkMuted)) { index ->
        if (!resumed || closing || cameraId != id || cli.active != null || recordingVideo || stoppingRecording ||
            pendingPermissionAction != null || mediaBusy()) return@show
        val chosen = sizes.getOrNull(index) ?: return@show
        recorder.finish("depth_view_changed")?.let(::exportCliIncident)
        captureFeedback.clearResult()
        engineName = "Camera2"
        videoMode = false
        resetModeSettings()
        depthSize = chosen
        paused = false
        updateCameraChoices()
        restartCamera()
    }
}

internal fun MainActivity.createDepthEngine(released: String?, status: (String, Boolean) -> Unit): CameraEngine? {
    val size = requireNotNull(depthSize)
    val thisSession = sessionId
    val chars = try { manager.getCameraCharacteristics(cameraId) } catch (e: Exception) {
        setStatus("Cannot read depth camera: ${e.message}", false)
        return null
    }
    val view = DepthMapView(this, size, chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
        chars[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT)
    previewHost.addView(view, FrameLayout.LayoutParams(-1, -1))
    return DepthCameraEngine(manager, cameraId, size, thisSession, telemetry, released, { frame ->
        if (thisSession == sessionId && resumed && !closing && !paused) {
            view.bind(frame)
            previewFrameArrived()
        }
    }, status)
}

internal fun MainActivity.updateDepthControls(callbacks: Boolean, topInset: Int, bottomInset: Int) {
    depthModeButton.isEnabled = resumed && !closing && !recordingVideo && bursts.run == null && !stoppingRecording && !mediaBusy() &&
        cli.active == null && pendingPermissionAction == null && cameraId.isNotEmpty()
    val selected = depthSize != null || portraitConfig != null
    depthModeButton.isSelected = selected
    depthModeButton.setTextColor(if (selected) Look.onDark else Look.onDarkMuted)
    depthModeButton.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    depthModeButton.alpha = if (depthModeButton.isEnabled) 1f else .4f
    depthModeButton.contentDescription = "Depth and Portrait: choose manufacturer Bokeh photos or a sensor Depth map"
    ViewCompat.setStateDescription(depthModeButton, if (selected) "Selected" else "Not selected")
    if (depthSize != null) {
        mediaButton.isEnabled = false
        mediaButton.contentDescription = "Depth view only: select Photo or Video to capture media"
        physicalPipButton.isEnabled = false
        physicalPipButton.alpha = .4f
        zoomControl.isEnabled = false
        controlBar.bind(false, false, false)
        liveIndicator.setSizesEnabled(false)
        metrics.visibility = View.GONE
        manualPanel.close()
        (previewHost.getChildAt(0) as? DepthMapView)?.setChromeInsets(topInset, bottomInset)
    } else if (!callbacks) metrics.visibility = View.VISIBLE
}

/** Photo and Video also leave the depth-only session, including after a depth failure. */
internal fun MainActivity.selectMode(video: Boolean) {
    if (cli.active != null) return
    if (recordingVideo || stoppingRecording || closing || pendingPermissionAction != null ||
        mediaBusy() || (depthSize == null && portraitConfig == null && (!ready || videoMode==video))) return
    pendingPermissionAction=null
    videoMode=video
    resetModeSettings()
    restartCamera()
    updateMediaControls()
}
