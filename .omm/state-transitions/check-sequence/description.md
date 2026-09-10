AutoCheckRunner는 엔드포인트별 OPEN → CONFIGURE → FIRST_FRAME → OBSERVE(기본 10초) → STILL(기본 3회) → CLOSE를 진행합니다. 개별 실패는 다음 엔드포인트로 이어지고 명시적 abort는 종료합니다.

BenchmarkRunner는 launchIterations 회의 warm reopen과 추가 관측 세션을 분리합니다. 각 사이클은 OPEN → CONFIGURE → FIRST_FRAME → CYCLE_CLOSE이며, 추가 세션은 WARMUP → OBSERVE → STILL → CLOSE입니다. 연속 사이클 실패 한도(기본 3회), 관측 세션 실패, 명시적 abort에서 조기 종료합니다. 근거: check/AutoCheckRunner.kt, benchmark/BenchmarkRunner.kt.
