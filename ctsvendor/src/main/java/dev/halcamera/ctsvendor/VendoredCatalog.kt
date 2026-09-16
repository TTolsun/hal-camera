package dev.halcamera.ctsvendor

import android.hardware.camera2.cts.RecordingTest
import org.junit.Test

/** One vendored JUnit test method, addressed as the CTS class#method it is upstream. */
data class VendoredTest(val className: String, val method: String) {
    val simpleClass: String get() = className.substringAfterLast('.')
    val source: String get() = "$simpleClass#$method"
    val id: String get() = "$className#$method"
}

/**
 * The vendored test classes and their `@Test` methods, found by reflection: adding a class to [classes] is
 * all it takes to list every test it declares. [VendoredCts.install] must have run before this is used.
 */
object VendoredCatalog {
    val classes: List<Class<*>> = listOf(RecordingTest::class.java)

    /** Methods listed first within their class, in this order; the rest follow alphabetically. */
    private val pinned = listOf("testBasicRecording")

    fun tests(): List<VendoredTest> = classes.flatMap { cls ->
        val names = cls.methods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.distinct()
        val (first, rest) = names.partition { it in pinned }
        (first.sortedBy { pinned.indexOf(it) } + rest.sorted()).map { VendoredTest(cls.name, it) }
    }

    fun byId(id: String?): VendoredTest? = id?.let { wanted -> tests().firstOrNull { it.id == wanted } }
}
