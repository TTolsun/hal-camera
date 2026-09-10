`app/src/main/java/dev/halcamera/check/CheckResult.kt` (47 lines). What the result screen renders, built either from a fresh evaluation (`fromEvaluations`) or read back from a stored run JSON (`fromFile`).

Having both constructors is the point: a run just finished and a run loaded from `files/checks` reach the UI as the same shape, so `CheckActivity` has exactly one rendering path. Each `Endpoint` entry carries the role, the endpoint key, its level, the diagnosis rule, the metric states, the raw millisecond values for the L3 detail view, and any hard failure.

`fromFile` returns null on any parse problem rather than throwing, matching `RunSummary`'s rule that a corrupt file must not break a screen.
