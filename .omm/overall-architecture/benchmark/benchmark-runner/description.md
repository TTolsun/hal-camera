benchmark/BenchmarkRunner.kt의 순수 상태 기계입니다. Driver와 Scheduler를 주입받아 warm reopen 반복, 별도 관측 세션, 연속 촬영 및 종료를 진행합니다. 사이클·촬영 표본과 관측 범위를 Result에 담습니다.
