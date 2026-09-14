`app/src/main/java/dev/halcamera/benchmark/BenchmarkProfile.kt` (105 lines). An immutable description of *how* a run drove the camera, per `PLAN-BenchMarker-v0.3.md` chapter 3.

It pins every knob that could change a number: engine, preview / YUV / still sizes and format, FPS range, ZSL and trigger flags, AF mode, launch mode, iteration count, warm-up and observation durations, still count and whether the first sample is excluded. Every run JSON stores both the id and the full content, so a stored run can be re-read even after the profile definition moves on.

`CAMERA2_STANDARD_V1` is the standard profile: camera2, 1920x1080 for all three streams, fixed [30,30], ZSL and trigger off, CONTINUOUS_PICTURE, warm reopen, 10 launch iterations, 3 s warm-up, 10 s observation, 10 stills, first excluded.

The current standard ID is camera2-standard-v1 (not draft). isDraft remains available for draft profiles. expectedLaunchSamples and expectedStillSamples are 9; expectedShotToShotSamples is 8 with the standard 10 executions and first-sample exclusion. Device validation claims are not re-certified by this code scan.
