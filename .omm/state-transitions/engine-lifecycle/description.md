Camera2Engine은 HandlerThread에서 카메라를 열고 세션과 반복 요청을 구성합니다. CameraXEngine은 ProcessCameraProvider를 사용하며, 자세한 수명 주기는 하위 요소 camerax-lifecycle에 있습니다. 두 엔진은 active 상태로 늦은 신호를 걸러 내며 close(done) 완료 후 다음 열기를 진행합니다.

Live 녹화는 선택한 설정, Benchmark 녹화는 profile을 사용합니다. 녹화 상태는 overall-architecture/camera-engines/camera2-engine/live-recorder, 측정 순서는 runner-sequence에 있습니다. PIP 종료 전에 메인 사진 대기를 취소하여 순환 대기를 막습니다.

단계 전이의 시간 제한과 close 완료 처리는 runner-sequence에 있습니다.
