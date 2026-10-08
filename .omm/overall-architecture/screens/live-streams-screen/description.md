Live Streams는 Lab 또는 Live의 크기 표시에서 열 수 있으며 카메라를 열지 않습니다. 진입 전에 Live 엔진의 close(done)를 기다립니다. 상단·시스템 뒤로 가기는 초안을 버리고 진입한 화면으로 돌아갑니다. 저장과 직전 정상 구성 복원도 같은 화면으로 돌아갑니다. Lab은 결과를 보관했다가 Live 복귀 시 전달하며, 직접 진입은 결과를 Live에 바로 전달합니다. 설정과 직전 정상 구성은 카메라·엔진별로 분리하고 Activity 재생성 시 복원합니다.

LiveStreamSettingsView는 LabTheme·Look.titleBar·페이지 여백을 사용하는 전체 페이지입니다. 영어 제목과 간결한 한국어 안내, 둥근 회색 그룹, 파란 선택값을 사용합니다. 해상도는 픽셀 수 내림차순이며 녹화는 Format → Resolution → Frame Rate 순서로 포맷별 지원 후보를 제공합니다. 초기 표시값과 녹화기는 기본 크기 선택 함수를 공유합니다. 지원 정보 조회는 작업 스레드에서 수행하고 화면이 닫힌 뒤의 결과는 버립니다.

YUV Save Format에서 JPEG 또는 NV21을 선택합니다. JPEG는 기존 `_YUV.jpg`를, NV21은 `_YUV.nv21`을 저장하며 두 파일을 함께 만들지 않습니다. 사진 모드는 선택한 포맷과 관계없이 `_metadata.json`을 함께 저장합니다. JPEG는 DCIM/HALCamera에, NV21과 JSON은 Download/HALCamera에 있습니다. NV21은 Camera2에서만 지원하며 짝수 크기와 프레임당 16 MiB 제한이 있습니다.

YUV Save Format의 기본값은 JPEG입니다. YUV 출력이 꺼져 있으면 포맷 선택을 비활성화하고, CameraX에서는 JPEG만 제공합니다. 상시 Metadata 안내는 표시하지 않습니다. 화면 하단의 Apply 버튼은 스크롤과 독립적으로 유지하며, 변경된 설정이나 실패한 구성의 재적용이 있을 때 활성화합니다. 뒤로 가기는 초안을 적용하지 않습니다.

RAW (DNG)는 RAW capability가 있는 Camera2 카메라에서만 RAW_SENSOR 크기와 Off를 제공하며 기본값은 Off입니다. 선택하면 사진당 예상 크기를 안내하고, 지원하지 않으면 선택을 비활성화하고 이유를 표시합니다.
