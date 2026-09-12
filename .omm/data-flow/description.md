입력은 Telemetry의 콜백 이벤트와 BenchmarkRunner의 실행 시각입니다. RunAssembler가 두 입력을 결합하고 BenchmarkEvaluator·RunValidityEvaluator가 측정값과 validity를 계산합니다. ScoreComposer가 검토한 calibration에 맞는 적격 release run의 내부 점수를 채우고 BenchmarkReport가 schema 4 JSON을 저장합니다.

LIVE still 요청은 YUV와 JPEG 버퍼를 같은 센서 타임스탬프로 연결합니다. YUV는 JPEG으로 변환하고 카메라 JPEG은 원본 바이트로 MediaStore에 저장합니다. 녹화는 MediaRecorder 임시 파일을 완성한 뒤 MediaLibrary가 앨범에 공개합니다.
