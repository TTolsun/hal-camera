package dev.halcamera.ctsvendor

import android.hardware.camera2.cts.BurstCaptureTest
import android.hardware.camera2.cts.RecordingTest
import android.hardware.camera2.cts.StillCaptureTest
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
    val classes: List<Class<*>> = listOf(RecordingTest::class.java, StillCaptureTest::class.java, BurstCaptureTest::class.java)

    /** Methods listed first within their class, in this order; the rest follow alphabetically. */
    private val pinned = listOf("testBasicRecording")

    /**
     * `@Test` methods whose upstream body is only `// TODO. Need implement.`: they pass in a millisecond and
     * check nothing on any device, so the list leaves them out. Re-check the sources when the commit moves.
     */
    val unimplemented: Set<String> = setOf(
        "android.hardware.camera2.cts.RecordingTest#testCameraRecorderOrdering",
        "android.hardware.camera2.cts.RecordingTest#testMediaCodecRecording",
        "android.hardware.camera2.cts.RecordingTest#testTimelapseRecording"
    )

    /**
     * What a method needs when it skips every camera without a log line of its own (`continue` with no
     * Log.i). Keyed by class#method; the host shows this as the skip reason when the log held none.
     */
    val silentSkipHints: Map<String, String> = mapOf(
        "android.hardware.camera2.cts.RecordingTest#testBasic10BitRecordingAV1" to "10비트 AV1 CamcorderProfile(AV1ProfileMain10 계열)을 가진 카메라가 없습니다",
        "android.hardware.camera2.cts.RecordingTest#testBasic10BitRecordingHEVC" to "10비트 HEVC CamcorderProfile을 가진 카메라가 없습니다",
        "android.hardware.camera2.cts.RecordingTest#testSlowMotionRecording" to "고속 동영상(high speed video)을 지원하는 카메라가 없습니다"
    )

    fun tests(): List<VendoredTest> = classes.flatMap { cls ->
        val names = cls.methods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.distinct()
            .filter { "${cls.name}#$it" !in unimplemented }
        val (first, rest) = names.partition { it in pinned }
        (first.sortedBy { pinned.indexOf(it) } + rest.sorted()).map { VendoredTest(cls.name, it) }
    }

    fun byId(id: String?): VendoredTest? = id?.let { wanted -> tests().firstOrNull { it.id == wanted } }
}
