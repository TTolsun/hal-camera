LivePipSession은 Camera2의 보조 Service 장치와 합성 입력을 소유합니다. Physical 출력은 부모 CameraDevice의 세션에 연결하며 Service는 별도 CameraDevice를 엽니다. configurePipSession과 지원 조회는 같은 출력 설명자와 Physical ID를 사용합니다. 부모 출력이 복원된 뒤 보조 장치와 합성 Surface를 해제합니다.

JPEG가 켜져 있으면 메인 촬영 출력과 보조 JPEG 또는 YUV 촬영 출력을 세션에 포함합니다. 보조 Physical 출력은 메인 촬영 요청에 함께 연결하며, Service 출력은 각 장치의 요청으로 촬영합니다. JPEG Off이면 이 보조 촬영 출력을 열지 않고 프리뷰 합성 사진만 저장합니다. Camera2 RAW는 기존 메인 촬영 경로에서 유지합니다.

CameraXPipSession은 두 Service 카메라를 ConcurrentCamera로 bind합니다. 프리뷰와 원본 촬영 use case는 지원 조회에서 선택한 크기로 제한하며 명시한 크기는 대체하지 않습니다. 자동 기본 설정에서는 JPEG 촬영과 함께 사용할 수 있도록 메인 분석 use case를 PIP 동안 생략합니다. 명시한 분석 출력은 유지하며 CameraX의 공통 동시 스트림 제한을 넘으면 사전 검사에서 거부합니다. PIP Off이면 기존 단일 카메라 use case를 복원합니다.

PipMedia는 사진과 녹화 작업을 직렬화하며 종료 전에 완료를 기다립니다. Camera2는 보조 장치 종료와 미디어 완료를 기다리고, CameraX는 두 CameraState.CLOSED와 SurfaceRequest 반환 및 미디어 완료를 기다린 뒤 합성 입력을 해제합니다. 카메라 재열기는 이 완료 콜백 이후에 수행합니다.
PIP Service 메타데이터는 각 카메라의 실제 출력만 등록하며 합성 인코더는 제외합니다. 센서별 프레임 번호와 프리뷰 이벤트는 각 세션에 유지합니다.
