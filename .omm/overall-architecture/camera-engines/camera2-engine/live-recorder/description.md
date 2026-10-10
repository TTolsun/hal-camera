Camera2LiveRecorder는 Camera2Engine의 Live 녹화를 맡습니다. 벤치마크 RECORD 단계는 크기를 스스로 고르거나 소리를 녹음하거나 앨범에 저장하면 안 되므로 별도의 BenchmarkRecorder를 씁니다.

VIDEO 모드 진입 시 프리뷰·인코더와 선택한 JPEG 또는 YUV 스냅샷 출력을 구성합니다. 녹화 버튼은 이미 구성한 세션에서 MediaRecorder를 시작하고 인코더를 repeating request의 대상으로 추가합니다. persistent input surface를 사용하므로 오디오 설정을 적용할 때도 카메라 세션을 다시 만들지 않습니다. 준비 단계에서는 인코더로 프레임을 보내지 않으며 RAW/DNG 출력은 제외합니다. 정지하면 세션을 닫고 파일을 마무리한 뒤 VIDEO 스트림을 다시 준비합니다. 너무 짧거나 실패한 파일은 폐기합니다.

녹화 중에는 YUV·JPEG 사진 쌍 촬영 요청을 받지 않습니다. 대신 Camera2VideoSnapshot이 TEMPLATE_VIDEO_SNAPSHOT 요청으로 카메라 JPEG 또는 YUV를 받아 JPEG 한 장을 저장하며, 한 번에 한 장만 처리하고 실패해도 녹화를 끝내지 않습니다. 줌과 Live 제어도 받습니다. 녹화 세션이 구성 중이거나 종료 중이면 blocksRequests가 참이 되어 엔진이 요청을 보내지 않고, 다음 세션이 구성될 때 현재 값을 읽어 적용합니다. 녹화 중인 인코더 surface는 이 클래스가 들고 있으며, 엔진은 이 값이 있으면 repeating request를 녹화 템플릿으로 만듭니다.

Android 13 이상에서는 카메라의 녹화 출력을 PRIVATE ImageReader(USAGE_VIDEO_ENCODE)에 연결하고 RecordingBufferRelay가 별도 스레드에서 버퍼 도착 이벤트를 남긴 뒤 ImageWriter로 MediaRecorder에 넘깁니다. 픽셀을 CPU로 읽거나 복사하지 않으며 벤치마크 녹화 경로는 변경하지 않습니다. 세션과 요청, 그래프는 동일한 recording 출력 ID를 사용합니다. OutputConfiguration의 timestampBase를 SENSOR로 지정하여 버퍼와 onCaptureStarted의 시각 키를 정확히 연결합니다. REALTIME 센서인 경우 인코더에 넘기는 타임스탬프만 monotonic 시계로 변환하여 오디오 동기화 시계와 맞춥니다. UNKNOWN 센서 시각에서 앱 시각을 빼지 않습니다. 종료는 새 버퍼 수신 차단, MediaRecorder 정지·해제, 작업 스레드의 writer·reader 해제 순서입니다. EIS (Video) 또는 EIS (Preview + Video)를 명시적으로 켜면 인코더 직결 경로를 사용합니다. Galaxy S25+에서 EIS·YUV·오디오 조합의 relay 버퍼가 인코더에서 거부되는 것을 확인했기 때문입니다. 이 경로와 Android 12 이하에서는 녹화 버퍼 도착 시각을 관측하지 않으며 값을 추정하지 않습니다. MediaRecorder 오류는 녹화 종료 절차로 전달하고, 이미 해제한 recorder에서 온 콜백은 무시합니다.

LiveVideo를 지정하면 카메라 크기·고정 FPS 범위와 인코더의 surface 입력·크기·프레임률·비트레이트 지원을 대조한 조합을 사용합니다. H264 또는 HEVC, 24·25·30·60fps 후보 중 지원되는 것을 선택하며 크기를 자동 대체하지 않습니다. LiveSessionCheck는 출력 조합을 조회하고 조회 미지원은 unknown으로 기록합니다. 녹화 준비 실패는 기존 프리뷰를 유지하고, 녹화 구성 실패는 자동 재시도하지 않고 알립니다. 정지 후에는 VIDEO 구성을 다시 준비합니다. 요청 설정과 preflight 결과 및 녹화 시작 설정은 별도 이벤트에 남습니다.

LiveRecorderState가 준비·대기·시작·녹화·종료 상태를 구분합니다. busy, prepared, recording은 이 상태에서 유도합니다. 준비 완료 대기 중에도 인코더 오류가 발생하면 세션을 닫고 자원을 해제한 뒤 프리뷰를 복구합니다. 종료 상태는 MediaRecorder.stop 호출 필요 여부를 유지합니다.
