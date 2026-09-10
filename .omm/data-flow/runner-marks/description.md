Stage 1b, the parallel track. `AutoCheckRunner` keeps a `LinkedHashMap<String, Long>` of named timestamps per endpoint and derives every launch and capture latency by subtracting pairs from it.

The marks are `open_call`, `opened`, `configure_call`, `configured`, `repeating_call`, `first_started`, `first_yuv`, `observe_start`, `observe_end`, `close_call`, `closed`, plus `timeout` or `error` when a step fails. `mark()` uses `putIfAbsent` by default, so the *first* occurrence wins and a retried callback cannot overwrite a measured start.

This track exists because the event track cannot cover it: there is no callback for "the app called `openCamera`", so the driver calls `mark()` immediately before the API call rather than letting the runner guess. Still captures keep their own three parallel lists — submit, image and result timestamps — and are zipped by index, so a missing image does not shift the pairing.

The two window marks are the bridge to the event track: `CheckEvaluator` passes `observe_start` and `observe_end` to `MetricExtractor` as the time window to read events from.
