`app/src/main/java/dev/halcamera/baseline/BaselineStore.kt` (40 lines). The v0.2 device baseline, held in `SharedPreferences("baseline")` rather than in a file, per `PRODUCT-v0.2.md` 5.3.

The key is `Build.FINGERPRINT | endpointKey | conditions`, and the fingerprint is stored *inside* the value as well as in the key. `get()` re-checks it and returns null on a mismatch, so a platform update silently invalidates every baseline instead of comparing a new build against numbers from an old one.

Each entry holds a run id and a map of metric id to `BaselineValue(value, p95)`. It is created by the first run that qualifies — `CheckEvaluator` only offers a candidate when nothing hard failed and no H metric was short on samples — and is never updated automatically afterwards.

This is the v0.2 mechanism. The v0.3 layer replaces it with `BenchmarkIndex`, which points at a whole stored run instead of copying values out of it, and which deliberately survives a fingerprint change.
