CameraXEngine은 Live의 CameraX 엔진입니다. MainActivity에서만 쓰며, Live에서 Camera2Engine이 제공하는 기능을 모두 제공합니다. 사진 쌍 저장은 하위 요소 still-capture, 동영상 녹화는 live-recorder, EV·AE/AF 잠금·플래시와 터치 측광은 controls가 맡고, 엔진은 use case bind와 줌, 수명 주기만 다룹니다. 벤치마크 profile은 Camera2 전용이므로(PLAN-BenchMarker-v0.3 8.2) 이 엔진은 StreamSpec을 받지 않습니다.

ProcessCameraProvider로 Preview, ImageAnalysis(KEEP_ONLY_LATEST), ImageCapture(MINIMIZE_LATENCY)를 Activity 수명 주기에 bind합니다. Camera2Engine의 preview·YUV·JPEG 세 스트림에 대응하는 구성입니다. `Camera2Interop.Extender(builder).setSessionCaptureCallback(...)`으로 Camera2와 같은 Telemetry 콜백을 preview use case에 붙이므로 두 엔진이 같은 이벤트 종류를 만들고 수치를 비교할 수 있습니다. 엔진은 이 콜백을 한 번 감싸서 모든 repeating 결과를 controls에도 넘깁니다. AE 재잠금과 길게 누르기 측광이 AE 상태를 읽기 때문입니다.

카메라는 `Camera2CameraInfo.from(it).cameraId`로 거르는 CameraSelector로 고르므로 전면·후면이 아닌 특정 카메라 ID를 열 수 있습니다. 줌은 zoomState 범위로 제한한 `CameraControl.setZoomRatio`이며, 녹화 중에도 bind된 세션에 그대로 적용됩니다. 녹화 시작과 정지는 use case를 다시 bind하므로 그때마다 줌과 제어를 새 세션에 다시 보냅니다. 협상된 해상도는 각 use case의 resolutionInfo에서 읽어 Camera2와 같은 방식으로 세션 맵에 저장합니다.

닫는 경로가 가장 까다롭습니다. Activity가 멈춘 상태에서도 해제가 끝나도록 수명 주기에 묶인 observer 대신 observeForever로 cameraState를 관찰합니다. unbind가 돌아올 때 이미 CLOSED인 경우를 위해 finished 플래그로 finish()를 한 번만 실행합니다. 녹화 중에 닫히면 VideoCapture도 함께 unbind하고, 파일 저장이 mediaIo에 들어간 뒤에 그 실행기를 종료합니다.
