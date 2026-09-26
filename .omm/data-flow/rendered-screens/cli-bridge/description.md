일반 Live 조작은 MainActivity가 선택한 CameraEngine에 전달합니다. LiveController는 CLI 요청을 MainActivity의 실제 카메라 동작에 연결하는 어댑터이며 일반 셔터 경로의 필수 중간 계층은 아닙니다.

BenchmarkController는 CLI의 `benchmark.run` 요청을 화면의 기존 실행 경로에 연결하고, 실행 JSON이 저장된 뒤에 요청을 완료합니다. 다른 명령은 Live를 열라는 오류로 돌려보냅니다. 두 어댑터는 화면이 그리는 결과를 바꾸지 않습니다.
