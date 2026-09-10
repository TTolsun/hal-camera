`app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt` (101 lines). The ring buffer, the incident window and `FrameTracker`.

The ring is bounded twice: by time (`retentionNs`, 30 s by default) and by count (`maxEvents`, 18,000). Evictions caused by the count cap are tallied in `capacityEvictions` and exported, so a truncated bundle is visible as truncated rather than silently short. `CheckActivity` overrides the defaults to 120 s and 60,000 events, because a full Auto Check outlives the incident-sized buffer.

An incident is a pre/post window around a trigger: `trigger(id)` snapshots everything already in the ring newer than `preNs` (10 s), then keeps appending until `postNs` (5 s) has passed; `finish()` returns the closed `Incident`, or null while the post-window is still open unless a `forceReason` is given. Only one incident may be pending at a time — `trigger` returns false otherwise.

Two threading decisions are load-bearing. `store()` is `@Synchronized` but `record()` invokes `listener` *outside* the lock, so a live listener may call `snapshot()` or `record()` without deadlocking. And the listener runs synchronously on the recording thread, which is why its documentation asks for it to be cheap.

`FrameTracker` derives per-frame statistics from consecutive results: `intervalMs` and `fps` from sensor timestamps, and `resultGap` from frame numbers. Its own comment states the limit: a gap is an observed result gap, not a count of HAL drops or of dropped preview frames.
