ConcurrentCameraActivity는 기존 Dual 모드를 대체하는 Multi 화면입니다. Multi는 2개 이상의 카메라 장치를 독립적으로 사용합니다. PIP는 주·보조 프리뷰를 보이는 구도 그대로 합성하여 사진·영상으로 저장하는 별도 기능을 뜻하며 이 화면의 기능명이 아닙니다. Android 11 이상에서 cameraIdList에 있는 독립 장치의 조합을 고르고, 각 카메라의 preview ≤720p와 JPEG ≤1440p 출력을 isConcurrentSessionConfigurationSupported로 확인합니다. 후면·후면과 후면·전면을 같은 목록에서 선택합니다. concurrentCameraIds의 광고 조합을 먼저 표시하고 상세 창에서 실제 구성 상태를 확인하며, 구성 거부 시 자동으로 크기를 바꾸지 않습니다. CameraX에서 진입하면 Camera2 경로로 전환해야 한다는 안내를 제공합니다.

Live Streams의 Multi 최대 장치 수는 기본 All available이며 Apply에서 저장합니다. 광고된 동시 조합의 부분집합 중 설정 상한 이내의 모든 조합을 만들고 큰 조합부터 제시합니다. 독립 ID 쌍은 미광고 조합도 사전 검사 결과를 확인할 수 있습니다. 화면과 사진 결과는 선택한 장치 수에 맞춰 구성합니다. ConcurrentSession은 모든 open 완료 후 각 세션을 구성합니다. ConcurrentLifecycle은 카메라별 open·streaming·closing·closed 상태를 관리하며, 한쪽 실패나 화면 종료 시 늦게 도착하는 open 콜백까지 기다린 뒤 모든 장치의 출력을 해제합니다. Live는 자신의 close(done) 이후 화면을 열고, 돌아오면 원래 프리뷰를 다시 엽니다. Benchmark의 profile이나 측정 경로는 바꾸지 않습니다.

모든 스트림의 결과가 도착해야 사진 버튼을 활성화합니다. ConcurrentLease는 화면 재생성으로 새 인스턴스가 생겨도 이전 인스턴스의 출력 해제 이후에 시작하도록 직렬화합니다. 사진 제한 시간은 10초이며 만료 시 미완료 카메라를 실패로 기록하고 모든 장치를 닫습니다.

프리뷰는 보조 영상 배치와 상하 분할을 지원합니다. 주 화면과 여러 보조 프리뷰를 표시합니다. 보조 영상 열의 위치와 크기를 바꾸고 Swap으로 주 카메라를 순환할 수 있습니다. 분할에서는 모든 영상을 세로로 배치합니다. Move inset 버튼은 드래그 대신 네 모서리로 이동합니다. 프리뷰 배치를 합성한 사진·영상은 저장하지 않습니다.

사진은 하나의 앱 명령에서 카메라별 독립 요청을 발행합니다. Image.timestamp와 CaptureResult.SENSOR_TIMESTAMP가 같은 이미지만 요청 결과와 결합합니다. ConcurrentPhotoStore는 성공한 JPEG를 DCIM/HALCamera에 저장하고, 공통 group ID·camera ID·timestamp source·타임스탬프·각 카메라의 성공 또는 실패·파일 URI를 Download/HALCamera의 JSON으로 저장합니다. 부분 실패에도 성공한 카메라 파일과 실패 사유를 식별할 수 있으며 센서 동기 촬영이라고 주장하지 않습니다. 메타데이터 저장 실패 시 이번 묶음의 파일을 롤백합니다.
