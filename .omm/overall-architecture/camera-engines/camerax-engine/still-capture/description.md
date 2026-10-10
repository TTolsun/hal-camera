CameraXStillCapture는 JPEG 선택에 따라 ImageCapture가 생성한 JPEG 또는 촬영 요청 뒤 도착한 ImageAnalysis 프레임 하나를 저장합니다. 두 이미지를 동시에 저장하거나 가까운 프레임을 짝짓지 않습니다. YUV를 선택하면 ImageCapture를 생성하지 않으며, encodeYuvStill로 현재 YUV 크기의 프레임을 JPEG로 변환합니다. 분석 프레임은 촬영 대기 중에만 복사합니다.

촬영 직전 targetRotation을 맞추며 카메라 JPEG는 EXIF를 유지하고 앱 변환은 픽셀을 회전합니다. 선택한 이미지의 센서 시각과 일치하는 CaptureResult만 JSON에 기록하고 없으면 unavailable로 표시합니다. 변환 완료는 app_jpeg_available로 기록합니다. 5초 안에 이미지가 도착하지 않으면 실패합니다. 저장 중에는 BUSY를 유지하고 이미 시작된 파일 쓰기는 카메라 종료 후에도 완료합니다. RAW/DNG는 지원하지 않습니다.
