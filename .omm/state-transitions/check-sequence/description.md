`check/AutoCheckRunner.kt`. Per endpoint: OPEN -> CONFIGURE -> FIRST_FRAME -> OBSERVE (fixed 10 s) -> STILL x3 -> CLOSE, then the next endpoint; DONE or ABORTED at the end.

Transitions are driven by `Signal` (OPENED, CONFIGURED, FIRST_FRAME, STILL_RECEIVED, CLOSED, ERROR) and by a `Scheduler` timer. Each `enter(step)` cancels the previous timer, notifies the listener, performs the step's action and arms a new timeout. A timeout sets `failedStep = step`, records a `timeout` mark and jumps to CLOSE; ERROR does the same with an `error` mark. Either way `endpointDone()` still builds a full `EndpointResult`, so a failed endpoint contributes a row rather than a gap.

`abort(reason)` from the UI takes the same path when a step is in flight, so the camera is always closed properly rather than dropped.
