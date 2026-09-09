package dev.cameradoctor.benchmark

/**
 * One validity flag and which consumers it blocks (docs/PLAN-BenchMarker-v0.3.md 5.3).
 * "The measurement is valid", "it may be compared" and "it may feed a cross-device score" are three different
 * questions; the three booleans of [RunValidity] are derived from this table, never set by hand.
 */
data class ValidityFlag(
    val code: String,
    val blocksMeasurement: Boolean,
    val blocksComparison: Boolean,
    val blocksScoring: Boolean
)

object ValidityFlags {
    // Measurement invalid: the run does not contain what the profile promised.
    val ABORTED = ValidityFlag("ABORTED", true, true, true)
    val HARD_FAILURE = ValidityFlag("HARD_FAILURE", true, true, true)
    val PROFILE_UNSUPPORTED = ValidityFlag("PROFILE_UNSUPPORTED", true, true, true)
    val INSUFFICIENT_SAMPLES = ValidityFlag("INSUFFICIENT_SAMPLES", true, true, true)
    // Measured correctly, but not comparable with other runs.
    val CADENCE_NOT_FIXED = ValidityFlag("CADENCE_NOT_FIXED", false, true, true)
    val THERMAL_HIGH = ValidityFlag("THERMAL_HIGH", false, true, true)
    val POWER_SAVE_MODE = ValidityFlag("POWER_SAVE_MODE", false, true, true)
    // Comparable in-house, excluded from cross-device scoring.
    val CHARGING = ValidityFlag("CHARGING", false, false, true)
    val BATTERY_LOW = ValidityFlag("BATTERY_LOW", false, false, true)
    val PROFILE_DRAFT = ValidityFlag("PROFILE_DRAFT", false, false, true)
    // Informational only.
    val PREFLIGHT_MISMATCH = ValidityFlag("PREFLIGHT_MISMATCH", false, false, false)
    val THERMAL_CHANGED = ValidityFlag("THERMAL_CHANGED", false, false, false)
    val LABEL_MISSING = ValidityFlag("LABEL_MISSING", false, false, false)

    val all: List<ValidityFlag> = listOf(
        ABORTED, HARD_FAILURE, PROFILE_UNSUPPORTED, INSUFFICIENT_SAMPLES,
        CADENCE_NOT_FIXED, THERMAL_HIGH, POWER_SAVE_MODE,
        CHARGING, BATTERY_LOW, PROFILE_DRAFT,
        PREFLIGHT_MISMATCH, THERMAL_CHANGED, LABEL_MISSING
    )

    fun byCode(code: String): ValidityFlag? = all.firstOrNull { it.code == code }

    /** PowerManager.THERMAL_STATUS_MODERATE. Kept as a plain int so this file has no Android dependency. */
    const val THERMAL_MODERATE = 2
    const val BATTERY_LOW_PCT = 20
    /** MetricExtractor's minimum observation sample count. */
    const val MIN_OBSERVED_FRAMES = 15
}

data class RunValidity(
    val measurementValid: Boolean,
    val comparisonEligible: Boolean,
    val scoringEligible: Boolean,
    val flags: List<String>
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "measurement_valid" to measurementValid, "comparison_eligible" to comparisonEligible,
        "scoring_eligible" to scoringEligible, "flags" to flags
    )

    companion object {
        fun from(flags: Collection<ValidityFlag>): RunValidity {
            val measurement = flags.none { it.blocksMeasurement }
            val comparison = measurement && flags.none { it.blocksComparison }
            val scoring = comparison && flags.none { it.blocksScoring }
            return RunValidity(measurement, comparison, scoring, flags.map { it.code }.distinct())
        }

        /** Reading a stored run re-derives the booleans from the flag codes so a table change is applied consistently. */
        fun fromJsonMap(m: Map<String, Any?>?): RunValidity {
            val codes = JsonMaps.strings(m?.get("flags"))
            val known = codes.mapNotNull { ValidityFlags.byCode(it) }
            val derived = from(known)
            // Unknown codes (from a newer app) are kept in the list but cannot influence the booleans.
            return derived.copy(flags = codes)
        }
    }
}

/** Everything the validity evaluation needs, gathered by the runner and the activity. Nulls mean "unknown". */
data class ValidityInputs(
    val aborted: String?,
    val hardFailure: Boolean,
    val preflightSupported: Boolean,
    val preflightMismatch: Boolean,
    val launchSamples: Int,
    val stillSamples: Int,
    val observedFrames: Int,
    val expectedLaunchSamples: Int,
    val expectedStillSamples: Int,
    val cadenceFixed: Boolean?,
    val thermalStart: Int?,
    val thermalMax: Int?,
    val thermalEnd: Int?,
    val powerSaveMode: Boolean?,
    val charging: Boolean?,
    val batteryStart: Int?,
    val profileDraft: Boolean,
    val subjectLabeled: Boolean,
    val minObservedFrames: Int = ValidityFlags.MIN_OBSERVED_FRAMES
)

object RunValidityEvaluator {
    fun flags(x: ValidityInputs): List<ValidityFlag> {
        val out = ArrayList<ValidityFlag>()
        if (x.aborted != null) out += ValidityFlags.ABORTED
        if (x.hardFailure) out += ValidityFlags.HARD_FAILURE
        if (!x.preflightSupported) out += ValidityFlags.PROFILE_UNSUPPORTED
        if (x.launchSamples < x.expectedLaunchSamples || x.stillSamples < x.expectedStillSamples || x.observedFrames < x.minObservedFrames)
            out += ValidityFlags.INSUFFICIENT_SAMPLES
        if (x.cadenceFixed == false) out += ValidityFlags.CADENCE_NOT_FIXED
        // The run-wide maximum decides, not the start value: LIGHT at start and SEVERE at the end is not comparable.
        val peak = listOfNotNull(x.thermalStart, x.thermalMax, x.thermalEnd).maxOrNull()
        if (peak != null && peak >= ValidityFlags.THERMAL_MODERATE) out += ValidityFlags.THERMAL_HIGH
        if (x.powerSaveMode == true) out += ValidityFlags.POWER_SAVE_MODE
        if (x.preflightMismatch) out += ValidityFlags.PREFLIGHT_MISMATCH
        if (x.charging == true) out += ValidityFlags.CHARGING
        if (x.batteryStart != null && x.batteryStart < ValidityFlags.BATTERY_LOW_PCT) out += ValidityFlags.BATTERY_LOW
        if (x.profileDraft) out += ValidityFlags.PROFILE_DRAFT
        if (x.thermalStart != null && x.thermalEnd != null && x.thermalStart != x.thermalEnd) out += ValidityFlags.THERMAL_CHANGED
        if (!x.subjectLabeled) out += ValidityFlags.LABEL_MISSING
        return out
    }

    fun evaluate(x: ValidityInputs): RunValidity = RunValidity.from(flags(x))
}
