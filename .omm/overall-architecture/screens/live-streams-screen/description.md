Live Streams는 Lab 또는 Live의 크기 표시에서 열 수 있으며 카메라를 열지 않습니다. 진입 전에 Live 엔진의 close(done)를 기다립니다. 상단·시스템 뒤로 가기는 초안을 버리고 진입한 화면으로 돌아갑니다. 저장과 직전 정상 구성 복원도 같은 화면으로 돌아갑니다. Lab은 결과를 보관했다가 Live 복귀 시 전달하며, 직접 진입은 결과를 Live에 바로 전달합니다. 설정과 직전 정상 구성은 카메라·엔진별로 분리하고 Activity 재생성 시 복원합니다.

LiveStreamSettingsView는 LabTheme·Look.titleBar·페이지 여백을 사용하는 전체 페이지입니다. 영어 제목과 간결한 한국어 안내, 둥근 회색 그룹, 파란 선택값을 사용합니다. 해상도는 픽셀 수 내림차순이며 녹화는 Format → Resolution → Frame Rate 순서로 포맷별 지원 후보를 제공합니다. 초기 표시값과 녹화기는 기본 크기 선택 함수를 공유합니다. 지원 정보 조회는 작업 스레드에서 수행하고 화면이 닫힌 뒤의 결과는 버립니다.

Outputs의 YUV 원본 추가 저장은 Android 10 이상 Camera2에서 NV21과 메타데이터 ZIP을 추가합니다. 기본값은 꺼짐이며 YUV 출력과 16 MiB 이하 짝수 크기를 요구합니다. CameraX에서는 선택을 비활성화하고 Camera2로 전환하라는 안내를 표시합니다. 저장 경로는 Download/HALCamera이며 사진 모드에만 적용합니다.
