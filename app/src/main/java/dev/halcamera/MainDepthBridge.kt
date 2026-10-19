package dev.halcamera

import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import dev.halcamera.camera.*
import dev.halcamera.ui.*

/** LIVE depth UI; MainActivity owns the camera. */
internal fun MainActivity.chooseDepth() {
    if (!resumed || closing || cli.active != null || recordingVideo || stoppingRecording || mediaBusy() ||
        pendingPermissionAction != null || cameraId.isEmpty()) return
    val support = try { depthSupport(manager.getCameraCharacteristics(cameraId)) } catch (e: Exception) {
        Toast.makeText(this, "Cannot read depth capabilities: ${e.message}", Toast.LENGTH_LONG).show(); return
    }
    val sizes = support.sizes.takeIf { support.available }.orEmpty()
    val choices = listOf(LiveChoiceSheet.Choice("Off", "Return to Photo")) +
        sizes.map { LiveChoiceSheet.Choice("DEPTH16 · $it", "Camera2 · Depth-only view") } +
        if (support.available) emptyList() else listOf(LiveChoiceSheet.Choice("Depth unavailable", support.unavailableReason.orEmpty(), false))
    LiveChoiceSheet.show(this, "Depth map", choices, if (videoMode) -1 else sizes.indexOf(depthSize) + 1,
        content = Look.text(this, "${support.colorSummary}\nNear: bright · Far: dark · Black: invalid\nView only; photos and video are disabled.", 12, Look.onDarkMuted)) { index ->
        if (!resumed || closing || cli.active != null || recordingVideo || mediaBusy()) return@show
        val chosen = if (index == 0) null else sizes.getOrNull(index - 1) ?: return@show
        if (chosen == depthSize && !videoMode) { return@show }
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
            depthFrameArrived()
        }
    }, status)
}

internal fun MainActivity.updateDepthControls(callbacks: Boolean, topInset: Int, bottomInset: Int) {
    depthModeButton.isEnabled = resumed && !closing && !recordingVideo && bursts.run == null && !stoppingRecording && !mediaBusy() &&
        cli.active == null && pendingPermissionAction == null && cameraId.isNotEmpty()
    depthModeButton.isSelected = depthSize != null
    depthModeButton.setTextColor(if (depthSize != null) Look.onDark else Look.onDarkMuted)
    depthModeButton.setTypeface(null, if (depthSize != null) Typeface.BOLD else Typeface.NORMAL)
    depthModeButton.alpha = if (depthModeButton.isEnabled) 1f else .4f
    depthModeButton.contentDescription = "Depth map: choose a DEPTH16 resolution or view availability"
    ViewCompat.setStateDescription(depthModeButton, if (depthSize != null) "Selected" else "Not selected")
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
        mediaBusy() || (depthSize == null && (!ready || videoMode==video))) return
    pendingPermissionAction=null
    videoMode=video
    resetModeSettings()
    restartCamera()
    updateMediaControls()
}
