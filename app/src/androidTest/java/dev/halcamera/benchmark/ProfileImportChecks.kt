package dev.halcamera.benchmark

import android.content.Context
import android.content.ContextWrapper
import dev.halcamera.camera.CameraEndpoint
import dev.halcamera.camera.LensRole
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real Android JSON/file boundary; isolated from the user's local history, imports and baseline. */
object ProfileImportChecks {
    fun fixture(): BenchmarkRun {
        val profile = BenchmarkProfile.CAMERA2_STANDARD_V1
        return BenchmarkRun(
            runId = "SYNTHETIC-A-1", exportedAtUtc = "2026-09-14T00:00:00Z", aborted = null,
            profile = profile, contract = MeasurementContract(profile.id, MeasurementContract.METRIC_DEFINITION_VERSION,
                MeasurementContract.STATS_METHOD, MeasurementContract.CLOCK),
            compatibility = Compatibility("device_setup", true, emptyList(), true),
            effectiveConditions = mapOf("af_mode" to "CONTINUOUS_PICTURE", "fps_range" to "[30,30]",
                "preview_size" to "1920x1080", "yuv_size" to "1920x1080", "still_size" to "1920x1080"),
            endpoint = CameraEndpoint("0", null, LensRole.MAIN, 1, true, true, null, 24.0, 1, 3, 1f, 10f),
            device = DeviceInfo("Synthetic", "Fixture A", "test-only", "test", "synthetic/system", "synthetic/vendor", 36, "2026-01-01", "test"),
            app = AppInfo("test-only", 1, false), subject = SubjectLabel("SYNTHETIC BEFORE", "fixture-before", note = "UI fixture, not hardware data"),
            env = RunEnv(0, 0, 0, 80, 80, false, false, 0), validity = RunValidity.from(emptyList()),
            baselineRef = null, referenceRef = null,
            metrics = listOf(BenchmarkMetric("H.1", Category.PREVIEW, "ms", 30.0, 30.0, 30.0, 30.0, 30.0, 300, null, null)),
            raw = mapOf("device_instance_id" to "SYNTHETIC-installation-A")
        )
    }

    fun run(context: Context): Int {
        val directory = File(context.cacheDir, "profile-checks-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = directory }
        var passed = 0
        fun json() = BenchmarkReport.json(BenchmarkReportCodec.toJsonMap(fixture()))
        try {
            val library = ProfileLibrary(isolated)
            val local = BenchmarkStore(isolated)
            val baseline = File(local.dir, "index.json").apply { writeText("{\"schema_version\":1,\"baselines\":{\"sentinel\":\"original\"}}") }
            val baselineBytes = baseline.readBytes()
            val first = library.import(json().toString().byteInputStream())
            check(!first.duplicate); passed++
            check(library.import(json().toString().byteInputStream()).duplicate); passed++
            val collision = json().put("exported_at_utc", "2026-09-14T00:01:00Z")
            check(!library.import(collision.toString().byteInputStream()).duplicate); passed++
            check(ProfileLibrary(isolated).load().entries.size == 2); passed++
            check(local.files().isEmpty() && baseline.readBytes().contentEquals(baselineBytes)); passed++
            val loaded = library.load().entries
            check(loaded.all { it.key.startsWith("import:") && it.run.runId == fixture().runId }); passed++
            library.deleteImported(loaded.first())
            check(ProfileLibrary(isolated).load().entries.size == 1 && baseline.readBytes().contentEquals(baselineBytes)); passed++

            fun rejects(value: String) {
                val count = library.load().entries.size
                check(runCatching { library.import(value.byteInputStream()) }.isFailure)
                check(library.load().entries.size == count && baseline.readBytes().contentEquals(baselineBytes))
                passed++
            }
            rejects(json().put("schema_version", 999).toString())
            rejects(json().put("schema_version", 4.2).toString())
            rejects(json().put("kind", "incident").toString())
            rejects(json().put("comparison_contract_id", "mismatch").toString())
            rejects(json().apply { getJSONObject("profile").put("observe_ms", 1) }.toString())
            rejects(json().apply { remove("device") }.toString())
            rejects(json().apply { remove("compatibility") }.toString())
            rejects(json().apply { getJSONObject("validity").remove("flags") }.toString())
            rejects(json().apply { getJSONArray("metrics").getJSONObject(0).put("timeout", "false") }.toString())
            rejects(json().apply { getJSONArray("metrics").getJSONObject(0).put("value", "30") }.toString())
            rejects(json().apply { getJSONObject("env").put("thermal_max", -1) }.toString())
            rejects(json().toString() + "{}")
            rejects("{incomplete")
            val old = ProfileLibrary.parse(json().put("schema_version", 3).apply {
                getJSONObject("raw").remove("device_instance_id"); getJSONObject("app").remove("debuggable")
            }.toString())
            check(old.app.debuggable == null && old.raw["device_instance_id"] == null); passed++
            val spoof = ProfileLibrary.parse(json().apply {
                getJSONObject("validity").put("comparison_eligible", true).getJSONArray("flags").put("THERMAL_HIGH")
                getJSONObject("summary").put("endpoint_score", 1000)
            }.toString())
            check(ProfileComparison.exclusion(spoof) != null); passed++
            val badEnv = ProfileLibrary.parse(json().apply { getJSONObject("env").put("power_save_mode", true) }.toString())
            check(ProfileComparison.exclusion(badEnv) != null); passed++

            // These explicitly synthetic fixtures are copied to Downloads by the UI test operator.
            val export = File(context.getExternalFilesDir(null), "profile-ui-fixtures").apply { mkdirs() }
            for (side in listOf("A", "B")) for (i in 1..5) {
                val f = fixture().copy(runId = "SYNTHETIC-$side-$i", subject = SubjectLabel("SYNTHETIC $side", "fixture-$side"),
                    metrics = fixture().metrics.map { it.copy(value = if (side == "A") 30.0 + i else 60.0 + i) })
                File(export, "$side-$i.json").writeText(BenchmarkReport.json(BenchmarkReportCodec.toJsonMap(f)).toString(2))
            }
            File(export, "foreign.json").writeText(json().apply {
                put("run_id", "SYNTHETIC-FOREIGN-1"); getJSONObject("device").put("model", "Fixture B")
                getJSONObject("raw").put("device_instance_id", "SYNTHETIC-installation-B")
            }.toString(2))
            File(export, "invalid.json").writeText("{\"kind\":\"not-a-benchmark\"}")
            passed++
        } finally { directory.deleteRecursively() }
        return passed
    }
}
