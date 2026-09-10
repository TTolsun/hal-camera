`app/src/main/java/dev/halcamera/check/CheckEvaluator.kt` (100 lines). Joins one endpoint's `EndpointResult` with the telemetry events recorded during its observe window and produces an `EndpointEvaluation`.

It assembles the full sample list in one place: launch and still samples from the runner, H.1 to H.8 from `MetricExtractor.observe` over the observe window, H.9 from `failureSample`, and explicit `NOT_RUN` entries for every metric the v0.2 check does not run (2.1, 2.4, 2.6, 2.7 and all of 3.x) so the report says "not run" rather than staying silent. The first five frames of a fresh stream are dropped as `WARMUP_FRAMES`.

Two rules in it are the interesting part. The exposure-load guard computes ISO x exposure at p50 for the window and, if it differs from the baseline scene by more than 4x either way, rewrites the 3A metrics to `UNKNOWN(condition_mismatch)` instead of letting a dark room look like a regression. And baseline qualification is strict: a run becomes a baseline candidate only when nothing hard failed, an observation exists, and no H metric was short on samples.

`overall()` composes the device-level verdict as the worst endpoint, in the order ISSUE, WARNING, INSUFFICIENT, NORMAL.
