package dev.halcamera.cli

/**
 * One line of `requests.list`: enough for a client that lost its request ID to find it again and decide between
 * `status`, `fetch` or waiting, without the request parameters or file locations. Pure Kotlin, so the listing order
 * and shape are JVM tested apart from [CommandStore].
 */
data class CliRequestSummary(
    val requestId: String,
    val command: String,
    val state: String,
    val createdAtMs: Long,
    val artifactCount: Int,
    val errorCode: String? = null
) {
    val completed get() = state in CliStates.terminal

    fun toMap(): Map<String, Any?> = mapOf("request_id" to requestId, "command" to command, "state" to state,
        "completed" to completed, "created_at_ms" to createdAtMs, "artifact_count" to artifactCount, "error_code" to errorCode)

    companion object {
        /** Newest first, so the request just lost is the first row; equal times keep a stable order by ID. */
        fun listing(items: List<CliRequestSummary>): List<Map<String, Any?>> =
            items.sortedWith(compareByDescending<CliRequestSummary> { it.createdAtMs }.thenBy { it.requestId }).map { it.toMap() }
    }
}
