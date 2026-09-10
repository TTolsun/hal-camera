M2 `BenchmarkRunner`의 설계입니다. 아직 구현되지 않은 것을 다루므로, 코드를 비추는 다른 관점들과 달리 이 관점은 **무엇을 만들 것인가**를 기술합니다. 근거는 `docs/PLAN-BenchMarker-v0.3.md` 3장이고, 여기서는 그 규격이 기존 코드와 어디서 맞물리고 어디서 충돌하는지를 정리합니다.

러너는 `camera2-standard-v1` 프로파일 하나를, 사용자가 LIVE 화면에서 고른 카메라 하나에 대해 실행합니다. 순서는 PREFLIGHT, LAUNCH_CYCLE 10회, 11회차 OPEN 유지, WARMUP 3초, OBSERVE 10초, STILL 10장, CLOSE입니다. 예상 소요는 25초에서 30초이며 화면 카드에는 약 45초로 표시한 뒤 실기기 실측으로 갱신합니다.

`AutoCheckRunner`가 가장 가까운 참고 대상이지만 그대로 쓸 수는 없습니다. Auto Check는 엔드포인트 여러 개를 한 번씩 순회하는 구조인 반면, 벤치마크는 엔드포인트 하나를 같은 단계로 열 번 반복하고 그 뒤 세션 하나를 길게 유지합니다. 따라서 파라미터를 조정하는 것이 아니라 새 상태 기계를 만들어야 합니다.

산출물은 `BenchmarkEvaluator.Input`이 요구하는 형태입니다. `LaunchCycle` 10개, `StillSample` 10개, `MetricExtractor.Observation` 하나, 그리고 콜백 실패 횟수입니다. 이 네 가지가 채워지는 순간 v0.3 데이터 경로 전체가 디스크까지 연결됩니다.
