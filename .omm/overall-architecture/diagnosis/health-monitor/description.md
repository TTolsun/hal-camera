`app/src/main/java/dev/halcamera/diagnosis/HealthMonitor.kt` (133 lines). The live health strip on the expert screen: the same extractor, engine and rules run over a rolling 1.5 s window instead of a 10 s observation.

Three live-specific choices are documented in `PRODUCT-v0.2.md` 12.1 and implemented here. The recent window is aggregated by MAX rather than p50, so a single late frame is visible instead of being averaged away, and it is compared against the session's own baseline p95. Only H.1 to H.5 and H.9 are judged — the 3A metrics are excluded because the start of a rolling window is not the start of a convergence, so a duration measured from it would be meaningless. And when AE changes the cadence, the H.1 baseline is scaled by the frame-duration ratio, so a legitimate 30 to 15 fps drop in dim light does not read as a slowdown.

A warning is held for `holdNs` (3 s) after it stops matching, so the banner does not flicker on a single frame.

Its own header states the threading contract: not thread-safe, held-warning state lives in plain fields, and every call must come from one thread — the main thread in `MainActivity`.
