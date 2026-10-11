package dev.halcamera.camera

/** A physical output belongs to the parent device; a service source owns a separate device. */
data class PipSource(val id: String, val physical: Boolean, val label: String, val unavailableReason: String? = null) {
    val key get() = "${if (physical) "physical" else "service"}:$id"
    val selectable get() = unavailableReason == null
}

/** Service devices must belong to a combination advertised by the device, regardless of lens labels. */
internal object PipAvailability {
    fun serviceReason(devices: Set<String>, combinations: Set<Set<String>>): String? =
        if (combinations.any { it.containsAll(devices) }) null
        else "Concurrent use with the main camera is not supported"
}

/** Unknown support is kept visible but cannot be selected until it can be verified. */
internal enum class PipStreamSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

internal fun PipSource.withStreamSupport(query: () -> PipStreamSupport): PipSource {
    if (!selectable) return this
    val support = try { query() } catch (_: Exception) { PipStreamSupport.UNKNOWN }
    return copy(unavailableReason = when (support) {
        PipStreamSupport.SUPPORTED -> null
        PipStreamSupport.UNSUPPORTED -> "The current PIP resolution / stream combination is not supported"
        PipStreamSupport.UNKNOWN -> "Stream support cannot be verified on this device"
    })
}

/** Shared by the support query and actual compositor input allocation. */
internal fun pipPreviewSize(sizes: List<LiveSize>): LiveSize? = sizes
    .filter { it.width.toLong() * it.height <= 1280L * 720 }
    .maxByOrNull { it.width.toLong() * it.height }

internal data class PipInputRoute(val deviceId: String, val physicalId: String? = null)

internal object PipRouting {
    fun inputs(parent: String, physical: List<String>, service: String?): List<PipInputRoute> {
        require(physical.size + (if (service == null) 0 else 1) <= 1) { "Choose one PIP camera" }
        require(service != parent) { "PIP camera must differ from the main camera" }
        return listOf(PipInputRoute(parent)) + physical.map { PipInputRoute(parent,it) } +
            listOfNotNull(service?.let { PipInputRoute(it) })
    }
}
