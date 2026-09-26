DiagnosticsPanel은 Live 진단 패널의 뷰를 만들고 배치합니다. 머리글에는 제목, 녹화 중에만 보이는 경과 시간과 정지 버튼, 앱 정보와 닫기 아이콘이 있습니다. 그 아래에 MainActivity가 만든 카메라 선택 버튼과 일시정지 버튼을 놓고, 프레임 간격 스트립, 펼쳐 보는 프레임·3A 수치, 3A oscilloscope, 프레임 콜백 타임라인, 앱 CPU·PSS·Thermal 줄, 펼쳐 보는 ADB CLI 허용 스위치, incident ZIP 공유·기록 목록·권한 재시도·측정 안내 버튼을 차례로 둡니다.

이 클래스는 배치만 맡고 동작은 갖지 않습니다. 버튼이 하는 일은 DiagnosticsPanel.Actions로 MainActivity에 넘기며, 카메라·FlightRecorder·CLI는 MainActivity가 소유합니다. MainActivity는 패널이 돌려준 뷰에 매 틱 측정값을 씁니다. 패널 높이를 작업 행 위 가용 높이의 60%로 맞추는 레이아웃 리스너와 시스템 바 여백 처리도 MainActivity에 남아 있습니다.

CameraWidgets는 Live 화면과 이 패널이 함께 쓰는 라벨·버튼·외곽선 카드 생성기입니다. 이 생성기로 만든 버튼은 ADB CLI 요청이 카메라를 제어하는 동안 탭을 무시합니다. 두 파일은 MainActivity가 요소 스캔 입력 한도(6만 자)에 닿지 않도록 패널 구성을 옮기면서 생겼습니다.
