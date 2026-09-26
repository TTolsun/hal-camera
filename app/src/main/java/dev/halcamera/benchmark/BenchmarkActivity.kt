package dev.halcamera.benchmark


import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import dev.halcamera.benchmark.domain.*
import dev.halcamera.benchmark.platform.*
import dev.halcamera.camera.Camera2Engine
import dev.halcamera.camera.CameraEndpoint
import dev.halcamera.camera.CameraLabel
import dev.halcamera.camera.CameraEndpointResolver
import dev.halcamera.camera.LensRoles
import dev.halcamera.cli.BenchmarkController
import dev.halcamera.telemetry.Event
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import dev.halcamera.telemetry.nowNs
import dev.halcamera.ui.BenchmarkResultCards
import dev.halcamera.ui.Look
import dev.halcamera.ui.showSelectionPopup
import java.io.File
import java.util.concurrent.Executors

/**
 * Benchmark setup, progress, results and comparison. The activity owns camera and file I/O;
 * pure presenters supply measurement values and verdicts, and [BenchmarkResultCards] lays out the result and
 * compare cards.
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

    private enum class Screen { CARD, RUNNING, RESULT }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = FlightRecorder(::nowNs, retentionNs = 180_000_000_000L, maxEvents = 60_000, preNs = 0, postNs = 0)
    private val telemetry = Telemetry(recorder)
    // v2 measures everything v1 measured and the RECORD stage on top. Runs stored under v1 stay readable and
    // keep comparing with each other, but a device needs a new baseline and a new calibration under v2, because
    // the profile id is part of the comparison contract (docs/PLAN-Recording-v0.1.md 3.1).
    private val profile = BenchmarkProfile.CAMERA2_STANDARD_V2

    // Writing a run file and reading the baseline and reference runs back are hundreds of kilobytes of JSON each;
    // doing that on the main thread would freeze the screen exactly when the result is supposed to appear.
    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { BenchmarkStore(this) }
    private val report by lazy { BenchmarkReport(store) }
    private val baselines by lazy { BaselineManager(StoreRunCatalog(store, report)) }
    private val subjectPrefs by lazy { SubjectPrefs(this) }
    private val settings by lazy { BenchmarkPrefs(this) }
    private val probe = EnvironmentProbe(this)

    private lateinit var preview: TextureView
    private lateinit var header: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var actions: LinearLayout

    // Views the running screen updates in place instead of rebuilding on every frame.
    private var progressHeadline: TextView? = null
    private var progressBar: TextView? = null
    private var progressStats: TextView? = null
    private var buildInput: EditText? = null

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
    private var settingsDialog: android.app.Dialog? = null
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

        // The title and the way out sit at the top left as on every other screen; the cards stay at the bottom.
        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
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
                finish()
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
        settingsDialog?.dismiss()
        settingsDialog = null
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
        showSelectionPopup(anchor, endpoints.map(CameraLabel::full), selected) { index ->
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
            endpointName = CameraLabel.full(endpoint),
            engineName = engineName,
            thermalStatus = probe.thermalStatus(),
            powerSaveMode = probe.powerSaveMode()
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

    // ---- screens ----

    private fun render() {
        cli.setUiBusy(benchmarkCli, screen == Screen.RUNNING || historyLoading)
        header.removeAllViews()
        content.removeAllViews()
        actions.removeAllViews()
        // RUNNING has no back icon: Abort is its way out, and a stray tap must not end a run.
        when (screen) {
            Screen.CARD, Screen.RESULT -> header.addView(Look.titleBar(this, "Benchmark", 22,
                if (intent.hasExtra(EXTRA_RUN_ID)) "실행 이력으로 돌아가기" else "카메라로 돌아가기") { finish() })
            Screen.RUNNING -> Unit
        }
        when (screen) {
            Screen.CARD -> renderCard()
            Screen.RUNNING -> renderRunning()
            Screen.RESULT -> renderResult()
        }
        if (screen == Screen.CARD) {
            val row = Look.row(this)
            row.addView(Look.ghostButton(this, "실행 기록", dark = true) { openHistoryScreen() },
                Look.buttonParams(0, 1f))
            row.addView(Look.ghostButton(this, "설정", dark = true) { openSettings() }.apply {
                contentDescription = "벤치마크 설정, 최대 보관 개수 ${RunRetention.label(settings.runLimit)}"
            }, Look.buttonParams(0, 1f).apply { marginStart = dp(8) })
            actions.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        // A result opened from run history already returns there through the back arrow, and this button did
        // exactly the same (openHistoryScreen finishes in that case). It stays after a fresh run, where the back
        // arrow leads to the camera and this is the short way to the history.
        if (screen == Screen.RESULT && !intent.hasExtra(EXTRA_RUN_ID)) {
            actions.addView(Look.ghostButton(this, "실행 기록", dark = true) { openHistoryScreen() },
                Look.buttonParams().apply { topMargin = dp(8) })
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
    /**
     * Settings. The limit is an ordered scale, so it is a slider: a five-item list made the reader compare
     * five near-identical strings to find the one number that differs, while a slider puts the choice on one
     * axis and shows where the current value sits on it.
     */
    private fun openSettings() {
        // The stored runs and the baselines are read off the main thread; the dialog needs them to say what a
        // limit would delete before it is applied.
        io.execute {
            val ids = store.files().map { it.nameWithoutExtension }
            val protected = store.index().baselines.values.toSet()
            main.post { if (!destroyed) showSettings(ids, protected) }
        }
    }

    private fun showSettings(storedIds: List<String>, protected: Set<String>) {
        val options = RunRetention.OPTIONS
        var picked = options.indexOf(settings.runLimit).coerceAtLeast(0)
        val baselineCount = storedIds.count { it in protected }
        fun impact(limit: Int) = RunRetention.impactLine(storedIds.size, baselineCount, RunRetention.toDelete(storedIds, protected, limit).size)

        // A plain Dialog with the app's own card, not AlertDialog: the platform dialog arrives in the system
        // theme, so a grey sheet with system buttons would sit on top of this screen's black cards.
        // Title and the one fact it does not say first; then the value with what it would do, directly above the
        // slider that changes it. The value is white: blue is what can be pressed on these screens.
        val card = Look.card(this, dark = true)
        card.addView(Look.text(this, "최대 보관 개수", 19, Look.onDark, bold = true))
        card.addView(Look.text(this, "Baseline은 유지됩니다.", 12, Look.onDarkMuted), lp(top = 4))
        val valueRow = Look.row(this)
        val value = Look.text(this, RunRetention.valueLabel(options[picked]), 30, Look.onDark, bold = true, mono = true)
        valueRow.addView(value, LinearLayout.LayoutParams(0, -2, 1f))
        val effect = Look.text(this, impact(options[picked]), 12, Look.onDarkMuted)
        valueRow.addView(effect)
        card.addView(valueRow, lp(top = 16))

        // The horizontal padding is the thumb's own radius: with it removed the thumb is clipped in half at
        // both ends of the track, so it stays and the tick row is inset to match instead.
        val bar = android.widget.SeekBar(this).apply {
            max = options.lastIndex
            progress = picked
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: android.widget.SeekBar, position: Int, fromUser: Boolean) {
                    picked = position
                    value.text = RunRetention.valueLabel(options[position])
                    effect.text = impact(options[position])
                }
                override fun onStartTrackingTouch(seek: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(seek: android.widget.SeekBar) = Unit
            })
        }
        card.addView(bar, lp(top = 8))

        // Only the ends are labelled. Eleven stops will not fit as text, and a label on every other stop
        // would have to lie about where the thumb lands; the chosen value is already set in large type above.
        val ticks = Look.row(this).apply { setPadding(bar.paddingLeft, 0, bar.paddingRight, 0) }
        ticks.addView(
            Look.text(this, RunRetention.tickLabel(options.first()), 12, Look.onDarkMuted),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        ticks.addView(Look.text(this, RunRetention.tickLabel(options.last()), 12, Look.onDarkMuted))
        card.addView(ticks, lp(top = 2))

        // Held in a field so onDestroy can close it: a dialog still showing when the activity goes away
        // leaks its window, and the CLI can finish this screen while the sheet is open.
        settingsDialog?.dismiss()
        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { settingsDialog = null }
        }
        settingsDialog = dialog
        val actionRow = Look.row(this)
        actionRow.addView(Look.ghostButton(this, "취소", dark = true) { dialog.dismiss() },
            Look.buttonParams(0, 1f))
        actionRow.addView(Look.primaryButton(this, "적용") { applyRunLimit(options[picked]); dialog.dismiss() },
            Look.buttonParams(0, 1f).apply { marginStart = dp(8) })
        card.addView(actionRow, lp(top = 18))

        val frame = FrameLayout(this).apply { setPadding(dp(16), 0, dp(16), 0); addView(card) }
        dialog.setContentView(frame)
        dialog.show()
    }

    private fun applyRunLimit(limit: Int) {
        settings.runLimit = limit
        io.execute {
            val deleted = prune()
            main.post {
                if (destroyed) return@post
                val suffix = if (deleted == 0) "" else " · 오래된 run ${deleted}개 삭제"
                android.widget.Toast.makeText(
                    this, "최대 보관 개수 ${RunRetention.label(limit)}$suffix", android.widget.Toast.LENGTH_SHORT
                ).show()
                render()
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
        if (state == null) {
            card.addView(Look.text(this, cardError ?: "카메라를 확인하는 중입니다.", 13, Look.onDarkMuted))
            content.addView(card)
            return
        }
        // The measured condition leads; "카메라를 벤치마킹합니다." only repeated the screen title. What the run does
        // comes first and how to prepare for it second, one line each.
        card.addView(Look.text(this, state.titleLine, 15, Look.onDark, bold = true))
        card.addView(Look.text(this, "${state.detailLine}\n${state.durationLine}", 13, Look.onDarkMuted), lp(top = 4))
        card.addView(statusChips(), lp(top = 14))
        state.notices.forEach { card.addView(Look.text(this, "· $it", 12, Look.statusWarn), lp(top = 8)) }
        state.blockedReason?.let { card.addView(Look.text(this, it, 13, Look.statusFail, bold = true), lp(top = 10)) }

        // One label, not three. The build under test is the only one anybody typed; a commit and a note were
        // extra fields to skip past, and the run JSON still carries all three for files written earlier.
        if (state.canStart || state.refreshable) {
            val draft = draftSubject ?: subjectPrefs.last()
            // A label above the field: its hint disappears once a value is typed, and "i123-s7-control-day2" alone
            // did not say what it was.
            card.addView(Look.text(this, "측정 대상 빌드", 12, Look.onDarkMuted), lp(top = 14))
            buildInput = input(draft.subjectBuildLabel).also {
                it.contentDescription = "측정 대상 빌드 이름"
                it.hint = "선택"
                card.addView(it, lp(top = 4))
            }
        } else {
            buildInput = null
        }
        content.addView(card)

        val row = Look.row(this)
        val cameraLabel = endpoints.getOrNull(selected)?.let { "${CameraLabel.full(it)} ▾" } ?: "카메라 없음"
        row.addView(Look.ghostButton(this, cameraLabel, dark = true) {}.apply {
            setOnClickListener { selectCamera(it) }
            isEnabled = endpoints.isNotEmpty()
            contentDescription = "벤치마크 카메라 선택, 현재 $cameraLabel"
        }, Look.buttonParams(0, 1f))
        actions.addView(row)
        if (state.canStart) {
            actions.addView(Look.primaryButton(this, "벤치마크 시작") { begin() }, Look.buttonParams().apply { topMargin = dp(8) })
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
        val thermal = probe.thermalStatus()
        if (thermal != null) {
            val (label, color) = when {
                thermal >= StartCardPresenter.THERMAL_SEVERE -> "Thermal severe" to Look.statusFail
                thermal >= ValidityFlags.THERMAL_MODERATE -> "Thermal moderate" to Look.statusWarn
                thermal >= 1 -> "Thermal light" to Look.statusWarn
                else -> "Thermal OK" to Look.statusPass
            }
            row.addView(chip(label, color))
        }
        probe.batteryPercent()?.let {
            row.addView(chip("Battery $it%", Look.onDarkMuted),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = if (row.childCount > 0) dp(8) else 0 })
        }
        return row
    }

    /** Keeps what is typed across a re-render; the fields describe the build under test, not this card. */
    private fun captureDraft() {
        if (buildInput == null) return
        draftSubject = readSubject()
    }

    /** 8.3. */
    private fun renderRunning() {
        val card = Look.card(this, dark = true)
        card.addView(Look.text(this, "벤치마크 실행 중", 19, Look.onDark, bold = true))
        // The first phase is shown before the runner starts, so the card never appears blank for a frame.
        val first = ProgressPresenter.headline(BenchmarkRunner.Phase.CAMERA_OPEN, 0, profile.launchIterations, profile.records)
        progressHeadline = Look.text(this, first, 14, Look.onDark, mono = true).also { card.addView(it, lp(top = 10)) }
        progressBar = Look.text(this, ProgressPresenter.barLine(0), 13, Look.primaryOnDark, mono = true)
            .also { it.maxLines = 1; card.addView(it, lp(top = 4)) }
        progressStats = Look.text(this, "", 12, Look.onDarkMuted, mono = true).also { card.addView(it, lp(top = 10)) }
        content.addView(card)
        actions.addView(Look.ghostButton(this, "중단", dark = true) { runner?.abort("user") }, Look.buttonParams())
        updateStats()
    }

    /** 8.4. The verdict leads, four key metrics follow with bars, and everything else folds (mockup v7). */
    private fun renderResult() {
        progressHeadline = null; progressBar = null; progressStats = null
        val run = lastRun
        if (run == null) {
            val card = Look.card(this, dark = true)
            card.addView(Look.text(this, "벤치마크 결과", 19, Look.onDark, bold = true))
            card.addView(Look.text(this, lastSummary.ifBlank { "결과를 만들지 못했습니다." }, 12, Look.onDarkMuted, mono = true), lp(top = 10))
            content.addView(card)
            if (!intent.hasExtra(EXTRA_RUN_ID) && !historyLoading) actions.addView(Look.primaryButton(this, "다시 실행") { preflight() }, Look.buttonParams())
            return
        }
        // The result card is the comparison: its ticks are the reference run, and it carries what the removed 비교
        // screen showed (percentages, the reference's facts), so there is no second chart behind a button.
        val view = BenchmarkResultCards.addResult(this, content, run, comparison, comparedTo, isBaseline, lastFile?.name,
            reference = baseRun)

        // Give the longer baseline action a full row to avoid truncation.
        actions.addView(
            action(view.baselineButton, view.baselineButtonEnabled) { toggleBaseline() },
            Look.buttonParams()
        )
        actions.addView(
            action("내보내기", lastFile != null) { lastFile?.let(::share) },
            Look.buttonParams().apply { topMargin = dp(8) }
        )
        if (!intent.hasExtra(EXTRA_RUN_ID) && !historyLoading) actions.addView(Look.primaryButton(this, "다시 실행") { preflight() }, Look.buttonParams().apply { topMargin = dp(8) })
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
        envStart.clear(); envStart += probe.environment()
        liveStats.reset()
        livePhase = null
        thermal = ThermalTracker(this) { status ->
            recorder.record("run", "thermal_status", values = mapOf("status" to status))
        }.also { it.start() }

        val runId = BenchmarkReport.newRunId()
        val spec = StreamSpecs.of(profile)
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
            // The engine answers through telemetry events, which onEvent turns into runner signals, exactly as
            // it does for open, configure and capture.
            override fun prepareRecord(session: String, iteration: Int) {
                val e = engine
                if (e == null) recorder.record(session, "record_configure_failed", values = mapOf("reason" to "engine_gone"))
                else e.prepareBenchmarkRecording(iteration)
            }
            override fun startRecord(session: String) { engine?.startBenchmarkRecording() }
            override fun stopRecord(session: String) { engine?.stopBenchmarkRecording() }
            override fun abortRecord(session: String) { engine?.abortBenchmarkRecording() }
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
                progressHeadline?.text = ProgressPresenter.headline(phase, iteration, total, profile.records)
                progressBar?.text = ProgressPresenter.barLine(ProgressPresenter.percent(phase, iteration, total, profile.records))
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
            subjectCommit = previous.subjectCommit,
            subjectBranch = previous.subjectBranch,
            note = null
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
            "capture_started" -> {
                if (s == r.currentSession) r.firstStarted(s, e.atNs)
                // The recording requests carry this cycle's tag; the first of them is the end point of 3.1.
                (e.values["requestTag"] as? String)?.takeIf { it.startsWith("record-") }
                    ?.let { r.recordFrameStarted(s, it, e.atNs) }
            }
            // record_prepare_call is left to the runner, which marks it when it calls the driver; the engine
            // records it too, for the event log, but only the two calls 3.1 and 3.6 measure need the engine's time.
            "record_configured" -> r.signal(s, BenchmarkRunner.Signal.RECORD_READY, e.atNs)
            "record_configure_failed" -> r.signal(s, BenchmarkRunner.Signal.RECORD_UNSUPPORTED, e.atNs, e.kind)
            "record_start_call" -> r.recordMark(s, RecordCycle.START_CALL_MARK, e.atNs)
            "record_started" -> r.signal(s, BenchmarkRunner.Signal.RECORD_STARTED, e.atNs)
            "record_stop_call" -> r.recordMark(s, RecordCycle.STOP_CALL_MARK, e.atNs)
            "record_stopped" -> r.signal(s, BenchmarkRunner.Signal.RECORD_STOPPED, e.atNs)
            // Not an ERROR signal: a recorder error is posted asynchronously and may name a cycle the stage has
            // already left, so the runner decides whether it still belongs to anything.
            "record_failed" -> r.recordFailed(
                s, (e.values["iteration"] as? Number)?.toInt(),
                (e.values["reason"] as? String)?.let { "record_failed:$it" } ?: e.kind, e.atNs
            )
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
        val endEnv = probe.environment()
        val env = RunEnv(
            thermalStart = thermal?.start, thermalMax = thermal?.max, thermalEnd = thermalEnd,
            batteryStart = envStart["battery_pct"] as? Int, batteryEnd = endEnv["battery_pct"] as? Int,
            charging = envStart["charging"] as? Boolean, powerSaveMode = envStart["power_save"] as? Boolean,
            rotation = envStart["rotation"] as? Int
        )
        thermal = null
        val context = RunAssembler.Context(
            exportedAtUtc = BenchmarkReport.utcNow(),
            device = probe.deviceInfo(result.endpoint.logicalCameraId),
            deviceInstanceId = DeviceInstance.id(this),
            app = probe.appInfo(),
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
                    render()
                }
            } catch (e: Exception) {
                main.post {
                    if (!destroyed) android.widget.Toast.makeText(this, e.message ?: "Baseline 변경에 실패했습니다.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Environment and identity readings live in EnvironmentProbe (benchmark/platform).

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

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("benchmark", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "run JSON 공유"))
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
