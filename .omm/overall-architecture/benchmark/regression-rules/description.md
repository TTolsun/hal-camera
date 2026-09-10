`app/src/main/java/dev/halcamera/benchmark/RegressionRules.kt` (62 lines), version `regression-rule-v1`. The only place regression thresholds live, per `PLAN-BenchMarker-v0.3.md` 7.2. M1 fixes the table; the detector that applies it arrives in M4.

Two rule kinds. A LATENCY rule fires REGRESSED only when *both* the relative change reaches its percentage and the absolute change reaches its noise floor in milliseconds, which is what stops a 15 % swing on a 2 ms metric from counting. A COUNT rule has no percentage at all and fires on the absolute change alone — a stall count going from 0 to 2 has no meaningful percentage.

The thresholds are graded by how noisy each metric is: preview interval metrics H.1 and H.2 are tightest at 10 % with a 2 ms floor, most launch and capture metrics sit at 15 %, and the 3A convergence metrics H.6 to H.8 are loosest at 30 % with a 200 ms floor because scene-dependent convergence is inherently unstable. H.10 jitter uses 20 % with a 1 ms floor. Counts H.5 and 2.7 need a change of 2, and H.9 (callback failures) a change of 1.

The `init` block enforces the invariant that `deltaPct` is null exactly for COUNT rules, so a malformed rule fails at construction rather than at comparison time. Changing any value in this table is defined to require bumping `VERSION`, which is stored in every run JSON.
