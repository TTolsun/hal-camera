`app/src/main/java/dev/halcamera/report/HealthReport.kt` (98 lines). Writes the v0.2 run JSON to `files/checks/<runId>.json`, schema 2, per `PRODUCT-v0.2.md` 13.3.

The header of every file records the versions that make it interpretable later: product definition, threshold table version, metric definition version, stats method (`nearest_rank`), clock (`elapsedRealtimeNanos`) and the UTC export time. Below that come the device identity, the environment gathered by `CheckActivity`, the endpoint list, one entry per endpoint result with its metric states, diagnosis and raw milliseconds, the composed health, and the baseline references.

Writing the versions rather than assuming them is what lets an old run still be read after the thresholds move: the file states which table judged it.
