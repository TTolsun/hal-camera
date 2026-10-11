package dev.halcamera.cli

import android.content.Context
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import dev.halcamera.camera.CameraEndpointResolver
import dev.halcamera.camera.CameraProbeReader
import dev.halcamera.camera.CameraProbeText
import dev.halcamera.cts.CtsCatalog
import dev.halcamera.cts.suite.SuiteItem
import dev.halcamera.cts.suite.SuitePlan
import dev.halcamera.ctsvendor.VendoredCatalog
import dev.halcamera.ctsvendor.VendoredCts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** Screenless device discovery and report generation run separately from request ownership. */
class CliQueries(private val context: Context, private val commands: CliArtifacts, private val io: Executor) {
    fun execute(command: CliCommand) {
        when (command.command) {
            "streams" -> streamSupport(command)
            "probe" -> probe(command)
            "cts.cases" -> commands.complete(command.id, JSONObject().put("cases", CliJson.of(suiteItems())))
            "cameras" -> {
                val endpoints = CameraEndpointResolver(context.getSystemService(CameraManager::class.java)).resolve()
                val cameras = JSONArray(endpoints.map { JSONObject(it.toJsonMap()).put("selectable", it.independentlyOpenable && it.physicalCameraId == null) })
                commands.complete(command.id, JSONObject().put("cameras", cameras))
            }
            "dual.cameras" -> commands.complete(command.id, JSONObject().put("cameras", CliJson.of(dev.halcamera.camera.readLogicalMultiCameras(context.getSystemService(CameraManager::class.java)).map {
                mapOf("camera_id" to it.logicalId, "physical" to it.physical.map { lens -> mapOf("id" to lens.id, "role" to lens.role.name) })
            })))
            else -> throw CliFailure("INVALID_ARGUMENT", "Unknown query")
        }
    }

    private fun streamSupport(command: CliCommand) {
        io.execute {
            try {
                val manager = context.getSystemService(CameraManager::class.java)
                if (command.camera !in manager.cameraIdList) throw CliFailure("UNSUPPORTED_CAMERA", "Camera is not independently openable")
                var support = dev.halcamera.camera.liveStreamSupport(manager.getCameraCharacteristics(command.camera!!))
                if (command.engine == "CameraX") support = dev.halcamera.camera.cameraXStreamSupport(context, command.camera, support)
                val characteristics = manager.getCameraCharacteristics(command.camera)
                val controls = dev.halcamera.camera.liveControlSupport(characteristics)
                val manual = if (command.engine == "CameraX") dev.halcamera.camera.ManualSupport(camera2 = false) else dev.halcamera.camera.manualSupport(characteristics, 30)
                commands.complete(command.id, JSONObject().put("camera_id", command.camera).put("engine", command.engine ?: "Camera2").put("mode", command.mode ?: "Photo")
                    .put("controls", CliJson.of(mapOf("zoom" to dev.halcamera.camera.zoomRange(manager, command.camera).let { listOf(it.first, it.second) },
                        "ev_steps" to controls.evRange?.let { listOf(it.first, it.last) }, "ev_step" to controls.evStep,
                        "ae_lock" to controls.aeLock, "af_lock" to controls.afLock, "flash" to controls.flashModes(command.videoMode).map { it.name },
                        "iso" to manual.iso?.let { listOf(it.first, it.last) }, "exposure_ns" to manual.exposureNs?.let { listOf(it.first.toString(), manual.maxExposureNs.toString()) },
                        "focus_max_diopters" to manual.maxFocus, "white_balance" to manual.whiteBalances.map { it.name })))
                    .put("streams", CliJson.of(mapOf("preview" to support.preview.map { it.toString() },
                        "yuv" to support.yuv.map { it.toString() }, "jpeg" to support.jpeg.map { it.toString() },
                        "raw" to support.raw.takeUnless { command.videoMode }.orEmpty().map { it.toString() }, "fps" to support.fps.takeUnless { command.videoMode }.orEmpty().map { "${it.min}-${it.max}" },
                        "stabilization" to (if (command.videoMode) support.stabilization else listOf(dev.halcamera.camera.LiveStabilization.OFF)).map { it.name }, "jpeg_source" to listOf("CAMERA", "YUV"),
                        "video" to support.videos.takeIf { command.videoMode }.orEmpty().map { mapOf("size" to it.size.toString(), "fps" to it.fps, "codec" to it.codec) }))))
            } catch (e: Exception) { commands.fail(command.id, (e as? CliFailure)?.code ?: "PREFLIGHT_FAILED", e.message ?: "Cannot read stream capabilities") }
        }
    }

    /** Probe reads CameraCharacteristics only, so it needs no screen: read on the IO thread and file the two renderings. */
    private fun probe(command: CliCommand) {
        io.execute {
            try {
                val snapshot = CameraProbeReader(context.getSystemService(CameraManager::class.java)).read()
                val dir = commands.artifactDir(command.id)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                // halcam accepts one dot in an artifact name, so the model keeps letters, digits, _ and - only.
                val model = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val json = File(dir, "camera-probe-$model-$stamp.json").apply { writeText((CliJson.of(snapshot.toJsonMap()) as JSONObject).toString(2), Charsets.UTF_8) }
                val text = File(dir, "camera-probe-$model-$stamp.txt").apply { writeText(CameraProbeText.render(snapshot), Charsets.UTF_8) }
                commands.complete(command.id, JSONObject().put("captured_at", snapshot.capturedAt).put("camera_count", snapshot.cameras.size)
                    .put("errors", JSONArray(snapshot.errors)).put("artifact_count", 2),
                    listOf(CliArtifact(json.name, "application/json", Uri.fromFile(json)), CliArtifact(text.name, "text/plain", Uri.fromFile(text))))
            } catch (e: Exception) { commands.fail(command.id, (e as? CliFailure)?.code ?: "PROBE_FAILED", e.message ?: "Cannot read camera characteristics") }
        }
    }

    /**
     * The CTS suite checklist as the CLI lists it: every item of both lists with the key `cts.run` takes. The
     * vendored list needs the in-app Instrumentation installed and exists only from API 34.
     */
    fun suiteItems(): List<Map<String, Any?>> {
        val vendored = if (Build.VERSION.SDK_INT >= VendoredCts.MIN_SDK) { VendoredCts.install(context); VendoredCatalog.tests() } else emptyList()
        val cameras = context.getSystemService(CameraManager::class.java).cameraIdList.size
        return SuitePlan.items(CtsCatalog.cases, vendored).map { item ->
            mapOf("key" to item.key, "kind" to if (item is SuiteItem.Custom) "custom" else "vendored", "title" to item.title,
                "source" to item.source, "needs_audio" to item.needsAudio, "estimate_seconds" to item.estimateSeconds(cameras))
        }
    }

}
