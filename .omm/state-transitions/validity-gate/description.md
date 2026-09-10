`benchmark/RunValidity.kt`. A cascade over the finished run, evaluated once by `RunValidityEvaluator` and then re-derived every time the run is read back.

`RunValidityEvaluator.flags` collects flags from the run's facts — aborted, hard failure, preflight result, sample counts against what the profile promised, cadence, thermal peak, power save, charging, battery, draft profile, missing subject label. `RunValidity.from` then folds them: measurement valid when no flag blocks measurement, comparison eligible only if measurement is valid *and* nothing blocks comparison, scoring eligible only if comparison holds *and* nothing blocks scoring.

Separating the three is the point. A run measured on a charging phone is perfectly good data and perfectly comparable in-house, but must not feed a cross-device score. A thermally throttled run is valid as a measurement and useless as a comparison.

`fromJsonMap` re-derives the booleans from the stored flag codes rather than trusting the stored booleans, so changing the table applies to old files; unknown codes from a newer app are preserved but cannot influence the result.
