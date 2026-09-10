`app/src/main/java/dev/halcamera/benchmark/BenchmarkModel.kt` (292 lines). The run shape itself: `BenchmarkRun`, `BenchmarkMetric`, `MeasurementContract` and the surrounding metadata types.

`MeasurementContract` is the piece that decides whether two runs may be compared at all. It concatenates profile id, metric definition version (`metrics-0.3`), stats method (`nearest_rank`) and clock (`elapsedRealtimeNanos`) into one `comparisonContractId`, and comparison requires the whole id to match. The reason for splitting profile from metric version is stated in the file: the profile says how the camera was driven, the metric version says how those events became numbers, and either changing invalidates a comparison.

`BenchmarkMetric` holds statistics (`value`, `p50`, `p95`, `min`, `max`, `n`) plus comparison fields that stay null and UNKNOWN until M4 fills them. `samples` is stored only for metrics with a profile-bounded count — launch, still, 3A — while observation-window metrics keep null and are recomputed from the events, which is what keeps the JSON from duplicating thousands of frame intervals.

`SubjectLabel` separates the thing under test from the tool testing it: build label, commit, branch of the *camera build*, never the app's own version, which lives in `AppInfo`. `DeviceInfo` carries both the system fingerprint and the vendor fingerprint, `RunEnv` the thermal, battery, charging, power-save and rotation context, `Compatibility` the preflight result, and `RunRef` a pointer to a baseline or reference run together with the `BuildIdentityComparison` against it.

`JsonMaps` at the bottom is the tolerant reader: it accepts the Int / Long / Double mixes `org.json` produces and treats the string `"null"` as missing.
