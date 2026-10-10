package dev.halcamera.camera

/** A physical output belongs to the parent device; a service source owns a separate device. */
data class PipSource(val id: String, val physical: Boolean, val label: String) {
    val key get() = "${if (physical) "physical" else "service"}:$id"
}

internal data class PipInputRoute(val deviceId: String, val physicalId: String? = null)

internal object PipRouting {
    fun inputs(parent: String, physical: List<String>, service: String?): List<PipInputRoute> {
        require(physical.size + (if (service == null) 0 else 1) <= 1) { "Choose one PIP camera" }
        require(service != parent) { "PIP camera must differ from the main camera" }
        return listOf(PipInputRoute(parent)) + physical.map { PipInputRoute(parent,it) } +
            listOfNotNull(service?.let { PipInputRoute(it) })
    }
}
