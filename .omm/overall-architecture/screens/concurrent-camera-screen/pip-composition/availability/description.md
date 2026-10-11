PIP 목록은 모든 후보를 유지하고 지원 불가가 확인된 항목만 비활성화합니다. Camera2 Physical 후보는 부모의 물리 카메라 목록에서, Service 후보는 독립 카메라 목록에서 가져옵니다. Service 조합은 기기의 동시 사용 목록에 있어야 하며 CameraX도 자신의 동시 목록을 사용합니다. CameraX Physical 후보는 Camera2 only로 표시합니다. 제조사·칩셋·카메라 ID별 예외는 두지 않습니다.

Camera2PipSupport는 열린 장치의 Physical 세션 또는 두 Service 세션을 현재 출력으로 조회합니다. 메인 프리뷰·YUV·JPEG·RAW와 보조 프리뷰·선택한 원본 촬영 출력을 포함하며 video_snapshot과 합성기 인코더 출력은 제외합니다. 임시 Surface와 ImageReader는 조회 후 해제하고 기존 세션은 바꾸지 않습니다. 조회와 실제 구성은 같은 출력 설명자와 Physical ID를 사용합니다.

자동 보조 크기는 원본의 선택 크기 예산과 프리뷰 기본 예산에서 지원되는 조합을 탐색합니다. 가장 큰 크기의 거부만으로 후보를 제외하지 않습니다. Live Streams에서 지정한 PIP Preview·Photo 크기는 정확히 조회하고 적용하며 다른 크기로 대체하지 않습니다. 설정은 엔진·부모 카메라·Physical/Service ID로 구분합니다. JPEG Off이면 보조 원본 출력은 열지 않습니다.

CameraX는 1.6.2의 공통 동시 PRIVATE/YUV/JPEG 조합 제한과 HAL 조회를 함께 사용합니다. 자동 기본 구성에서는 원본 JPEG와 Preview를 위해 분석 use case를 PIP 동안 생략합니다. 사용자가 명시한 분석 출력은 생략하지 않습니다. 조회 불가 항목은 Not verified로 표시하고 실제 구성을 허용합니다. 선택 전 거부는 기존 실행 중 PIP를 유지합니다.

PhysicalPipPicker는 비동기 검사를 기다린 뒤 LiveChoiceSheet를 갱신합니다. Cannot run together, Unsupported streams, Camera2 only처럼 사유를 짧게 표시하고 스크롤 위치를 유지합니다. 열 때와 적용 직전에 다시 검사하며 실행 중 자원 부족이나 HAL 오류까지 성공을 보장하지 않습니다.