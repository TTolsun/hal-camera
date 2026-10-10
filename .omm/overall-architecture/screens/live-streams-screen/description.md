Live Streams는 Lab 또는 Live의 크기 표시에서 열 수 있으며 카메라를 열지 않습니다. 진입 전에 Live 엔진의 close(done)를 기다립니다. 상단·시스템 뒤로 가기는 초안을 버리고 진입한 화면으로 돌아갑니다. 저장과 직전 정상 구성 복원도 같은 화면으로 돌아갑니다. Lab은 결과를 보관했다가 Live 복귀 시 전달하며, 직접 진입은 결과를 Live에 바로 전달합니다. 설정과 직전 정상 구성은 카메라·엔진별로 분리하고 Activity 재생성 시 복원합니다.

LiveStreamSettingsView는 LabTheme·Look.titleBar·페이지 여백을 사용하는 전체 페이지입니다. 영어 제목과 간결한 한국어 안내, 둥근 회색 그룹, 파란 선택값을 사용합니다. 해상도는 픽셀 수 내림차순이며 녹화는 Format → Resolution → Frame Rate 순서로 포맷별 지원 후보를 제공합니다. 초기 표시값과 녹화기는 기본 크기 선택 함수를 공유합니다. 지원 정보 조회는 작업 스레드에서 수행하고 화면이 닫힌 뒤의 결과는 버립니다.

JPEG 목록 첫 항목은 YUV (현재 크기)이며 YUV 스트림이 켜져 있을 때 제공합니다. 이를 선택하면 HAL JPEG 출력을 끄고 앱에서 JPEG를 만듭니다. 현재 크기 아래에 App converts YUV to JPEG. 한 줄을 표시합니다. 다른 JPEG 해상도를 고르면 HAL JPEG를 저장합니다. YUV 크기만 켜는 것은 분석 스트림 설정이며 사진 저장을 뜻하지 않습니다.

YUV Save Format과 NV21 파일 저장은 제거했습니다. YUV를 끄면 이를 사용하던 JPEG 선택도 Off로 바뀝니다. Apply 전에는 초안을 유지하고 뒤로 가면 버립니다.

RAW (DNG)는 RAW capability가 있는 Camera2 카메라에서만 RAW_SENSOR 크기와 Off를 제공하며 기본값은 Off입니다. 선택하면 사진당 예상 크기를 안내하고, 지원하지 않으면 선택을 비활성화하고 이유를 표시합니다.

Dual에서 진입하면 현재 엔진·물리 ID·프리뷰 크기와 녹화 구성을 읽기 전용으로 표시합니다. Dual이 적용하지 않는 단일 카메라 설정과 Apply는 표시하지 않습니다.

Multi의 Maximum camera devices는 All available 또는 공개 camera ID 개수 이내의 2 이상 상한을 선택합니다. Apply에서 별도 환경설정에 저장하며 뒤로 가기는 초안을 버립니다. Multi 화면의 Streams에서 열면 이 설정만 표시하고, 장치를 모두 닫은 뒤 진입합니다. 복귀 시 저장된 상한으로 조합을 다시 조회합니다.

VIDEO에서는 RAW (DNG)를 비활성화하고, PHOTO에서는 Stabilization을 Off로 고정하여 선택을 비활성화합니다. Live와 Lab 진입 경로 모두 현재 모드를 전달합니다.

LiveModePolicy가 PHOTO의 Stabilization 제한과 VIDEO의 RAW/DNG 제한을 정의합니다. 설정 화면의 선택 가능 여부와 엔진·PIP의 적용값은 이 공통 정책을 사용합니다.
