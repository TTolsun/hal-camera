benchmark/domain/RunAssembler.kt는 러너 Result와 이벤트에서 관측 세션·워밍업 수·콜백 실패를 계산합니다. BenchmarkEvaluator와 RunValidityEvaluator를 호출하고 환경·프로파일·원시 표본을 합쳐 BenchmarkRun을 구성합니다. 이어서 ScoreComposer가 calibration 범위와 적격 조건에 맞는 run의 내부 점수를 채웁니다. 실제 파일 쓰기는 Activity가 BenchmarkReport에 요청합니다.

Context의 선택적 deviceInstanceId를 raw.device_instance_id에 기록합니다. BenchmarkActivity가 앱 설치 단위 UUID를 제공하며, 재설치나 앱 데이터 삭제 후 동일 물리 기기 여부는 사용자가 확인해야 합니다.

ObservationInput.observedFrames는 워밍업을 제외한 steadyFrames 수이며 RunValidityEvaluator의 표본 수 검사에 전달됩니다. RunAssembler는 BenchmarkRun을 구성하고, BenchmarkReportCodec이 schema 4로 직렬화합니다.
