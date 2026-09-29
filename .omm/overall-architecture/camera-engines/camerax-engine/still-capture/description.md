CameraXStillCapture는 CameraXEngine의 Live 사진 촬영을 맡습니다. ImageCapture가 만든 카메라 JPEG와 ImageAnalysis 스트림의 YUV 프레임을 한 쌍으로 묶어, Camera2StillCapture와 같은 MediaLibrary 경로로 앨범에 두 장을 저장합니다.

CameraX는 앱이 analysis 스트림을 still 요청의 대상에 넣을 방법을 제공하지 않습니다. 그래서 Camera2처럼 두 버퍼가 한 capture에서 나오지 않습니다. 대신 JPEG의 센서 타임스탬프와 가장 가까운 analysis 프레임을 YUV 쪽으로 고르고, 두 타임스탬프의 차이를 media_saved 이벤트의 yuvOffsetNs에 기록합니다. 평소에는 프레임을 복사하지 않고, 촬영 요청부터 짝이 정해질 때까지만 최근 8개 프레임을 NV21로 복사해 둡니다. JPEG가 도착한 뒤에는 그보다 늦은 프레임이 하나 올 때까지 최대 100ms 기다립니다. 늦은 프레임이 가장 가까운 후보의 위쪽 경계가 되기 때문입니다. 아직 프레임이 하나도 없으면(still 동안 repeating 스트림을 멈추는 HAL) 다음 프레임을 기다리고, 5초 안에 끝나지 않으면 capture_timeout으로 실패를 돌려줍니다.

촬영 직전에 ImageCapture와 ImageAnalysis의 targetRotation을 현재 화면 회전으로 맞춥니다. 카메라 JPEG는 요청의 방향 정보를 그대로 담고, YUV 쪽은 프레임의 rotationDegrees만큼 픽셀을 돌려 JPEG으로 만듭니다. 이 변환은 두 엔진이 함께 쓰는 encodeYuvStill(StillEncoding.kt)입니다. 플래시 Auto·On의 precapture는 ImageCapture가 직접 수행하므로 이 클래스에는 측광 단계가 없습니다. 녹화 중에는 촬영을 거절합니다.

JPEG 단독 출력은 analysis를 기다리지 않습니다. YUV 단독 출력은 촬영 요청 뒤의 analysis 프레임을 저장하며 ImageCapture 요청은 하지 않습니다. 둘 다 꺼진 구성은 촬영을 거부합니다. MediaLibrary.savePhotos는 켜진 출력만 저장하고 결과 URI 수를 반환합니다.
