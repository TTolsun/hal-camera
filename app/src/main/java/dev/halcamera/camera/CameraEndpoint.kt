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
 * The one spelling for naming a camera on screen: "Camera · 0 (Wide · Rear)", or "Camera · 0" where the row is too
 * narrow for the qualifier. Every screen used to invent its own — LIVE said "후면 · 0", BENCHMARK said
 * "Rear main · ID 0", PROBE said "후면 · MAIN · FULL" and dropped the id entirely, CTS said "ID 0" — so the same
 * camera read as a different camera depending on where you looked.
 *
 * Android-free on purpose: `benchmark/domain` borrows it, and `LayerIsolationTest` rejects a borrowed symbol whose
 * file imports Android.
 */
object CameraLabel {
    // CameraCharacteristics.LENS_FACING values, repeated rather than imported to keep this file Android-free.
    const val FACING_FRONT = 0
    const val FACING_BACK = 1
    const val FACING_EXTERNAL = 2

    /** "Camera · 0" — buttons, filter chips and table cells that cannot hold the qualifier. */
    fun short(cameraKey: String): String = if (cameraKey.isEmpty()) "Camera · —" else "Camera · $cameraKey"

    /** "Camera · 0 (Wide · Rear)", falling back to [short] when the enumeration placed neither lens nor facing. */
    fun full(cameraKey: String, role: LensRole, facing: Int?): String {
        val qualifier = listOfNotNull(lens(role), facing(role, facing)).joinToString(" · ")
        return if (qualifier.isEmpty()) short(cameraKey) else "${short(cameraKey)} ($qualifier)"
    }

    fun full(endpoint: CameraEndpoint): String = full(endpoint.key, endpoint.role, endpoint.facing)

    /** Null when the role carries no lens: a front or external camera's lens is never inferred, and UNKNOWN failed. */
    fun lens(role: LensRole): String? = when (role) {
        LensRole.MAIN -> "Wide"
        LensRole.ULTRA_WIDE -> "UWide"
        LensRole.TELE -> "Tele"
        LensRole.FRONT, LensRole.EXTERNAL, LensRole.UNKNOWN -> null
    }

    /**
     * The reported value decides. The role is only a fallback, and never for EXTERNAL: both enumerations use
     * EXTERNAL as their `else` branch when LENS_FACING could not be read, so an EXTERNAL role with no value behind
     * it proves nothing. Naming that camera "External" would put a claim in PROBE that the HAL never made.
     */
    fun facing(role: LensRole, facing: Int?): String? = when (facing) {
        FACING_BACK -> "Rear"
        FACING_FRONT -> "Front"
        FACING_EXTERNAL -> "External"
        else -> if (role == LensRole.FRONT) "Front" else null
    }
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
