package dev.halcamera.benchmark.domain

import java.util.Locale

data class ProfileEntry(val key: String, val sha256: String, val source: String, val run: BenchmarkRun) {
    val deviceLabel get() = "${run.device.manufacturer} ${run.device.model}"
    val instanceId get() = JsonMaps.s(run.raw["device_instance_id"])?.takeIf { it.isNotBlank() }
    val label get() = "$deviceLabel · ${instanceId?.take(8) ?: "기기 ID 없음"} · $source\n${run.runId} · ${run.subject.subjectBuildLabel ?: run.device.buildDisplay}\nCamera ${run.endpoint.key} ${run.endpoint.role} · ${run.profile.id}"
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
            appendLine(if (options.differentDevices) "선택한 기기 간 지표 비교 · SW 수정 효과 판정 아님" else "동일 기기 수정 전후 반복 측정 비교")
            appendLine("A ${before.size}개 · B ${after.size}개 · 검정 ${metrics.count { it.p != null }}개 지표 · 보정 후 유의차 ${metrics.count { it.adjustedP != null && it.adjustedP < 0.05 }}개 지표")
            problems.forEach { appendLine("분석 제한: $it") }
            for (m in metrics.sortedBy { it.before == null && it.after == null }) {
                fun describe(s: RepeatStatistics.Summary?) = s?.let { "n=${it.n}, 평균 ${f(it.mean)}, 중앙값 ${f(it.median)}, 표준편차 ${f(it.sd)}" } ?: "유효 실행 없음"
                appendLine("\n${m.id} (${m.unit})\nA: ${describe(m.before)}\nB: ${describe(m.after)}")
                appendLine("차이 B−A: ${f(m.delta)} · 변화율 ${m.deltaPct?.let { f(it) + "%" } ?: "계산 불가(0 기준 또는 값 없음)"}")
                appendLine(when {
                    m.adjustedP == null -> "유의차 판정 불가: ${m.blocked.joinToString(" · ")}"
                    m.adjustedP < 0.05 -> "통계적 차이 검출 · 보정 p=${f(m.adjustedP)} (원 p=${f(m.p)})"
                    else -> "통계적 차이 미검출 · 보정 p=${f(m.adjustedP)} (원 p=${f(m.p)})"
                })
                appendLine("기존 실무 임계값(${RegressionRules.VERSION}): ${m.practical ?: "판정 불가"} · 통계적 유의성과 별개")
                m.exclusions.forEach { appendLine("제외: $it") }
            }
            appendLine("\n분석 방법과 가정")
            appendLine("${RepeatStatistics.VERSION} · 독립 실행 단위 · 양측 순열검정 · Bonferroni 보정 · 가족 유의수준 0.05")
            appendLine("각 묶음 지표별 최소 ${RepeatStatistics.MIN_RUNS}회, 최대 ${RepeatStatistics.MAX_RUNS}회 · 무작위 검정 ${RepeatStatistics.RESAMPLES}회 · seed ${RepeatStatistics.SEED}")
            appendLine("가정: 실행 간 독립성, 귀무가설 아래 그룹 교환 가능성. 시간 경과·장면·환경 차이의 영향은 별도로 통제해야 합니다.")
            appendLine("유의차 미검출은 동등성이나 '차이 없음'의 증명이 아닙니다. 프레임/촬영 개수를 실행 수로 세지 않습니다.")
            appendLine("최소 5회가 충분한 검정력을 보장하지 않습니다. 예를 들어 20개 지표를 각 5회로 정확 검정하면 보정 p의 최솟값도 0.1587입니다.")
            appendLine("동일 기기 사용자 확인=${options.sameDeviceConfirmed} · 독립 실행·동일 장면 확인=${options.independentRunsConfirmed}")
            if (options.differentDevices) appendLine("카메라 ID·역할 일치는 동일 렌즈·화각을 보장하지 않습니다. 기기 간 광학계 차이를 포함한 탐색적 비교입니다.")
            for ((name, entries) in listOf("수정 전 / A" to before, "수정 후 / B" to after)) {
                appendLine("\n$name · 선택 ${entries.size}개")
                entries.forEach { e ->
                    val r = e.run
                    appendLine(e.label)
                    appendLine("sha256=${e.sha256} · key=${e.key}")
                    appendLine("기기 설치 ID=${e.instanceId ?: "알 수 없음"} · system=${r.device.fingerprint} · vendor=${r.device.vendorFingerprint ?: "알 수 없음"} · camera INFO=${r.device.cameraInfoVersion ?: "알 수 없음"}")
                    appendLine("app=${r.app.versionName}/${r.app.versionCode} debug=${r.app.debuggable} · subject=${r.subject}")
                    appendLine("계약=${r.contract.comparisonContractId} · 설정=${r.effectiveConditions} · 환경=${r.env}")
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
        if (a.isEmpty() || b.isEmpty()) problems += "전·후 실행 묶음을 모두 선택하세요."
        if (a.size > RepeatStatistics.MAX_RUNS || b.size > RepeatStatistics.MAX_RUNS) problems += "묶음별 최대 50개 실행까지 분석합니다."
        if (all.map { it.sha256 }.distinct().size != all.size) problems += "동일 원본이 중복되거나 전·후에 겹칩니다."
        // Different files with the same measurement identity cannot become independent observations.
        if (all.map { listOf(it.instanceId, it.deviceLabel, it.run.runId) }.distinct().size != all.size)
            problems += "동일 기기·실행 ID의 사본 또는 충돌이 있습니다. 한 원본만 선택하세요."
        for ((name, entries) in listOf("A" to a, "B" to b)) {
            if (entries.map { buildKey(it.run) }.distinct().size > 1) problems += "$name 묶음에 여러 기기/빌드가 섞여 있습니다."
            if (entries.map { it.instanceId }.distinct().size > 1) problems += "$name 묶음의 기기 설치 ID가 서로 다릅니다."
        }
        if (!options.differentDevices && all.isNotEmpty()) {
            if (all.map { it.deviceLabel }.distinct().size != 1) problems += "서로 다른 모델입니다. 기기 간 비교 모드를 선택하세요."
            val ids = all.map { it.instanceId }
            if ((ids.any { it == null } || ids.distinct().size != 1) && !options.sameDeviceConfirmed)
                problems += "동일 물리 기기인지 확인되지 않았습니다. 구형 파일/재설치 자료는 직접 확인하세요."
        }
        if (!options.independentRunsConfirmed) problems += "독립 반복 실행과 동일 장면·조명 확인이 필요합니다."
        if (all.map { it.run.contract }.distinct().size > 1 || all.map { it.run.profile }.distinct().size > 1)
            problems += "측정 계약 또는 profile이 다릅니다."
        if (all.map { listOf(it.run.endpoint.key, it.run.endpoint.role, it.run.endpoint.facing) }.distinct().size > 1)
            problems += "카메라 ID·역할·방향이 다릅니다. 원시 지표만 열람합니다."
        if (all.map { it.run.effectiveConditions }.distinct().size > 1 || all.any { entry ->
                listOf("af_mode", "fps_range", "preview_size", "yuv_size", "still_size").any { entry.run.effectiveConditions[it].isNullOrBlank() }
            })
            problems += "실제 카메라 설정이 다르거나 누락됐습니다."
        val eligible = all.filter { exclusion(it.run) == null }
        if (eligible.any { r -> with(r.run.env) { charging == null || powerSaveMode == null || thermalStart == null || thermalMax == null || thermalEnd == null || rotation == null } })
            problems += "필수 환경 정보가 누락됐습니다."
        if (eligible.map { it.run.env.charging }.distinct().size > 1 || eligible.map { it.run.env.powerSaveMode }.distinct().size > 1)
            problems += "충전 또는 절전 조건이 다릅니다."
        if (eligible.map { it.run.env.rotation }.distinct().size > 1) problems += "화면 회전 조건이 다릅니다."
        val temperatures = eligible.mapNotNull { it.run.env.thermalMax }
        if (temperatures.isNotEmpty() && temperatures.max() - temperatures.min() >= RegressionDetector.THERMAL_MAX_STEP_DIFF)
            problems += "발열 단계 차이가 비교 허용 범위를 넘습니다."
        val rows = BenchmarkMetricCatalog.ids.map { id ->
            val reasons = mutableListOf<String>()
            fun values(entries: List<ProfileEntry>, side: String) = entries.mapNotNull { e ->
                val m = e.run.metrics.singleOrNull { it.id == id }
                val info = BenchmarkMetricCatalog.info(id)
                val why = exclusion(e.run) ?: when {
                    m == null -> "지표 없음 또는 중복"
                    m.value == null -> "측정값 없음"
                    !m.value.isFinite() || m.value !in 0.0..1e12 -> "유효 범위 밖 측정값"
                    m.timeout -> "타임아웃"
                    m.sampleCount <= 0 -> "샘플 없음"
                    m.unit != info?.unit || m.category != info.category -> "지표 단위·분류 불일치"
                    else -> null
                }
                if (why != null) { reasons += "$side ${e.run.runId} [${e.sha256.take(8)}]: $why"; null } else m!!.value
            }
            val av = values(a, "A").sorted(); val bv = values(b, "B").sorted()
            val sa = RepeatStatistics.summary(av); val sb = RepeatStatistics.summary(bv)
            val blocked = problems.toMutableList()
            if (av.size < RepeatStatistics.MIN_RUNS || bv.size < RepeatStatistics.MIN_RUNS) blocked += "유효 실행 수 부족(각 5회 이상 필요)"
            if (id in setOf("H.6", "H.7", "H.8")) {
                val loads = eligible.map { RegressionDetector.exposureLoad(it.run) }
                if (loads.any { it == null || !it.isFinite() || it <= 0 } ||
                    (loads.isNotEmpty() && loads.filterNotNull().max() / loads.filterNotNull().min() > 4)) blocked += "3A 노출 조건 불명 또는 차이"
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
        run.aborted != null -> "중단된 실행"
        run.contract.metricDefinitionVersion != MeasurementContract.METRIC_DEFINITION_VERSION ||
            run.contract.statsMethod != MeasurementContract.STATS_METHOD || run.contract.clock != MeasurementContract.CLOCK -> "지원하지 않는 측정 계약"
        run.validity.ruleVersion != ValidityFlags.VERSION -> "지원하지 않는 유효성 규칙"
        !RunValidity.fromJsonMap(run.validity.toJsonMap()).comparisonEligible -> "비교 부적격: ${run.validity.flags.joinToString()}"
        run.env.powerSaveMode == true -> "절전 모드"
        run.effectiveConditions["fps_range"]?.let { ProfileCompatibility.normalizeRange(it) != ProfileCompatibility.normalizeRange(run.profile.fpsRange) } == true -> "실제 FPS 범위가 profile과 다릅니다."
        listOfNotNull(run.env.thermalStart, run.env.thermalMax, run.env.thermalEnd).any { it >= ValidityFlags.THERMAL_MODERATE } -> "높은 발열 단계"
        !run.compatibility.supported -> "지원되지 않는 구성"
        run.metrics.map { it.id }.distinct().size != run.metrics.size -> "중복 지표 ID"
        else -> null
    }
}
