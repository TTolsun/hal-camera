시간 제한과 관측 창을 한자리에 모으면 다음과 같습니다.

| 대상 | 값 |
| --- | --- |
| open, configure, first frame, close | 각 3000 ms |
| 촬영 | 5000 ms |
| 관측 창(Auto Check) | 고정 10,000 ms |
| 엔드포인트당 촬영 횟수(Auto Check) | 3회 |
| 실행당 최대 엔드포인트 | 4개 |
| Camera2 내부 촬영 watchdog | 5000 ms |
| incident 이전 구간 / 이후 구간 | 10초 / 5초 |
| recorder 보존(기본 / CheckActivity) | 30초 / 120초 |
| recorder 이벤트 상한(기본 / CheckActivity) | 18,000개 / 60,000개 |
| 라이브 건강 창 / warning 유지 | 1.5초 / 3초 |
| 벤치마크 프로파일 warm-up / 관측 | 3000 ms / 10,000 ms |

`AutoCheckRunnerTest`가 시간 초과 경로를 포함한 단계 기계의 실행 가능한 명세입니다.
