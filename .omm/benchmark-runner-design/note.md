러너 내부 상태와 화면 단계는 서로 다릅니다. 계획 문서 3.2의 대응 관계입니다.

| 러너 상태 | 화면 |
| --- | --- |
| PREFLIGHT (카메라를 열지 않음) | 카드의 START 가능 여부 |
| LAUNCH_CYCLE x10 | 1 / 6 Camera Open |
| OPEN 11회차 (유지) | 2 / 6 First Preview |
| WARMUP 3초 | |
| OBSERVE 10초 | 3 / 6 Preview Stability |
| (같은 창에서 3A 수렴 계산) | 4 / 6 3A Response |
| STILL x10 | 5 / 6 Still Capture |
| CLOSE | 6 / 6 Camera Close |

preflight 실패 사유 코드는 `PREVIEW_SIZE`, `YUV_SIZE`, `JPEG_SIZE`, `FPS_RANGE`, `STREAM_COMBINATION` 다섯 가지입니다.

이 관점은 `docs/PLAN-BenchMarker-v0.3.md` 3장을 근거로 작성했습니다. 계획 문서가 결정과 근거의 원본이고, 이 관점은 그 결정이 코드 구조와 어디서 맞물리는지를 보여 주는 뷰입니다. 두 곳이 어긋나면 계획 문서가 기준입니다.
