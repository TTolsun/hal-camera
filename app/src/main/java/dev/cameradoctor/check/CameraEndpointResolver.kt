package dev.cameradoctor.check

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Enumerates camera endpoints from the public API only (docs/PRODUCT-v0.2.md 9.3). Hidden ids are never probed.
 * Physical cameras behind a logical camera are listed with independentlyOpenable=false and are not checked in v0.2.
 */
class CameraEndpointResolver(private val manager: CameraManager) {

    fun resolve(): List<CameraEndpoint> {
        val ids = try { manager.cameraIdList.toList() } catch (_: Exception) { emptyList() }
        val rear = mutableListOf<Pair<String, CameraCharacteristics>>()
        val all = mutableListOf<CameraEndpoint>()
        val chars = ids.mapNotNull { id -> try { id to manager.getCameraCharacteristics(id) } catch (_: Exception) { null } }
        chars.forEach { (id, c) -> if (c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK) rear += id to c }

        val rearRoles = LensRoles.dedupeMain(rear.map { (_, c) -> LensRoles.roleFor(equivalentFocal(c)) })
        val roleById = rear.mapIndexed { i, (id, _) -> id to rearRoles[i] }.toMap()

        chars.forEach { (id, c) ->
            val facing = c[CameraCharacteristics.LENS_FACING] ?: -1
            val role = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> LensRole.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> roleById[id] ?: LensRole.UNKNOWN
                else -> LensRole.EXTERNAL
            }
            val zoom = zoomRange(c)
            all += endpoint(id, null, role, facing, c, zoom, openable = true)
            if (Build.VERSION.SDK_INT >= 28) {
                c.physicalCameraIds.forEach { pid ->
                    val pc = try { manager.getCameraCharacteristics(pid) } catch (_: Exception) { null }
                    val prole = pc?.let { LensRoles.roleFor(equivalentFocal(it)) } ?: LensRole.UNKNOWN
                    all += endpoint(id, pid, if (facing == CameraCharacteristics.LENS_FACING_FRONT) LensRole.FRONT else prole, facing, pc ?: c, pc?.let(::zoomRange) ?: zoom,
                        openable = pid in ids)
                }
            }
        }
        return all
    }

    private fun endpoint(id: String, pid: String?, role: LensRole, facing: Int, c: CameraCharacteristics, zoom: Pair<Float, Float>?, openable: Boolean) =
        CameraEndpoint(
            logicalCameraId = id, physicalCameraId = pid, role = role, facing = facing,
            independentlyOpenable = openable && pid == null,
            selectableByZoom = zoom != null && zoom.first < 1f,
            exposedToCameraX = null,
            equivalentFocalMm = equivalentFocal(c),
            timestampSource = c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE],
            hardwareLevel = c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL],
            zoomRatioMin = zoom?.first, zoomRatioMax = zoom?.second
        )

    private fun equivalentFocal(c: CameraCharacteristics): Double? {
        val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull()
        val size = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        return LensRoles.equivalentFocalMm(focal, size?.width, size?.height)
    }

    private fun zoomRange(c: CameraCharacteristics): Pair<Float, Float>? =
        if (Build.VERSION.SDK_INT >= 30) c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let { it.lower to it.upper }
        else c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]?.let { 1f to it }
}
