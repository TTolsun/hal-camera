DualCameraXSession은 동일 논리 카메라의 서로 다른 physical ID를 ConcurrentCamera로 연결합니다. DualPreviewRelay는 각 Preview를 자체 TextureView와 녹화 인코더에 전달하며 두 카메라의 영상을 합성하지 않습니다. CameraState 관찰자와 장치 onClosed, surface 반환을 기다린 뒤 종료하고 관찰자를 제거합니다. Activity가 중지되어도 종료 관찰을 계속합니다. Dual · V는 1280×720이며 구성 거부 시 자동으로 Camera2로 바꾸지 않습니다.

바인딩한 카메라의 CameraControl에 공통 줌을 요청합니다. 메인 개별 제어와 두 센서 사진은 지원하지 않습니다. 각 relay는 GL 입력을 읽은 직후에 프리뷰 이름, 물리 ID, 버퍼 타임스탬프를 기록한 뒤 화면과 선택한 인코더로 전달합니다. Callback은 이 물리 ID와 타임스탬프가 표시할 프레임의 물리 결과와 일치할 때만 해당 Preview 행에 시간을 표시합니다.
