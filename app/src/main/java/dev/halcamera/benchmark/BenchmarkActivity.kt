package dev.halcamera.benchmark

import dev.halcamera.ui.MetricRows

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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.halcamera.R
import dev.halcamera.benchmark.domain.*
import dev.halcamera.benchmark.platform.*
import dev.halcamera.camera.Camera2Engine
import dev.halcamera.camera.StreamSpec
import dev.halcamera.camera.CameraEndpoint
import dev.halcamera.camera.CameraEndpointResolver
import dev.halcamera.camera.LensRole
import dev.halcamera.camera.LensRoles
import dev.halcamera.cli.BenchmarkController
import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import dev.halcamera.telemetry.nowNs
import dev.halcamera.ui.DeltaBarView
import dev.halcamera.ui.IconButton
import dev.halcamera.ui.Look
import dev.halcamera.ui.MeterView
import dev.halcamera.ui.showSelectionPopup
import java.io.File
import java.util.concurrent.Executors

/**
 * Benchmark setup, progress, results and comparison. The activity owns camera and file I/O;
 * pure presenters supply measurement values and verdicts, while [MetricRows] lays out wrapping rows.
 */
class BenchmarkActivity : ComponentActivity() {
    private val cli by lazy { dev.halcamera.cli.CommandCoordinator.get(this) }
    private val benchmarkCli by lazy {
        BenchmarkController(cli, object : BenchmarkController.Driver {
            override fun busy() = screen == Screen.RUNNING || runner != null || historyLoading
            override fun begin(camera: String): Boolean {
                val index = endpoints.indexOfFirst { it.logicalCameraId == camera && it.physicalCameraId == null }
                if (index < 0) return false
                selected = index; preflight()
                if (startCard?.canStart != true) return false
                begin(fromCli = true)
                return runner != null
            }
            override fun abort() { runner?.abort("cli") }
        })
    }

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
    private val settings by lazy { BenchmarkPrefs(this) }

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
    /** Re-runs preflight while the card is blocked on heat, so START returns by itself once the device cools. */
    private var cardRecheck: Runnable? = null
    private var firstYuvSeen = false
    private var destroyed = false
    private val envStart = HashMap<String, Any?>()
    /** Read once when START is pressed: the fields describe the run that is starting, not whatever is on screen when it ends. */
    private var runSubject = SubjectLabel()
    /** What is currently typed into the card. Null until the fields are shown, when the last run's labels seed them. */
    private var draftSubject: SubjectLabel? = null
    private var labelsExpanded = false

