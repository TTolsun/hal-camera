package dev.halcamera.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.annotation.RequiresApi
@RequiresApi(30)
fun readConcurrentPlans(manager: CameraManager, maxDevices: Int = Int.MAX_VALUE): List<ConcurrentPlan> =
    ConcurrentPlanner.plans(30, manager.concurrentCameraIds, manager.cameraIdList.map { readConcurrentCamera(manager, it) }, maxDevices)

@RequiresApi(30)
fun readConcurrentCamera(manager: CameraManager, id: String): ConcurrentCamera {
    val c = manager.getCameraCharacteristics(id)
    val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
    val logical = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in
        (c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf())
    return ConcurrentCamera(id, c[CameraCharacteristics.LENS_FACING],
        map?.getOutputSizes(SurfaceTexture::class.java)?.map { LiveSize(it.width,it.height) }.orEmpty(),
        map?.getOutputSizes(ImageFormat.JPEG)?.map { LiveSize(it.width,it.height) }.orEmpty(),
        if (logical) c.physicalCameraIds.sorted() else emptyList())
}

fun readPipSources(manager: CameraManager, parent: String): List<PipSource> {
    if (android.os.Build.VERSION.SDK_INT < 30 || parent.isEmpty()) return emptyList()
    return runCatching {
        val combinations = runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet())
        val physical = readConcurrentCamera(manager,parent).physicalIds.map { PipSource(it,true,"Physical $it") }
        val service = manager.cameraIdList.filter { it != parent }.map { id ->
            val camera = runCatching { readConcurrentCamera(manager,id) }.getOrNull()
            val reason = when {
                camera == null -> "Unavailable"
                camera.previews.isEmpty() -> "No preview"
                else -> PipAvailability.serviceReason(setOf(parent,id),combinations)
            }
            PipSource(id,false,"${camera?.label ?: "Camera $id"} · Service",reason)
        }
        physical + service
    }.getOrDefault(emptyList())
}
