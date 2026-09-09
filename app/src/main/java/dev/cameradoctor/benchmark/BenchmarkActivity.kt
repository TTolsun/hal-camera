package dev.cameradoctor.benchmark

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.cameradoctor.camera.Camera2Engine
import dev.cameradoctor.camera.StreamSpec
import dev.cameradoctor.check.CameraEndpoint
import dev.cameradoctor.check.CameraEndpointResolver
import dev.cameradoctor.check.LensRole
import dev.cameradoctor.check.LensRoles
import dev.cameradoctor.telemetry.Event
import dev.cameradoctor.telemetry.FlightRecorder
import dev.cameradoctor.telemetry.Telemetry
import dev.cameradoctor.telemetry.nowNs
import dev.cameradoctor.ui.Look
import java.io.File

/**
 * Minimum BENCHMARK screen for M2 (docs/PLAN-BenchMarker-v0.3.md chapter 9): pick a camera, show the preflight
 * verdict and how it was reached, run the profile, show the six phases and finally the path of the run JSON.
 * The result screen with metric values is M3; here the numbers are only written to the file.
 */
class BenchmarkActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val recorder = FlightRecorder(::nowNs, retentionNs = 180_000_000_000L, maxEvents = 60_000, preNs = 0, postNs = 0)
    private val telemetry = Telemetry(recorder)
    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1

    private lateinit var preview: TextureView
    private lateinit var title: android.widget.TextView
    private lateinit var preflightText: android.widget.TextView
    private lateinit var progressText: android.widget.TextView
    private lateinit var resultText: android.widget.TextView
    private lateinit var startButton: Button
    private lateinit var cameraButton: Button

    private var endpoints: List<CameraEndpoint> = emptyList()
    private var selected = 0
    private var compatibility: Compatibility = Compatibility.NOT_CHECKED
    private var runner: BenchmarkRunner? = null
    private var engine: Camera2Engine? = null
    private var thermal: ThermalTracker? = null
    private var firstYuvSeen = false
    private var destroyed = false
    private var lastFile: File? = null
    private val envStart = HashMap<String, Any?>()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enumerate() else title.text = "카메라 권한이 없어 벤치마크를 실행할 수 없습니다."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
            setPadding(dp(20), dp(48), dp(20), dp(28))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(60, 0, 0, 0))
            )
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))

        val card = Look.card(this, dark = true)
        title = Look.text(this, "BENCHMARK", 21, Look.onDark, bold = true)
        card.addView(title)
        card.addView(Look.text(this, "profile ${profile.id}", 12, Look.onDarkMuted, mono = true), lp(top = 4))
        preflightText = Look.text(this, "카메라를 확인하는 중입니다.", 13, Look.onDarkMuted, mono = true)
        card.addView(copyOnTap(preflightText, "preflight"), lp(top = 8))
        progressText = Look.text(this, "", 14, Look.onDark, mono = true)
        card.addView(progressText, lp(top = 10))
        resultText = Look.text(this, "", 11, Look.onDarkMuted, mono = true)
        card.addView(copyOnTap(resultText, "run"), lp(top = 8))
        panel.addView(card)

        val row = Look.row(this)
        cameraButton = Look.ghostButton(this, "카메라 변경", dark = true) { selectNext() }
        row.addView(cameraButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(Look.ghostButton(this, "JSON 공유", dark = true) { lastFile?.let(::share) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        panel.addView(row, lp(top = 12))

        val row2 = Look.row(this)
        startButton = Look.primaryButton(this, "START BENCHMARK") { begin() }
        row2.addView(startButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        row2.addView(Look.ghostButton(this, "닫기", dark = true) { finish() }, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
        panel.addView(row2, lp(top = 8))

        recorder.listener = { e -> main.post { if (!destroyed) onEvent(e) } }

        if (hasPermission()) enumerate() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onStop() { runner?.abort("background"); super.onStop() }
    override fun onDestroy() { destroyed = true; recorder.listener = null; thermal?.stop(); super.onDestroy() }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---- camera selection and preflight ----

    private fun enumerate() {
        val manager = getSystemService(CameraManager::class.java)
        endpoints = LensRoles.checkOrder(CameraEndpointResolver(manager).resolve()).filter { it.independentlyOpenable }
        if (endpoints.isEmpty()) { title.text = "열 수 있는 카메라가 없습니다."; startButton.isEnabled = false; return }
        selected = 0
        preflight()
    }

    private fun selectNext() {
        if (endpoints.isEmpty() || runner != null) return
        selected = (selected + 1) % endpoints.size
        preflight()
    }

    private fun preflight() {
        val endpoint = endpoints[selected]
        val manager = getSystemService(CameraManager::class.java)
        val metrics = resources.displayMetrics
        val checker = ProfileCompatibilityChecker(manager, metrics.widthPixels, metrics.heightPixels)
        compatibility = checker.check(profile, endpoint.logicalCameraId)
        val setupSupported = if (Build.VERSION.SDK_INT >= 35)
            runCatching { manager.isCameraDeviceSetupSupported(endpoint.logicalCameraId) }.getOrNull() else null
        recorder.record("app", "preflight", values = mapOf(
            "cameraId" to endpoint.logicalCameraId, "method" to compatibility.method,
            "supported" to compatibility.supported, "reasons" to compatibility.reasons,
            "frame_budget_ok" to compatibility.frameBudgetOk, "device_setup_supported" to setupSupported
        ))
        title.text = "${roleText(endpoint.role)} · ${endpoint.key}"
        preflightText.text = buildString {
            append(if (compatibility.supported) "SUPPORTED" else "UNSUPPORTED")
            append(" · method=${compatibility.method}")
            append(" · frame_budget_ok=${compatibility.frameBudgetOk}")
            append("\ndevice_setup_supported=$setupSupported")
            if (compatibility.reasons.isNotEmpty()) append("\n실패 사유: ${compatibility.reasons.joinToString(", ")}")
            append("\n약 45초 · ${profile.launchIterations}회 open · ${profile.observeMs / 1000}초 관측 · ${profile.stillCount}장")
        }
        // UNSUPPORTED never produces a run file (3.6): the profile is not lowered to fit the device.
        startButton.isEnabled = compatibility.supported
        progressText.text = ""
    }

    // ---- run ----

    private fun begin() {
        if (runner != null || endpoints.isEmpty()) return
        if (!hasPermission()) { requestCamera.launch(Manifest.permission.CAMERA); return }
        if (!compatibility.supported) return
        val endpoint = endpoints[selected]
        startButton.isEnabled = false
        cameraButton.isEnabled = false
        resultText.text = ""
        envStart.clear(); envStart += environment()
        thermal = ThermalTracker(this) { status ->
            recorder.record("run", "thermal_status", values = mapOf("status" to status))
        }.also { it.start() }

        val runId = BenchmarkReport.newRunId()
        val spec = StreamSpec(
            preview = ProfileCompatibilityChecker.size(profile.previewSize) ?: Size(1920, 1080),
            yuv = ProfileCompatibilityChecker.size(profile.yuvSize) ?: Size(1920, 1080),
            jpeg = ProfileCompatibilityChecker.size(profile.stillSize) ?: Size(1920, 1080),
            fpsRange = ProfileCompatibilityChecker.fpsRange(profile.fpsRange) ?: Range(30, 30)
        )
        val scheduler = object : BenchmarkRunner.Scheduler {
            override fun after(delayMs: Long, action: () -> Unit): Any {
                val r = Runnable { action() }; main.postDelayed(r, delayMs); return r
            }
            override fun cancel(token: Any) { main.removeCallbacks(token as Runnable) }
        }
        val driver = object : BenchmarkRunner.Driver {
            override fun open(endpoint: CameraEndpoint, session: String) {
                firstYuvSeen = false
                try {
                    engine = Camera2Engine(this@BenchmarkActivity, preview, endpoint.logicalCameraId, session, telemetry, spec) { _, _ -> }
                        .also { it.start() }
                } catch (e: Exception) {
                    // Without this the runner would only learn about the failure from the open timeout, five
                    // seconds later and with no reason recorded.
                    engine = null
                    recorder.record(session, "camera_error", values = mapOf("message" to e.toString(), "where" to "engine_start"))
                }
            }
            override fun still(session: String) { engine?.capture() }
            override fun close(session: String) {
                val e = engine; engine = null
                // Camera2Engine records "closed" too; a second CLOSED signal for the same session is ignored.
                if (e == null) runner?.signal(session, BenchmarkRunner.Signal.CLOSED)
                else e.close { runner?.signal(session, BenchmarkRunner.Signal.CLOSED) }
            }
        }
        val listener = object : BenchmarkRunner.Listener {
            override fun onProgress(phase: BenchmarkRunner.Phase, step: BenchmarkRunner.Step, iteration: Int, total: Int) {
                val n = phase.ordinal + 1
                val suffix = if (phase == BenchmarkRunner.Phase.CAMERA_OPEN && total > 0) "  ${iteration + 1}/$total" else ""
                progressText.text = "$n/6  ${phaseText(phase)}$suffix"
            }
            override fun onFinished(result: BenchmarkRunner.Result) { if (!destroyed) finishRun(result) }
        }
        runner = BenchmarkRunner(driver, scheduler, ::nowNs, profile, endpoint, runId, BenchmarkRunner.Config(), listener)
            .also { it.start() }
    }

    /** Maps Camera2Engine telemetry events to runner signals. Runs on the main thread. */
    private fun onEvent(e: Event) {
        val r = runner ?: return
        val s = e.session
        when (e.kind) {
            "open_call" -> r.mark(s, "open_call", e.atNs, override = true)
            "opened" -> r.signal(s, BenchmarkRunner.Signal.OPENED, e.atNs)
            "configure_requested" -> r.mark(s, "configure_call", e.atNs)
            "session_configured" -> r.signal(s, BenchmarkRunner.Signal.CONFIGURED, e.atNs)
            "repeating_submit" -> r.mark(s, "repeating_call", e.atNs)
            "capture_started" -> if (s == r.currentSession) r.firstStarted(s, e.atNs)
            // The engine records capture_submit right before CameraCaptureSession.capture(), which is the
            // submission time METRICS.md asks for, and the tag ties every still callback to its request.
            "capture_submit" -> (e.values["requestTag"] as? String)?.let { r.stillSubmitted(s, it, e.atNs) }
            "image_available" -> when (e.values["stream"]) {
                "still" -> r.stillImage(s, e.sensorNs, e.atNs)
                else -> if (!firstYuvSeen && s == r.currentSession) {
                    firstYuvSeen = true; r.signal(s, BenchmarkRunner.Signal.FIRST_FRAME, e.atNs)
                }
            }
            "capture_result" -> (e.values["requestTag"] as? String)?.takeIf { it.startsWith("still-") }
                ?.let { r.stillResult(s, it, e.sensorNs, e.atNs) }
            "closed" -> r.signal(s, BenchmarkRunner.Signal.CLOSED, e.atNs)
            "camera_error", "configure_failed", "capture_timeout" ->
                r.signal(s, BenchmarkRunner.Signal.ERROR, e.atNs, e.kind)
        }
    }

    private fun finishRun(result: BenchmarkRunner.Result) {
        runner = null
        val thermalEnd = thermal?.stop()
        val events = recorder.snapshot()
        val endEnv = environment()
        val env = RunEnv(
            thermalStart = thermal?.start, thermalMax = thermal?.max, thermalEnd = thermalEnd,
            batteryStart = envStart["battery_pct"] as? Int, batteryEnd = endEnv["battery_pct"] as? Int,
            charging = envStart["charging"] as? Boolean, powerSaveMode = envStart["power_save"] as? Boolean,
            rotation = envStart["rotation"] as? Int
        )
        thermal = null
        val context = RunAssembler.Context(
            exportedAtUtc = BenchmarkReport.utcNow(),
            device = deviceInfo(result.endpoint.logicalCameraId),
            app = appInfo(),
            subject = SubjectLabel(),
            env = env,
            compatibility = compatibility
        )
        val run = RunAssembler.assemble(result, events, profile, context)
        val file = try { BenchmarkReport(this).write(run, events) } catch (e: Exception) { null }
        lastFile = file
        startButton.isEnabled = true
        cameraButton.isEnabled = true
        progressText.text = if (result.aborted != null) "중단됨 (${result.aborted})" else "완료"
        resultText.text = buildString {
            append(file?.let { "run ${run.runId}\n${it.absolutePath}" } ?: "run JSON 저장에 실패했습니다")
            append("\nlaunch n=${result.validLaunchSamples}/${profile.expectedLaunchSamples}")
            append(" · still n=${result.validStillSamples}/${profile.expectedStillSamples}")
            append(" · flags=${run.validity.flags.joinToString(",").ifEmpty { "none" }}")
            append("\nmeasurement=${run.validity.measurementValid} comparison=${run.validity.comparisonEligible} scoring=${run.validity.scoringEligible}")
            result.hardFailure?.let { append("\nhard failure: $it") }
            append("\n\nTap to copy this summary. Long press to copy the JSON path.")
        }
    }

    // ---- environment and identity ----

    private fun environment(): Map<String, Any?> = mapOf(
        "battery_pct" to batteryPercent(),
        "charging" to getSystemService(BatteryManager::class.java)?.isCharging,
        "power_save" to getSystemService(PowerManager::class.java)?.isPowerSaveMode,
        "rotation" to rotation()
    )

    @Suppress("DEPRECATION")
    private fun rotation(): Int = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else windowManager.defaultDisplay.rotation

    private fun batteryPercent(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it > 0 }

    private fun deviceInfo(cameraId: String): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER, model = Build.MODEL, buildDisplay = Build.DISPLAY,
        buildIncremental = Build.VERSION.INCREMENTAL, fingerprint = Build.FINGERPRINT,
        vendorFingerprint = systemProperty("ro.vendor.build.fingerprint"),
        sdk = Build.VERSION.SDK_INT,
        securityPatch = if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else null,
        cameraInfoVersion = cameraInfoVersion(cameraId)
    )

    private fun appInfo(): AppInfo {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        return AppInfo(info.versionName ?: "", code)
    }

    private fun cameraInfoVersion(cameraId: String): String? = if (Build.VERSION.SDK_INT < 28) null else try {
        getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)[CameraCharacteristics.INFO_VERSION]
    } catch (_: Exception) { null }

    /** getprop through a subprocess: SystemProperties is not public API and the vendor fingerprint has no getter. */
    private fun systemProperty(key: String): String? = try {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readLine() }?.trim()
        process.waitFor()
        value?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /**
     * Makes a read-only text block copyable. The preflight verdict and the run summary are the two things worth
     * carrying to a PC (a run id, a file path, the flags of a run), and reading them off the screen by hand is
     * error-prone. A tap copies the whole block; a long press copies just the run JSON path when there is one.
     */
    private fun copyOnTap(view: android.widget.TextView, label: String): android.widget.TextView = view.apply {
        setOnClickListener { copy(label, text.toString()) }
        setOnLongClickListener {
            val path = lastFile?.absolutePath
            if (path == null) copy(label, text.toString()) else copy("$label path", path)
            true
        }
    }

    private fun copy(label: String, value: String) {
        if (value.isBlank()) return
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, value)) ?: return
        // Android 13 and above shows its own copy confirmation, so a second toast would just repeat it.
        if (Build.VERSION.SDK_INT < 33) {
            android.widget.Toast.makeText(this, "복사했습니다", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("benchmark", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "run JSON 공유"))
    }

    private fun phaseText(p: BenchmarkRunner.Phase) = when (p) {
        BenchmarkRunner.Phase.CAMERA_OPEN -> "Camera Open"
        BenchmarkRunner.Phase.FIRST_PREVIEW -> "First Preview"
        BenchmarkRunner.Phase.PREVIEW_STABILITY -> "Preview Stability"
        BenchmarkRunner.Phase.THREE_A -> "3A Response"
        BenchmarkRunner.Phase.STILL_CAPTURE -> "Still Capture"
        BenchmarkRunner.Phase.CAMERA_CLOSE -> "Camera Close"
    }

    private fun roleText(r: LensRole) = when (r) {
        LensRole.MAIN -> "후면 메인"; LensRole.ULTRA_WIDE -> "후면 초광각"; LensRole.TELE -> "후면 망원"
        LensRole.FRONT -> "전면"; LensRole.EXTERNAL -> "외부"; LensRole.UNKNOWN -> "기타"
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(v: Int) = Look.dp(this, v)
}
