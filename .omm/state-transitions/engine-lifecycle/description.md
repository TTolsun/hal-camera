Camera2Engine은 HandlerThread에서 카메라를 열고 세션과 반복 요청을 구성합니다. CameraXEngine은 ProcessCameraProvider를 사용합니다. 두 엔진은 active 상태로 늦은 신호를 걸러 내며 close(done) 완료 후 다음 열기를 진행합니다.

Camera2Engine은 녹화 경로를 두 개 가지고 있습니다. Live 경로는 크기를 스스로 고르고 오디오를 넣어 갤러리에 저장하며, 벤치마크 경로는 profile이 지정한 조건만 쓰고 cacheDir의 파일을 사이클이 끝나면 지웁니다. 벤치마크 경로는 세션을 녹화 스트림으로 다시 구성한 뒤 MediaRecorder.start()가 반환한 다음에야 녹화 대상 request를 제출하고, 종료할 때에는 repeating을 먼저 멈춘 뒤 stop()을 부릅니다. 이 순서 자체가 측정 조건입니다.

단계 전이의 시간 제한과 close 완료 처리는 runner-sequence에 있습니다.
