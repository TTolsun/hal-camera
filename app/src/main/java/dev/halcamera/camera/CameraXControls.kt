package dev.halcamera.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.PreviewView
import dev.halcamera.telemetry.Telemetry

/**
 * The Live controls (#169, #176) and touch metering (#168) of CameraXEngine, kept out of the engine. They follow
 * Camera2Engine's rules; only the calls differ:
 *
 * - EV, torch and the still's flash mode are CameraX calls. ImageCapture runs the flash precapture itself.
 * - CameraX has no AE lock, so CONTROL_AE_LOCK goes on the repeating request through Camera2CameraControl. It is the
 *   only interop key: interop options override CameraX's own, and an AE mode set there would break its flash.
 * - AF lock, a tapped AF point and a pressed AE point are one FocusMeteringAction, because CameraX holds a single
 *   action and a new one cancels the old. [submitMetering] rebuilds it from what is held. Every action disables
 *   CameraX's auto-cancel; a tapped point ends after [TouchMeter.HOLD_MS] here, as on Camera2.
 * - cancelFocusAndMetering is never called. On camera-pipe it is unlock3A(ae = true), which leaves aeLock = false in
 *   the graph's 3A state, and that state overrides request options: CONTROL_AE_LOCK stayed OFF for the rest of the
 *   session on the S25+. Nothing held is an AE-only action on the whole frame instead (regions only), and an AF
 *   point or lock is released with a one-shot CONTROL_AF_TRIGGER_CANCEL on the interop options.
 * - A rebuilt session (recording start and stop) relocks AE with [AeRelock] and puts AF lock and a pressed point back.
 *
 * Public calls come from the main thread; [onResult] comes from the camera's callback thread. State is guarded by
 * [lock], and CameraX's controls are thread-safe.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal class CameraXControls(
    private val view: PreviewView,
    private val main: Handler,
    private val telemetry: Telemetry,
    private val sessionId: String,
    private val host: Host,
) {
    interface Host {
        val camera: Camera?
        val imageCapture: ImageCapture?
        val active: Boolean
        fun notice(text: String)
    }

    private class Target(val point: MeteringPoint, val feedback: (TouchPhase) -> Unit) {
        /** A tap reports one outcome, from whichever action carrying it completes first or from the scan timeout. Main thread. */
        var answered = false
    }

    private val lock = Any()
    @Volatile var controls = LiveControls()
        private set
    private val aeRelock = AeRelock()
    private val exposureWatch = TouchExposureWatch()
    /** The tapped AF point while it is held, and the pressed AE point until a tap or the AE lock releases it. */
    private var af: Target? = null
    private var ae: Target? = null
    /** The last action scanned and locked AF, so dropping it needs an AF cancel trigger. */
    private var afInAction = false
    /** CONTROL_AF_TRIGGER_CANCEL rides on the interop options until a result shows it was sent. */
    private var afCancelPending = false

    fun setControls(next: LiveControls, restore: Boolean) {
        val old = synchronized(lock) {
            val old = controls
            controls = next
            // A restored lock meets a camera that has just started metering: relock it like a rebuilt session.
            if (old.aeLock != next.aeLock) { if (restore && next.aeLock) startRelock() else aeRelock.lockChanged(next.aeLock) }
            if (old.afLock && !next.afLock) dropFocus()
            if (old.aeLock && !next.aeLock) dropExposure()
            old
        }
        val camera = host.camera ?: return
        telemetry.event(sessionId, "controls_set", mapOf("evIndex" to next.evIndex, "aeLock" to next.aeLock, "afLock" to next.afLock,
            "flash" to next.flash.name, "api" to "CameraControl + Camera2CameraControl"))
        apply(camera, old)
        if (old.afLock != next.afLock || (old.aeLock && !next.aeLock)) submitMetering()
    }

    /**
     * The session was rebuilt with the same camera. A tapped AF point is one-shot and goes; AF lock, a pressed AE
     * point and every other control are sent again, since CameraX may reset them with the session.
     */
    fun sessionRebuilt() {
        // A new session starts with AF unlocked, so there is nothing to cancel.
        synchronized(lock) { dropFocus(); startRelock(); afInAction = false; afCancelPending = false }
        val camera = host.camera ?: return
        apply(camera, null)
        submitMetering()
    }

    /** Sends AF lock and a pressed AE point again, for a stream that went live after [sessionRebuilt]. */
    fun resendMetering() { submitMetering() }

    /** Focuses at ([x], [y]) in the view's pixels, or meters exposure there; PreviewView maps the point itself. */
    fun meterAt(x: Float, y: Float, exposure: Boolean, feedback: (TouchPhase) -> Unit,
        mappedPoint: MeteringPoint? = null): Boolean {
        val camera = host.camera ?: return false
        val point = mappedPoint ?: view.meteringPointFactory.createPoint(x, y)
        val flag = if (exposure) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF
        if (!host.active || !camera.cameraInfo.isFocusMeteringSupported(FocusMeteringAction.Builder(point, flag).build())) return false
        val target = Target(point, feedback)
        synchronized(lock) {
            // As on Camera2: a tap ends a pressed exposure point, and a press ends a tapped focus point.
            dropExposure(); dropFocus()
            if (exposure) { ae = target; exposureWatch.press() } else af = target
        }
        telemetry.event(sessionId, "touch_meter", mapOf("kind" to if (exposure) "AE" else "AF", "x" to x, "y" to y, "api" to "CameraControl.startFocusAndMetering"))
        feedback(TouchPhase.SCANNING)
        // A press that AE never settles on is taken as metered after the timeout, and a tap that no action answers
        // as failed, as on Camera2.
        if (exposure) main.postDelayed({ if (synchronized(lock) { ae === target && exposureWatch.timedOut(exposureWatch.generation) }) exposed(target) }, AeRelock.TIMEOUT_MS)
        else main.postDelayed({ focused(target, TouchPhase.FAILED) }, TouchMeter.SCAN_TIMEOUT_MS)
        val future = submitMetering() ?: return true
        // Results from before the action still carried the old region, so the settle count starts again here.
        if (exposure) future.addListener({
            if (runCatching { future.get() }.isSuccess) synchronized(lock) { if (ae === target) exposureWatch.press() }
        }, { main.post(it) })
        return true
    }

    /**
     * Every result of the repeating request, on the camera's callback thread. CameraX rebuilds a session on its own
     * executor after bindToLifecycle returns, so results of the old, locked session still arrive after
     * [sessionRebuilt]; their LOCKED state must not count as the new session settling. While the relock waits only
     * results of unlocked requests are fed to [AeRelock].
     */
    fun onResult(request: CaptureRequest, result: TotalCaptureResult) {
        val state = result[CaptureResult.CONTROL_AE_STATE]
        val time = result[CaptureResult.SENSOR_EXPOSURE_TIME]; val iso = result[CaptureResult.SENSOR_SENSITIVITY]
        var metered: Target? = null
        val step = synchronized(lock) {
            if (exposureWatch.onResult(state)) metered = ae
            if (aeRelock.waiting && request[CaptureRequest.CONTROL_AE_LOCK] == true) AeRelock.Step.None
            else aeRelock.onResult(state, if (time != null && iso != null) AeRelock.Exposure(time, iso) else null)
        }
        metered?.let { target -> main.post { exposed(target) } }
        val cancelSent = request[CaptureRequest.CONTROL_AF_TRIGGER] == CaptureRequest.CONTROL_AF_TRIGGER_CANCEL &&
            synchronized(lock) { afCancelPending.also { afCancelPending = false } }
        if (cancelSent) host.camera?.let(::applyOptions)
        when (step) {
            AeRelock.Step.None -> Unit
            AeRelock.Step.Relock -> { telemetry.event(sessionId, "ae_relock", mapOf("reason" to "settled", "aeState" to state)); host.camera?.let(::applyOptions) }
            is AeRelock.Step.Relocked -> {
                telemetry.event(sessionId, "ae_relocked", mapOf(
                    "beforeExposureNs" to step.before?.timeNs, "beforeIso" to step.before?.iso,
                    "exposureNs" to step.after.timeNs, "iso" to step.after.iso, "deltaEv" to step.deltaEv))
                LiveControlText.relockNotice(step.deltaEv)?.let { text -> main.post { if (host.active) host.notice(text) } }
            }
        }
    }

    /** Sends what changed since [old], or everything when [old] is null. */
    private fun apply(camera: Camera, old: LiveControls?) {
        val now = controls
        val control = camera.cameraControl
        if (old?.evIndex != now.evIndex && camera.cameraInfo.exposureState.isExposureCompensationSupported) control.setExposureCompensationIndex(now.evIndex)
        host.imageCapture?.flashMode = when (now.flash) {
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.OFF, FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
        }
        if ((old == null || (old.flash == FlashMode.TORCH) != (now.flash == FlashMode.TORCH)) && camera.cameraInfo.hasFlashUnit()) control.enableTorch(now.flash == FlashMode.TORCH)
        if (old?.aeLock != now.aeLock) applyOptions(camera)
    }

    /**
     * The interop options: CONTROL_AE_LOCK as the requests carry it (off while [aeRelock] waits for a rebuilt session
     * to settle), plus the AF cancel trigger while one is pending.
     */
    private fun applyOptions(camera: Camera) {
        val (locked, cancel) = synchronized(lock) { (controls.aeLock && !aeRelock.waiting) to afCancelPending }
        val options = CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
        if (cancel) options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
    }

    /** Caller holds [lock]. With the lock on, the session runs unlocked until [onResult] or the timeout relocks it. */
    private fun startRelock() {
        val generation = aeRelock.sessionRebuilt(controls.aeLock)
        if (!aeRelock.waiting) return
        telemetry.event(sessionId, "ae_relock_wait", emptyMap())
        main.postDelayed({
            // A closed engine's timer must not reach the CameraControl a reopened engine shares.
            if (host.active && synchronized(lock) { aeRelock.timedOut(generation) }) {
                telemetry.event(sessionId, "ae_relock", mapOf("reason" to "timeout"))
                host.camera?.let(::applyOptions)
            }
        }, AeRelock.TIMEOUT_MS)
    }

    /**
     * The one action for everything held now: the tapped point or, under AF lock, the whole frame for AF, and the
     * pressed point for AE. Without either it meters AE on the whole frame, which only resets the regions, and an AF
     * the previous action locked is released with the cancel trigger. Null without a camera.
     */
    private fun submitMetering(): com.google.common.util.concurrent.ListenableFuture<androidx.camera.core.FocusMeteringResult>? {
        val camera = host.camera ?: return null
        var releaseAf = false
        val (tapped, focus, exposure) = synchronized(lock) {
            val focus = af?.point ?: if (controls.afLock) wholeFrame else null
            releaseAf = focus == null && afInAction
            afInAction = focus != null
            // A new AF scan must not meet a cancel still riding on the options.
            if (focus != null) afCancelPending = false else if (releaseAf) afCancelPending = true
            Triple(af, focus, ae?.point)
        }
        if (releaseAf || focus != null) applyOptions(camera)
        val builder = when {
            focus != null -> FocusMeteringAction.Builder(focus, FocusMeteringAction.FLAG_AF).also { b -> exposure?.let { b.addPoint(it, FocusMeteringAction.FLAG_AE) } }
            else -> FocusMeteringAction.Builder(exposure ?: wholeFrame, FocusMeteringAction.FLAG_AE)
        }
        val future = camera.cameraControl.startFocusAndMetering(builder.disableAutoCancel().build())
        // Any action carrying an unanswered tap answers it: a resend cancels the earlier action, whose future then fails.
        if (tapped != null) future.addListener({
            val result = runCatching { future.get() }.getOrNull() ?: return@addListener
            focused(tapped, if (result.isFocusSuccessful) TouchPhase.FOCUSED else TouchPhase.FAILED)
        }, { main.post(it) })
        return future
    }

    /** Main thread. The square stays [TouchMeter.HOLD_MS]; without AF lock the point then ends as well. */
    private fun focused(target: Target, phase: TouchPhase) {
        if (target.answered || synchronized(lock) { af !== target } || !host.active) return
        target.answered = true
        telemetry.event(sessionId, "touch_meter_result", mapOf("kind" to "AF", "phase" to phase.name))
        target.feedback(phase)
        main.postDelayed({
            if (!host.active) return@postDelayed
            val ended = synchronized(lock) {
                if (af !== target) return@postDelayed
                if (controls.afLock) false else { af = null; true }
            }
            target.feedback(TouchPhase.DONE)
            if (ended) submitMetering()
        }, TouchMeter.HOLD_MS)
    }

    private fun exposed(target: Target) {
        if (synchronized(lock) { ae !== target } || !host.active) return
        telemetry.event(sessionId, "touch_meter_result", mapOf("kind" to "AE", "phase" to TouchPhase.METERED.name))
        target.feedback(TouchPhase.METERED)
    }

    /** Caller holds [lock]. Forgets the tapped point; the next [submitMetering] leaves it out. */
    private fun dropFocus() {
        val t = af ?: return
        af = null
        main.post { if (host.active) t.feedback(TouchPhase.DONE) }
    }

    /** Caller holds [lock]. Forgets the pressed point, for a tap, a new press or the AE lock released. */
    private fun dropExposure() {
        val t = ae ?: return
        ae = null; exposureWatch.cancel()
        main.post { if (host.active) t.feedback(TouchPhase.DONE) }
    }

    private companion object {
        /** AF lock without a tapped point meters the whole frame, as Camera2's trigger does without AF regions. */
        val wholeFrame: MeteringPoint = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, 1f)
    }
}
