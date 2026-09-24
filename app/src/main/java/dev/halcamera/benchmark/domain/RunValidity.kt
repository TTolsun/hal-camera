package dev.halcamera.benchmark.domain

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
    /** Bumped whenever a flag is added or a column of the table changes. Stored in every run's validity block. */
    const val VERSION = "validity-v3"

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
    /** A debuggable build measures its own overhead as well, so its numbers never leave this device. */
    val DEBUGGABLE_BUILD = ValidityFlag("DEBUGGABLE_BUILD", false, false, true)
    /**
     * The profile has a RECORD stage but it did not deliver what the profile promised, so the 3.x metrics are
     * missing or short. Everything measured before the stage is intact and still comparable in house, which is
     * why this does not invalidate the measurement; a cross-device score covering fewer metrics than another
     * device's would not be the same number, so scoring is blocked (docs/PLAN-Recording-v0.1.md 9).
     */
    val RECORD_NOT_MEASURED = ValidityFlag("RECORD_NOT_MEASURED", false, false, true)
    // Informational only.
    val PREFLIGHT_MISMATCH = ValidityFlag("PREFLIGHT_MISMATCH", false, false, false)
    val THERMAL_CHANGED = ValidityFlag("THERMAL_CHANGED", false, false, false)
    val LABEL_MISSING = ValidityFlag("LABEL_MISSING", false, false, false)

    val all: List<ValidityFlag> = listOf(
        ABORTED, HARD_FAILURE, PROFILE_UNSUPPORTED, INSUFFICIENT_SAMPLES,
        CADENCE_NOT_FIXED, THERMAL_HIGH, POWER_SAVE_MODE,
        CHARGING, BATTERY_LOW, PROFILE_DRAFT, DEBUGGABLE_BUILD, RECORD_NOT_MEASURED,
        PREFLIGHT_MISMATCH, THERMAL_CHANGED, LABEL_MISSING
    )

    fun byCode(code: String): ValidityFlag? = all.firstOrNull { it.code == code }

    /** PowerManager.THERMAL_STATUS_MODERATE. Kept as a plain int so this file has no Android dependency. */
    const val THERMAL_MODERATE = 2
    const val BATTERY_LOW_PCT = 20
    /** MetricExtractor's minimum observation sample count. */
    const val MIN_OBSERVED_FRAMES = 15

    /**
     * Fewest usable recording cycles for a 3.x value. With the profile's five cycles minus the warm-up one the
     * stage promises four, so three still gives a median worth reading while two would be a midpoint of a pair.
     */
    const val MIN_RECORD_CYCLES = 3
}

/**
 * [unknownFlags] are codes this app version does not know. They are kept verbatim and treated fail-closed: a run
 * carrying a flag we cannot interpret is never compared or scored, because the flag may have been a blocking one
 * in the version that wrote it (PR #11 follow-up review).
 */
data class RunValidity(
    val measurementValid: Boolean,
    val comparisonEligible: Boolean,
    val scoringEligible: Boolean,
    val flags: List<String>,
    val unknownFlags: List<String> = emptyList(),
    val ruleVersion: String = ValidityFlags.VERSION
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "validity_rule_version" to ruleVersion,
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

        /**
         * Reading a stored run re-derives the booleans from the flag codes so a table change is applied consistently.
         * Unknown codes block comparison and scoring (fail-closed); the stored booleans are never trusted.
         */
        fun fromJsonMap(m: Map<String, Any?>?): RunValidity {
            val codes = JsonMaps.strings(m?.get("flags")).distinct()
            val known = codes.mapNotNull { ValidityFlags.byCode(it) }
            val unknown = codes.filter { ValidityFlags.byCode(it) == null }
            val derived = from(known)
            val version = JsonMaps.s(m?.get("validity_rule_version")) ?: ValidityFlags.VERSION
            return if (unknown.isEmpty()) derived.copy(flags = codes, ruleVersion = version)
            else derived.copy(comparisonEligible = false, scoringEligible = false, flags = codes, unknownFlags = unknown, ruleVersion = version)
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
    /** Usable recording cycles and what the profile promised; both 0 for a profile without a RECORD stage. */
    val recordSamples: Int = 0,
    val expectedRecordSamples: Int = 0,
    val cadenceFixed: Boolean?,
    val thermalStart: Int?,
    val thermalMax: Int?,
    val thermalEnd: Int?,
    val powerSaveMode: Boolean?,
    val charging: Boolean?,
    val batteryStart: Int?,
    val profileDraft: Boolean,
    val debuggableBuild: Boolean?,
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
        // Only a profile that promised recordings can miss them. INSUFFICIENT_SAMPLES is not used here on
        // purpose: that flag invalidates the measurement, and everything measured before the RECORD stage is
        // intact even when no cycle succeeded.
        if (x.expectedRecordSamples > 0 && x.recordSamples < x.expectedRecordSamples) out += ValidityFlags.RECORD_NOT_MEASURED
        if (x.debuggableBuild == true) out += ValidityFlags.DEBUGGABLE_BUILD
        if (x.thermalStart != null && x.thermalEnd != null && x.thermalStart != x.thermalEnd) out += ValidityFlags.THERMAL_CHANGED
        if (!x.subjectLabeled) out += ValidityFlags.LABEL_MISSING
        return out
    }

    fun evaluate(x: ValidityInputs): RunValidity = RunValidity.from(flags(x))
}
