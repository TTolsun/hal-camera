> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

Stage 3. Everything measurable is flattened into one uniform shape, `MetricSample(id, value, p95, n, hardFailure, unknownReason, expectedMs, fixedCadence, timeout)`, regardless of which track produced it.

Three producers fill the same list. `MetricExtractor.observe` yields H.1 to H.8 from the steady frames. `MetricExtractor.failureSample` counts `capture_failed` and `buffer_lost` for H.9. `EndpointResult.launchSamples()` yields 1.1 to 1.8 and 2.2, 2.3, 2.5 from the runner marks. `CheckEvaluator` then appends explicit `NOT_RUN` samples for every metric the v0.2 check never attempts, so the report states an absence rather than omitting it.

The uniform shape is what lets one `ThresholdEngine` judge all of them. Two fields carry information the engine cannot otherwise recover: `expectedMs` carries the physics bound for cadence metrics (the frame's own duration, or 1e9/fps when the rate is fixed), and `hardFailure` marks a null that came from a failed step rather than from an unmeasured one — the difference between FAIL and UNKNOWN.

A null `value` is always accompanied by an `unknownReason`. There is no unexplained absence at this stage.
