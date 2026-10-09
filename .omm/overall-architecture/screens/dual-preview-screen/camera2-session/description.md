DualPreviewSession은 공개 논리 카메라의 물리 출력 두 개를 구성합니다. DualPreviewPlanner가 공통 크기를 선택하고 세션 구성 거부 시 다음 후보를 시도합니다. Camera2는 physical ID별 OutputConfiguration에 프리뷰와 녹화 surface를 공유하며, 장치 onClosed 이후 surface와 인코더를 해제합니다. 카메라 또는 녹화 오류는 저장 쌍을 무효화합니다.

DualMainControls는 지원하는 물리 요청 키로 메인 센서의 노출·초점·WB를 제어합니다. 줌만 논리 요청에 적용하여 두 출력을 함께 확대합니다. 공유 플래시와 사용자 지정 WB는 제공하지 않습니다.

사진은 프리뷰 세션의 onClosed 뒤 DualStillCapture가 공통 크기의 YUV 출력 두 개를 구성합니다. 하나의 STILL 요청에 두 physical target을 넣으며, DualStillPair는 같은 결과의 논리 또는 물리 타임스탬프와 맞는 두 버퍼가 모두 있어야 완료합니다. MediaLibrary는 공통 식별자와 센서 ID를 가진 JPEG 두 장을 저장하고 한쪽 저장 실패 시 둘 다 제거합니다. 촬영 후 프리뷰를 복구하며, 카메라 오류·화면 종료 후에는 다시 구성하거나 저장하지 않습니다. 최대 사진 해상도와 하드웨어 노출 동기는 보장하지 않습니다.
