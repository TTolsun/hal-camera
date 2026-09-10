Stage 1. Every callback becomes one `Event(atNs, session, kind, frame?, sensorNs?, values)` in the `FlightRecorder` ring.

`atNs` is always `elapsedRealtimeNanos` at record time. `session` is the string the engine registered, and is the filter every later join applies first. `kind` is one of a fixed vocabulary. `frame` and `sensorNs` are the two join keys. `values` is an untyped map, copied defensively on store.

The stage stores and does not interpret. The single derivation it does perform is `FrameTracker`, which adds `intervalMs`, `resultFps` and `observedResultGap` to a `capture_result` from the previous frame's sensor timestamp and frame number — cheap, and needed live by the health strip.

Alongside the ring, `Telemetry.sessions` holds the `describeCamera` snapshot per session: hardware level, timestamp source and whether it is realtime-comparable, lens facing, capabilities, zoom range, output sizes and the negotiated stream sizes. Without that map a stored event stream cannot be interpreted later, which is why the incident bundle ships it as `camera_characteristics.json`.
