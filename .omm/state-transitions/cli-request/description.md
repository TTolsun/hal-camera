`cli/CommandStore.kt`, `cli/CommandCoordinator.kt`와 화면 어댑터 `LiveController`, `CtsController`, `BenchmarkController`가 CLI 요청의 상태를 관리합니다. accepted → preparing → running → saving → succeeded로 진행합니다. 실패는 failed, 취소는 cancelling을 거쳐 cancelled, 프로세스 재시작으로 끝나지 못한 요청은 interrupted로 기록하며, `CliStates.allows`가 허용하는 전이만 `CommandStore.transition`이 받습니다.

`submit`은 기록을 먼저 저장한 뒤 main thread에서 명령을 나눕니다. `cameras`, `streams`, `probe`, `cts.cases`는 화면 없이 처리합니다. `preview`, `preview.stop`, `capture`, `record.start`, `cts.run`, `benchmark.run`은 Live host에 전달합니다. CTS와 벤치마크는 Live 카메라의 close(done) 이후 전용 화면으로 인계합니다. 벤치마크는 고정 Camera2 표준 v2 profile만 허용하며 기본 실행 제한은 600초입니다. 기존 Runner의 중단·종료와 보고서 저장 완료를 CLI 상태에 연결합니다.

녹화 요청은 실제 시작 콜백에서 `result.recording=true`를 기록합니다. `record.stop`은 새 요청을 만들지 않고 현재 CLI 녹화를 멈추므로 BUSY 상태에서도 사용할 수 있습니다. MP4가 MediaStore에 저장된 뒤 artifact를 등록합니다. 무음 녹화는 마이크 권한을 요구하지 않습니다. 녹화 준비는 30초, 전체 실행은 기본 1시간 이내이며 시간 초과는 failed로 기록합니다. 화면 이탈로 종료된 녹화는 RECORDING_INTERRUPTED로 구분합니다.

`complete`는 saving으로 옮긴 뒤 IO 스레드에서 artifact의 크기와 SHA-256을 계산하고 terminal state를 정합니다. 취소 코드가 `EXECUTION_TIMEOUT`이면 failed, 결과가 `cancelled`이거나 실패가 `CANCELLED`면 cancelled, 그 밖에는 succeeded입니다. 이미 saving에 들어간 요청은 취소해도 실제 결과로 끝나며 `cancel_effective`로 늦은 취소를 표시합니다. `release`는 active 요청과 timeout을 정리하고, `CommandStore.cleanup`은 만료·초과 기록을 지우면서 `onExpire`로 그 요청의 artifact 디렉터리도 함께 지웁니다.

ADB CLI는 저장된 설정이 없으면 허용합니다. 사용자가 명시적으로 끈 설정은 업데이트 후에도 유지하며, 비활성화하면 실행 중인 요청을 취소합니다.

전체 CLI 확장에서는 `CliOptions`가 명령별 입력을 검증하고 `CliSequence`가 연사·AEB를 `BurstRun`과 기존 저장 경로로 실행합니다. `cancel`은 다음 촬영을 막고 완료된 파일을 등록합니다. HDR은 세 JPEG 원본이 있을 때 수행하며 실패·생략 이유를 결과에 남깁니다. `record.snapshot`은 원래 녹화 요청에 사진 파일을 추가하고 사진 저장 중 정지는 저장 콜백 이후 수행합니다.

`DualPreviewActivity`는 별도 CLI host이며 Live의 close(done) 이후 요청을 넘겨받습니다. 명시한 물리 ID를 검증하고 두 프리뷰의 첫 업데이트를 확인합니다. 사진·영상 저장 URI를 파일 목록으로 전달하며 종료 시 영상 저장을 기다립니다.

`CliLibrary`는 결과·baseline·갤러리·진단 ZIP·보관 한도를 처리합니다. 삭제는 대상 ID와 명시적 확인을 요구하며 보관 한도는 먼저 영향 목록을 반환합니다. 기준 실행은 `BaselineManager`로 명시적으로 추가합니다. 작업이 실행 중이면 저장 결과가 확정될 때까지 소유권을 유지합니다. `live.set`과 `live.reset`은 현재 Live 또는 CLI 녹화의 제어를 바꾸는 별도 제어 명령이며 새 저장 요청을 만들지 않습니다.
