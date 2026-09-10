AutoCheckRunner와 BenchmarkRunner는 API 호출 직전 mark와 현재 세션의 완료 신호 시각으로 지연을 계산합니다. request_observed는 onCaptureStarted 시점의 요청 내용이므로 제출 시각을 대체하지 않습니다.

Auto Check의 EndpointResult는 CheckEvaluator로 전달됩니다. BenchmarkRunner는 LaunchCycle, StillSample, 관측 세션과 창 정보를 Result로 내보내고 RunAssembler가 이를 받습니다. 근거: check/AutoCheckRunner.kt, benchmark/BenchmarkRunner.kt, benchmark/RunAssembler.kt.
