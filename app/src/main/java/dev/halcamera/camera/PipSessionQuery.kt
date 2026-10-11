package dev.halcamera.camera

import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.media.ImageReader
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi

/** Camera-thread adapter shared by picker preflight and selection; sizes belong to the checked outputs. */
internal class Camera2PipSupport(private val manager: CameraManager, private val handler: Handler) {
    private val sizes = mutableMapOf<String,PipCheckedInputs>()
    fun size(source: PipSource) = sizes[source.key]?.previews?.get(1)
    fun photoSize(source: PipSource) = sizes[source.key]?.photo
    fun checkAll(candidates: List<PipSource>, check: (PipSource) -> PipSource, main: Handler,
                 done: (List<PipSource>) -> Unit) {
        handler.post { val checked = candidates.map(check); main.post { done(checked) } }
    }
    fun check(source: PipSource, candidates: List<PipSource>, camera: CameraDevice?, preview: LiveSize?,
              retained: List<ConfiguredOutput<Surface>>, photo: PipPhotoPlan? = null, streams: PipStreamSettings? = null): PipSource {
        val candidate = candidates.firstOrNull { it.key == source.key }
            ?: return source.copy(unavailableReason = "Cannot run together")
        return candidate.withStreamSupport {
            sizes.remove(source.key)
            if (android.os.Build.VERSION.SDK_INT < 30 || camera == null || preview == null) PipStreamSupport.UNKNOWN
            else {
                val (support,inputs) = queryCamera2Pip(manager,camera,candidate,preview,retained,handler,photo,streams)
                inputs?.let { sizes[source.key] = it }
                support
            }
        }
    }
}

internal fun camera2PipPhotoPlan(settings: LiveStreamSettings?, jpeg: ImageReader?, yuv: ImageReader?): PipPhotoPlan? =
    if (settings?.jpegFromYuv == true) yuv?.let { PipPhotoPlan(OutputKind.YUV, LiveSize(it.width,it.height)) }
    else jpeg?.let { PipPhotoPlan(OutputKind.JPEG, LiveSize(it.width,it.height)) }

internal fun readPipPhotoSizes(manager: CameraManager, source: PipSource, plan: PipPhotoPlan?, requested: LiveSize? = null): List<LiveSize?> =
    if (plan == null) listOf(null) else {
        val format = if (plan.kind == OutputKind.JPEG) ImageFormat.JPEG else ImageFormat.YUV_420_888
        val sizes = manager.getCameraCharacteristics(source.id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?.getOutputSizes(format)?.map { LiveSize(it.width,it.height) }.orEmpty()
        if (requested != null) sizes.filter { it == requested } else plan.candidates(sizes)
    }

internal class CameraXPipSupport(private val manager: CameraManager, private val parent: String,
                                 private val main: Handler, private val executor: java.util.concurrent.Executor) {
    private val sizes = java.util.concurrent.ConcurrentHashMap<String,PipCheckedInputs>()
    fun sizes(source: PipSource) = sizes[source.key]?.previews
    fun photoSize(source: PipSource) = sizes[source.key]?.photo
    fun check(source: PipSource, analysis: LiveSize?, available: Boolean, settings: LiveStreamSettings? = null,
        nativePhoto: LiveSize? = null): PipSource = source.withStreamSupport {
        sizes.remove(source.key)
        if (!cameraXConcurrentAnalysisSupported(analysis)) PipStreamSupport.UNSUPPORTED
        else if (!available || android.os.Build.VERSION.SDK_INT < 30) PipStreamSupport.UNKNOWN
        else {
            val photo = cameraXPipPhotoPlan(manager,parent,settings,analysis,nativePhoto)
            val (support,inputs) = queryCameraXPip(manager,parent,source,analysis,main,photo,settings)
            inputs?.let { sizes[source.key] = it }
            support
        }
    }
    fun checkAll(candidates: List<PipSource>, analysis: LiveSize?, available: () -> Boolean,
                 done: (List<PipSource>) -> Unit, settings: LiveStreamSettings? = null, nativePhoto: LiveSize? = null) {
        executor.execute { val checked = candidates.map { check(it,analysis,available(),settings,nativePhoto) }; main.post { done(checked) } }
    }
}

internal fun cameraXPipPhotoPlan(manager: CameraManager, parent: String, settings: LiveStreamSettings?,
    analysis: LiveSize?, nativePhoto: LiveSize?): PipPhotoPlan? {
    if (settings?.jpegFromYuv == true) return analysis?.let { PipPhotoPlan(OutputKind.YUV,it) }
    if (settings != null && settings.jpeg == null) return null
    val size = (if (settings == null) null else nativePhoto ?: settings.jpeg) ?: manager.getCameraCharacteristics(parent)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        ?.getOutputSizes(ImageFormat.JPEG)?.map { LiveSize(it.width,it.height) }?.let { sizes ->
            PipPhotoPlan(OutputKind.JPEG,LiveSize(1920,1080)).candidates(sizes).firstOrNull()
        } ?: return null
    return PipPhotoPlan(OutputKind.JPEG,size)
}

internal fun readPipPreviewSizes(manager: CameraManager, id: String, requested: LiveSize? = null): List<LiveSize> {
    val sizes = manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        ?.getOutputSizes(SurfaceTexture::class.java)?.map { LiveSize(it.width,it.height) }.orEmpty()
    return sizes.filter { if (requested != null) it == requested else it.width.toLong()*it.height <= 1280L*720 }
        .sortedByDescending { it.width.toLong()*it.height }
}

internal fun readPipPreviewSize(manager: CameraManager, id: String): LiveSize? = readPipPreviewSizes(manager,id).firstOrNull()

/** Temporary, detached preview surfaces query the exact output sizes without changing any live session. */
@RequiresApi(30)
internal fun queryCamera2Pip(manager: CameraManager, camera: CameraDevice, source: PipSource,
                            preview: LiveSize, retained: List<ConfiguredOutput<Surface>>, handler: Handler, photo: PipPhotoPlan? = null,
                            streams: PipStreamSettings? = null) =
    choosePipInputs(listOf(preview),readPipPreviewSizes(manager,source.id,streams?.preview),readPipPhotoSizes(manager,source,photo,streams?.photo)) { mainSize,pipSize,photoSize ->
        try { queryPipOutputs(manager,camera.id,source,mainSize,pipSize,retained,handler,camera,photo,photoSize) }
        catch (_: Exception) { PipStreamSupport.UNKNOWN }
    }

@RequiresApi(30)
internal fun queryPipOutputs(manager: CameraManager, parent: String, source: PipSource,
                            preview: LiveSize, secondary: LiveSize, retained: List<ConfiguredOutput<Surface>>,
                            handler: Handler, camera: CameraDevice? = null,
                            photo: PipPhotoPlan? = null, photoSize: LiveSize? = null): PipStreamSupport {
    val textures = mutableListOf<SurfaceTexture>()
    val surfaces = mutableListOf<Surface>()
    var photoReader: ImageReader? = null
    try {
        listOf(preview,secondary).forEach { size ->
            val texture = SurfaceTexture(false).also { textures += it }
            texture.setDefaultBufferSize(size.width,size.height)
            surfaces += Surface(texture)
        }
        val plan = PipOutputs(source.id.takeIf { source.physical })
        val original = if (photo != null && photoSize != null) {
            photoReader = ImageReader.newInstance(photoSize.width,photoSize.height,
                if (photo.kind == OutputKind.JPEG) ImageFormat.JPEG else ImageFormat.YUV_420_888,2)
            listOf(ConfiguredOutput(photo.descriptor(source),checkNotNull(photoReader).surface))
        } else emptyList()
        val mainOutputs = plan.configure(if (source.physical) surfaces else listOf(surfaces[0]),retained + if (source.physical) original else emptyList())
        fun config(outputs: List<ConfiguredOutput<Surface>>) = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,pipOutputConfigurations(outputs), { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = Unit
                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            })
        val mainConfig = config(mainOutputs.outputs)
        val supported = if (source.physical) checkNotNull(camera).isSessionConfigurationSupported(mainConfig)
        else manager.isConcurrentSessionConfigurationSupported(mapOf(parent to mainConfig,
            source.id to config(listOf(ConfiguredOutput(OutputDescriptor("pip",OutputKind.PREVIEW,true),surfaces[1])) + original)))
        return if (supported) PipStreamSupport.SUPPORTED else PipStreamSupport.UNSUPPORTED
    } catch (_: IllegalArgumentException) {
        return PipStreamSupport.UNSUPPORTED
    } catch (_: SecurityException) {
        return PipStreamSupport.UNKNOWN
    } finally {
        photoReader?.close()
        surfaces.forEach { it.release() }
        textures.forEach { it.release() }
    }
}

