package dev.halcamera.cli

import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder

/** Preserve callback identity and clock domains in both Main and Dual diagnostics. */
internal object CliEvents {
    fun recent(recorder: FlightRecorder): List<Map<String, Any?>> =
        recorder.snapshot(1_000_000_000L).takeLast(100).map(::toMap)

    fun toMap(event: Event): Map<String, Any?> = mapOf(
        "kind" to event.kind,
        "at_ns" to event.atNs.toString(),
        "session_id" to event.session,
        "frame_number" to event.frame,
        "sensor_timestamp_ns" to event.sensorNs?.toString(),
        "values" to event.values
    )
}