    private var lastRun: BenchmarkRun? = null
    private var lastFile: File? = null
    private var lastSummary: String = ""
    private var baseRun: BenchmarkRun? = null
    private var comparison: RunComparison? = null
    private var comparedTo: ComparedTo = ComparedTo.NONE
    private var isBaseline = false
    private var historyLoading = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enumerate() else { cardError = "카메라 권한이 없어 벤치마크를 실행할 수 없습니다."; render() }
    }

    private val openHistory = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (screen == Screen.RESULT && lastFile != null) lastRun?.let { loadHistoryRun(it.runId) }
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == Screen.COMPARE) { screen = Screen.RESULT; render() } else finish()
            }
        })
        intent.getStringExtra(EXTRA_RUN_ID)?.let { loadHistoryRun(it); return }

        if (hasPermission()) enumerate() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() {
        super.onStart()
        if (intent.getStringExtra("cli_request_id") == cli.active?.id && cli.active != null) cli.continueHandover(benchmarkCli)
        else cli.attach(benchmarkCli)
    }

    override fun onStop() { cli.detach(benchmarkCli); runner?.abort("background"); super.onStop() }

    override fun onDestroy() {
        destroyed = true
        recorder.listener = null
        ticker?.let { main.removeCallbacks(it) }
        cardRecheck?.let { main.removeCallbacks(it) }
        thermal?.stop()
        if (runner == null) io.shutdown()
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

    private fun selectCamera(anchor: View) {
        if (cli.active != null) return
        if (endpoints.isEmpty() || runner != null) return
        showSelectionPopup(anchor, endpoints.map { "${roleText(it.role)} · ID ${it.logicalCameraId}" }, selected) { index ->
            if (runner == null && selected != index) { selected = index; preflight() }
        }
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
        cli.setUiBusy(benchmarkCli, screen == Screen.RUNNING || historyLoading)
        content.removeAllViews()
        actions.removeAllViews()
        when (screen) {
            Screen.CARD -> renderCard()
            Screen.RUNNING -> renderRunning()
            Screen.RESULT -> renderResult()
            Screen.COMPARE -> renderCompare()
        }
        if (screen == Screen.CARD) {
            val row = Look.row(this)
            row.addView(Look.ghostButton(this, "History", dark = true) { openHistoryScreen() },
                LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(Look.ghostButton(this, "Settings", dark = true) {}.apply {
                setOnClickListener { openSettings(it) }
                contentDescription = "벤치마크 설정, profiling data 한도 ${RunRetention.label(settings.runLimit)}"
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            actions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        if (screen == Screen.RESULT) {
            actions.addView(Look.ghostButton(this, "History", dark = true) { openHistoryScreen() },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        }
    }

    private fun openHistoryScreen() {
        if (intent.hasExtra(EXTRA_RUN_ID)) finish() else {
            captureDraft()
            openHistory.launch(Intent(this, HistoryActivity::class.java)
                .putExtra("profile", profile.id)
                .putExtra("endpoint", endpoints.getOrNull(selected)?.key))
        }
    }

    /** Settings → the profiling data limit. Applying a smaller limit prunes immediately, keeping every baseline. */
    private fun openSettings(anchor: View) {
        val options = RunRetention.OPTIONS
        val labels = options.map { "Profiling data limit · ${RunRetention.label(it)}" }
        showSelectionPopup(anchor, labels, options.indexOf(settings.runLimit).coerceAtLeast(0)) { index ->
            settings.runLimit = options[index]
            io.execute {
                val deleted = prune()
                main.post {
                    if (destroyed) return@post
                    val suffix = if (deleted == 0) "" else " · 오래된 run ${deleted}개 삭제"
                    android.widget.Toast.makeText(
                        this, "Profiling data limit: ${RunRetention.label(settings.runLimit)}$suffix",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    render()
                }
            }
        }
    }

    /** Deletes over-limit run files, oldest first, never a baseline. Runs on the io thread. */
    private fun prune(): Int {
        val limit = settings.runLimit
        if (limit == RunRetention.UNLIMITED) return 0
        val protected = store.index().baselines.values.toSet()
        val doomed = RunRetention.toDelete(store.files().map { it.nameWithoutExtension }, protected, limit)
        return doomed.count { store.deleteRun(it) }
    }

    private fun loadHistoryRun(id: String) {
        val previousSummary = lastSummary.takeIf { lastRun?.runId == id }
        lastRun = null; lastFile = null
        historyLoading = true
        screen = Screen.RESULT
        lastSummary = "실행 기록을 읽는 중입니다."
        render()
        io.execute {
            val result = runCatching {
                val run = report.read(store.file(id)) ?: error(report.lastReadError ?: "실행을 읽을 수 없습니다.")
                require(run.runId == id) { "실행 ID와 파일명이 다릅니다." }
                val baseline = baselines.baselineRun(run)?.takeIf { it.runId != run.runId }
                val base = baseline ?: baselines.reference(run)
                val to = if (baseline != null) ComparedTo.BASELINE else if (base != null) ComparedTo.PREVIOUS else ComparedTo.NONE
                val cmp = RegressionDetector.compare(base, run)
                val onBaseline = baselines.isBaseline(run)
                main.post {
                    if (destroyed) return@post
                    historyLoading = false
                    lastRun = run; lastFile = store.file(id); baseRun = base
                    comparedTo = to; comparison = cmp; isBaseline = onBaseline
                    lastSummary = previousSummary ?: "${run.runId}\n${run.validity.flags.joinToString(" · ")}"
                    render()
                }
            }
            result.exceptionOrNull()?.let { error -> main.post {
                if (!destroyed) { historyLoading = false; lastSummary = error.message ?: "실행을 읽을 수 없습니다."; render() }
            } }
        }
    }

    /** 8.2. */
    private fun renderCard() {
        progressHeadline = null; progressBar = null; progressStats = null
        cardRecheck?.let { main.removeCallbacks(it) }; cardRecheck = null
        val card = Look.card(this, dark = true)
        val state = startCard
        card.addView(Look.text(this, "Benchmark", 19, Look.onDark, bold = true))
        if (state == null) {
            card.addView(Look.text(this, cardError ?: "카메라를 확인하는 중입니다.", 13, Look.onDarkMuted), lp(top = 10))
            content.addView(card)
            actions.addView(IconButton(this, R.drawable.ic_action_close, "벤치마크 닫기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            return
        }
        card.addView(Look.text(this, state.titleLine, 13, Look.onDarkMuted), lp(top = 6))
        // What the feature does and what the run needs, before any jargon: the profile id and the preflight
        // verdict move into the Details fold below.
        card.addView(Look.text(this, "카메라를 벤치마킹합니다.", 15, Look.onDark, bold = true), lp(top = 12))
        card.addView(Look.text(this, "${state.durationLine}\n${state.detailLine}", 13, Look.onDarkMuted), lp(top = 4))
        card.addView(statusChips(), lp(top = 14))
        state.notices.forEach { card.addView(Look.text(this, "· $it", 12, Look.statusWarn), lp(top = 8)) }
        state.blockedReason?.let { card.addView(Look.text(this, it, 13, Look.statusFail, bold = true), lp(top = 10)) }
        card.addView(Look.disclosure(this, "Details",
            Look.text(this, "${state.profileLine}\n${state.verdictLine}", 12, Look.onDarkMuted, mono = true)), lp(top = 10))

        // The fields stay while the device cools, so a label typed before the phone got hot is not lost.
        if (state.canStart || state.refreshable) {
            val draft = draftSubject ?: subjectPrefs.last()
            val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val hasLabels = listOf(draft.subjectBuildLabel, draft.subjectCommit, draft.note).any { !it.isNullOrBlank() }
            val savedLabels = Look.text(this, "", 12, Look.onDarkMuted)
            val toggle = Look.ghostButton(this, "", dark = true) {}.apply {
                fun updateLabel() { text = if (labelsExpanded) "Hide build info ▴" else if (hasLabels) "Edit build info ▾" else "Add build info (optional) ▾" }
                updateLabel()
                setOnClickListener {
                    labelsExpanded = !labelsExpanded
                    fields.visibility = if (labelsExpanded) View.VISIBLE else View.GONE
                    savedLabels.text = listOfNotNull(buildInput?.text?.toString()?.takeIf { it.isNotBlank() }, commitInput?.text?.toString()?.takeIf { it.isNotBlank() }, noteInput?.text?.toString()?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    savedLabels.visibility = if (!labelsExpanded && savedLabels.text.isNotBlank()) View.VISIBLE else View.GONE
                    updateLabel()
                }
            }
            card.addView(toggle, lp(top = 14))
            savedLabels.text = listOfNotNull(draft.subjectBuildLabel, draft.subjectCommit, draft.note).filter { it.isNotBlank() }.joinToString(" · ")
            savedLabels.visibility = if (!labelsExpanded && hasLabels) View.VISIBLE else View.GONE
            card.addView(savedLabels, lp(top = 4))
            fields.addView(Look.text(this, "Build", 12, Look.onDarkMuted), lp(top = 10))
            buildInput = input(draft.subjectBuildLabel).also { it.contentDescription = "측정 대상 빌드"; fields.addView(it, lp(top = 4)) }
            fields.addView(Look.text(this, "Commit", 12, Look.onDarkMuted), lp(top = 10))
            commitInput = input(draft.subjectCommit).also { it.contentDescription = "측정 대상 커밋"; fields.addView(it, lp(top = 4)) }
            fields.addView(Look.text(this, "Note", 12, Look.onDarkMuted), lp(top = 10))
            noteInput = input(draft.note).also { it.contentDescription = "실행 메모"; fields.addView(it, lp(top = 4)) }
            fields.visibility = if (labelsExpanded) View.VISIBLE else View.GONE
            card.addView(fields)
        } else {
            buildInput = null; commitInput = null; noteInput = null
        }
        content.addView(card)

        val row = Look.row(this)
        val cameraLabel = endpoints.getOrNull(selected)?.let { "${roleText(it.role)} · ID ${it.logicalCameraId} ▾" } ?: "카메라 없음"
        row.addView(Look.ghostButton(this, cameraLabel, dark = true) {}.apply {
            setOnClickListener { selectCamera(it) }
            isEnabled = endpoints.isNotEmpty()
            contentDescription = "벤치마크 카메라 선택, 현재 $cameraLabel"
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(IconButton(this, R.drawable.ic_action_close, "벤치마크 닫기") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        actions.addView(row)
        if (state.canStart) {
            actions.addView(Look.primaryButton(this, "Start benchmark") { begin() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        } else if (state.refreshable) {
            // A card opened at SEVERE re-checks itself, so START returns without anyone tapping a button while
            // the device rests. The recheck stops the moment the card is replaced or the screen changes.
            val r = Runnable {
                if (!destroyed && screen == Screen.CARD && runner == null) { captureDraft(); refreshCard(); render() }
            }
            cardRecheck = r
            main.postDelayed(r, RECHECK_INTERVAL_MS)
        }
    }

    /** The thermal and battery state at a glance, in the same pill shape the LIVE screen uses for status. */
    private fun statusChips(): LinearLayout {
        val row = Look.row(this)
        fun chip(label: String, color: Int) = Look.text(this, label, 12, color, bold = true).apply {
            background = Look.cardBackground(this@BenchmarkActivity, Look.expertTile2, Look.expertTile3)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val thermal = currentThermalStatus()
        if (thermal != null) {
            val (label, color) = when {
                thermal >= StartCardPresenter.THERMAL_SEVERE -> "Thermal severe" to Look.statusFail
                thermal >= ValidityFlags.THERMAL_MODERATE -> "Thermal moderate" to Look.statusWarn
                thermal >= 1 -> "Thermal light" to Look.statusWarn
                else -> "Thermal OK" to Look.statusPass
            }
            row.addView(chip(label, color))
        }
        batteryPercent()?.let {
            row.addView(chip("Battery $it%", Look.onDarkMuted),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (row.childCount > 0) dp(8) else 0 })
        }
        return row
    }

    /** Keeps what is typed across a re-render; the fields describe the build under test, not this card. */
    private fun captureDraft() {
        if (buildInput == null && commitInput == null && noteInput == null) return
        draftSubject = readSubject()
    }

    /** 8.3. */
    private fun renderRunning() {
        val card = Look.card(this, dark = true)
        card.addView(Look.text(this, "Benchmarking", 19, Look.onDark, bold = true))
        // The first phase is shown before the runner starts, so the card never appears blank for a frame.
        val first = ProgressPresenter.headline(BenchmarkRunner.Phase.CAMERA_OPEN, 0, profile.launchIterations)
        progressHeadline = Look.text(this, first, 14, Look.onDark, mono = true).also { card.addView(it, lp(top = 10)) }
        progressBar = Look.text(this, ProgressPresenter.barLine(0), 13, Look.primaryOnDark, mono = true)
            .also { it.maxLines = 1; card.addView(it, lp(top = 4)) }
        progressStats = Look.text(this, "", 12, Look.onDarkMuted, mono = true).also { card.addView(it, lp(top = 10)) }
        content.addView(card)
        actions.addView(Look.ghostButton(this, "Abort", dark = true) { runner?.abort("user") }, LinearLayout.LayoutParams(-1, dp(52)))
        updateStats()
    }

    /** 8.4. The verdict leads, four key metrics follow with bars, and everything else folds (mockup v7). */
    private fun renderResult() {
        progressHeadline = null; progressBar = null; progressStats = null
        val run = lastRun
        if (run == null) {
            val card = Look.card(this, dark = true)
            card.addView(Look.text(this, "Benchmark result", 19, Look.onDark, bold = true))
            card.addView(Look.text(this, lastSummary.ifBlank { "결과를 만들지 못했습니다." }, 12, Look.onDarkMuted, mono = true), lp(top = 10))
            content.addView(card)
            if (!intent.hasExtra(EXTRA_RUN_ID) && !historyLoading) actions.addView(Look.primaryButton(this, "New run") { preflight() }, LinearLayout.LayoutParams(-1, dp(56)))
            return
        }
        val endpointName = roleText(run.endpoint.role)
        val view = ResultPresenter.present(run, comparison, comparedTo, isBaseline, "${run.device.manufacturer} ${run.device.model}", endpointName)

        // Headline card: the verdict, what it was measured against, and the score.
        val head = ResultPresenter.headline(run, comparison, comparedTo, isBaseline, endpointName)
        val card = Look.card(this, dark = true)
        val headColor = when (head.tone) {
            Tone.BAD -> Look.statusFail
            Tone.GOOD -> Look.statusPass
            Tone.NEUTRAL -> Look.onDark
        }
        card.addView(Look.text(this, head.text, 21, headColor, bold = true))
        card.addView(Look.text(this, head.sub, 13, Look.onDarkMuted), lp(top = 6))
        view.conditionLine?.let { card.addView(Look.text(this, it, 13, Look.statusWarn), lp(top = 8)) }
        ResultPresenter.scoreValue(run)?.let { total ->
            val scoreRow = Look.row(this)
            scoreRow.addView(Look.text(this, total.toString(), 30, Look.onDark, bold = true, mono = true))
            scoreRow.addView(Look.text(this, "/ 1000 · Camera Endpoint Score · internal draft", 12, Look.onDarkMuted),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            card.addView(scoreRow, lp(top = 12))
        }
        content.addView(card)

        // Metrics card: the four key bars, any degraded row the bars do not carry, then the folds.
        val metricsCard = Look.card(this, dark = true)
        val keys = ResultPresenter.keyMetrics(run, comparison, comparedTo)
        keys.forEachIndexed { i, k ->
            val top = Look.row(this)
            val label = if (k.statLabel.isBlank()) k.label else "${k.label} · ${k.statLabel}"
            top.addView(Look.text(this, label, 13, Look.onDark), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(Look.text(this, k.valueText, 15, if (k.tone == Tone.BAD) Look.statusFail else Look.onDark, bold = true, mono = true))
            k.deltaText?.let {
                val deltaColor = when (k.tone) {
                    Tone.BAD -> Look.statusFail
                    Tone.GOOD -> Look.primaryOnDark
                    Tone.NEUTRAL -> Look.onDarkMuted
                }
                top.addView(Look.text(this, it, 12, deltaColor, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }
            metricsCard.addView(top, lp(top = if (i == 0) 0 else 14))
            metricsCard.addView(MeterView(this, k.fraction.toFloat(), k.baseFraction?.toFloat(), k.tone == Tone.BAD), lp(top = 6))
        }
        if (keys.any { it.baseFraction != null }) {
            val tickName = if (comparedTo == ComparedTo.BASELINE) "baseline" else "이전 run"
            metricsCard.addView(Look.text(this, "막대 = 이번 run · 눈금 = $tickName", 11, Look.onDarkMuted), lp(top = 10))
        }
        val regressed = view.sections.flatMap { it.rows }.filter { it.marker == "▲" }
        regressed.forEach { metricsCard.addView(MetricRows.result(this, it)) }
        val allMetrics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        view.sections.forEach { section ->
            allMetrics.addView(Look.text(this, section.title, 15, Look.onDarkMuted, bold = true), lp(top = 12))
            section.rows.forEach { allMetrics.addView(MetricRows.result(this, it)) }
        }
        view.threeALine?.let { allMetrics.addView(Look.text(this, it, 13, Look.onDarkMuted), lp(top = 8)) }
        metricsCard.addView(Look.disclosure(this, "All metrics", allMetrics), lp(top = 12))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOfNotNull(view.titleLine, view.subLine, view.eligibilityLine, view.identityLine, view.hint, view.scoreLine, lastSummary).forEach {
            details.addView(Look.text(this, it, 12, Look.onDarkMuted), lp(top = 8))
        }
        metricsCard.addView(Look.disclosure(this, "Run info · flags · file", details), lp(top = 4))
        metricsCard.addView(Look.ghostButton(this, "Copy result", dark = true) {}.also { copyOnTap(it, "result", view.render()) }, lp(top = 8))
        content.addView(metricsCard, lp(top = 10))

        // Give the longer baseline action a full row to avoid truncation.
        actions.addView(
            action(view.baselineButton, view.baselineButtonEnabled) { toggleBaseline() },
            LinearLayout.LayoutParams(-1, dp(52))
        )
        val row = Look.row(this)
        row.addView(action("Compare", baseRun != null) { screen = Screen.COMPARE; render() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(
            action("Export", lastFile != null) { lastFile?.let(::share) },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) }
        )
        actions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (!intent.hasExtra(EXTRA_RUN_ID) && !historyLoading) actions.addView(Look.primaryButton(this, "New run") { preflight() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
    }

    /** 7.3. A delta chart around a zero line first; rows a chart cannot carry stay as text below it. */
    private fun renderCompare() {
        val run = lastRun
        val base = baseRun
        val cmp = comparison
        val card = Look.card(this, dark = true)
        if (run == null || base == null || cmp == null) {
            card.addView(Look.text(this, "비교할 run이 없습니다.", 13, Look.onDarkMuted), lp(top = 2))
        } else {
            val view = ComparePresenter.present(base, run, cmp, comparedTo, isBaseline)
            card.addView(Look.text(this, "Delta vs ${view.baseHeader.lowercase()}", 19, Look.onDark, bold = true))
            view.referenceNote?.let { card.addView(Look.text(this, it, 13, Look.onDarkMuted), lp(top = 6)) }
            val regressed = view.rows.filter { it.marker.startsWith("▲") }
            if (regressed.isNotEmpty()) {
                card.addView(Look.text(this, "▲ ${regressed.size} degraded", 17, Look.statusFail, bold = true), lp(top = 10))
            }
            view.conditionLine?.let { card.addView(Look.text(this, it, 13, Look.statusWarn), lp(top = 8)) }

            // The chart: one shared percentage scale, degraded grows right, improved grows left.
            val charted = view.rows.filter { it.deltaPct != null }
            if (charted.isNotEmpty()) {
                card.addView(Look.text(this, "◀ Improved · Degraded ▶", 11, Look.onDarkMuted), lp(top = 12))
                val maxPct = charted.maxOf { kotlin.math.abs(it.deltaPct!!) }.coerceAtLeast(1.0)
                charted.forEach { row ->
                    val line = Look.row(this)
                    val degraded = row.marker.startsWith("▲")
                    val valueColor = when {
                        degraded -> Look.statusFail
                        row.marker.startsWith("▼") -> Look.primaryOnDark
                        else -> Look.onDarkMuted
                    }
                    line.addView(Look.text(this, row.label, 12, Look.onDarkMuted),
                        LinearLayout.LayoutParams(dp(96), -2))
                    line.addView(DeltaBarView(this, row.deltaPct!!.toFloat(), maxPct.toFloat(), degraded),
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(8) })
                    line.addView(Look.text(this, row.delta, 12, valueColor, bold = true, mono = true).apply {
                        gravity = Gravity.END
                    }, LinearLayout.LayoutParams(dp(56), -2))
                    card.addView(line, lp(top = 8))
                }
            }

            // Rows without a percentage (counts, unit changes, unknowns) keep their textual form.
            view.rows.filter { it.deltaPct == null }.forEach { card.addView(MetricRows.comparison(this, it, view.baseHeader)) }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            listOfNotNull(view.baseLine, view.currentLine, view.identityLine).forEach {
                details.addView(Look.text(this, it.trim().replace(Regex(" {2,}"), " · "), 12, Look.onDarkMuted), lp(top = 8))
            }
            card.addView(Look.disclosure(this, "Run info", details), lp(top = 10))
            card.addView(Look.ghostButton(this, "Copy comparison", dark = true) {}.also { copyOnTap(it, "compare", view.render()) }, lp(top = 8))
        }
        content.addView(card)
        actions.addView(IconButton(this, R.drawable.ic_action_back, "벤치마크 결과로 돌아가기") { screen = Screen.RESULT; render() }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    // ---- run ----

    private fun begin(fromCli: Boolean = false) {
        if (!fromCli && cli.active != null) return
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
            override fun onFinished(result: BenchmarkRunner.Result) { finishRun(result) }
        }
        if (!benchmarkCli.started()) { thermal?.stop(); thermal = null; return }
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
            deviceInstanceId = DeviceInstance.id(this),
            app = appInfo(),
            subject = runSubject,
            env = env,
            compatibility = compatibility
        )
        // Assembling, writing and then reading the baseline back is far too much work for the main thread; the
        // screen shows the progress card until the result is ready.
        io.execute {
            try {
                val run = RunAssembler.assemble(result, events, profile, context)
                val file = try { report.write(run, events) } catch (e: Exception) { null }
                // The limit is applied right after the new file lands, so the store never grows past it by more
                // than the run that was just measured.
                if (file != null) prune()
                benchmarkCli.reportSaved(
                    runId = run.runId, aborted = result.aborted, hardFailure = result.hardFailure,
                    schemaVersion = BenchmarkReportCodec.SCHEMA_VERSION, file = file
                )
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
            } catch (error: Exception) {
                benchmarkCli.saveFailed(error.message ?: "Cannot assemble or save report")
                main.post {
                    if (!destroyed) {
                        lastRun = null; lastFile = null
                        lastSummary = error.message ?: "run JSON 저장에 실패했습니다"
                        screen = Screen.RESULT; render()
                    }
                }
            } finally {
                if (destroyed) io.shutdown()
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
            try {
                store.index()
                check(store.lastIndexError == null) { "Baseline 파일을 읽을 수 없어 변경할 수 없습니다." }
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
            } catch (e: Exception) {
                main.post {
                    if (!destroyed) android.widget.Toast.makeText(this, e.message ?: "Baseline 변경에 실패했습니다.", android.widget.Toast.LENGTH_LONG).show()
                }
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
        minimumHeight = dp(48)
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

    /**
     * Makes a read-only text block copyable. A run id, a file path and the result table are the things worth
     * carrying to a PC, and reading them off the screen by hand is error-prone. A tap copies the whole block; a
     * long press copies just the run JSON path when there is one.
     */
    private fun copyOnTap(view: TextView, label: String, fullText: String? = null): TextView = view.apply {
        setOnClickListener { copy(label, fullText ?: text.toString()) }
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
        LensRole.MAIN -> "Rear main"; LensRole.ULTRA_WIDE -> "Rear ultra wide"; LensRole.TELE -> "Rear tele"
        LensRole.FRONT -> "Front"; LensRole.EXTERNAL -> "External"; LensRole.UNKNOWN -> "Other"
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(v: Int) = Look.dp(this, v)

    companion object {
        /** The engine and camera the LIVE screen was showing (8.1); the benchmark measures what the user was looking at. */
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_CAMERA_ID = "camera_id"
        private const val STATS_INTERVAL_MS = 250L
        /** How often a heat-blocked card re-runs preflight; SEVERE→cool takes tens of seconds, so 5 s is enough. */
        private const val RECHECK_INTERVAL_MS = 5_000L
    }
}
