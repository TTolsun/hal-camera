입력은 Telemetry의 콜백 이벤트와 BenchmarkRunner의 실행 시각입니다. RunAssembler가 두 입력을 결합하고 BenchmarkEvaluator·RunValidityEvaluator가 측정값과 validity를 계산합니다. BenchmarkReport가 schema 5 JSON을 저장합니다.

Camera2 Live still은 활성 YUV·JPEG 출력을 capture 센서 시각으로 연결하며 꺼진 출력은 기다리지 않습니다. 기본은 두 출력입니다. YUV는 JPEG으로 변환하고 카메라 JPEG은 원본을 MediaStore에 저장합니다. MediaLibrary는 완성된 MediaRecorder 임시 파일을 앨범에 공개합니다.

YUV Save Format은 `_YUV.jpg` 또는 `_YUV.nv21` 하나를 저장하고 사진에는 `_metadata.json`을 동반합니다. JPEG는 DCIM/HALCamera, NV21·JSON은 Download/HALCamera에 저장합니다. Camera2는 이미지와 최종 CaptureResult의 센서 시각을 맞춥니다. RAW (DNG)를 켜면 같은 시각의 RAW·CaptureResult로 `_RAW.dng`를 DCIM/HALCamera에 저장합니다. 벤치마크 JSON과 incident ZIP에는 이미지 픽셀을 넣지 않습니다.

RecentMediaThumbnail은 MediaStore 변경·화면 복귀 시 저장 완료 항목을 조회합니다. 별도 스레드에서 디코딩하고 활성 화면의 최신 결과만 RecentMediaButton에 반영합니다.

CLI는 직접 인자 또는 base64url JSON을 받고 shell UID·허용 설정·프로토콜을 검사합니다. CommandStore는 실행 전 요청을 저장합니다. CommandCoordinator는 저장 완료 뒤 artifact 크기·SHA-256·원본 URI를 기록하며, PC에는 URI 없이 결과와 등록된 artifact ID의 읽기 전용 파일을 제공합니다. ADB 스크립트는 manifest의 이름·크기·SHA-256을 검증하고 Download/HALCamera-cli/요청ID에 파일을 준비해 adb pull을 안내합니다. Python CLI도 검증한 임시 파일만 공개합니다.

벤치마크 JSON은 기기 안에서만 생성하며, files/benchmarks와 baseline 목록으로 비교합니다. 외부 JSON 가져오기는 0.13.0에서 제거했습니다.
