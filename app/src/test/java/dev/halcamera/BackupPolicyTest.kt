package dev.halcamera

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class BackupPolicyTest {
    @Test fun `device identity and pending commands are excluded from both restore transports`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File(main, "AndroidManifest.xml"))
        val app = manifest.getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("false", app.getAttributeNS(android, "fullBackupContent"))
        val resource = app.getAttributeNS(android, "dataExtractionRules")
        assertEquals("@xml/data_extraction_rules", resource)
        val rules = factory.newDocumentBuilder().parse(File(main, "res/xml/data_extraction_rules.xml"))
        val domains = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        for (transport in listOf("cloud-backup", "device-transfer")) {
            val section = rules.getElementsByTagName(transport).item(0) as Element
            assertEquals(0, section.getElementsByTagName("include").length)
            val excludes = section.getElementsByTagName("exclude")
            val actual = (0 until excludes.length).map { excludes.item(it) as Element }
            assertFalse(actual.isEmpty())
            assertEquals(domains, actual.map { it.getAttribute("domain") }.toSet())
            actual.forEach { assertEquals(".", it.getAttribute("path")) }
        }
    }
}
