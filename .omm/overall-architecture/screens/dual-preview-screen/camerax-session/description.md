DualCameraXSession은 동일 논리 카메라의 서로 다른 physical ID를 ConcurrentCamera로 연결합니다. DualPreviewRelay는 각 Preview를 자체 TextureView와 녹화 인코더에 전달하며 두 카메라의 영상을 합성하지 않습니다. CameraState 관찰자와 장치 onClosed, surface 반환을 기다린 뒤 종료하고 관찰자를 제거합니다. Activity가 중지되어도 종료 관찰을 계속합니다. Dual · V는 1280×720이며 구성 거부 시 자동으로 Camera2로 바꾸지 않습니다.

바인딩한 카메라의 CameraControl에 공통 줌을 요청합니다. 메인 개별 제어와 두 센서 사진은 지원하지 않습니다. Relay는 화면 출력에도 입력 센서 타임스탬프를 유지하여 Main/Sub display Callback을 해당 요청에 연결합니다.
