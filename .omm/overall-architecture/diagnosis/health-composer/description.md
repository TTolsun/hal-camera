`app/src/main/java/dev/halcamera/diagnosis/HealthComposer.kt` (47 lines). Folds the metric states into one `Health`: a level, a coverage fraction, an optional score and whether that score may be shown.

The level rule is asymmetric on purpose. ISSUE requires a FAIL whose basis is HARD or ABSOLUTE_VALIDATED — a relative or heuristic FAIL stops at WARNING, because "slower than your own baseline" is not the same claim as "below a validated bound". NORMAL additionally requires that at least half the total metric weight actually passed, so a run that measured almost nothing lands in INSUFFICIENT rather than looking healthy.

Coverage is weight-based, not count-based: known weight over total weight from `ThresholdTable`. The score is weighted too (PASS 1.0, WARN 0.5, otherwise 0) and is suppressed entirely without a baseline, below 70 % coverage, or with no known weight; a hard failure caps it at 59.

`scoreEnabled` defaults to false, so no score reaches any screen. That default is the current product decision, kept across the v0.3 pivot: no score is shown until enough profile v1 runs exist to justify one.
