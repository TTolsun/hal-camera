BenchmarkRunner는 API 호출 직전 시각과 현재 세션의 완료 신호로 지연을 계산합니다. Result의 cycles·stills·관측 세션·시간 창은 RunAssembler로 전달됩니다. request_observed는 onCaptureStarted에서 관측한 요청이므로 제출 시각을 대체하지 않습니다.
