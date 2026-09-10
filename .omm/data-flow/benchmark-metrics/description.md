Stage 4b, the v0.3 branch. `BenchmarkEvaluator` produces one `BenchmarkMetric` per id in `BenchmarkMetrics.ALL` — twenty metrics across launch, preview, capture, stability and 3A — carrying `value`, `p50`, `p95`, `min`, `max` and `n`, and nothing else.

No thresholds are consulted here. `regression` is left `UNKNOWN` and `unknownReason` is set to `NO_BASELINE` on every metric that has a value, because the comparison belongs to a detector that does not exist yet.

Its input is the shape the missing M2 runner must produce: `LaunchCycle` per warm-reopen iteration and `StillSample` per capture, both carrying a `warmup` flag so the excluded first sample is reported in `excluded_warmup` rather than discarded. Shot-to-shot (2.5) is derived here as the differences between consecutive submit timestamps.

The branch is currently unreachable: nothing constructs `Input`. It is written and unit-tested ahead of its producer so the run JSON contract could be frozen first.
