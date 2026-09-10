실행 상태는 AutoCheckRunner와 BenchmarkRunner, CameraEngine의 수명주기, FlightRecorder의 incident 창으로 나뉩니다.

AutoCheckRunner는 엔드포인트별 OPEN → CONFIGURE → FIRST_FRAME → OBSERVE → STILL → CLOSE를 구동합니다. 타임아웃은 현재 엔드포인트 실패를 기록하고 다음 대상으로 진행하며 명시적 abort는 전체 실행을 종료합니다.

BenchmarkRunner는 launchIterations 회의 열기·닫기 사이클 뒤 추가 관측 세션에서 WARMUP → OBSERVE → STILL → CLOSE를 실행합니다. 연속 사이클 실패 한도, 관측 세션 실패, 명시적 abort가 조기 종료 조건입니다.

지표 판정과 RunValidity는 카메라 상태 기계와 구분합니다. 전자는 hard failure·unknown·임계값을, 후자는 기록된 flag를 해석합니다.
