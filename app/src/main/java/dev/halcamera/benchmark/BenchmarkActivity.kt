package dev.halcamera.benchmark

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.halcamera.camera.Camera2Engine
import dev.halcamera.camera.StreamSpec
import dev.halcamera.camera.CameraEndpoint
import dev.halcamera.camera.CameraEndpointResolver
import dev.halcamera.camera.LensRole
import dev.halcamera.camera.LensRoles
import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import dev.halcamera.telemetry.nowNs
import dev.halcamera.ui.Look
import java.io.File
import java.util.concurrent.Executors

/**
 * The BENCHMARK screen (docs/PLAN-BenchMarker-v0.3.md 8.2 - 8.4 and 7.3): the start card with the preflight
 * verdict and the subject label, the six-phase progress, the result table, and COMPARE against the run the
 * result is measured against.
 *
 * The activity owns the camera, the files and the clock; every layout and verdict rule lives in a pure object
 * ([StartCardPresenter], [ProgressPresenter], [ResultPresenter], [ComparePresenter], [RegressionDetector]) so
 * that what the screen says is testable without a device.
 */
class BenchmarkActivity : ComponentActivity() {

    private enum class Screen { CARD, RUNNING, RESULT, COMPARE }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = FlightRecorder(::nowNs, retentionNs = 180_000_000_000L, maxEvents = 60_000, preNs = 0, postNs = 0)
    private val telemetry = Telemetry(recorder)
    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V1

    // Writing a run file and reading the baseline and reference runs back are hundreds of kilobytes of JSON each;
    // doing that on the main thread would freeze the screen exactly when the result is supposed to appear.
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { BenchmarkStore(this) }
    private val report by lazy { BenchmarkReport(store) }
    private val baselines by lazy { BaselineManager(StoreRunCatalog(store, report)) }
    private val subjectPrefs by lazy { SubjectPrefs(this) }

    private lateinit var preview: TextureView
    private lateinit var content: LinearLayout
    private lateinit var actions: LinearLayout

    // Views the running screen updates in place instead of rebuilding on every frame.
    private var progressHeadline: TextView? = null
    private var progressBar: TextView? = null
    private var progressStats: TextView? = null
    private var buildInput: EditText? = null
    private var commitInput: EditText? = null
    private var noteInput: EditText? = null

    private var screen = Screen.CARD
    private var endpoints: List<CameraEndpoint> = emptyList()
    private var selected = 0
    private var compatibility: Compatibility = Compatibility.NOT_CHECKED
    private var deviceSetupSupported: Boolean? = null
    private var startCard: StartCard? = null
    private var cardError: String? = null
    private var engineName = StartCardPresenter.ENGINE_CAMERA2

    private var runner: BenchmarkRunner? = null
    private var engine: Camera2Engine? = null
    private var thermal: ThermalTracker? = null
    private val liveStats = LiveFrameStats()
    private var livePhase: BenchmarkRunner.Phase? = null
    private var ticker: Runnable? = null
    private var firstYuvSeen = false
    private var destroyed = false
    private val envStart = HashMap<String, Any?>()
    /** Read once when START is pressed: the fields describe the run that is starting, not whatever is on screen when it ends. */
    private var runSubject = SubjectLabel()
    /** What is currently typed into the card. Null until the fields are shown, when the last run's labels seed them. */
    private var draftSubject: SubjectLabel? = null

