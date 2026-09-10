Two metrics cannot currently produce a real value, because their source does not exist yet:

- `2.7` (preview stall during capture) is returned by an unconditional `notRun("2.7")`. Nothing computes it.
- `H.9` is built from `Input.callbackFailures`, an integer the caller must supply. Only the missing M2 runner would count it.

The class still imports `MetricExtractor` and `UnknownReason` from `diagnosis/`, so the v0.3 layer is not yet fully independent of the v0.2 layer it is meant to replace. The naming dependency is already gone — display metadata moved to `MetricInfo.kt` — so what remains is the shared `percentile` implementation and the `UnknownReason` enum. Both need a neutral home before the M3 deletion.
