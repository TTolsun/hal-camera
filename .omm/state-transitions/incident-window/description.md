`telemetry/FlightRecorder.kt`. A three-state timeline independent of the check sequence: idle, pending, closed.

`trigger(id)` moves to pending by snapshotting everything already in the ring newer than `preNs` (10 s), then keeps appending arriving events until `postNs` (5 s) after the trigger. `finish()` returns null while the post-window is still open unless given a `forceReason`, which is how a user leaving the screen still produces a bundle — marked `finishReason` other than `"completed"`.

Only one incident may be pending; a second `trigger()` returns false rather than replacing it. `remainingNs()` lets the UI count the post-window down. The incident's own event list is capped at twice `maxEvents`, and hitting that sets `truncated`, which is exported so a short bundle is identifiable as truncated.
