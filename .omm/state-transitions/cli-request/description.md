`cli/CommandStore.kt`, `cli/CommandCoordinator.kt`와 화면 어댑터 `LiveController`, `CtsController`, `BenchmarkController`가 CLI 요청의 상태를 관리합니다. accepted → preparing → running → saving → succeeded로 진행합니다. 실패는 failed, 취소는 cancelling을 거쳐 cancelled, 프로세스 재시작으로 끝나지 못한 요청은 interrupted로 기록하며, `CliStates.allows`가 허용하는 전이만 `CommandStore.transition`이 받습니다.

`submit`은 기록을 먼저 저장한 뒤 main thread에서 명령을 나눕니다. `cameras`, `streams`, `probe`, `cts.cases`는 화면 없이 처리합니다. `preview`, `preview.stop`, `capture`, `record.start`, `cts.run`, `benchmark.run`은 Live host에 전달합니다. CTS와 벤치마크는 Live 카메라의 close(done) 이후 전용 화면으로 인계합니다. 벤치마크는 고정 Camera2 표준 v2 profile만 허용하며 기본 실행 제한은 600초입니다. 기존 Runner의 중단·종료와 보고서 저장 완료를 CLI 상태에 연결합니다.

녹화 요청은 실제 시작 콜백에서 `result.recording=true`를 기록합니다. `record.stop`은 새 요청을 만들지 않고 현재 CLI 녹화를 멈추므로 BUSY 상태에서도 사용할 수 있습니다. MP4가 MediaStore에 저장된 뒤 artifact를 등록합니다. 무음 녹화는 마이크 권한을 요구하지 않습니다. 녹화 준비는 30초, 전체 실행은 기본 1시간 이내이며 시간 초과는 failed로 기록합니다. 화면 이탈로 종료된 녹화는 RECORDING_INTERRUPTED로 구분합니다.

`complete`는 saving으로 옮긴 뒤 IO 스레드에서 artifact의 크기와 SHA-256을 계산하고 terminal state를 정합니다. 취소 코드가 `EXECUTION_TIMEOUT`이면 failed, 결과가 `cancelled`이거나 실패가 `CANCELLED`면 cancelled, 그 밖에는 succeeded입니다. 이미 saving에 들어간 요청은 취소해도 실제 결과로 끝나며 `cancel_effective`로 늦은 취소를 표시합니다. `release`는 active 요청과 timeout을 정리하고, `CommandStore.cleanup`은 만료·초과 기록을 지우면서 `onExpire`로 그 요청의 artifact 디렉터리도 함께 지웁니다.
