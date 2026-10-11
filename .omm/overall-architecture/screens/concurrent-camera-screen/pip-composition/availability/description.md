PIP 선택창은 모든 후보를 유지하고 지원 불가가 확인된 항목만 비활성화합니다. Camera2의 Physical 후보는 부모의 물리 카메라 목록에서, Service 후보는 독립 카메라 목록에서 가져옵니다. Service 조합은 기기가 제공하는 동시 사용 목록에 있어야 합니다. CameraX는 자신의 동시 사용 목록으로 선택 가능한 Service 후보를 제한하며 Physical 후보는 Camera2 only로 표시합니다. 제조사·칩셋·카메라 ID에 따른 예외는 두지 않습니다.

Camera2PipSupport는 카메라 스레드에서 후보의 최신 조건과 출력 지원을 검사하고 확인된 보조 프리뷰 크기를 보관합니다. Physical은 열린 CameraDevice의 isSessionConfigurationSupported, Service는 CameraManager의 isConcurrentSessionConfigurationSupported를 사용합니다. PIP에 유지되는 YUV·RAW 출력을 포함하고 video_snapshot 출력은 제외합니다. 임시 프리뷰 Surface만 생성하며 기존 세션을 바꾸지 않습니다. pipOutputConfigurations는 실제 PIP 구성과 사전 검사의 Physical ID 지정 규칙을 공유합니다.

CameraX도 두 장치의 출력 구성을 함께 조회합니다. 현재 분석 크기를 포함하며 720p 이하의 프리뷰 크기 중 지원되는 조합을 실제 Preview에도 적용합니다. CameraX 1.6.2의 동시 분석 크기 공통 상한은 1920×1440입니다. 가장 큰 보조 프리뷰가 거부되어도 작은 지원 크기가 있으면 선택할 수 있습니다. 조회 불가를 지원 불가로 단정하지 않으며 Not verified로 표시하고 실제 구성을 허용합니다. 선택 직전 거부는 PipSelectionRejected로 전달하여 기존 PIP를 유지합니다.

PhysicalPipPicker는 엔진의 비동기 검사가 끝날 때까지 후보 선택을 잠시 막고 LiveChoiceSheet의 항목을 갱신합니다. 사유는 Cannot run together, Unsupported streams, Camera2 only처럼 짧게 표시합니다. 엔진·부모 ID별 스크롤 위치와 Physical·Service 구분을 유지합니다. 설정창을 열 때와 선택 직전에 다시 검사하며, 실행 중 자원 부족이나 HAL 오류까지 성공을 보장하지 않습니다.
