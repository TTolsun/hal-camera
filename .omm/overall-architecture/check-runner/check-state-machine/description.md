> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

`app/src/main/java/dev/halcamera/check/AutoCheckRunner.kt` (205 lines). The Auto Check state machine from `PRODUCT-v0.2.md` chapter 10, and the most carefully isolated class in the project.

One instance runs one check. It visits up to `maxEndpoints` (4) independently openable endpoints and walks each through OPEN, CONFIGURE, FIRST_FRAME, a fixed 10 s OBSERVE, three STILL captures, then CLOSE. Every step except OBSERVE is armed with a timeout (3 s, or 5 s for a still); a timeout records the failed step, jumps to CLOSE, and the *next* endpoint still runs. There is no automatic retry anywhere.

Timing is not guessed. The driver calls `mark(session, name)` immediately before each API call, and the runner subtracts marks pairwise in `endpointDone()` — `open_call` to `opened`, `configure_call` to `configured`, `repeating_call` to `first_started` and to `first_yuv`, `open_call` to `first_yuv` for the preview total, `close_call` to `closed`. Signals carrying a session id other than the current one are ignored outright, which is what keeps a late callback from a previous endpoint out of the numbers.

`launchSamples()` on `EndpointResult` converts those timings into `MetricSample`s and encodes two judgements worth knowing. A metric that is null because its own step failed is a hard failure; a metric that is null because the run never reached that step is `NOT_RUN`. And close latency is reported only when the failure was in CLOSE itself, because after an earlier failure the engine has often already closed and the observed value is meaningless, sometimes negative.
