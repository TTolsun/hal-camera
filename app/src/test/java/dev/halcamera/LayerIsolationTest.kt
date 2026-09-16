package dev.halcamera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * The measurement and judgement logic lives in pure Kotlin so it can be tested here without a device; only
 * Activities and the file/device adapters know Android. The package layout is what makes that visible
 * (benchmark/domain vs benchmark/platform), and this test keeps the layout honest by reading the sources.
 */
@RunWith(JUnit4::class)
class LayerIsolationTest {
    private val root = listOf("src/main/java/dev/halcamera", "app/src/main/java/dev/halcamera")
        .map(::File).first { it.isDirectory }

    private val pureDirs = listOf("benchmark/domain", "metrics")
    private val androidPrefixes = listOf("android.", "androidx.", "org.json.")

    private fun kotlinFiles(dir: String): List<File> =
        File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun imports(file: File): List<String> =
        file.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }

    @Test fun `pure packages import nothing from Android`() {
        val offenders = pureDirs.flatMap(::kotlinFiles).flatMap { file ->
            imports(file).filter { imp -> androidPrefixes.any(imp::startsWith) }.map { "${file.relativeTo(root)}: $it" }
        }
        assertEquals("Android imports in a pure package; move the file to benchmark/platform or inject the dependency", emptyList<String>(), offenders)
    }

    @Test fun `benchmark domain does not depend on platform adapters, screens or the CLI`() {
        val forbidden = listOf("dev.halcamera.benchmark.platform.", "dev.halcamera.cli.", "dev.halcamera.ui.", "dev.halcamera.cts.")
        val offenders = kotlinFiles("benchmark/domain").flatMap { file ->
            imports(file).filter { imp ->
                forbidden.any(imp::startsWith) ||
                    // The root benchmark package holds only Activities.
                    (imp.startsWith("dev.halcamera.benchmark.") && imp.removePrefix("dev.halcamera.benchmark.").none { it == '.' })
            }.map { "${file.relativeTo(root)}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `symbols the domain borrows from camera and telemetry are declared in Android-free files`() {
        // A column-0 line declaring the symbol: types, top-level functions (extension receivers included), properties, aliases.
        fun declares(file: File, symbol: String): Boolean {
            val declaration = Regex("""^(?![\s/*]).*?\b(?:class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?$symbol\b""")
            return file.readLines().any(declaration::containsMatchIn)
        }
        val candidates = listOf("camera", "telemetry").flatMap(::kotlinFiles)
        val offenders = kotlinFiles("benchmark/domain").flatMap { file ->
            imports(file).filter { it.startsWith("dev.halcamera.camera.") || it.startsWith("dev.halcamera.telemetry.") }.mapNotNull { imp ->
                val symbol = imp.substringAfterLast('.')
                val declared = candidates.filter { declares(it, symbol) }
                if (declared.isEmpty()) return@mapNotNull "${file.relativeTo(root)}: $imp is not declared at the top level of camera/ or telemetry/"
                val leaked = declared.firstNotNullOfOrNull { d -> imports(d).firstOrNull { dep -> androidPrefixes.any(dep::startsWith) }?.let { d to it } }
                leaked?.let { (d, dep) -> "${file.relativeTo(root)}: $imp comes from ${d.relativeTo(root)} which imports $dep" }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
