---
title: 설계 결정 기록
---
# 설계 결정 기록

**현재 구현과 설계 의도를 구분할 때 결정 번호를 확인하세요.** 이 기록에 없는 선택 이유는 추정하지 않고 `확인 필요`로 남깁니다.

각 결정은 `## D-번호 제목` 형식으로 기록합니다. 원고가 D-번호를 인용하면 생성기가 해당 번호의 존재를 확인합니다. 아래 날짜와 상태는 기존 결정 기록을 그대로 유지했습니다.

## D-001 Camera2 엔진과 CameraX 엔진을 모두 유지합니다

| 항목 | 기록 |
| --- | --- |
| 결정일 | 2026-09-08 |
| 상태 | 확정 |

**선택한 방식:** Camera2를 측정 경로로, CameraX를 비교 경로로 사용합니다.

카메라를 점유하는 `CameraEngine`은 항상 하나여야 합니다. 두 엔진을 유지하더라도 이 제약은 그대로 적용합니다.

배경, 검토한 대안, 선택한 이유는 아직 기록되지 않았습니다. 결정에 참여한 사람이 이 세 항목을 보완해야 합니다.

## D-002 v0.2 건강 판정 제품을 중단하고 v0.3 Camera BenchMarker로 전환합니다

| 항목 | 기록 |
| --- | --- |
| 결정일 | 2026-09-09 |
| 상태 | 확정 |

**선택한 방식:** 건강 판정인 PASS / WARN / FAIL에서 측정·비교 계약인 IMPROVED / STABLE / REGRESSED / UNKNOWN으로 전환합니다. Score는 M5로 미룹니다.

결정 당시 계획은 M3까지 `diagnosis/`와 `benchmark/`를 함께 유지하는 것입니다. 상세 계획은 [Camera BenchMarker v0.3 계획](https://github.com/TTolsun/hal-camera/blob/main/docs/PLAN-BenchMarker-v0.3.md)에 있습니다.

배경, 검토한 대안, 전환 이유는 아직 기록되지 않았습니다.

**이후 진행:** M3(PR #35)에서 `check/`, `diagnosis/`, `home/`, `report/`, `baseline/` 패키지를 삭제했습니다. 현재 코드에는 `benchmark/` 측정·비교 경로만 남아 있으며, 계산 로직 중 `MetricExtractor`와 `UnknownReason`은 `metrics/`로 옮겨졌습니다.

**다음 단계:** [아키텍처 문서의 앱의 역할과 평가 경로](../architecture.md#앱의-역할과-평가-경로)를 읽으세요.
