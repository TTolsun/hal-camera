Camera2LiveRecorder는 Camera2Engine의 Live 녹화를 맡습니다. 벤치마크 RECORD 단계는 크기를 스스로 고르거나 소리를 녹음하거나 앨범에 저장하면 안 되므로 별도의 BenchmarkRecorder를 씁니다.

녹화는 MediaRecorder의 영상·마이크 입력을 사용합니다. 시작하면 1080p 이하의 가로형 크기를 고르고 임시 MP4에 기록하도록 준비한 뒤, 프리뷰와 인코더 출력으로 새 세션을 구성합니다. 세션이 구성되면 엔진이 AE 재잠금 대기와 녹화 repeating request, AF 재잠금을 차례로 처리하고, 그다음 recorder.start()를 부릅니다. 정지하면 세션을 닫고, 세션이 닫힐 때 파일을 앨범에 저장한 뒤 엔진에 프리뷰 세션을 다시 만들게 합니다. 실패하거나 너무 짧아 stop에 실패한 파일은 폐기합니다. 카메라가 닫힐 때도 finish가 같은 정리를 합니다.

녹화 중에는 사진 촬영 요청을 받지 않지만 줌과 Live 제어는 받습니다. 녹화 세션이 구성 중이거나 종료 중이면 blocksRequests가 참이 되어 엔진이 요청을 보내지 않고, 다음 세션이 구성될 때 현재 값을 읽어 적용합니다. 녹화 중인 인코더 surface는 이 클래스가 들고 있으며, 엔진은 이 값이 있으면 repeating request를 녹화 템플릿으로 만듭니다.

Android 13 이상에서는 카메라의 녹화 출력을 PRIVATE ImageReader(USAGE_VIDEO_ENCODE)에 연결하고 RecordingBufferRelay가 별도 스레드에서 버퍼 도착 이벤트를 남긴 뒤 ImageWriter로 MediaRecorder에 넘깁니다. 픽셀을 CPU로 읽거나 복사하지 않으며 벤치마크 녹화 경로는 변경하지 않습니다. 세션과 요청, 그래프는 동일한 recording 출력 ID를 사용합니다. OutputConfiguration의 timestampBase를 SENSOR로 지정하여 버퍼와 onCaptureStarted의 시각 키를 정확히 연결합니다. REALTIME 센서인 경우 인코더에 넘기는 타임스탬프만 monotonic 시계로 변환하여 오디오 동기화 시계와 맞춥니다. UNKNOWN 센서 시각에서 앱 시각을 빼지 않습니다. 종료는 새 버퍼 수신 차단, MediaRecorder 정지·해제, 작업 스레드의 writer·reader 해제 순서입니다. Android 12 이하에서는 기존 MediaRecorder 직결 경로를 유지하며 값을 추정하지 않습니다.

LiveVideo를 지정하면 카메라 크기·고정 FPS 범위와 인코더의 surface 입력·크기·프레임률·비트레이트 지원을 대조한 조합을 사용합니다. H264 또는 HEVC, 24·25·30·60fps 후보 중 지원되는 것을 선택하며 크기를 자동 대체하지 않습니다. LiveSessionCheck는 출력 조합을 조회하고 조회 미지원은 unknown으로 기록합니다. 녹화 준비 실패는 기존 프리뷰를 유지하고, 녹화 세션 실패·정지 후에는 선택했던 프리뷰 구성을 다시 만듭니다. 요청 설정과 preflight 결과 및 녹화 시작 설정은 별도 이벤트에 남습니다.
