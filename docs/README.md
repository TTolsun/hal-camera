# 문서 찾기

**앱을 사용하려면 [사용자 가이드](https://ttolsun.github.io/hal-camera/)부터 여세요.** 이 폴더에는 배포 사이트와 개발·검증 기록이 함께 있습니다.

| 필요한 내용 | 원본 |
| --- | --- |
| 현재 조작과 결과 해석 | [guide/](../guide/index.md)의 Live·Callback·Benchmark 등입니다. `docs/*.html`은 여기서 생성합니다. |
| 지표와 판정 규칙 | [METRICS.md](METRICS.md)와 [SCORING.md](SCORING.md)를 확인합니다. |
| 실제로 확인한 기기·조건·한계 | [Validation](../guide/evidence.md)에서 `validation/`의 개별 기록으로 이동합니다. |
| 버전별 변경·설계 이력 | `releases/`, `design/`, `archive/`, `PLAN-*.md`, [STATUS.md](STATUS.md)에 당시 상태를 보존합니다. 현재 사용법과 구분합니다. |

문서를 고칠 때에는 `guide/*.md`의 일반 본문 또는 `guide/_content/` 원고를 수정합니다. 마커 블록과 HTML은 직접 고치지 않습니다. 생성·검사 절차는 [문서 파이프라인](../tools/docgen/README.md)에 있습니다.
