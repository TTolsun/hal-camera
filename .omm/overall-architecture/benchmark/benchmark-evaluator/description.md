`app/src/main/java/dev/halcamera/benchmark/BenchmarkEvaluator.kt` (186 lines). Turns the runner's raw samples into `BenchmarkMetric` statistics, and nothing else: no thresholds, no judgement. Comparison fields stay `UNKNOWN(no_baseline)` inside this evaluator; the separate RegressionDetector implementation applies comparisons.

RunAssembler supplies these inputs from BenchmarkRunner and recorded events: `LaunchCycle` (one warm-reopen cycle with open, configure, first-started, YUV, preview-total and close milliseconds, plus a warm-up flag) and `StillSample` (submit, image and result timestamps, deriving both latencies).

Three shapes of metric are computed differently on purpose. Bounded metrics (launch 1.x, still 2.2 / 2.3 / 2.5) store their samples and report p50 as the value. Windowed metrics (H.1 to H.4, H.10) report an aggregate but store no samples, and follow `MetricExtractor`'s rule that below 15 steady frames after warm-up exclusion the value is null with `INSUFFICIENT_SAMPLES` while the statistics remain. Count metrics (H.5, H.9, 2.7) report a plain count. 3A metrics take the single duration `MetricExtractor` already produced, carrying its `timeout` flag through.

`BenchmarkMetrics.ALL` fixes the reported set and its display order: six launch, five preview, three capture, three stability, three 3A.

`Stats.of` uses the shared nearest-rank percentile, and its comment records the consequence honestly — with n = 9 the p95 *is* the maximum, and the UI is expected to label it that way. `Stats.stdDev` is the population standard deviation (ddof = 0) used for the H.10 jitter metric.
