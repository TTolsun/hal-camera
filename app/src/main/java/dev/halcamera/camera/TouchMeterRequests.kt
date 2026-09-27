package dev.halcamera.camera

import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.TextureView

/**
 * The Camera2 side of [TouchMeter] (#168). A short tap focuses and a long press meters exposure, as in the Samsung
 * camera's photo mode. [Camera2TouchFocus] keeps one [TouchTarget] for each, and Camera2Engine calls [applyTouch]
 * on every LIVE request after the AF mode is set, so preview, still and recording requests all carry them.
 */
class TouchTarget(val generation: Int, val rect: MeteringRectangle, val feedback: (TouchPhase) -> Unit)

/**
 * Tap-to-focus needs AF regions, the AUTO AF mode, whose trigger always starts a new scan (a continuous mode
 * may lock at once on the old point), and a lens that moves. A fixed-focus camera still meters exposure on a long press.
 */
fun touchSupport(c: CameraCharacteristics): Pair<Boolean, Boolean> {
    val afModes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
    val af = (c[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0 && CaptureRequest.CONTROL_AF_MODE_AUTO in afModes &&
        (c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f) > 0f
    return af to ((c[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0)
}

/**
 * A view pixel as a point normalized to the upright picture. The TextureView draws the upright picture over its
 * whole size and then applies its transform (fill-crop, and the display rotation in landscape), so inverting that
 * transform undoes both.
 */
fun TextureView.naturalPoint(x: Float, y: Float): Pair<Double, Double>? {
    val inverse = Matrix()
    if (width == 0 || height == 0 || !getTransform(null).invert(inverse)) return null
    val p = floatArrayOf(x, y).also { inverse.mapPoints(it) }
    return p[0].toDouble() / width to p[1].toDouble() / height
}

/**
 * The metering square for a normalized upright point. With CONTROL_ZOOM_RATIO the region coordinates are
 * post-zoom, so the whole active array is the zoomed field of view; below API 30 the zoom is a crop region
 * inside the active array, computed as Camera2Engine.applyZoom does.
 */
fun touchRegion(c: CameraCharacteristics, u: Double, v: Double, zoom: Float, preview: Size): MeteringRectangle? {
    val active = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return null
    val base = if (Build.VERSION.SDK_INT >= 30 && c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] != null) {
        MeterRect(0, 0, active.width(), active.height())
    } else {
        val r = zoom.coerceIn(1f, c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f)
        val w = (active.width() / r).toInt(); val h = (active.height() / r).toInt()
        MeterRect((active.width() - w) / 2, (active.height() - h) / 2, (active.width() + w) / 2, (active.height() + h) / 2)
    }
    val (su, sv) = TouchMeter.toSensor(u, v, c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
        c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT)
    val r = TouchMeter.region(su, sv, base, preview.width.toDouble() / preview.height)
    return MeteringRectangle(r.left, r.top, r.width, r.height, MeteringRectangle.METERING_WEIGHT_MAX)
}

/**
 * Camera2Engine's touch sequences, kept out of the engine. Camera thread only; the feedback goes to the main thread.
 *
 * A tap ([focusAt]): the repeating request takes the AF region and the AUTO AF mode, then one capture carries AF
 * trigger START. The outcome comes from the trigger's result and the results after it ([TouchFocusWatch]); the point
 * is held for [TouchMeter.HOLD_MS], or while AF lock is on, before AF goes back to the whole frame with a CANCEL, as
 * the AOSP camera does. A tap also ends a long-pressed exposure point, as in the Samsung camera.
 *
 * A long press ([exposeAt]): the repeating request takes the AE region and reports METERED once AE has settled on it
 * ([TouchExposureWatch]), or after [AeRelock.TIMEOUT_MS]; a held AF point ends. The caller then takes the AE lock through the Live
 * controls, so the AE button shows it. The point stays until a tap, another long press or the AE lock is released;
 * a session rebuild keeps it, and [AeRelock] locks the new session on it again.
 */
class Camera2TouchFocus(private val handler: Handler, private val main: Handler, private val host: Host) {
    interface Host {
        val live: Boolean
        val afLocked: Boolean
        /** The telemetry callback, which every capture of this class also reports to. */
        val captureCallback: CameraCaptureSession.CaptureCallback
        fun event(kind: String, values: Map<String, Any?>)
        fun submitRepeating(kind: String, values: Map<String, Any?>)
        /** One capture of the current repeating request with [afTrigger]; false when no session could take it. */
        fun trigger(kind: String, afTrigger: Int, callback: CameraCaptureSession.CaptureCallback): Boolean
    }

    /** The tapped AF point while it is held. */
    var af: TouchTarget? = null
        private set
    /** The long-pressed AE point. */
    var ae: TouchTarget? = null
        private set
    private val focusWatch = TouchFocusWatch()
    private val exposureWatch = TouchExposureWatch()

    fun focusAt(c: CameraCharacteristics, u: Double, v: Double, zoom: Float, preview: Size, feedback: (TouchPhase) -> Unit) {
        val rect = touchRegion(c, u, v, zoom, preview) ?: return
        dropExposure()
        val gen = focusWatch.tap()
        af = TouchTarget(gen, rect, feedback)
        host.submitRepeating("touch_meter", mapOf("kind" to "AF", "generation" to gen, "u" to u, "v" to v, "region" to rect.rect.toShortString()))
        post(feedback, TouchPhase.SCANNING)
        val cb = host.captureCallback
        host.trigger("touch_af_trigger", CaptureRequest.CONTROL_AF_TRIGGER_START, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) =
                cb.onCaptureStarted(s, r, timestamp, frameNumber)
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                cb.onCaptureCompleted(s, r, result)
                focusWatch.triggerCompleted(gen, result[CaptureResult.CONTROL_AF_STATE])?.let { focused(gen, it) }
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                cb.onCaptureFailed(s, r, failure); if (focusWatch.timedOut(gen)) focused(gen, TouchPhase.FAILED)
            }
        })
        handler.postDelayed({ if (focusWatch.timedOut(gen)) focused(gen, TouchPhase.FAILED) }, TouchMeter.SCAN_TIMEOUT_MS)
    }

    fun exposeAt(c: CameraCharacteristics, u: Double, v: Double, zoom: Float, preview: Size, feedback: (TouchPhase) -> Unit) {
        val rect = touchRegion(c, u, v, zoom, preview) ?: return
        dropExposure()
        // The press hides the AF square, so a held AF point ends too instead of lingering unseen in AUTO mode.
        if (af != null) endFocus()
        val gen = exposureWatch.press()
        ae = TouchTarget(gen, rect, feedback)
        host.submitRepeating("touch_meter", mapOf("kind" to "AE", "generation" to gen, "u" to u, "v" to v, "region" to rect.rect.toShortString()))
        post(feedback, TouchPhase.SCANNING)
        handler.postDelayed({ if (exposureWatch.timedOut(gen)) exposed(gen) }, AeRelock.TIMEOUT_MS)
    }

    /** Every LIVE repeating result. */
    fun onResult(result: TotalCaptureResult) {
        focusWatch.onResult(result[CaptureResult.CONTROL_AF_STATE])?.let { focused(focusWatch.generation, it) }
        if (exposureWatch.onResult(result[CaptureResult.CONTROL_AE_STATE])) exposed(exposureWatch.generation)
    }

    /** Forgets the AF point without a request: a new session, or AF lock released, builds the next request anyway. */
    fun dropFocus() {
        val t = af ?: return
        post(t.feedback, TouchPhase.DONE)
        af = null; focusWatch.cancel()
    }

    /** Forgets the AE point, for a tap, a new long press or the AE lock released. */
    fun dropExposure() {
        val t = ae ?: return
        post(t.feedback, TouchPhase.DONE)
        ae = null; exposureWatch.cancel()
    }

    private fun focused(gen: Int, phase: TouchPhase) {
        val t = af?.takeIf { it.generation == gen } ?: return
        host.event("touch_meter_result", mapOf("kind" to "AF", "generation" to gen, "phase" to phase.name))
        post(t.feedback, phase)
        // With AF lock on the point stays until the lock is released; only the square goes.
        handler.postDelayed({ if (af === t) { if (host.afLocked) post(t.feedback, TouchPhase.DONE) else endFocus() } }, TouchMeter.HOLD_MS)
    }

    private fun exposed(gen: Int) {
        val t = ae?.takeIf { it.generation == gen } ?: return
        host.event("touch_meter_result", mapOf("kind" to "AE", "generation" to gen, "phase" to TouchPhase.METERED.name))
        post(t.feedback, TouchPhase.METERED)
    }

    private fun endFocus() {
        dropFocus()
        host.submitRepeating("touch_meter_end", mapOf("kind" to "AF"))
        host.trigger("af_trigger", CaptureRequest.CONTROL_AF_TRIGGER_CANCEL, host.captureCallback)
    }

    private fun post(feedback: (TouchPhase) -> Unit, phase: TouchPhase) { main.post { if (host.live) feedback(phase) } }
}

/** Sets the touched regions, and the AUTO AF mode for a tapped AF point. Without them the HAL meters the frame. */
fun CaptureRequest.Builder.applyTouch(touch: Camera2TouchFocus) {
    touch.af?.let { set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO); set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it.rect)) }
    touch.ae?.let { set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it.rect)) }
}
