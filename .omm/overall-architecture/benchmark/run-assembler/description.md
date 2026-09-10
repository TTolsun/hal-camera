benchmark/RunAssembler.kt는 러너 Result와 이벤트에서 관측 세션·워밍업 수·콜백 실패를 계산합니다. BenchmarkEvaluator와 RunValidityEvaluator를 호출하고 환경·프로파일·원시 표본을 합쳐 BenchmarkRun을 구성합니다. 실제 파일 쓰기는 Activity가 BenchmarkReport에 요청합니다.
