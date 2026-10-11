Camera2Engine은 CameraDevice와 CameraCaptureSession 콜백을 카메라 스레드에서 받아 Telemetry로 전달합니다. 캡처 요청별 출력 ID를 보관하여 request_observed와 캡처 시작·결과·실패·버퍼 손실을 실제 구성된 출력에 연결합니다. ImageReader와 프리뷰 버퍼의 관측은 별도 출력 이벤트로 기록합니다.

PIP로 출력 구성이 바뀌면 같은 출력 목록으로 세션, 반복 요청과 콜백 그래프를 갱신합니다. 메인 장치는 Camera2Engine이 유지하고 추가 Service 장치는 LivePipSession이 관리합니다. PIP 지원 조회는 카메라 세션을 구성하거나 프레임을 생성하지 않으며 조회 자체를 캡처 콜백으로 기록하지 않습니다. 메인·Physical 프리뷰와 활성화된 합성 사진의 관측은 PipCallbacks가 전달합니다. 추가 Service 장치의 요청·결과는 메인 콜백 그래프에 합치지 않습니다. 합성 사진 완료는 앱 이벤트이며 카메라 Callback 그래프에 등록하지 않습니다. JPEG Off에서도 PIP 합성 저장은 유지하며 HAL JPEG 요청은 추가하지 않습니다.
