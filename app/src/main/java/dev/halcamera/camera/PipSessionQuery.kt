package dev.halcamera.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi

internal fun readPipPreviewSizes(manager: CameraManager, id: String): List<LiveSize> =
    manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        ?.getOutputSizes(SurfaceTexture::class.java)?.map { LiveSize(it.width,it.height) }.orEmpty()
        .filter { it.width.toLong()*it.height <= 1280L*720 }.sortedByDescending { it.width.toLong()*it.height }

internal fun readPipPreviewSize(manager: CameraManager, id: String): LiveSize? = readPipPreviewSizes(manager,id).firstOrNull()

/** Temporary, detached preview surfaces query the exact output sizes without changing any live session. */
@RequiresApi(30)
internal fun queryCamera2Pip(manager: CameraManager, camera: CameraDevice, source: PipSource,
                            preview: LiveSize, retained: List<ConfiguredOutput<Surface>>, handler: Handler) =
    choosePipPreviewSizes(listOf(preview),readPipPreviewSizes(manager,source.id)) { mainSize,pipSize ->
        try { queryPipOutputs(manager,camera.id,source,mainSize,pipSize,retained,handler,camera) }
        catch (_: Exception) { PipStreamSupport.UNKNOWN }
    }

@RequiresApi(30)
internal fun queryPipOutputs(manager: CameraManager, parent: String, source: PipSource,
                            preview: LiveSize, secondary: LiveSize, retained: List<ConfiguredOutput<Surface>>,
                            handler: Handler, camera: CameraDevice? = null): PipStreamSupport {
    val textures = mutableListOf<SurfaceTexture>()
    val surfaces = mutableListOf<Surface>()
    try {
        listOf(preview,secondary).forEach { size ->
            val texture = SurfaceTexture(false).also { textures += it }
            texture.setDefaultBufferSize(size.width,size.height)
            surfaces += Surface(texture)
        }
        val plan = PipOutputs(source.id.takeIf { source.physical })
        val mainOutputs = plan.configure(if (source.physical) surfaces else listOf(surfaces[0]),retained)
        fun config(outputs: List<ConfiguredOutput<Surface>>) = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,outputs.map { value -> OutputConfiguration(value.target).apply {
                value.descriptor.physicalId?.let { setPhysicalCameraId(it) }
            } }, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = Unit
                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            })
        val mainConfig = config(mainOutputs.outputs)
        val supported = if (source.physical) checkNotNull(camera).isSessionConfigurationSupported(mainConfig)
        else manager.isConcurrentSessionConfigurationSupported(mapOf(parent to mainConfig,
            source.id to config(listOf(ConfiguredOutput(OutputDescriptor("pip",OutputKind.PREVIEW,true),surfaces[1])))))
        return if (supported) PipStreamSupport.SUPPORTED else PipStreamSupport.UNSUPPORTED
    } catch (_: IllegalArgumentException) {
        return PipStreamSupport.UNSUPPORTED
    } catch (_: SecurityException) {
        return PipStreamSupport.UNKNOWN
    } finally {
        surfaces.forEach { it.release() }
        textures.forEach { it.release() }
    }
}

/** Query both devices in concurrent mode, and return the sizes the actual CameraX previews must use. */
@RequiresApi(30)
internal fun queryCameraXPip(manager: CameraManager, parent: String, source: PipSource,
                            analysis: LiveSize?, handler: Handler): Pair<PipStreamSupport,List<LiveSize>?> {
    val mainSizes = readPipPreviewSizes(manager,parent)
    val pipSizes = readPipPreviewSizes(manager,source.id)
    if (mainSizes.isEmpty() || pipSizes.isEmpty()) return PipStreamSupport.UNSUPPORTED to null
    val reader = analysis?.let { android.media.ImageReader.newInstance(it.width,it.height,android.graphics.ImageFormat.YUV_420_888,2) }
    try {
        val retained = reader?.let { listOf(ConfiguredOutput(OutputDescriptor("analysis",OutputKind.YUV,true),it.surface)) }.orEmpty()
        return choosePipPreviewSizes(mainSizes,pipSizes) { mainSize,pipSize ->
            try { queryPipOutputs(manager,parent,source,mainSize,pipSize,retained,handler) }
            catch (_: Exception) { PipStreamSupport.UNKNOWN }
        }
    } finally { reader?.close() }
}
