package dev.halcamera.camera

/**
 * Capability model from docs/archive/PRODUCT-v0.2.md 9.2. Built from enumeration results; the UI never assumes a lens
 * exists. It sits in `camera` rather than the deleted `check` package because an endpoint identifies a camera and
 * has nothing to do with whichever measurement is pointed at it.
 */
enum class LensRole { MAIN, ULTRA_WIDE, TELE, FRONT, EXTERNAL, UNKNOWN }

data class CameraEndpoint(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val role: LensRole,
    val facing: Int,                     // CameraCharacteristics.LENS_FACING value
    val independentlyOpenable: Boolean,
    val selectableByZoom: Boolean,
    val exposedToCameraX: Boolean?,      // null until CameraX enumeration is compared
    val equivalentFocalMm: Double?,
    val timestampSource: Int?,
    val hardwareLevel: Int?,
    val zoomRatioMin: Float?,
    val zoomRatioMax: Float?
) {
    val key: String get() = physicalCameraId?.let { "$logicalCameraId.$it" } ?: logicalCameraId
    /** CDD camera latency requirements apply to the primary rear and front cameras only. */
    val primary: Boolean get() = physicalCameraId == null && (role == LensRole.MAIN || role == LensRole.FRONT)

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "logicalCameraId" to logicalCameraId, "physicalCameraId" to physicalCameraId, "role" to role.name,
        "facing" to facing, "independentlyOpenable" to independentlyOpenable, "selectableByZoom" to selectableByZoom,
        "exposedToCameraX" to exposedToCameraX, "equivalentFocalMm" to equivalentFocalMm,
        "timestampSource" to timestampSource, "hardwareLevel" to hardwareLevel,
        "zoomRatioMin" to zoomRatioMin, "zoomRatioMax" to zoomRatioMax
    )
}

/**
 * Role inference from 35 mm equivalent focal length (9.3 step 3). Pure so it can be tested without CameraManager.
 * Returns roles for the given rear endpoints in input order; at most one MAIN, others in the 20-35 mm band become UNKNOWN.
 */
object LensRoles {
    fun equivalentFocalMm(focalMm: Float?, sensorWidthMm: Float?, sensorHeightMm: Float?): Double? {
        if (focalMm == null || sensorWidthMm == null || sensorHeightMm == null || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
        val diagonal = kotlin.math.sqrt((sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm).toDouble())
        return focalMm * 43.27 / diagonal
    }

    fun roleFor(equivalentFocalMm: Double?): LensRole = when {
        equivalentFocalMm == null -> LensRole.UNKNOWN
        equivalentFocalMm < 20.0 -> LensRole.ULTRA_WIDE
        equivalentFocalMm <= 35.0 -> LensRole.MAIN
        else -> LensRole.TELE
    }

    /** Keep only the first MAIN among rear cameras; later MAIN candidates become UNKNOWN. */
    fun dedupeMain(roles: List<LensRole>): List<LensRole> {
        var seen = false
        return roles.map { r -> if (r == LensRole.MAIN) { if (seen) LensRole.UNKNOWN else { seen = true; r } } else r }
    }

    /** 10.2: check order when more than maxEndpoints are openable. */
    fun checkOrder(endpoints: List<CameraEndpoint>): List<CameraEndpoint> {
        val rank = mapOf(LensRole.MAIN to 0, LensRole.FRONT to 1, LensRole.ULTRA_WIDE to 2, LensRole.TELE to 3, LensRole.UNKNOWN to 4, LensRole.EXTERNAL to 5)
        return endpoints.sortedWith(compareBy({ rank[it.role] ?: 9 }, { it.logicalCameraId }))
    }
}
