입력은 Telemetry의 콜백 이벤트와 BenchmarkRunner의 실행 시각입니다. RunAssembler가 두 입력을 결합하고 BenchmarkEvaluator·RunValidityEvaluator가 측정값과 validity를 계산합니다. BenchmarkReport가 schema 5 JSON을 저장합니다.

Camera2 Live still 요청은 켜진 YUV·JPEG 출력을 capture 센서 타임스탬프로 연결합니다. 기본은 두 출력이며 단일 출력은 꺼진 버퍼를 기다리지 않습니다. YUV는 JPEG으로 변환하고 카메라 JPEG은 원본 바이트로 MediaStore에 저장합니다. 녹화는 MediaRecorder 임시 파일을 완성한 뒤 MediaLibrary가 앨범에 공개합니다.

YUV Save Format에서 JPEG 또는 NV21을 선택합니다. JPEG는 기존 `_YUV.jpg`를, NV21은 `_YUV.nv21`을 저장하며 두 파일을 함께 만들지 않습니다. 사진 모드는 선택한 포맷과 관계없이 `_metadata.json`을 함께 저장합니다. JPEG는 DCIM/HALCamera에, NV21과 JSON은 Download/HALCamera에 있습니다. Camera2는 최종 CaptureResult와 이미지의 센서 시각을 맞춥니다. 벤치마크 JSON과 incident ZIP에는 이미지 픽셀을 넣지 않습니다.

RecentMediaThumbnail은 MediaStore 변경과 촬영 화면 복귀 시 HALCamera의 저장 완료 항목을 조회합니다. 썸네일 디코딩은 별도 작업 스레드에서 수행하고, 활성 화면의 최신 조회 결과만 RecentMediaButton에 반영합니다.

CLI 요청은 직접 명령 인자 또는 기존 base64url JSON으로 들어오며 shell UID·CLI 허용 설정·프로토콜 검사를 거칩니다. CommandStore는 동작 전 요청을 저장하고, 저장 완료 콜백 이후 CommandCoordinator가 artifact 크기·SHA-256·원본 URI를 기록합니다. PC에는 원본 URI를 제외한 결과를 반환하고, 등록된 artifact ID로 읽기 전용 파일을 전달합니다. ADB 스크립트는 files manifest의 이름·크기·SHA-256을 확인하고 기기의 Download/HALCamera-cli/요청ID에 파일을 준비한 뒤 adb pull 명령을 안내합니다. Python CLI도 검증한 임시 파일만 최종 파일로 공개합니다.

벤치마크 실행 JSON은 기기 안에서만 만들어집니다. 외부 JSON을 가져와 별도 archive에 보관하던 경로는 0.13.0에서 제거했으므로, files/benchmarks의 실행과 baseline 실행 목록이 비교의 전부입니다.
