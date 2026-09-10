`app/src/main/java/dev/halcamera/benchmark/` — the Camera BenchMarker v0.3 data contract, milestone M1. Nine files, all pure Kotlin, implementing chapters 3 to 7 of `docs/PLAN-BenchMarker-v0.3.md`.

This layer answers a different question from `diagnosis/`. It does not ask whether the camera is healthy; it asks how fast it is and how that changed since a named baseline. States are IMPROVED / STABLE / REGRESSED / UNKNOWN, and there is no PASS or FAIL anywhere in it.

What exists today is the contract, not the measurement. `BenchmarkProfile` fixes how the camera must be driven, `MeasurementContract` fixes how events become numbers, `BenchmarkModel` defines the run shape, `RegressionRules` fixes the per-metric thresholds, `RunValidity` fixes what a run may be used for, `BuildIdentity` fixes what "the same build" means, `MetricInfo` supplies the display metadata, and `BenchmarkReport` serialises all of it as schema 3. `BenchmarkEvaluator` computes the statistics — but the M2 runner that would feed it real `LaunchCycle` and `StillSample` data does not exist yet.

Everything serialises through `Map<String, Any?>` with snake_case keys, so the whole contract round-trips in JVM tests and `org.json` appears only at the file boundary.

Reading is deliberately fail-closed throughout. A required field that is missing or mistyped raises an `IllegalArgumentException` naming the key rather than falling back to a default, a confirmed canonical profile id must carry exactly the definition this app version holds or the file is rejected, and a validity flag code this version does not recognise blocks comparison and scoring instead of being ignored. The reasoning is the same in each case: a silently substituted value would let two runs compare that must not.
