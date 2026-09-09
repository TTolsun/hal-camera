package dev.cameradoctor.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunValidityTest {
    private fun clean() = ValidityInputs(
        aborted = null, hardFailure = false, preflightSupported = true, preflightMismatch = false,
        launchSamples = 9, stillSamples = 9, observedFrames = 297, expectedLaunchSamples = 9, expectedStillSamples = 9,
        cadenceFixed = true, thermalStart = 0, thermalMax = 0, thermalEnd = 0,
        powerSaveMode = false, charging = false, batteryStart = 80, profileDraft = false, subjectLabeled = true
    )

    private fun eval(x: ValidityInputs) = RunValidityEvaluator.evaluate(x)

    @Test fun cleanRunIsEligibleForEverything() {
        val v = eval(clean())
        assertTrue(v.measurementValid); assertTrue(v.comparisonEligible); assertTrue(v.scoringEligible)
        assertTrue(v.flags.isEmpty())
    }

    @Test fun flagTableIsMonotonic() {
        // A flag that blocks measurement must block comparison, and one that blocks comparison must block scoring.
        for (f in ValidityFlags.all) {
            if (f.blocksMeasurement) assertTrue(f.code, f.blocksComparison)
            if (f.blocksComparison) assertTrue(f.code, f.blocksScoring)
        }
    }

    @Test fun thermalUsesTheRunMaximumNotTheStart() {
        val light = eval(clean().copy(thermalStart = 1, thermalMax = 1, thermalEnd = 1))
        assertTrue(light.comparisonEligible)
        // Start LIGHT, peak MODERATE: measured fine, but not comparable.
        val moderate = eval(clean().copy(thermalStart = 1, thermalMax = 2, thermalEnd = 1))
        assertTrue(moderate.measurementValid); assertFalse(moderate.comparisonEligible); assertFalse(moderate.scoringEligible)
        assertTrue(moderate.flags.contains("THERMAL_HIGH"))
        // Start LIGHT, end SEVERE with no listener value: the end value still counts as the peak.
        val severeEnd = eval(clean().copy(thermalStart = 1, thermalMax = null, thermalEnd = 3))
        assertFalse(severeEnd.comparisonEligible)
        assertTrue(severeEnd.flags.contains("THERMAL_HIGH"))
        assertTrue(severeEnd.flags.contains("THERMAL_CHANGED"))
    }

    @Test fun chargingAndLowBatteryAllowComparisonButNotScoring() {
        val v = eval(clean().copy(charging = true, batteryStart = 15))
        assertTrue(v.measurementValid); assertTrue(v.comparisonEligible); assertFalse(v.scoringEligible)
        assertEquals(listOf("CHARGING", "BATTERY_LOW"), v.flags)
    }

    @Test fun cadenceNotFixedIsMeasuredButNotComparable() {
        val v = eval(clean().copy(cadenceFixed = false))
        assertTrue(v.measurementValid); assertFalse(v.comparisonEligible); assertFalse(v.scoringEligible)
    }

    @Test fun powerSaveModeBlocksComparison() {
        val v = eval(clean().copy(powerSaveMode = true))
        assertTrue(v.measurementValid); assertFalse(v.comparisonEligible)
    }

    @Test fun abortedAndHardFailureInvalidateTheMeasurement() {
        assertFalse(eval(clean().copy(aborted = "background")).measurementValid)
        assertFalse(eval(clean().copy(hardFailure = true)).measurementValid)
        assertFalse(eval(clean().copy(preflightSupported = false)).measurementValid)
    }

    @Test fun insufficientSamplesBoundaries() {
        assertTrue(eval(clean().copy(launchSamples = 9)).measurementValid)
        assertFalse(eval(clean().copy(launchSamples = 8)).measurementValid)
        assertFalse(eval(clean().copy(stillSamples = 8)).measurementValid)
        assertTrue(eval(clean().copy(observedFrames = 15)).measurementValid)
        assertFalse(eval(clean().copy(observedFrames = 14)).measurementValid)
    }

    @Test fun draftProfileOnlyBlocksScoring() {
        val v = eval(clean().copy(profileDraft = true))
        assertTrue(v.comparisonEligible); assertFalse(v.scoringEligible)
        assertEquals(listOf("PROFILE_DRAFT"), v.flags)
    }

    @Test fun informationalFlagsDoNotChangeEligibility() {
        val v = eval(clean().copy(subjectLabeled = false, preflightMismatch = true, thermalStart = 0, thermalEnd = 1, thermalMax = 1))
        assertTrue(v.scoringEligible)
        assertEquals(setOf("LABEL_MISSING", "PREFLIGHT_MISMATCH", "THERMAL_CHANGED"), v.flags.toSet())
    }

    @Test fun jsonReadRederivesBooleansFromFlagCodes() {
        // Stored booleans lie (all true) but the flag table decides: CHARGING blocks scoring only.
        val stored = mapOf("measurement_valid" to true, "comparison_eligible" to true, "scoring_eligible" to true,
            "flags" to listOf("CHARGING"))
        val v = RunValidity.fromJsonMap(stored)
        assertTrue(v.measurementValid); assertTrue(v.comparisonEligible); assertFalse(v.scoringEligible)
        assertEquals(listOf("CHARGING"), v.flags)
        assertTrue(v.unknownFlags.isEmpty())
        assertEquals(ValidityFlags.VERSION, v.ruleVersion)
        assertEquals(v, RunValidity.fromJsonMap(v.toJsonMap()))
        assertEquals(ValidityFlags.VERSION, v.toJsonMap()["validity_rule_version"])
    }

    @Test fun unknownFlagsFailClosed() {
        // A newer app may have added a blocking flag this version does not know: never compare or score such a run.
        val stored = mapOf("validity_rule_version" to "validity-v9", "measurement_valid" to true,
            "comparison_eligible" to true, "scoring_eligible" to true, "flags" to listOf("FUTURE_FLAG"))
        val v = RunValidity.fromJsonMap(stored)
        assertTrue(v.measurementValid)
        assertFalse(v.comparisonEligible); assertFalse(v.scoringEligible)
        assertEquals(listOf("FUTURE_FLAG"), v.unknownFlags)
        assertEquals(listOf("FUTURE_FLAG"), v.flags)
        assertEquals("validity-v9", v.ruleVersion)
    }
}
