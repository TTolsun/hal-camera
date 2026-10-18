package dev.halcamera.cli

import org.junit.Assert.*
import org.junit.Test

class CliRequestSummaryTest {
    private val older = CliRequestSummary("a616d5cc-7706-4983-b49e-4e4d5c0ef816", "capture", "succeeded", 1_000, 2)
    private val newer = CliRequestSummary("b616d5cc-7706-4983-b49e-4e4d5c0ef816", "record.start", "running", 2_000, 0)

    @Test fun `listing puts the newest request first so a just-lost id is the first row`() {
        val rows = CliRequestSummary.listing(listOf(older, newer))
        assertEquals(listOf(newer.requestId, older.requestId), rows.map { it["request_id"] })
    }

    @Test fun `equal creation times keep a stable order`() {
        val twin = older.copy(requestId = "0616d5cc-7706-4983-b49e-4e4d5c0ef816")
        assertEquals(listOf(twin.requestId, older.requestId), CliRequestSummary.listing(listOf(older, twin)).map { it["request_id"] })
    }

    @Test fun `each row says whether files can be fetched and why a request ended`() {
        val failed = CliRequestSummary(older.requestId, "burst", "cancelled", 3_000, 1, "CANCELLED")
        assertEquals(mapOf("request_id" to older.requestId, "command" to "burst", "state" to "cancelled", "completed" to true,
            "created_at_ms" to 3_000L, "artifact_count" to 1, "error_code" to "CANCELLED"), failed.toMap())
        assertFalse(newer.toMap()["completed"] as Boolean)
        assertNull(newer.toMap()["error_code"])
    }

    @Test fun `listing is a read, not a command that would add a record`() {
        assertFalse("requests.list" in CliCommand.COMMANDS)
        try { AdbArguments.command("requests.list", emptyMap()); fail("Expected INVALID_ARGUMENT") }
        catch (e: CliFailure) { assertEquals("INVALID_ARGUMENT", e.code) }
    }
}
