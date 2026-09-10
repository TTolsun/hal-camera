구현 순서 제안입니다.

1. `Camera2Engine`에 프로파일이 지정한 스트림 크기를 그대로 받는 경로를 만들고 `CONTROL_AE_TARGET_FPS_RANGE`를 설정합니다. 이것이 없으면 나머지가 모두 잘못된 조건에서 측정됩니다.
2. `ProfileCompatibility` preflight를 만듭니다. 정적 판정과 API 35 이상의 `device_setup` 질의 두 경로를 모두 구현하고 결과를 `Compatibility`에 담습니다.
3. 러너 상태 기계를 만듭니다. `AutoCheckRunner`처럼 `Driver`, `Scheduler`, `clock`, `Listener`로 격리하고 단위 테스트를 먼저 씁니다.
4. `LaunchCycle`과 `StillSample`을 채워 `BenchmarkEvaluator.Input`을 완성하고, `RunValidityEvaluator`에 넘길 `ValidityInputs`를 모읍니다.
5. `BenchmarkActivity`와 6단계 진행 표시를 만들고 `BenchmarkReport`로 기록합니다.
6. Galaxy S25+에서 실측해 1080p 3-stream 조합과 `isCameraDeviceSetupSupported()` 결과를 확인합니다. 성립하면 프로파일 ID에서 `-draft`를 뗍니다.
