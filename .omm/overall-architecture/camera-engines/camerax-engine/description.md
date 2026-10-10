CameraXEngine은 Live의 CameraX 엔진입니다. MainActivity에서 Live 촬영·녹화·크기 선택을 제공하며 코덱 선택 등 Camera2와 다른 제약은 유지합니다. 사진 쌍 저장은 하위 요소 still-capture, 동영상 녹화는 live-recorder, EV·AE/AF 잠금·플래시와 터치 측광은 controls가 맡고, 엔진은 use case bind와 줌, 수명 주기만 다룹니다. 벤치마크 profile은 Camera2 전용이므로(PLAN-BenchMarker-v0.3 8.2) 이 엔진은 StreamSpec을 받지 않습니다.

ProcessCameraProvider로 Preview, ImageAnalysis(KEEP_ONLY_LATEST), ImageCapture(MINIMIZE_LATENCY)를 Activity 수명 주기에 bind합니다. Camera2Engine의 preview·YUV·JPEG 세 스트림에 대응하는 구성입니다. `Camera2Interop.Extender(builder).setSessionCaptureCallback(...)`으로 Camera2와 같은 Telemetry 콜백을 preview use case에 붙이므로 두 엔진이 같은 이벤트 종류를 만들고 수치를 비교할 수 있습니다. 엔진은 이 콜백을 한 번 감싸서 모든 repeating 결과를 controls에도 넘깁니다. AE 재잠금과 길게 누르기 측광이 AE 상태를 읽기 때문입니다.

카메라는 `Camera2CameraInfo.from(it).cameraId`로 거르는 CameraSelector로 고르므로 전면·후면이 아닌 특정 카메라 ID를 열 수 있습니다. 줌은 zoomState 범위로 제한한 `CameraControl.setZoomRatio`이며, 녹화 중에도 bind된 세션에 그대로 적용됩니다. VIDEO 진입 시 use case를 bind하며 녹화 시작·정지 때는 유지합니다. 출력 구성이 바뀔 때 줌과 제어를 새 세션에 다시 보냅니다. 협상된 해상도는 각 use case의 resolutionInfo에서 읽어 Camera2와 같은 방식으로 세션 맵에 저장합니다.

닫는 경로가 가장 까다롭습니다. Activity가 멈춘 상태에서도 해제가 끝나도록 수명 주기에 묶인 observer 대신 observeForever로 cameraState를 관찰합니다. unbind가 돌아올 때 이미 CLOSED인 경우를 위해 finished 플래그로 finish()를 한 번만 실행합니다. 녹화 중에 닫히면 VideoCapture도 함께 unbind하고, 파일 저장이 mediaIo에 들어간 뒤에 그 실행기를 종료합니다. CameraX 1.6은 닫은 카메라를 1초 동안 열어 두므로, CLOSED 뒤에 ProcessCameraProvider.shutdownAsync()로 카메라를 바로 놓고 나서 done을 부릅니다(#230). 종료는 최대 1초만 기다리고 provider_shutdown 이벤트를 남깁니다. Live에서 다음 엔진도 CameraX이면 releaseOnClose를 꺼서 종료하지 않습니다.

LiveStreamSettings의 정확한 크기는 ResolutionSelector 필터로 선택합니다. 꺼진 YUV·JPEG의 use case는 생성하지 않습니다. 프리뷰 단독 구성에서는 PreviewView STREAMING이 준비 완료를 알립니다. 구성 결과는 negotiatedStreams에 기록하고 실패는 화면과 CLI에 전달합니다.

PIP는 CameraXPipSources가 availableConcurrentCameraInfos의 두 장치 조합을 필터링하며 CameraXPipSession이 두 Preview를 ConcurrentCamera로 엽니다. 화면·사진·영상은 기존 DeviceCompositor를 공유합니다. Live는 세로 방향이며 ViewPort crop을 추가하지 않고 SurfaceTexture의 카메라 변환을 적용합니다. PIP 터치 측광은 협상한 프리뷰의 fill-center crop과 DisplayOrientedMeteringPointFactory를 사용합니다. 종료·실패 후 일반 use case를 복원하고 줌·제어를 다시 적용합니다. 상세 저장·수명주기 계약은 pip-composition 요소를 따릅니다.
