camera/는 CameraEngine 인터페이스와 Camera2Engine·CameraXEngine·DepthCameraEngine을 제공합니다. Camera2Engine은 기본 크기 선택 경로와 벤치마크용 명시적 StreamSpec 경로를 구분합니다. 세션별 콜백과 close(done) 완료 통지가 러너에 연결됩니다.
StreamConfiguration은 실제 카메라 출력으로 Callback 목록을 만듭니다. 앱의 YUV JPEG 변환·PIP 합성·GPU 인코더 출력은 Callback에 추가하지 않습니다.
