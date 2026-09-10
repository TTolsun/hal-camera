`app/src/main/java/dev/halcamera/benchmark/BenchmarkProfile.kt` (105 lines). An immutable description of *how* a run drove the camera, per `PLAN-BenchMarker-v0.3.md` chapter 3.

It pins every knob that could change a number: engine, preview / YUV / still sizes and format, FPS range, ZSL and trigger flags, AF mode, launch mode, iteration count, warm-up and observation durations, still count and whether the first sample is excluded. Every run JSON stores both the id and the full content, so a stored run can be re-read even after the profile definition moves on.

`CAMERA2_STANDARD_V1` is the standard profile: camera2, 1920x1080 for all three streams, fixed [30,30], ZSL and trigger off, CONTINUOUS_PICTURE, warm reopen, 10 launch iterations, 3 s warm-up, 10 s observation, 10 stills, first excluded.

Two derived pieces carry rules rather than data. `isDraft` is true while the id ends in `-draft`, which it still does — the suffix is only dropped once M2 confirms the 1080p YUV stream holds on the Galaxy S25+, and until then every run is excluded from scoring. And `expectedLaunchSamples` / `expectedStillSamples` / `expectedShotToShotSamples` state what the profile *promises*, which is what `RunValidityEvaluator` compares the actual counts against to raise `INSUFFICIENT_SAMPLES`. Shot-to-shot is `(stillCount - 1) - excluded`, because n captures yield n-1 intervals.
