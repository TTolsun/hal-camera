일반 Live 조작은 MainActivity가 선택한 CameraEngine에 전달합니다. LiveController는 CLI 요청을 MainActivity의 실제 카메라 동작에 연결하는 어댑터이며 일반 셔터 경로의 필수 중간 계층은 아닙니다.

CLI는 benchmark.run을 접수하지 않습니다. LiveController는 요청 엔진과 스트림 옵션의 지원 검사가 끝난 뒤 새 프리뷰를 준비하고, 준비 완료 후에만 촬영·녹화를 시작합니다. CtsController는 기존 CTS 화면의 보고서 저장 완료를 CLI 결과에 연결합니다. streams는 화면 없이 지원 후보를 조회합니다.
