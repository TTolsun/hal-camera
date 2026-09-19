입력은 Telemetry 의 콜백 이벤트와 BenchmarkRunner 의 실행 시각입니다. RunAssembler 가 두 입력을 결합하고 BenchmarkEvaluator·RunValidityEvaluator 가 측정값과 validity 를 계산합니다. ScoreComposer 가 검토한 calibration 에 맞는 적격 release run 의 내부 점수를 채우고 BenchmarkReport 가 schema 4 JSON 을 저장합니다.

Live still 요청은 YUV 와 JPEG 버퍼를 같은 센서 타임스탬프로 연결합니다. YUV 는 JPEG 으로 변환하고 카메라 JPEG 은 원본 바이트로 MediaStore 에 저장합니다. 녹화는 MediaRecorder 임시 파일을 완성한 뒤 MediaLibrary 가 앨범에 공개합니다.

RecentMediaThumbnail 은 MediaStore 변경과 촬영 화면 복귀 시 HALCamera 의 저장 완료 항목을 조회합니다. 썸네일 디코딩은 별도 작업 스레드에서 수행하고, 활성 화면의 최신 조회 결과만 RecentMediaButton 에 반영합니다.

CLI 요청은 base64url JSON 으로 들어오며 shell UID·CLI 허용 설정·프로토콜 검사를 거칩니다. CommandStore 는 동작 전 요청을 저장하고, 저장 완료 콜백 이후 CommandCoordinator 가 artifact 크기·SHA-256·원본 URI 를 기록합니다. PC 에는 원본 URI 를 제외한 결과를 반환하고, 등록된 artifact ID 로 읽기 전용 파일을 전달합니다. Python CLI 는 검증한 임시 파일만 최종 파일로 공개합니다.

외부 benchmark JSON 은 시스템 문서 선택기에서 ProfileArchive 로 들어갑니다. 형식·크기·계약을 검사한 원본만 SHA-256 이름으로 별도 저장합니다. ProfileLibrary 는 로컬 기록과 가져온 자료의 출처를 구별하고 ProfileComparison 은 선택한 독립 실행을 비교합니다. 선택 키는 preferences 에, 분석 방법·원본 해시·제외 사유를 포함한 결과는 내부 텍스트 파일에 저장합니다.