    private var lastRun: BenchmarkRun? = null
    private var lastFile: File? = null
    private var lastSummary: String = ""
    private var baseRun: BenchmarkRun? = null
    private var comparison: RunComparison? = null
    private var comparedTo: ComparedTo = ComparedTo.NONE
    private var isBaseline = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enumerate() else { cardError = "카메라 권한이 없어 벤치마크를 실행할 수 없습니다."; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engineName = intent.getStringExtra(EXTRA_ENGINE) ?: StartCardPresenter.ENGINE_CAMERA2

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(28))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(60, 0, 0, 0))
            )
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))

        // Short content sits at the bottom like the M2 card; a long result table scrolls instead of being cut off.
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        recorder.listener = { e -> main.post { if (!destroyed) onEvent(e) } }
        render()

        if (hasPermission()) enumerate() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onStop() { runner?.abort("background"); super.onStop() }

    override fun onDestroy() {
        destroyed = true
        recorder.listener = null
        ticker?.let { main.removeCallbacks(it) }
        thermal?.stop()
        io.shutdown()
        super.onDestroy()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---- camera selection and preflight ----

    private fun enumerate() {
        val manager = getSystemService(CameraManager::class.java)
        endpoints = LensRoles.checkOrder(CameraEndpointResolver(manager).resolve()).filter { it.independentlyOpenable }
        if (endpoints.isEmpty()) { cardError = "열 수 있는 카메라가 없습니다."; render(); return }
        // 8.1: the camera chosen on the LIVE screen is the benchmark subject.
        val wanted = intent.getStringExtra(EXTRA_CAMERA_ID)
        selected = endpoints.indexOfFirst { it.logicalCameraId == wanted }.takeIf { it >= 0 } ?: 0
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
        deviceSetupSupported = if (Build.VERSION.SDK_INT >= 35)
            runCatching { manager.isCameraDeviceSetupSupported(endpoint.logicalCameraId) }.getOrNull() else null
        recordPreflight(endpoint)
        cardError = null
        // A camera change is a new subject as far as the card is concerned, but the typed labels describe the
        // build under test, not the lens, so they survive it.
        captureDraft()
        refreshCard()
        screen = Screen.CARD
        render()
    }

    /**
     * Re-reads the conditions that can change while the card is open and rebuilds it. The preflight verdict is
     * kept: it describes the camera, not the moment.
     */
    private fun refreshCard() {
        val endpoint = endpoints.getOrNull(selected) ?: return
        startCard = StartCardPresenter.present(
            profile = profile,
            compatibility = compatibility,
            endpointName = roleText(endpoint.role),
            engineName = engineName,
            thermalStatus = currentThermalStatus(),
            powerSaveMode = getSystemService(PowerManager::class.java)?.isPowerSaveMode
        )
        // The card said Camera2 would be used, so the app is on Camera2 from here and says so only once.
        engineName = StartCardPresenter.ENGINE_CAMERA2
    }

    /** The preflight verdict belongs in every run file, so it is recorded again after the recorder is cleared. */
    private fun recordPreflight(endpoint: CameraEndpoint) {
        recorder.record("app", "preflight", values = mapOf(
            "cameraId" to endpoint.logicalCameraId, "method" to compatibility.method,
            "supported" to compatibility.supported, "reasons" to compatibility.reasons,
            "frame_budget_ok" to compatibility.frameBudgetOk, "device_setup_supported" to deviceSetupSupported
        ))
    }

    private fun currentThermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java)?.currentThermalStatus else null

    // ---- screens ----

    private fun render() {
        content.removeAllViews()
        actions.removeAllViews()
        when (screen) {
            Screen.CARD -> renderCard()
            Screen.RUNNING -> renderRunning()
            Screen.RESULT -> renderResult()
            Screen.COMPARE -> renderCompare()
        }
    }

    /** 8.2. */
    private fun renderCard() {
        progressHeadline = null; progressBar = null; progressStats = null
        val card = Look.card(this, dark = true)
        val state = startCard
        card.addView(Look.text(this, "STANDARD CAMERA BENCHMARK", 19, Look.onDark, bold = true))
        if (state == null) {
            card.addView(Look.text(this, cardError ?: "카메라를 확인하는 중입니다.", 13, Look.onDarkMuted), lp(top = 10))
            content.addView(card)
            actions.addView(Look.ghostButton(this, "닫기", dark = true) { finish() }, LinearLayout.LayoutParams(-1, dp(52)))
            return
        }
        card.addView(Look.text(this, state.titleLine, 13, Look.onDarkMuted), lp(top = 6))
        card.addView(Look.text(this, "${state.profileLine}\n${state.verdictLine}", 12, Look.onDarkMuted, mono = true), lp(top = 8))
        card.addView(Look.text(this, "${state.durationLine}\n${state.detailLine}", 13, Look.onDark), lp(top = 10))
        state.notices.forEach { card.addView(Look.text(this, "· $it", 12, Look.statusWarn), lp(top = 8)) }
        state.blockedReason?.let { card.addView(Look.text(this, it, 13, Look.statusFail, bold = true), lp(top = 10)) }

        // The fields stay while the device cools, so a label typed before the phone got hot is not lost.
        if (state.canStart || state.refreshable) {
            val draft = draftSubject ?: subjectPrefs.last()
            card.addView(Look.text(this, "Subject build (선택)", 11, Look.onDarkMuted), lp(top = 14))
            buildInput = input(draft.subjectBuildLabel).also { card.addView(it, lp(top = 4)) }
            card.addView(Look.text(this, "Subject commit (선택)", 11, Look.onDarkMuted), lp(top = 10))
            commitInput = input(draft.subjectCommit).also { card.addView(it, lp(top = 4)) }
            card.addView(Look.text(this, "Note (선택)", 11, Look.onDarkMuted), lp(top = 10))
            noteInput = input(draft.note).also { card.addView(it, lp(top = 4)) }
        } else {
            buildInput = null; commitInput = null; noteInput = null
        }
        content.addView(card)

        val row = Look.row(this)
        row.addView(Look.ghostButton(this, "카메라 변경", dark = true) { selectNext() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(Look.ghostButton(this, "닫기", dark = true) { finish() }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(8) })
        actions.addView(row)
        when {
            state.canStart ->
                actions.addView(Look.primaryButton(this, "START BENCHMARK") { begin() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
            // Without this a card opened at SEVERE never offers START again, however long the device rests.
            state.refreshable ->
                actions.addView(Look.primaryButton(this, "환경 다시 확인") { recheck() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        }
    }

    private fun recheck() {
        captureDraft()
        refreshCard()
        render()
    }

    /** Keeps what is typed across a re-render; the fields describe the build under test, not this card. */
    private fun captureDraft() {
        if (buildInput == null && commitInput == null && noteInput == null) return
        draftSubject = readSubject()
    }

    /** 8.3. */
    private fun renderRunning() {
        val card = Look.card(this, dark = true)
        card.addView(Look.text(this, "BENCHMARKING", 19, Look.onDark, bold = true))
        // The first phase is shown before the runner starts, so the card never appears blank for a frame.
        val first = ProgressPresenter.headline(BenchmarkRunner.Phase.CAMERA_OPEN, 0, profile.launchIterations)
        progressHeadline = Look.text(this, first, 14, Look.onDark, mono = true).also { card.addView(it, lp(top = 10)) }
        progressBar = Look.text(this, ProgressPresenter.barLine(0), 13, Look.primaryOnDark, mono = true)
            .also { it.maxLines = 1; card.addView(it, lp(top = 4)) }
        progressStats = Look.text(this, "", 12, Look.onDarkMuted, mono = true).also { card.addView(it, lp(top = 10)) }
        content.addView(card)
        actions.addView(Look.ghostButton(this, "중단", dark = true) { runner?.abort("user") }, LinearLayout.LayoutParams(-1, dp(52)))
        updateStats()
    }

    /** 8.4. */
    private fun renderResult() {
        progressHeadline = null; progressBar = null; progressStats = null
        val run = lastRun
        val card = Look.card(this, dark = true)
        if (run == null) {
            card.addView(Look.text(this, "CAMERA BENCHMARK", 19, Look.onDark, bold = true))
            card.addView(Look.text(this, lastSummary.ifBlank { "결과를 만들지 못했습니다." }, 12, Look.onDarkMuted, mono = true), lp(top = 10))
            content.addView(card)
            actions.addView(Look.primaryButton(this, "새 run") { preflight() }, LinearLayout.LayoutParams(-1, dp(56)))
            return
        }
        val view = ResultPresenter.present(run, comparison, comparedTo, isBaseline, deviceName(), roleText(run.endpoint.role))
        card.addView(wide(Look.text(this, view.render(), 11, Look.onDark, mono = true).also { copyOnTap(it, "result") }))
        card.addView(Look.text(this, lastSummary, 10, Look.onDarkMuted, mono = true), lp(top = 10))
        content.addView(card)

        // SET AS BASELINE is more than twice as long as the other two labels, and a third of the screen is not
        // enough for it once the pill padding is taken off: on a Galaxy S25+ it was drawn as "SET AS BASELI…"
        // and COMPARE was broken across two lines. So the long label gets a row of its own and the two short
        // ones share the row below it.
        actions.addView(
            action(view.baselineButton, view.baselineButtonEnabled) { toggleBaseline() },
            LinearLayout.LayoutParams(-1, dp(52))
        )
        val row = Look.row(this)
        row.addView(action("COMPARE", baseRun != null) { screen = Screen.COMPARE; render() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(
            action("EXPORT", lastFile != null) { lastFile?.let(::share) },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) }
        )
        actions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        actions.addView(Look.primaryButton(this, "새 run") { preflight() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
    }

    /** 7.3. */
    private fun renderCompare() {
        val run = lastRun
        val base = baseRun
        val cmp = comparison
        val card = Look.card(this, dark = true)
        if (run == null || base == null || cmp == null) {
            card.addView(Look.text(this, "비교할 run이 없습니다.", 13, Look.onDarkMuted), lp(top = 2))
        } else {
            val text = ComparePresenter.present(base, run, cmp, comparedTo, isBaseline).render()
            card.addView(wide(Look.text(this, text, 11, Look.onDark, mono = true).also { copyOnTap(it, "compare") }))
        }
        content.addView(card)
        actions.addView(Look.ghostButton(this, "결과로 돌아가기", dark = true) { screen = Screen.RESULT; render() }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    // ---- run ----

    private fun begin() {
        if (runner != null || endpoints.isEmpty()) return
        if (!hasPermission()) { requestCamera.launch(Manifest.permission.CAMERA); return }
        captureDraft()
        // The card is a snapshot taken when it was opened, and the device can heat up or enter power save while
        // the subject fields are being typed. ThermalTracker only records once the run is under way, so without
        // this a run would start at SEVERE and be thrown away afterwards.
        refreshCard()
        if (startCard?.canStart != true) { render(); return }
        val endpoint = endpoints[selected]
        runSubject = readSubject()
        subjectPrefs.save(runSubject)

        // Each run file carries only its own events. The recorder keeps 180 s, which is long enough for two runs.
        recorder.clear()
        recordPreflight(endpoint)
        envStart.clear(); envStart += environment()
        liveStats.reset()
        livePhase = null
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
                // The observation session starts here, so the live numbers describe it rather than the ten
                // launch cycles that came before and are measured separately.
                if (phase != livePhase && phase == BenchmarkRunner.Phase.FIRST_PREVIEW) liveStats.reset()
                livePhase = phase
                progressHeadline?.text = ProgressPresenter.headline(phase, iteration, total)
                progressBar?.text = ProgressPresenter.barLine(ProgressPresenter.percent(phase, iteration, total))
            }
            override fun onFinished(result: BenchmarkRunner.Result) { if (!destroyed) finishRun(result) }
        }
        screen = Screen.RUNNING
        render()
        startTicker()
        runner = BenchmarkRunner(driver, scheduler, ::nowNs, profile, endpoint, runId, BenchmarkRunner.Config(), listener)
            .also { it.start() }
    }

    private fun readSubject(): SubjectLabel {
        val previous = subjectPrefs.last()
        fun value(field: EditText?) = field?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return SubjectLabel(
            subjectBuildLabel = value(buildInput),
            subjectCommit = value(commitInput),
            subjectBranch = previous.subjectBranch,
            note = value(noteInput)
        )
    }

    /** Sorting the live intervals once per frame would be wasted work, so the numbers refresh on a timer (8.3). */
    private fun startTicker() {
        ticker?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (destroyed || screen != Screen.RUNNING) return
                updateStats()
                main.postDelayed(this, STATS_INTERVAL_MS)
            }
        }
        ticker = r
        main.postDelayed(r, STATS_INTERVAL_MS)
    }

    private fun updateStats() {
        progressStats?.text = ProgressPresenter.statLines(liveStats.snapshot(), thermal?.current)
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
                else -> {
                    if (s == r.currentSession) liveStats.frame(e.sensorNs)
                    if (!firstYuvSeen && s == r.currentSession) {
                        firstYuvSeen = true; r.signal(s, BenchmarkRunner.Signal.FIRST_FRAME, e.atNs)
                    }
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
        ticker?.let { main.removeCallbacks(it) }
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
            subject = runSubject,
            env = env,
            compatibility = compatibility
        )
        // Assembling, writing and then reading the baseline back is far too much work for the main thread; the
        // screen shows the progress card until the result is ready.
        io.execute {
            val run = RunAssembler.assemble(result, events, profile, context)
            val file = try { report.write(run, events) } catch (e: Exception) { null }
            val baseline = baselines.baselineRun(run)?.takeIf { it.runId != run.runId }
            val base = baseline ?: baselines.reference(run)
            val to = when {
                baseline != null -> ComparedTo.BASELINE
                base != null -> ComparedTo.PREVIOUS
                else -> ComparedTo.NONE
            }
            val cmp = RegressionDetector.compare(base, run)
            val onBaseline = baselines.isBaseline(run)
            main.post {
                if (destroyed) return@post
                lastRun = run; lastFile = file; baseRun = base; comparedTo = to; comparison = cmp; isBaseline = onBaseline
                lastSummary = summary(run, result, file)
                screen = Screen.RESULT
                render()
            }
        }
    }

    /** The measurement facts the result table does not show: sample counts, flags and the file the run went to. */
    private fun summary(run: BenchmarkRun, result: BenchmarkRunner.Result, file: File?): String = buildString {
        append(file?.absolutePath ?: "run JSON 저장에 실패했습니다")
        append("\nlaunch n=${result.validLaunchSamples}/${profile.expectedLaunchSamples}")
        append(" · still n=${result.validStillSamples}/${profile.expectedStillSamples}")
        append(" · flags=${run.validity.flags.joinToString(",").ifEmpty { "none" }}")
        result.aborted?.let { append("\n중단됨 ($it)") }
        result.hardFailure?.let { append("\nhard failure: $it") }
    }

    /**
     * `SET AS BASELINE` / `CLEAR BASELINE` (7.1). The pointer is written and the comparison recomputed on the
     * io thread, because clearing a baseline changes which run the result is measured against and that means
     * reading the reference run from disk.
     */
    private fun toggleBaseline() {
        val run = lastRun ?: return
        io.execute {
            baselines.toggle(run)
            val baseline = baselines.baselineRun(run)?.takeIf { it.runId != run.runId }
            val base = baseline ?: baselines.reference(run)
            val to = when {
                baseline != null -> ComparedTo.BASELINE
                base != null -> ComparedTo.PREVIOUS
                else -> ComparedTo.NONE
            }
            val cmp = RegressionDetector.compare(base, run)
            val onBaseline = baselines.isBaseline(run)
            main.post {
                if (destroyed) return@post
                baseRun = base; comparedTo = to; comparison = cmp; isBaseline = onBaseline
                if (screen == Screen.COMPARE && base == null) screen = Screen.RESULT
                render()
            }
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

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

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
        // Without this every run stores app.debuggable = null, which drops DEBUGGABLE_BUILD from the validity
        // flags and leaves BuildIdentity.sameAppBuild permanently unknown.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return AppInfo(info.versionName ?: "", code, debuggable)
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

    // ---- small view helpers ----

    private fun input(initial: String?): EditText = EditText(this).apply {
        setText(initial.orEmpty())
        setTextColor(Look.onDark)
        setHintTextColor(Look.onDarkMuted)
        textSize = 14f
        typeface = Look.mono
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = Look.cardBackground(this@BenchmarkActivity, Look.expertTile, Look.expertTile3)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /** The result and compare tables are laid out in fixed monospace columns, so they scroll sideways rather than wrap. */
    /** A ghost button that shows whether it can be pressed, since three of the result actions depend on state. */
    private fun action(label: String, enabled: Boolean, onClick: () -> Unit) =
        Look.ghostButton(this, label, dark = true) { onClick() }.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
        }

    private fun wide(view: View): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(view, LinearLayout.LayoutParams(-2, -2))
    }

    /**
     * Makes a read-only text block copyable. A run id, a file path and the result table are the things worth
     * carrying to a PC, and reading them off the screen by hand is error-prone. A tap copies the whole block; a
     * long press copies just the run JSON path when there is one.
     */
    private fun copyOnTap(view: TextView, label: String): TextView = view.apply {
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

    private fun roleText(r: LensRole) = when (r) {
        LensRole.MAIN -> "후면 메인"; LensRole.ULTRA_WIDE -> "후면 초광각"; LensRole.TELE -> "후면 망원"
        LensRole.FRONT -> "전면"; LensRole.EXTERNAL -> "외부"; LensRole.UNKNOWN -> "기타"
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(v: Int) = Look.dp(this, v)

    companion object {
        /** The engine and camera the LIVE screen was showing (8.1); the benchmark measures what the user was looking at. */
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_CAMERA_ID = "camera_id"
        private const val STATS_INTERVAL_MS = 250L
    }
}
