PortraitCameraEngine은 API 31 이상에서 제조사 BOKEH의 CameraExtensionSession을 소유합니다. 공개 카메라의 확장 지원·SurfaceTexture 미리보기 크기·JPEG 크기를 각각 조회하고, 선택한 PortraitConfig의 두 크기로 세션을 구성합니다. 일반 StreamConfigurationMap 크기를 대용으로 쓰거나 실패 때 다른 크기로 재시도하지 않습니다.

Live의 Depth 메뉴는 Off·Portrait (Bokeh)·Depth Map을 구분합니다. Portrait는 컬러 미리보기와 JPEG 촬영이며 센서 Depth 맵을 반환하지 않습니다. 선택하면 Camera2로 전환하고 기존 close(done) 뒤 확장 세션을 엽니다. TextureView의 첫 표시 프레임 뒤 촬영을 허용합니다. 미리보기 15초·JPEG 60초 시간 제한, 실패 후 해제, 늦은 JPEG 차단과 단일 촬영의 한 번뿐인 완료 전달을 둡니다. close(done)는 열기 중인 장치도 닫고 CameraDevice.onClosed 뒤 자원을 회수합니다. 이미 받은 JPEG의 저장 작업은 완료할 수 있습니다.

사진은 OEM JPEG를 그대로 MediaLibrary로 저장하고 JPEG 크기·미리보기 크기·manufacturer_bokeh 효과·시각을 촬영 JSON에 기록합니다. 수동 제어·줌·터치 측광·연사·AEB·RAW·PIP·동영상은 제공하지 않습니다. 일반 Camera2 콜백 지표로 확장 처리를 측정하지 않으며 Benchmark와 별도 경로입니다. Photo·Video·카메라·엔진 전환과 새 CLI 촬영 준비는 Portrait를 종료합니다.
