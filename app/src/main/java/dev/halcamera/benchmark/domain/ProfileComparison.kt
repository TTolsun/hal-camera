package dev.halcamera.benchmark.domain

import java.util.Locale

data class ProfileEntry(val key: String, val sha256: String, val source: String, val run: BenchmarkRun) {
    val deviceLabel get() = "${run.device.manufacturer} ${run.device.model}"
    val instanceId get() = JsonMaps.s(run.raw["device_instance_id"])?.takeIf { it.isNotBlank() }
    val label get() = "$deviceLabel · ${instanceId?.take(8) ?: "no device ID"} · $source\n${run.runId} · ${run.subject.subjectBuildLabel ?: run.device.buildDisplay}\nCamera ${run.endpoint.key} ${run.endpoint.role} · ${run.profile.id}"
}

/** Imports never enter RunCatalog. This comparison cannot set a local baseline or automatic reference. */
object ProfileComparison {
    data class Options(val differentDevices: Boolean = false, val sameDeviceConfirmed: Boolean = false,
                       val independentRunsConfirmed: Boolean = false)
    data class Metric(val id: String, val unit: String, val before: RepeatStatistics.Summary?,
                      val after: RepeatStatistics.Summary?, val delta: Double?, val deltaPct: Double?,
                      val p: Double?, val adjustedP: Double?, val practical: RegressionState?,
                      val exclusions: List<String>, val blocked: List<String>)
    data class Result(val before: List<ProfileEntry>, val after: List<ProfileEntry>, val options: Options,
                      val problems: List<String>, val metrics: List<Metric>) {
        fun render(): String = buildString {
            appendLine(if (options.differentDevices) "Cross-device metric comparison · not a verdict on a SW change" else "Same-device before/after repeat comparison")
            appendLine("A ${before.size} · B ${after.size} · ${metrics.count { it.p != null }} metrics tested · ${metrics.count { it.adjustedP != null && it.adjustedP < 0.05 }} significant after correction")
            problems.forEach { appendLine("Analysis limit: $it") }
            for (m in metrics.sortedBy { it.before == null && it.after == null }) {
                fun describe(s: RepeatStatistics.Summary?) = s?.let { "n=${it.n}, mean ${f(it.mean)}, median ${f(it.median)}, SD ${f(it.sd)}" } ?: "no valid runs"
                appendLine("\n${m.id} (${m.unit})\nA: ${describe(m.before)}\nB: ${describe(m.after)}")
                appendLine("Difference B−A: ${f(m.delta)} · change ${m.deltaPct?.let { f(it) + "%" } ?: "not computable (zero base or missing value)"}")
                appendLine(when {
                    m.adjustedP == null -> "No significance verdict: ${m.blocked.joinToString(" · ")}"
                    m.adjustedP < 0.05 -> "Statistical difference detected · adjusted p=${f(m.adjustedP)} (raw p=${f(m.p)})"
                    else -> "No statistical difference detected · adjusted p=${f(m.adjustedP)} (raw p=${f(m.p)})"
                })
                appendLine("Practical threshold (${RegressionRules.VERSION}): ${m.practical ?: "no verdict"} · independent of statistical significance")
                m.exclusions.forEach { appendLine("Excluded: $it") }
            }
            appendLine("\nMethod and assumptions")
            appendLine("${RepeatStatistics.VERSION} · independent-run unit · two-sided permutation test · Bonferroni correction · family-wise significance level 0.05")
            appendLine("Per metric per group at least ${RepeatStatistics.MIN_RUNS} and at most ${RepeatStatistics.MAX_RUNS} runs · ${RepeatStatistics.RESAMPLES} random resamples · seed ${RepeatStatistics.SEED}")
            appendLine("Assumptions: independence between runs, group exchangeability under the null hypothesis. Effects of elapsed time, scene, and environment differences must be controlled separately.")
            appendLine("No significant difference detected is not proof of equivalence or 'no difference'. Frames/shots are not counted as runs.")
            appendLine("A minimum of 5 runs does not guarantee sufficient statistical power. For example, exact tests of 20 metrics with 5 runs each give a minimum adjusted p of 0.1587.")
            appendLine("Same-device user confirmation=${options.sameDeviceConfirmed} · independent runs and same scene confirmed=${options.independentRunsConfirmed}")
            if (options.differentDevices) appendLine("Matching camera ID and role does not guarantee the same lens or field of view. This is an exploratory comparison including cross-device optics differences.")
            for ((name, entries) in listOf("Before / A" to before, "After / B" to after)) {
                appendLine("\n$name · ${entries.size} selected")
                entries.forEach { e ->
                    val r = e.run
                    appendLine(e.label)
                    appendLine("sha256=${e.sha256} · key=${e.key}")
                    appendLine("device install ID=${e.instanceId ?: "unknown"} · system=${r.device.fingerprint} · vendor=${r.device.vendorFingerprint ?: "unknown"} · camera INFO=${r.device.cameraInfoVersion ?: "unknown"}")
                    appendLine("app=${r.app.versionName}/${r.app.versionCode} debug=${r.app.debuggable} · subject=${r.subject}")
                    appendLine("contract=${r.contract.comparisonContractId} · conditions=${r.effectiveConditions} · env=${r.env}")
                }
            }
        }
    }

