> v0.2 이력: 이 경로는 M3에서 제거되었습니다. 아래 내용은 현재 구현의 설명이 아닙니다.

`app/src/main/java/dev/halcamera/diagnosis/Model.kt` (141 lines). The code contract from `PRODUCT-v0.2.md` 13.2 — every shared enum and data class the evaluation chain passes around.

Its organising idea is three orthogonal axes rather than one verdict. `State` (PASS / WARN / FAIL / UNKNOWN) says how bad the result is, `ThresholdBasis` (HARD, ABSOLUTE_VALIDATED, ABSOLUTE_REFERENCE, RELATIVE, HEURISTIC) says on what authority it was judged, and `ConditionEquivalence` says how close the measurement conditions were to the reference. Separating them is what lets a report distinguish "fails a validated bound" from "fails a house heuristic".

`UnknownReason` is the fourth vocabulary and is used across both layers: NOT_MEASURABLE, NOT_RUN, INSUFFICIENT_SAMPLES, NO_BASELINE, UNSUPPORTED, CADENCE_CHANGED, CONDITION_MISMATCH. An unknown is always explained.

`MetricSample` is the input shape (value, p95, n, hard-failure flag, `expectedMs` carrying the physics bound for cadence metrics), `MetricState` the judged output, `Diagnosis` and `Health` the composed results. `jsonName` lowercases every enum for serialisation, `rank` and `worstOf` define the severity order FAIL > WARN > PASS > UNKNOWN, and `DeviceContext.cddApplicability` encodes that CDD bounds apply only from API 34 and only to a primary camera.
