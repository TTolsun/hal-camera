Live 상단의 Callback 다음에 Lab 버튼을 표시합니다. Lab은 메뉴 없이 WorkbenchActivity를 열며, Probe·CTS·Benchmark, 실행 기록·갤러리·ZIP 기록과 설정을 모읍니다. ZIP 기록은 공유·다른 위치에 저장·삭제를 지원합니다. ADB CLI 허용과 앱 정보는 Lab에서 표시합니다.

Lab 진입은 openAfterClose를 거칩니다. 진행 중인 incident를 마무리하고 콜백 그래프를 닫은 뒤 closing을 켜고 카메라 세션 종료 중임을 표시합니다. Live 엔진의 close(done) 뒤에 Lab을 열며, Lab은 카메라를 열지 않습니다. 선택한 카메라 ID는 Probe와 Benchmark에, 엔진은 Benchmark에 전달합니다. 녹화·저장·세션 종료·CLI 작업 중에는 Lab을 비활성화합니다.

Lab의 시스템 뒤로 가기와 상단 Live 링크은 기존 Live로 복귀합니다. 프리뷰 일시정지·재개와 카메라 다시 연결은 Activity Result로 Live에 전달합니다. 일시정지는 목표 상태를 적용하므로 화면 재생성 뒤에도 토글의 의미가 뒤집히지 않습니다. Lab 복귀는 Activity Result를 받은 뒤 onResume에서 일시정지 여부에 따라 프리뷰를 한 번만 다시 엽니다. Android 권한 설정에서 돌아오는 경우에도 onResume에서 권한을 다시 확인합니다.

Live Streams는 Lab 또는 Live의 크기 표시에서 열 수 있으며 카메라를 열지 않습니다. 진입 전에 Live 엔진의 close(done)를 기다립니다. 상단·시스템 뒤로 가기는 초안을 버리고 진입한 화면으로 돌아갑니다. 저장과 직전 정상 구성 복원도 같은 화면으로 돌아갑니다. Lab은 결과를 보관했다가 Live 복귀 시 전달하며, 직접 진입은 결과를 Live에 바로 전달합니다. 설정과 직전 정상 구성은 카메라·엔진별로 분리하고 Activity 재생성 시 복원합니다.

Multi도 Live의 close(done) 뒤 ConcurrentCameraActivity를 엽니다. CameraX에서는 Camera2 전용 경로임을 먼저 안내합니다. 이 화면은 모든 카메라의 onClosed를 기다린 뒤 Live로 돌아가며, Live는 기존 엔진 선택으로 프리뷰를 다시 엽니다.
