1. M2 `BenchmarkRunner`를 만들어 벤치마크 갈래에 입력원을 붙입니다. warm-reopen 반복마다 `LaunchCycle`을, 촬영마다 `StillSample`을 만들어야 하고, H.9를 위해 콜백 실패 횟수도 세야 합니다.
2. `BenchmarkEvaluator`의 `2.7`(촬영 중 프리뷰 stall)에 무조건적인 `NOT_RUN` 대신 실제 계산을 넣습니다.
3. `BenchmarkEvaluator.windowed`가 H.1부터 H.4까지를 계속 다시 계산할지, 아니면 `MetricExtractor.Observation.samples`를 직접 사용할지 정합니다. 두 경로가 어긋나지 않게 하기 위해서입니다.
4. `benchmark-metrics`와 `run-json` 사이에 M4 regression detector를 넣습니다. `RegressionRules`를 적용해서 `baseline_value`, `delta_pct`, `regression`을 채우고 `baseline_ref`와 `reference_ref`를 설정합니다.
