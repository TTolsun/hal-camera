입력은 Telemetry의 콜백 이벤트와 BenchmarkRunner의 실행 시각입니다. RunAssembler가 두 입력을 결합하고 BenchmarkEvaluator·RunValidityEvaluator가 측정값과 validity를 계산합니다. BenchmarkReport가 schema 5 JSON을 저장합니다.

Camera2는 선택한 사진 출력과 같은 센서 시각의 최종 결과를 모아 저장합니다. CameraX는 ImageCapture JPEG 또는 촬영 뒤 받은 ImageAnalysis 프레임 하나를 저장합니다. 영상은 두 엔진 모두 완성된 녹화 파일을 MediaLibrary로 공개합니다.

JPEG에서 YUV를 고르면 앱이 현재 YUV 크기의 프레임을 JPEG로 변환합니다. 해상도를 고르면 HAL JPEG를 저장합니다. JPEG는 DCIM/HALCamera, 촬영 JSON은 Download/HALCamera에 저장합니다. RAW를 켠 Camera2는 같은 센서 시각의 RAW와 CaptureResult로 DNG를 만듭니다. 벤치마크와 incident ZIP에는 이미지 픽셀을 넣지 않습니다.

RecentMediaThumbnail은 MediaStore 변경·화면 복귀 시 저장 완료 항목을 조회합니다. 별도 스레드에서 디코딩하고 활성 화면의 최신 결과만 RecentMediaButton에 반영합니다.

CLI는 직접 인자 또는 base64url JSON을 받고 shell UID·허용 설정·프로토콜을 검사합니다. CommandStore는 실행 전 요청을 저장합니다. CommandCoordinator는 저장 완료 뒤 artifact 크기·SHA-256·원본 URI를 기록하며, PC에는 URI 없이 결과와 등록된 artifact ID의 읽기 전용 파일을 제공합니다. ADB 스크립트는 manifest의 이름·크기·SHA-256을 검증하고 Download/HALCamera-cli/요청ID에 파일을 준비해 adb pull을 안내합니다. Python CLI도 검증한 임시 파일만 공개합니다.

벤치마크 JSON은 기기 안에서만 생성하며, files/benchmarks와 baseline 목록으로 비교합니다. 외부 JSON 가져오기는 0.13.0에서 제거했습니다.

Multi 모드의 ConcurrentSession은 독립 장치마다 출력과 저장을 관리합니다. PIP를 끈 장치의 사진은 이미지·CaptureResult 센서 시각을 연결하고, PIP를 켠 장치는 DeviceCompositor의 화면과 같은 합성 JPEG를 저장합니다. 합성 사진은 실제 센서 촬영 시각을 주장하지 않고 입력 Surface 시각을 별도로 기록합니다. ConcurrentPhotoStore는 JPEG를 DCIM/HALCamera에, 공통 촬영 ID·합성 여부·장치 ID·Physical ID·성공 또는 실패를 Download/HALCamera의 JSON에 기록합니다. 영상은 장치별 compositor에서 별도 무음 MP4로 저장합니다. 이 경로는 Benchmark 계산과 분리됩니다.
