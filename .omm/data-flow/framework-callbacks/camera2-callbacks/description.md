Camera2Engine은 CameraDevice와 CameraCaptureSession 콜백을 카메라 스레드에서 받아 Telemetry로 전달합니다. 캡처 요청별 출력 ID를 보관하여 request_observed와 캡처 시작·결과·실패·버퍼 손실을 실제 구성된 출력에 연결합니다. ImageReader와 프리뷰 버퍼의 관측은 별도 출력 이벤트로 기록합니다.

PIP는 실제 출력 목록으로 세션과 콜백 그래프를 갱신합니다. 메인·Physical 요청은 Camera2Engine, 추가 Service는 LivePipSession이 관리합니다. 지원 조회는 캡처 이벤트를 만들지 않습니다. Service의 요청·결과는 ID 전환으로 구분합니다. 합성 완료는 앱 이벤트로만 기록합니다. JPEG Off에서도 합성 저장은 유지하며 HAL JPEG 요청은 보내지 않습니다.
