package dev.halcamera.benchmark

/** Measured 2026-09-12: ten release/noncharging main-camera runs. Provenance: docs/SCORING.md. */
object S25PlusScoreDraft {
    val calibration = ScoreCalibration(
        manufacturer = "samsung",
        model = "SM-S936N",
        endpointKey = "0",
        contractId = "camera2-standard-v1|metrics-0.3|nearest_rank|elapsedRealtimeNanos",
        normalRunIds = listOf(
            "20260912-204517-239",
            "20260912-204617-366",
            "20260912-204717-463",
            "20260912-204817-791",
            "20260912-204917-850",
            "20260912-205018-048",
            "20260912-205118-163",
            "20260912-205218-430",
            "20260912-205318-486",
            "20260912-205418-678"
        ),
        curves = listOf(
            ScoreCurve("1.1", 10.0103385, 10.0),
            ScoreCurve("1.2", 201.081771, 30.16226565),
            ScoreCurve("1.3", 264.21648400000004, 39.63247260000001),
            ScoreCurve("1.8", 320.81239600000004, 48.121859400000005),
            ScoreCurve("1.6", 536.8943489999999, 80.53415234999999),
            ScoreCurve("1.7", 247.1989585, 37.079843775),
            ScoreCurve("H.1", 33.335208, 5.0002812),
            ScoreCurve("H.2", 33.335417, 5.000312549999999),
            ScoreCurve("H.3", 63.6140885, 9.542113275),
            ScoreCurve("H.4", 67.6686725, 10.150300875),
            ScoreCurve("H.10", 8.350089819826081e-05, 1.0),
            ScoreCurve("2.2", 287.956276, 43.1934414),
            ScoreCurve("2.3", 288.2059635, 43.230894525),
            ScoreCurve("2.5", 286.151797, 42.92276955),
            ScoreCurve("H.5", 0.0, 2.0),
            ScoreCurve("H.9", 0.0, 1.0)
        )
    )
}
