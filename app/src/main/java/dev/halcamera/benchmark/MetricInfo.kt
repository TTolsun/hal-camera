package dev.halcamera.benchmark

/**
 * Display metadata for the benchmark metric ids (docs/PLAN-BenchMarker-v0.3.md chapter 4, 8.4): category, short
 * English name and unit. Lives in the benchmark package so that nothing here depends on the Doctor-era
 * diagnosis package (which is deleted in M3); the Korean names in diagnosis.MetricCatalog stay for the v0.2 UI.
 */
data class MetricInfo(val id: String, val category: Category, val short: String, val unit: String)

object BenchmarkMetricCatalog {
    private val infos = listOf(
        MetricInfo("1.1", Category.LAUNCH, "Open", "ms"),
        MetricInfo("1.2", Category.LAUNCH, "Configure", "ms"),
        MetricInfo("1.3", Category.LAUNCH, "First started", "ms"),
        MetricInfo("1.8", Category.LAUNCH, "First YUV", "ms"),
        MetricInfo("1.6", Category.LAUNCH, "First frame", "ms"),
        MetricInfo("1.7", Category.LAUNCH, "Close", "ms"),
        MetricInfo("H.1", Category.PREVIEW, "Interval p50", "ms"),
        MetricInfo("H.2", Category.PREVIEW, "Interval p95", "ms"),
        MetricInfo("H.3", Category.PREVIEW, "Partial", "ms"),
        MetricInfo("H.4", Category.PREVIEW, "Buffer", "ms"),
        MetricInfo("H.10", Category.PREVIEW, "Jitter", "ms"),
        MetricInfo("2.2", Category.CAPTURE, "Capture", "ms"),
        MetricInfo("2.3", Category.CAPTURE, "Result", "ms"),
        MetricInfo("2.5", Category.CAPTURE, "Shot-to-shot", "ms"),
        MetricInfo("H.5", Category.STABILITY, "Stalls", "count"),
        MetricInfo("H.9", Category.STABILITY, "Callback fail", "count"),
        MetricInfo("2.7", Category.STABILITY, "Stall during capture", "count"),
        MetricInfo("H.6", Category.THREE_A, "AE", "ms"),
        MetricInfo("H.7", Category.THREE_A, "AF", "ms"),
        MetricInfo("H.8", Category.THREE_A, "AWB", "ms")
    ).associateBy { it.id }

    fun info(id: String): MetricInfo? = infos[id]

    val ids: Set<String> get() = infos.keys
}
