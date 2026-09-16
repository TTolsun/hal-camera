package dev.halcamera.cts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CtsCatalogTest {
    @Test
    fun `every case has a unique id and a source`() {
        val ids = CtsCatalog.cases.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        CtsCatalog.cases.forEach { assertTrue(it.source, it.source.contains('#')) }
    }

    @Test
    fun `lookup by id finds the entry and rejects unknown ids`() {
        assertSame(CtsCatalog.cases.first(), CtsCatalog.byId(CtsCatalog.BASIC_RECORDING))
        assertNull(CtsCatalog.byId("nope"))
        assertNull(CtsCatalog.byId(null))
    }

    @Test
    fun `summaries name the camera count`() {
        CtsCatalog.cases.forEach { spec -> assertTrue(spec.id, spec.summary(3).contains("카메라 3대")) }
    }
}
