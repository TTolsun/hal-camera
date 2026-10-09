일반 Live 조작은 MainActivity가 선택한 CameraEngine에 전달합니다. LiveController는 CLI 요청을 MainActivity의 실제 카메라 동작에 연결하는 어댑터이며 일반 셔터 경로의 필수 중간 계층은 아닙니다.

LiveController는 요청 엔진과 스트림 옵션의 지원 검사가 끝난 뒤 새 프리뷰를 준비하고, 준비 완료 후에만 촬영·녹화를 시작합니다. CtsController는 기존 CTS 화면의 보고서 저장 완료를 CLI 결과에 연결합니다. benchmark.run은 Live 카메라 종료 후 BenchmarkActivity로 인계하며 BenchmarkController가 고정 Camera2 표준 v2 측정과 JSON 저장 완료를 CLI 결과에 연결합니다. streams는 화면 없이 지원 후보를 조회합니다.

`CliSequence`는 연사·AEB의 저장 장수와 HDR 상태를 요청 기록으로 보냅니다. MainActivity는 이를 셔터 옆 한 줄로 표시합니다. 기본 셸 도움말은 다섯 작업으로 시작하고 상세 명령은 `help all`, 수동 제어는 `help controls`에서 확인합니다. 실패 시 상태 확인 또는 파일 회수의 다음 명령을 제시합니다.

Python 클라이언트의 기본 출력도 상태·저장 위치·다음 명령을 제시합니다. 상태나 저장 장수가 바뀔 때만 진행을 알리며 `--json`은 전체 응답을 유지합니다. 제출 전 거부에는 새 요청 ID를 표시하지 않고, 제출 후 통신·대기 실패에는 요청 ID로 상태를 확인하도록 안내합니다. `--no-audio`는 녹화 요청의 audio=false로 전달합니다.

doctor는 카메라 권한·잠금·실행 중 작업을 검사하여 연결 성공과 준비 완료를 구분합니다. CLI 비활성·기록 만료는 같은 실패 조회를 반복시키지 않고 설정 변경이나 저장 파일 목록으로 안내합니다. 셸 status는 종료된 실패·취소에서도 회수 가능한 파일 수와 fetch 명령을 안내합니다.

RAW·NV21·FPS·보정 모드는 `CliStreams`가 기존 `LiveStreamSupport`로 검증합니다. 수동 제어는 `CliOptions`가 기존 제어·수동 지원 규칙으로 검증하며 지원하지 않는 값을 조용히 제한하지 않습니다. Dual 화면은 같은 coordinator에 붙고 원래 저장 URI를 결과로 보냅니다. 결과·파일 관리 작업은 `CliLibrary`에서 기존 비교·baseline·MediaStore·ZIP 저장소에 연결됩니다.
