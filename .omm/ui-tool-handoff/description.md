Live 상단의 Callback 다음에 Lab 버튼을 표시합니다. Lab은 메뉴 없이 WorkbenchActivity를 열며, Probe·CTS·Benchmark, 실행 기록·갤러리·ZIP 기록과 설정을 모읍니다. ZIP 기록은 공유·다른 위치에 저장·삭제를 지원합니다. ADB CLI 허용과 앱 정보는 Lab에서 표시합니다.

Lab 진입은 openAfterClose를 거칩니다. 진행 중인 incident를 마무리하고 콜백 그래프를 닫은 뒤 closing을 켜고 카메라 세션 종료 중임을 표시합니다. Live 엔진의 close(done) 뒤에 Lab을 열며, Lab은 카메라를 열지 않습니다. 선택한 카메라 ID는 Probe와 Benchmark에, 엔진은 Benchmark에 전달합니다. 녹화·저장·세션 종료·CLI 작업 중에는 Lab을 비활성화합니다.

Lab의 시스템 뒤로 가기와 상단 Live 링크은 기존 Live로 복귀합니다. 프리뷰 일시정지·재개와 카메라 다시 연결은 Activity Result로 Live에 전달합니다. 일시정지는 목표 상태를 적용하므로 화면 재생성 뒤에도 토글의 의미가 뒤집히지 않습니다. Lab 복귀는 Activity Result를 받은 뒤 onResume에서 일시정지 여부에 따라 프리뷰를 한 번만 다시 엽니다. Android 권한 설정에서 돌아오는 경우에도 onResume에서 권한을 다시 확인합니다.

Settings의 Live Streams는 Activity Result로 Live에 설정 열기를 요청합니다. 이 복귀에서는 프리뷰를 먼저 열지 않고 스트림 설정을 표시합니다. 적용은 선택한 구성으로 카메라를 열며, 취소하면 기존 설정으로 프리뷰를 재개합니다. 따라서 실패한 구성을 설정창보다 먼저 다시 시도하지 않습니다.
