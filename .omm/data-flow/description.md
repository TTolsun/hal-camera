입력은 Telemetry의 콜백 이벤트와 BenchmarkRunner의 실행 시각입니다. RunAssembler가 두 입력을 결합하고 BenchmarkEvaluator·RunValidityEvaluator가 측정값과 validity를 계산합니다. ScoreComposer가 검토한 calibration에 맞는 적격 release run의 내부 점수를 채우고 BenchmarkReport가 schema 4 JSON을 저장합니다.

LIVE still 요청은 YUV와 JPEG 버퍼를 같은 센서 타임스탬프로 연결합니다. YUV는 JPEG으로 변환하고 카메라 JPEG은 원본 바이트로 MediaStore에 저장합니다. 녹화는 MediaRecorder 임시 파일을 완성한 뒤 MediaLibrary가 앨범에 공개합니다.

CLI 요청은 base64url JSON으로 들어오며 shell UID·CLI 허용 설정·프로토콜 검사를 거칩니다. CommandStore는 동작 전 요청을 저장하고, 저장 완료 콜백 이후 CommandCoordinator가 artifact 크기·SHA-256·원본 URI를 기록합니다. PC에는 원본 URI를 제외한 결과를 반환하고, 등록된 artifact ID로 읽기 전용 파일을 전달합니다. Python CLI는 검증한 임시 파일만 최종 파일로 공개합니다.
