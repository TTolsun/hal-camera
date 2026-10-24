Camera2Engine은 HandlerThread에서 열기·세션·반복 요청을 처리합니다. CameraXEngine의 ProcessCameraProvider 수명주기는 camerax-lifecycle에 있습니다. 두 엔진은 active로 늦은 신호를 차단하고 close(done) 뒤 다음 엔진을 엽니다.

Live 녹화는 선택 설정, Benchmark는 profile을 사용합니다. 녹화 상태는 overall-architecture/camera-engines/camera2-engine/live-recorder에 있습니다. PIP 종료 전 메인 사진 대기를 취소하여 순환 대기를 막습니다.

측정 순서·시간 제한·close 처리는 runner-sequence에 있습니다.

센서 Depth의 별도 세션과 시간 제한은 depth-engine 요소에 설명합니다.
