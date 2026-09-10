Stage 2. `MetricExtractor.frames()` reduces the event stream for one session and window to one `FrameObservation` per capture result — the row shape every observation metric is computed from.

Each row joins three events. `capture_result` supplies the row itself plus AE, AF, AWB, ISO, exposure and frame duration. `capture_started`, matched by frame number, supplies `startedAtNs`, giving `partialMs` = result minus started. `image_available`, matched by sensor timestamp, gives `bufferMs` = image minus started. `request_observed` fills in `afMode` when the result omits it.

`intervalMs` prefers the value `FrameTracker` already recorded and falls back to its own sensor-timestamp difference, so events recorded without it still work. The seeding detail matters: `previousSensor` is initialised from the last `capture_result` *before* the window, so the first in-window interval is a real interval rather than a null.

Two judgement helpers live on the row rather than downstream. `autofocusEnabled` decides from the observed AF *mode* (0 = OFF, 5 = EDOF) rather than from an INACTIVE state, and `stalled(baseline)` applies the 1.5x rule against the frame's own `SENSOR_FRAME_DURATION` when present, falling back to the baseline interval alone.
