`app/src/main/java/dev/halcamera/diagnosis/` — the v0.2 health layer, and still the one every shipped screen uses. Nine files, all pure Kotlin, implementing chapters 4 to 8 and 13.2 of `docs/PRODUCT-v0.2.md`.

The chain is fixed: `MetricExtractor` turns events into `MetricSample`s, `ThresholdEngine` turns samples into `MetricState`s using `ThresholdTable` plus an optional device baseline, `DiagnosisRules` picks one rule id from those states, and `HealthComposer` folds them into a level and an optional score. `HealthMonitor` runs the same chain over a short live window for the expert screen's health strip. `MetricCatalog` supplies the human names, and `Model.kt` holds every shared enum and data class.

The layer's core idea is that a judgement carries its own justification. A `MetricState` records the absolute verdict *and* the relative verdict separately, the bound and its source, the delta against baseline, which basis decided the final state, whether the CDD even applied, and — when the answer is UNKNOWN — exactly why it is unknown. That is what lets a report say "slower than your own baseline, but within spec" rather than a bare colour.

Under the v0.3 pivot this layer is scheduled for removal in M3, but `benchmark/` currently imports `MetricCatalog`, `MetricExtractor` and `UnknownReason` from it, so the shared pieces have to move first.