/** Query both devices in concurrent mode, and return the sizes the actual CameraX previews must use. */
@RequiresApi(30)
internal fun queryCameraXPip(manager: CameraManager, parent: String, source: PipSource,
                            analysis: LiveSize?, handler: Handler, photo: PipPhotoPlan? = null,
                            settings: LiveStreamSettings? = null): Pair<PipStreamSupport,PipCheckedInputs?> {
    val mainSizes = readPipPreviewSizes(manager,parent,settings?.preview)
    val pipSizes = readPipPreviewSizes(manager,source.id,settings?.pip?.get(source.key)?.preview)
    if (mainSizes.isEmpty() || pipSizes.isEmpty()) return PipStreamSupport.UNSUPPORTED to null
    var reader: ImageReader? = null
    var jpeg: ImageReader? = null
    try {
        reader = analysis?.let { ImageReader.newInstance(it.width,it.height,ImageFormat.YUV_420_888,2) }
        jpeg = photo?.takeIf { it.kind == OutputKind.JPEG }?.mainSize?.let {
            ImageReader.newInstance(it.width,it.height,ImageFormat.JPEG,2)
        }
        val retained = listOfNotNull(reader?.let { ConfiguredOutput(OutputDescriptor("analysis",OutputKind.YUV,true),it.surface) },
            jpeg?.let { ConfiguredOutput(OutputDescriptor("still",OutputKind.JPEG,false,true),it.surface) })
        return choosePipInputs(mainSizes,pipSizes,readPipPhotoSizes(manager,source,photo,settings?.pip?.get(source.key)?.photo)) { mainSize,pipSize,photoSize ->
            val mainJpeg = photo?.mainSize?.takeIf { photo.kind == OutputKind.JPEG }
            val secondaryYuv = photoSize?.takeIf { photo?.kind == OutputKind.YUV }
            val secondaryJpeg = photoSize?.takeIf { photo?.kind == OutputKind.JPEG }
            if (!cameraXConcurrentPipStreamsSupported(mainSize,analysis,mainJpeg) ||
                !cameraXConcurrentPipStreamsSupported(pipSize,secondaryYuv,secondaryJpeg)) return@choosePipInputs PipStreamSupport.UNSUPPORTED
            try { queryPipOutputs(manager,parent,source,mainSize,pipSize,retained,handler,photo = photo,photoSize = photoSize) }
            catch (_: Exception) { PipStreamSupport.UNKNOWN }
        }
    } finally { jpeg?.close(); reader?.close() }
}
