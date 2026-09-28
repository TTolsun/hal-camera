Live Streams는 Lab 위에 LiveStreamsActivity를 열며 카메라를 열지 않습니다. 상단·시스템 뒤로가기는 변경을 버리고 Lab으로 돌아갑니다. 저장과 직전 정상 구성 복원도 Lab으로 돌아가며, Lab은 설정 결과를 보관했다가 Live로 복귀할 때 전달합니다. MainActivity는 이 결과를 반영한 뒤 프리뷰를 재개합니다. 화면 재생성 때에도 편집 중 초안과 저장한 결과를 각각 보존합니다.

LiveStreamSettingsView는 LabTheme·Look.titleBar·페이지 여백을 사용하는 전체 페이지입니다. 영어 제목과 간결한 한국어 안내, 둥근 회색 그룹, 파란 선택값을 사용합니다. 해상도는 픽셀 수 내림차순이며 녹화는 Format → Resolution → Frame Rate 순서로 포맷별 지원 후보를 제공합니다. 초기 표시값과 녹화기는 기본 크기 선택 함수를 공유합니다. 지원 정보 조회는 작업 스레드에서 수행하고 화면이 닫힌 뒤의 결과는 버립니다.
