BenchmarkRunner는 열기·닫기 반복과 추가 관측 세션을 분리합니다. 반복은 OPEN → CONFIGURE → FIRST_FRAME → CYCLE_CLOSE, 관측 세션은 WARMUP → OBSERVE → STILL → CLOSE로 진행합니다. 연속 사이클 실패 한도, 관측 세션 실패와 명시적 abort가 조기 종료 조건입니다.

카메라 수명주기, FlightRecorder의 incident 창, validity와 회귀 판정은 서로 다른 상태입니다. HistoryActivity는 목록·두 실행 비교·작업 중 상태를 관리하며 삭제 확인 후 파일과 baseline 포인터를 처리합니다. 기존 AutoCheckRunner·ThresholdEngine 설명은 제거된 v0.2 경로의 이력입니다.
