`app/src/main/java/dev/halcamera/benchmark/MetricInfo.kt` (37 lines). Display metadata for the twenty benchmark metric ids: category, short English name and unit.

It exists to break a dependency rather than to add behaviour. `BenchmarkEvaluator` previously read `category` and `unit` from `diagnosis/MetricCatalog`, which would have made the v0.2 layer impossible to delete in M3. `BenchmarkMetricCatalog` now holds the benchmark-side naming ("Open", "Interval p50", "Shot-to-shot"), and the Korean consumer names stay in `MetricCatalog` for the v0.2 UI only.

Units are stated in the benchmark vocabulary directly: `count` rather than the Korean 회 / 개 that the old lookup had to translate.
