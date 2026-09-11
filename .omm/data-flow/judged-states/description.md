> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

Stage 4a, the v0.2 branch. Three transforms turn samples into something a screen can state.

`ThresholdEngine` produces one `MetricState` per sample, judging absolutely against `ThresholdTable` and relatively against the device baseline, then taking the worse of the two — while keeping both verdicts, both sources and the delta, so the report can say which one decided.

`DiagnosisRules` collapses the whole state list into one rule id by fixed priority, keeping the losing matches as secondaries with their evidence.

`HealthComposer` folds the states into a level (NORMAL / WARNING / ISSUE / INSUFFICIENT), a weight-based coverage fraction and an optional score that is suppressed without a baseline, below 70 % coverage, or by the `scoreEnabled` flag — which is false.

This branch is the only one that currently reaches disk. The information it adds beyond the numbers is *interpretation*, and the v0.3 pivot removes exactly that: the benchmark branch stops at statistics and defers comparison to a detector.
