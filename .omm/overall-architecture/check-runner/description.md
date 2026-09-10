`app/src/main/java/dev/halcamera/check/` — enumerating what can be measured, driving the measurement, and evaluating one endpoint's result.

`CameraEndpointResolver` builds the list of `CameraEndpoint`s from public characteristics only. `AutoCheckRunner` is the state machine that walks each endpoint through a fixed sequence. `CheckEvaluator` joins one endpoint's timings with the recorded events and produces metric states, a diagnosis and a health level. `CheckResult` is the render-ready projection, buildable either from a fresh run or from a stored file.

The design decision that makes this layer testable is that `AutoCheckRunner` never touches Android: the camera arrives as a `Driver`, time as a `Scheduler` and `clock`, and results leave through a `Listener`. `CheckActivity` supplies the real implementations; `AutoCheckRunnerTest` supplies fakes.