    private fun f(value: Double?) = value?.let { String.format(Locale.US, "%.6g", it) } ?: "—"
    private fun buildKey(r: BenchmarkRun): List<Any?> = listOf(r.device, r.app,
        r.subject.subjectBuildLabel, r.subject.subjectCommit, r.subject.subjectBranch)

    fun compare(a: List<ProfileEntry>, b: List<ProfileEntry>, options: Options): Result {
        val all = a + b
        val problems = mutableListOf<String>()
        if (a.isEmpty() || b.isEmpty()) problems += "Select both the before and after run groups."
        if (a.size > RepeatStatistics.MAX_RUNS || b.size > RepeatStatistics.MAX_RUNS) problems += "At most 50 runs per group are analyzed."
        if (all.map { it.sha256 }.distinct().size != all.size) problems += "The same original is duplicated or appears in both before and after."
        // Different files with the same measurement identity cannot become independent observations.
        if (all.map { listOf(it.instanceId, it.deviceLabel, it.run.runId) }.distinct().size != all.size)
            problems += "Copies or collisions with the same device and run ID exist. Select only one original."
        for ((name, entries) in listOf("A" to a, "B" to b)) {
            if (entries.map { buildKey(it.run) }.distinct().size > 1) problems += "Group $name mixes multiple devices/builds."
            if (entries.map { it.instanceId }.distinct().size > 1) problems += "Group $name has differing device install IDs."
        }
        if (!options.differentDevices && all.isNotEmpty()) {
            if (all.map { it.deviceLabel }.distinct().size != 1) problems += "Different models. Select cross-device comparison mode."
            val ids = all.map { it.instanceId }
            if ((ids.any { it == null } || ids.distinct().size != 1) && !options.sameDeviceConfirmed)
                problems += "Not confirmed to be the same physical device. Verify older files or reinstalled data yourself."
        }
        if (!options.independentRunsConfirmed) problems += "Confirmation of independent repeated runs and same scene/lighting is required."
        if (all.map { it.run.contract }.distinct().size > 1 || all.map { it.run.profile }.distinct().size > 1)
            problems += "Measurement contract or profile differs."
        if (all.map { listOf(it.run.endpoint.key, it.run.endpoint.role, it.run.endpoint.facing) }.distinct().size > 1)
            problems += "Camera ID, role, or facing differs. Raw metrics only."
        if (all.map { it.run.effectiveConditions }.distinct().size > 1 || all.any { entry ->
                listOf("af_mode", "fps_range", "preview_size", "yuv_size", "still_size").any { entry.run.effectiveConditions[it].isNullOrBlank() }
            })
            problems += "Actual camera conditions differ or are missing."
        val eligible = all.filter { exclusion(it.run) == null }
        if (eligible.any { r -> with(r.run.env) { charging == null || powerSaveMode == null || thermalStart == null || thermalMax == null || thermalEnd == null || rotation == null } })
            problems += "Required environment information is missing."
        if (eligible.map { it.run.env.charging }.distinct().size > 1 || eligible.map { it.run.env.powerSaveMode }.distinct().size > 1)
            problems += "Charging or power save conditions differ."
        if (eligible.map { it.run.env.rotation }.distinct().size > 1) problems += "Screen rotation conditions differ."
        val temperatures = eligible.mapNotNull { it.run.env.thermalMax }
        if (temperatures.isNotEmpty() && temperatures.max() - temperatures.min() >= RegressionDetector.THERMAL_MAX_STEP_DIFF)
            problems += "Thermal level difference exceeds the comparison tolerance."
        val rows = BenchmarkMetricCatalog.ids.map { id ->
            val reasons = mutableListOf<String>()
            fun values(entries: List<ProfileEntry>, side: String) = entries.mapNotNull { e ->
                val m = e.run.metrics.singleOrNull { it.id == id }
                val info = BenchmarkMetricCatalog.info(id)
                val why = exclusion(e.run) ?: when {
                    m == null -> "metric missing or duplicated"
                    m.value == null -> "no value"
                    !m.value.isFinite() || m.value !in 0.0..1e12 -> "value out of range"
                    m.timeout -> "timeout"
                    m.sampleCount <= 0 -> "no samples"
                    m.unit != info?.unit || m.category != info.category -> "unit or category mismatch"
                    else -> null
                }
                if (why != null) { reasons += "$side ${e.run.runId} [${e.sha256.take(8)}]: $why"; null } else m!!.value
            }
            val av = values(a, "A").sorted(); val bv = values(b, "B").sorted()
            val sa = RepeatStatistics.summary(av); val sb = RepeatStatistics.summary(bv)
            val blocked = problems.toMutableList()
            if (av.size < RepeatStatistics.MIN_RUNS || bv.size < RepeatStatistics.MIN_RUNS) blocked += "not enough valid runs (at least 5 per group required)"
            if (id in setOf("H.6", "H.7", "H.8")) {
                val loads = eligible.map { RegressionDetector.exposureLoad(it.run) }
                if (loads.any { it == null || !it.isFinite() || it <= 0 } ||
                    (loads.isNotEmpty() && loads.filterNotNull().max() / loads.filterNotNull().min() > 4)) blocked += "3A exposure conditions unknown or differ"
            }
            val delta = if (sa != null && sb != null) sb.mean - sa.mean else null
            val p = if (blocked.isEmpty()) RepeatStatistics.pValue(av, bv) else null
            Metric(id, BenchmarkMetricCatalog.info(id)?.unit ?: "", sa, sb, delta,
                RegressionDetector.deltaPct(sa?.mean, sb?.mean), p, null,
                if (blocked.isEmpty() && sa != null && sb != null) RegressionRules.rule(id)?.let { RegressionDetector.state(it, sa.mean, sb.mean) } else null,
                reasons, blocked)
        }
        val tests = rows.count { it.p != null }
        return Result(a, b, options, problems, rows.map { it.copy(adjustedP = it.p?.let { p -> RepeatStatistics.adjustedP(p, tests) }) })
    }

