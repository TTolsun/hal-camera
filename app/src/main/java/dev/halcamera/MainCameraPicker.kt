package dev.halcamera

import dev.halcamera.camera.CameraLabel
import dev.halcamera.camera.zoomPresets
import dev.halcamera.camera.zoomRange
import dev.halcamera.ui.LiveChoiceSheet

/** Public camera selection leaves special sessions and resets lens-specific controls. */
internal fun MainActivity.selectCamera() {
    if (cli.active != null || recordingVideo || mediaBusy() || closing) return
    val ids = cameraIds.sortedWith(CameraLabel.idOrder)
    val choices = ids.map { id ->
        val endpoint = cameraEndpoints[id]
        val name = endpoint?.let { listOfNotNull(CameraLabel.facing(it.role, it.facing), CameraLabel.lens(it.role),
            CameraLabel.angle(it.role, it.equivalentFocalMm)).joinToString(" · ") }?.takeIf { it.isNotEmpty() } ?: "Camera"
        LiveChoiceSheet.Choice("Service · ID $id", name)
    }
    LiveChoiceSheet.show(this, "Camera", choices, ids.indexOf(cameraId)) { index ->
        if (recordingVideo || !resumed || cli.active != null || mediaBusy() || closing) return@show
        val chosen = ids[index]
        if (cameraId != chosen) {
            pendingPermissionAction = null
            recorder.finish("camera_changed")?.let(::exportCliIncident)
            cameraId = chosen; zoomRatio = 1f
            if (appBokehMode) captureFeedback.clearResult()
            depthSize = null; portraitConfig = null; appBokehMode = false
            resetControls(); updateCameraChoices(); restartCamera()
        }
    }
}

internal fun MainActivity.cameraLabel(id: String): String = if (id.isEmpty()) "No camera"
    else cameraEndpoints[id]?.let(CameraLabel::full) ?: CameraLabel.short(id)

internal fun MainActivity.applyZoom(ratio: Float) {
    if (depthSize != null || portraitConfig != null || !resumed || paused || closing || cli.active != null || bursts.controlsLocked ||
        !(ready || recordingVideo) || stoppingRecording || cameraId.isEmpty() || !ratio.isFinite()) return
    val range = zoomRange(manager, cameraId)
    zoomRatio = ratio.coerceIn(range.first, range.second)
    engine?.setZoom(zoomRatio)
    zoomControl.setRatio(zoomRatio)
}

internal fun MainActivity.updateCameraChoices() {
    if (cameraId.isNotEmpty()) {
        val range = zoomRange(manager, cameraId)
        zoomRatio = zoomRatio.coerceIn(range.first, range.second)
    }
    engineButton.text = engineName
    engineButton.contentDescription = "Current: $engineName; tap to switch to ${if (engineName == "Camera2") "CameraX" else "Camera2"}"
    cameraShortcut.contentDescription = "Choose camera, current: ${cameraLabel(cameraId)}"
    cameraShortcut.tooltipText = cameraShortcut.contentDescription
    zoomControl.setChoices(if (cameraId.isEmpty()) listOf(1f) else zoomPresets(zoomRange(manager, cameraId)), zoomRatio)
}
