ConcurrentSession은 HandlerThread에서 독립 CameraDevice와 세션을 관리합니다. 동시 세션 지원을 검사하고 모든 장치를 연 뒤 출력을 구성하며, 모든 장치의 실제 프레임이 도착하면 준비 완료를 알립니다. 종료는 늦은 open 콜백과 모든 onClosed를 기다린 뒤 합성기 자원을 해제하고 ConcurrentLease를 넘깁니다.

ConcurrentDiscovery는 장치와 조합의 조회를 담당합니다. ConcurrentSession은 PIP 입력·위치·합성 사진 경로를 받지 않으며 독립 장치만 관리합니다. 녹화 중 스냅샷도 기존 JPEG 출력을 사용하고 인코더를 유지합니다. 장치별 JPEG는 Image.timestamp와 CaptureResult.SENSOR_TIMESTAMP로 연결하며 ConcurrentPhotoStore가 파일과 묶음 JSON을 저장합니다. 영상은 장치별 전체 프레임의 무음 MP4이며, 패널 배치에서 인코더 크기를 계산하지 않습니다. 오류·사진 제한 시간은 미완료 장치를 실패로 기록하고 종료합니다. 저장 경계는 ConcurrentMediaResult로 성공·실패를 전달하며 UI가 안내 문자열을 분석하지 않습니다.

장치별 줌·AF·AE·EV와 Off·Torch 요청을 적용합니다. Camera2TouchFocus를 장치마다 유지하여 프리뷰의 터치 초점·노출을 처리합니다. ManualControls와 bracket은 요청에서 제거합니다. Telemetry는 장치별 세션 키에 요청·결과·프리뷰·JPEG 이벤트를 기록하고 Callback 출력 설명자를 제공합니다. 화면과 저장의 크기·시각을 동일한 값으로 추정하지 않습니다.
