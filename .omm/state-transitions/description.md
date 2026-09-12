BenchmarkRunner는 열기·닫기 반복과 추가 관측 세션을 분리합니다. 반복은 OPEN → CONFIGURE → FIRST_FRAME → CYCLE_CLOSE, 관측 세션은 WARMUP → OBSERVE → STILL → CLOSE로 진행합니다. 연속 사이클 실패 한도, 관측 세션 실패와 명시적 abort가 조기 종료 조건입니다.

카메라 수명주기, FlightRecorder의 incident 창, validity와 회귀 판정은 서로 다른 상태입니다. HistoryActivity는 목록·두 실행 비교·작업 중 상태를 관리하며 삭제 확인 후 파일과 baseline 포인터를 처리합니다.

LIVE의 측정 상세는 뒤로 가기로 닫힙니다. 카메라 관측과 녹화 중에는 화면을 유지하고 일시정지·비활성 상태에서는 화면 유지 플래그를 해제합니다. 녹화 세션이 닫히면 MediaRecorder를 종료·저장하고 활성 카메라는 사진용 프리뷰 세션으로 복귀합니다.

API 버튼을 누르면 Camera2와 CameraX 사이를 전환하며 기존 close 완료 후 선택한 엔진을 엽니다. 카메라 목록 열기와 모드 선택만으로 촬영하지 않습니다. 녹화 중에는 API·카메라·줌·모드 변경을 막습니다. 썸네일 조회는 onStart에서 시작하고 onStop에서 관찰과 지연 갱신을 중단하며 이전 조회의 결과를 무효화합니다.
