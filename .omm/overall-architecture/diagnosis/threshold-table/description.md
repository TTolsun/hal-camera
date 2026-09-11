> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

`app/src/main/java/dev/halcamera/diagnosis/ThresholdTable.kt` (94 lines), version `0.2-draft`. The single place every absolute bound, relative tolerance and metric weight lives, per `PRODUCT-v0.2.md` chapter 6.

Absolute rules are a sealed hierarchy, one shape per kind of question: `None` (only hard failures apply), `Bounds` (fixed warn/fail with a named source and basis), `Cdd` (a CDD reference value plus separate heuristic bounds), `Cadence` (compare against the sample's own expected interval times a tolerance), `Count` (0 passes, then warn and fail counts) and `ConvergeWarnOnly` (3A never reaches FAIL in v0.2). Relative rules are a warn/fail percentage over the device baseline with a 10 ms global noise floor, so a 30 % change of 3 ms raises nothing.

The `Cdd` shape encodes a lesson from real measurement, recorded in its own comment: the Galaxy S25+ does not declare a media performance class and sits at 550 to 620 ms preview start in every run, so judging it against the bare CDD value produced a permanent WARN that told the user nothing. On devices where the CDD does not apply, the product bounds are 1.5x and 2x the CDD value instead.

Every bound carries a source string (`cdd_2.2.7.2_H-1-6`, `physics`, `watchdog_v0.2`, `product_stability_v0.2`) which is written into the report, so a reader can tell a standard from a house rule.