    fun exclusion(run: BenchmarkRun): String? = when {
        run.aborted != null -> "aborted run"
        run.contract.metricDefinitionVersion != MeasurementContract.METRIC_DEFINITION_VERSION ||
            run.contract.statsMethod != MeasurementContract.STATS_METHOD || run.contract.clock != MeasurementContract.CLOCK -> "unsupported measurement contract"
        run.validity.ruleVersion != ValidityFlags.VERSION -> "unsupported validity rules"
        !RunValidity.fromJsonMap(run.validity.toJsonMap()).comparisonEligible -> "not comparison-eligible: ${run.validity.flags.joinToString()}"
        run.env.powerSaveMode == true -> "power save mode"
        run.effectiveConditions["fps_range"]?.let { ProfileCompatibility.normalizeRange(it) != ProfileCompatibility.normalizeRange(run.profile.fpsRange) } == true -> "actual FPS range differs from the profile"
        listOfNotNull(run.env.thermalStart, run.env.thermalMax, run.env.thermalEnd).any { it >= ValidityFlags.THERMAL_MODERATE } -> "high thermal level"
        !run.compatibility.supported -> "unsupported configuration"
        run.metrics.map { it.id }.distinct().size != run.metrics.size -> "duplicate metric id"
        else -> null
    }
}
