Telemetry는 onCaptureStarted, onCaptureProgressed, onCaptureCompleted를 각각 capture_started, capture_partial, capture_result 이벤트로 기록합니다. Partial과 Metadata의 도착 시각을 별개로 보존합니다. configureCallbackStreams는 실제 세션 출력 목록과 앱의 콜백 관측 가능 여부, 구성 시각을 세션 메타데이터에 기록합니다. 이미지 수신과 프리뷰 표시는 센서 타임스탬프를 연결 키로 쓰며, 콜백 수신 시간차는 앱 시계끼리 계산합니다.

출력 구성은 반복 요청 여부도 기록합니다. Camera2의 시작 이벤트에는 실제 요청을 만들 때 사용한 출력 목록의 대상 ID을 기록하여 같은 프레임의 요청 대상 여부를 구분합니다. 추적되지 않은 요청과 CameraX의 내부 요청 대상은 추정하지 않습니다. 최종 결과에 포함된 partialResultsCount는 수신 경로 확인을 위한 개수이며 중간 결과 도착 시각의 대체값으로 쓰지 않습니다.

Shutter 이벤트에는 직전 Start의 앱 수신 시각과 구성 후 첫 Shutter 여부도 보존합니다. 그래프는 Partial을 제외하고 Shutter, Metadata, 각 출력의 도착 시간을 직전 Shutter 기준으로 계산합니다. 첫 Start만 0ms이며, 구성 변경 시 기준을 초기화합니다.
