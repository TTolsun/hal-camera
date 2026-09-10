`diagnosis/ThresholdEngine.kt` plus `Model.kt`. Not a timeline but a fixed resolution order, and the order is the semantics.

1. No rule for the id: `UNKNOWN(not_measurable)`.
2. `sample.hardFailure`: `FAIL` with basis `HARD` and the watchdog source, no threshold consulted.
3. An explicit `unknownReason` on the sample wins over any judgement.
4. A null value: `UNKNOWN(not_measurable)`.
5. Otherwise judge absolutely and relatively, and take `worstOf` the two by the rank FAIL > WARN > PASS > UNKNOWN.

When the result is UNKNOWN the engine additionally resolves *which* reason to report, distinguishing "no baseline exists" from "this could not be measured". Both verdicts, both sources, the delta and the deciding basis are kept on the state, so nothing about how the answer was reached is discarded.
