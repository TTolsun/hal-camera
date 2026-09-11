> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

`app/src/main/java/dev/halcamera/diagnosis/MetricExtractor.kt` (191 lines). Computes the observation metrics H.1 to H.9 from one session's events inside a time window. No Android import.

`frames()` reconstructs a `FrameObservation` per capture result by joining three event kinds: `capture_started` by frame number gives the start timestamp, `request_observed` supplies `afMode` when the result omits it, and `image_available` matched on sensor timestamp gives the buffer arrival. From those it derives `intervalMs` (trusting `FrameTracker`'s value when present), `partialMs` (started to result) and `bufferMs` (started to image). It deliberately seeds `previousSensor` from the last result *before* the window, so the first in-window interval is real rather than missing.

`observe()` then aggregates. `warmupFrames` drops the first frames of a fresh stream — on the Galaxy S25+ frame #1 arrives 66.7 ms after frame #0 despite a 33.3 ms duration — from the interval, partial, buffer and stall metrics, while still counting them for 3A convergence, which is measured from the first result. A frame is stalled when its interval exceeds 1.5x its own `SENSOR_FRAME_DURATION` and 1.5x the baseline interval; with no own duration, only the baseline comparison applies.

Two aggregation modes exist for the same code: PERCENTILE for Auto Check (p50 over 10 s) and MAX for the live strip, so a single late frame is visible there. Below `minSamples` every value becomes null with `INSUFFICIENT_SAMPLES` while the statistics are still reported.

AF handling avoids a common misreading: `CONTROL_AF_STATE` INACTIVE can mean either "AF is off" or "AF has not converged yet", so `autofocusEnabled` decides from the observed *mode* (0 = OFF, 5 = EDOF), and H.7 becomes `UNSUPPORTED` rather than a failure when no frame ran AF.

`percentile()` is the nearest-rank definition from `METRICS.md` 0.2 and is the shared implementation the benchmark layer also calls.
