package dev.halcamera.benchmark.domain

/**
 * Display metadata for the benchmark metric ids (docs/PLAN-BenchMarker-v0.3.md chapter 4, 8.4): category, short
 * English name and unit. Lives in the benchmark package rather than beside the extractor because these names are
 * a property of the result screen, not of the measurement: `metrics` stays a leaf that knows nothing about how
 * its numbers are displayed.
 */
/**
 * [span] is what the metric measures in Camera2 terms, from the start point to the end point in docs/METRICS.md
 * (or, where the code differs from that table, what the code measures: 1.6 ends at the first YUV image). A Camera
 * HAL developer reads "Partial" as nothing; "onCaptureStarted → onCaptureCompleted" answers the question at once.
 */
data class MetricInfo(val id: String, val category: Category, val short: String, val unit: String, val span: String = "")

object BenchmarkMetricCatalog {
    private val infos = listOf(
        MetricInfo("1.1", Category.LAUNCH, "Open", "ms", "openCamera → onOpened"),
        MetricInfo("1.2", Category.LAUNCH, "Configure", "ms", "createCaptureSession → onConfigured"),
        MetricInfo("1.3", Category.LAUNCH, "First started", "ms", "setRepeatingRequest → onCaptureStarted"),
        MetricInfo("1.8", Category.LAUNCH, "First YUV", "ms", "setRepeatingRequest → 첫 YUV"),
        MetricInfo("1.6", Category.LAUNCH, "First frame", "ms", "openCamera → 첫 YUV"),
        MetricInfo("1.7", Category.LAUNCH, "Close", "ms", "close → onClosed"),
        MetricInfo("H.1", Category.PREVIEW, "Interval p50", "ms", "센서 timestamp 간격"),
        MetricInfo("H.2", Category.PREVIEW, "Interval p95", "ms", "센서 timestamp 간격"),
        MetricInfo("H.3", Category.PREVIEW, "Partial", "ms", "onCaptureStarted → onCaptureCompleted"),
        MetricInfo("H.4", Category.PREVIEW, "Buffer", "ms", "onCaptureStarted → onImageAvailable"),
        MetricInfo("H.10", Category.PREVIEW, "Jitter", "ms", "센서 간격 표준편차"),
        MetricInfo("2.2", Category.CAPTURE, "Capture", "ms", "capture → onImageAvailable"),
        MetricInfo("2.3", Category.CAPTURE, "Result", "ms", "capture → onCaptureCompleted"),
        MetricInfo("2.5", Category.CAPTURE, "Shot-to-shot", "ms", "capture → 다음 capture"),
        MetricInfo("H.5", Category.STABILITY, "Stalls", "count", "간격 > 1.5 × 기준"),
        MetricInfo("H.9", Category.STABILITY, "Callback fail", "count", "onCaptureFailed + onCaptureBufferLost"),
        // Six characters shorter than "Stall during capture", which was the one name that set the label column
        // width for the whole table and pushed the verdict markers off the screen.
        MetricInfo("2.7", Category.STABILITY, "Capture stalls", "count", "촬영 중 간격 > 1.5 × 기준"),
        MetricInfo("H.6", Category.THREE_A, "AE", "ms", "첫 result → AE CONVERGED"),
        MetricInfo("H.7", Category.THREE_A, "AF", "ms", "첫 result → AF FOCUSED"),
        MetricInfo("H.8", Category.THREE_A, "AWB", "ms", "첫 result → AWB CONVERGED"),
        // The RECORD stage (METRICS.md 3). Every name carries the prefix so the rows pair with the preview rows
        // that measure the same thing on the other stream: Stalls and Record stalls are both intervals past 1.5
        // times the reference, Jitter and Record jitter are both the deviation of those intervals. Record fps is
        // the one metric in the whole table whose larger value is the better one, and the only one whose unit is
        // neither milliseconds nor a count; the three-second skip behind it is explained in the guide, not in
        // a label that has room for two words.
        MetricInfo("3.1", Category.RECORD, "Record start", "ms", "MediaRecorder.start → onCaptureStarted"),
        MetricInfo("3.4", Category.RECORD, "Record fps", "fps", "1초 창의 녹화 result 수"),
        MetricInfo("3.6", Category.RECORD, "Record stop", "ms", "MediaRecorder.stop 반환까지"),
        MetricInfo("3.7", Category.RECORD, "Record jitter", "ms", "녹화 센서 간격 표준편차"),
        MetricInfo("3.2", Category.RECORD, "Record stalls", "count", "녹화 간격 > 1.5 × 기준")
    ).associateBy { it.id }

    fun info(id: String): MetricInfo? = infos[id]

    val ids: Set<String> get() = infos.keys
}
