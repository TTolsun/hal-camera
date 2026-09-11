> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

`app/src/main/java/dev/halcamera/diagnosis/ThresholdEngine.kt` (133 lines). Turns each `MetricSample` into a `MetricState` by judging it twice and keeping both answers.

The order is fixed. A hard failure short-circuits everything: FAIL with basis HARD and the watchdog source, no threshold consulted (`PRODUCT-v0.2.md` 5.6). Otherwise an explicit unknown reason on the sample wins, then a missing value becomes `NOT_MEASURABLE`. Only then does it judge the value absolutely against the rule and relatively against the baseline, and the final state is `worstOf` the two.

What makes the result auditable is that the losing verdict is not discarded. The state keeps `absolute` and `relative` separately, the bound and its source, the baseline value and `deltaPct`, and `thresholdBasis` naming which of the two produced the final answer. When the final answer is UNKNOWN it also resolves *which* unknown reason to report, distinguishing "there is no baseline" from "the metric could not be measured".

CDD gating lives here too: `cddApplicability` and `conditionEquivalence` are attached only for rules whose absolute is a `Cdd`, so a report can show that a CDD-derived bound was or was not applicable on this device.
