package dev.cameradoctor

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.cameradoctor.camera.*
import dev.cameradoctor.telemetry.*
import dev.cameradoctor.ui.ScopeView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val bg = Color.rgb(12,19,26)
    private val panel = Color.rgb(19,30,40)
    private val mint = Color.rgb(111,225,198)
    private val muted = Color.rgb(153,174,192)
    private val coral = Color.rgb(255,128,126)
    private val main = Handler(Looper.getMainLooper())
    private val cameraWorker = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val recorder = FlightRecorder(::nowNs)
    private val telemetry = Telemetry(recorder)
    private var engine: CameraEngine? = null
    private var closing = false
    private var resumed = false
    private var destroyed = false
    private var paused = false
    private var engineName = "CameraX"
    private var cameraId = ""
    private var sessionId = ""
    private var exporting = 0
    private var ready = false
    private var latestFile: File? = null
    private var saveFile: File? = null
    private var previousCpu = Process.getElapsedCpuTime()
    private var previousSample = SystemClock.elapsedRealtime()
    private var lastSystemNs = 0L
    private lateinit var previewHost: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var metrics: TextView
    private lateinit var timeline: TextView
    private lateinit var system: TextView
    private lateinit var scope: ScopeView
    private lateinit var reportButton: Button
    private lateinit var captureButton: Button
    private lateinit var engineX: Button
    private lateinit var engine2: Button
    private lateinit var pauseButton: Button
    private lateinit var recorderText: TextView
    private lateinit var shareButton: Button
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) restartCamera() else setStatus("카메라 권한이 필요합니다 · 권한 버튼을 눌러 재시도", false)
    }
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = saveFile
        if (uri != null && file != null) io.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { target -> file.inputStream().use { it.copyTo(target) } }
                    ?: error("저장 위치를 열 수 없습니다")
                main.post { if (!destroyed) toast("선택한 위치에 저장했습니다") }
            } catch (e: Exception) { main.post { if (!destroyed) toast("저장 실패: ${e.message}") } }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            val time = nowNs()
            val events = recorder.snapshot(10_000_000_000L)
            val frames = events.filter { it.session == sessionId && it.kind == "capture_result" }
            scope.update(frames, time)
            updateReadings(events, frames, time)
            if (time-lastSystemNs >= 1_000_000_000L) { sampleSystem(); lastSystemNs=time }
            recorder.finish()?.let { export(it) }
            val remaining = recorder.remainingNs()
            reportButton.isEnabled = remaining == null && ready && !paused
            reportButton.text = if (remaining != null) "기록 중 · ${"%.1f".format(Locale.US, remaining/1e9)}초" else "●  SOMETHING WRONG"
            val span = events.firstOrNull()?.let { (time-it.atNs)/1e9 } ?: 0.0
            recorderText.text = if (exporting > 0) "ZIP 저장 중…" else "30s 순환 버퍼  ·  ${"%.1f".format(Locale.US, span.coerceAtMost(10.0))}s / 10s 사전 기록 준비"
            main.postDelayed(this, 100)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engineName = savedInstanceState?.getString("engine") ?: "CameraX"
        cameraId = savedInstanceState?.getString("camera") ?: ""
        paused = savedInstanceState?.getBoolean("paused") ?: false
        buildUi()
        latestFile = incidentFiles().firstOrNull()
        shareButton.isEnabled = latestFile != null
        recorder.record("app", "clock_anchor", values = mapOf("wallTimeMs" to System.currentTimeMillis(), "uptimeMs" to SystemClock.uptimeMillis()))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("engine", engineName); outState.putString("camera", cameraId); outState.putBoolean("paused", paused)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); resumed = true
        main.post(tick)
        if (hasPermission()) restartCamera()
        else { setStatus("카메라 접근을 허용하면 측정이 시작됩니다", false); permission.launch(Manifest.permission.CAMERA) }
    }
    override fun onStop() {
        resumed = false; main.removeCallbacks(tick)
        telemetry.event(sessionId.ifEmpty { "app" }, "activity_stopped")
        recorder.finish("activity_stopped")?.let { export(it) }
        restartCamera()
        super.onStop()
    }
    override fun onDestroy() {
        destroyed = true
        io.shutdown()
        if (!closing && engine == null) cameraWorker.shutdown()
        super.onDestroy()
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun restartCamera() {
        if (closing) return
        ready=false; captureButton.isEnabled=false; reportButton.isEnabled=false
        val old = engine; engine = null
        if (old != null) {
            closing = true; setStatus("카메라 세션 종료 중…", false)
            old.close {
                closing = false
                if (destroyed) cameraWorker.shutdown() else openCamera()
            }
        } else openCamera()
    }
    private fun openCamera() {
        if (!resumed || destroyed || paused || !hasPermission()) {
            if (paused) setStatus("PAUSED · 재개 버튼으로 측정을 시작하세요", false)
            return
        }
        if (cameraId.isEmpty()) { setStatus("사용 가능한 카메라가 없습니다", false); return }
        sessionId = UUID.randomUUID().toString()
        val thisSession = sessionId
        setStatus("$engineName · 카메라 $cameraId 연결 중…", false)
        previewHost.removeAllViews()
        engine = if (engineName == "CameraX") {
            val view = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            CameraXEngine(this, this, view, cameraId, sessionId, telemetry, cameraWorker) { text, ok ->
                if (thisSession == sessionId && resumed && !closing) setStatus(text,ok)
            }
        } else {
            val view = TextureView(this)
            previewHost.addView(view, FrameLayout.LayoutParams(-1,-1))
            Camera2Engine(this, view, cameraId, sessionId, telemetry) { text, ok ->
                if (thisSession == sessionId && resumed && !closing) setStatus(text,ok)
            }
        }
        try { engine?.start() } catch (e: Exception) { setStatus("시작 실패: ${e.message}",false) }
        styleEngines()
    }
    private fun setStatus(text: String, ok: Boolean) {
        statusText.text=text; statusText.setTextColor(if(ok) mint else muted)
        ready=ok; captureButton.isEnabled=ok; reportButton.isEnabled=ok && recorder.remainingNs()==null
    }
    private fun buildUi() {
        val scroll=ScrollView(this).apply { setBackgroundColor(bg); isFillViewport=true }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(24)) }
        scroll.addView(body)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v,insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            } else { @Suppress("DEPRECATION") v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            insets
        }
        scroll.requestApplyInsets()
        body.addView(label("CAMERA DOCTOR",25,Color.WHITE,true))
        body.addView(label("CAMERA SYSTEM OBSERVATORY  /  0.1",10,muted))
        val controls=row(); body.addView(controls,lp(top=18))
        engineX=button("CameraX") { chooseEngine("CameraX") }
        engine2=button("Camera2") { chooseEngine("Camera2") }
        controls.addView(engineX,LinearLayout.LayoutParams(0,dp(46),1f))
        controls.addView(engine2,LinearLayout.LayoutParams(0,dp(46),1f))
        val manager=getSystemService(CameraManager::class.java)
        val ids=try { manager.cameraIdList.toList().sortedBy { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK } } catch (_:Exception) { emptyList() }
        if (cameraId !in ids) cameraId=ids.firstOrNull().orEmpty()
        val spinner=Spinner(this)
        val entries=ids.map { id ->
            val facing=manager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING]
            "Camera $id · ${when(facing) { CameraCharacteristics.LENS_FACING_BACK -> "Back"; CameraCharacteristics.LENS_FACING_FRONT -> "Front"; else -> "External" }}"
        }
        spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,entries)
        spinner.setSelection(ids.indexOf(cameraId).coerceAtLeast(0))
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                val chosen=ids.getOrNull(position) ?: return
                if(cameraId!=chosen) { recorder.finish("camera_changed")?.let { export(it) }; cameraId=chosen; restartCamera() }
            }
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
        }
        body.addView(spinner,lp(height=48))
        previewHost=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); contentDescription="실시간 카메라 프리뷰" }
        body.addView(previewHost,lp(height=230,top=8))
        statusText=label("INITIALIZING",12,mint,true); body.addView(statusText,lp(top=8))
        metrics=label("Result FPS —     Exposure —     ISO —",14,Color.WHITE,true)
        metrics.setPadding(dp(12),dp(15),dp(12),dp(15)); metrics.background=rounded(panel)
        body.addView(metrics,lp(top=14))
        val actions=row(); body.addView(actions,lp(top=6))
        captureButton=button("셔터 측정") { engine?.capture() }
        pauseButton=button(if(paused) "재개" else "일시정지") {
            paused=!paused; pauseButton.text=if(paused) "재개" else "일시정지"
            if(paused) recorder.finish("user_paused")?.let { export(it) }
            restartCamera()
        }
        actions.addView(captureButton,LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(pauseButton,LinearLayout.LayoutParams(0,dp(48),1f))
        body.addView(label("01   3A OSCILLOSCOPE",12,muted,true),lp(top=18))
        body.addView(label("최근 10초 · 3A 상태는 단계값, 연속값 그래프는 자동 스케일",10,muted),lp(top=4))
        scope=ScopeView(this).apply { background=rounded(panel); contentDescription="AE, AF, AWB 상태와 노출, ISO, 센서 프레임 간격 그래프" }
        body.addView(scope,lp(height=342,top=10))
        body.addView(label("02   FRAME CALLBACK TIMELINE",12,muted,true),lp(top=20))
        timeline=label("프레임 콜백을 기다리는 중…",12,Color.WHITE).apply { typeface=Typeface.MONOSPACE; setPadding(dp(12),dp(14),dp(12),dp(14)); background=rounded(panel) }
        body.addView(timeline,lp(top=10))
        system=label("APP CPU —  ·  PSS —  ·  THERMAL —",11,muted)
        body.addView(system,lp(top=12))
        body.addView(label("03   FLIGHT RECORDER",12,muted,true),lp(top=20))
        body.addView(label("문제 순간을 누르세요. 직전 10초 + 이후 5초를 저장합니다.",12,Color.WHITE),lp(top=8))
        reportButton=button("●  SOMETHING WRONG") {
            val id="incident_"+SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())+"_"+UUID.randomUUID().toString().take(8)
            if(recorder.trigger(id)) toast("5초 후 incident ZIP을 저장합니다")
        }.apply { setTextColor(bg); background=rounded(coral) }
        body.addView(reportButton,lp(height=56,top=12))
        recorderText=label("30s 순환 버퍼",11,muted); body.addView(recorderText,lp(top=8))
        val exports=row(); body.addView(exports,lp(top=8))
        shareButton=button("최근 ZIP 공유") { latestFile?.let { share(it) } }
        exports.addView(shareButton,LinearLayout.LayoutParams(0,dp(48),1f))
        exports.addView(button("기록 목록") { showIncidents() },LinearLayout.LayoutParams(0,dp(48),1f))
        val tools=row(); body.addView(tools,lp(top=8))
        tools.addView(button("권한 / 재시도") {
            if(hasPermission()) restartCamera()
            else if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) permission.launch(Manifest.permission.CAMERA)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        },LinearLayout.LayoutParams(0,dp(48),1f))
        tools.addView(button("측정 안내") { showNotes() },LinearLayout.LayoutParams(0,dp(48),1f))
        body.addView(label("LOCAL RECORDING · NO IMAGE PIXELS SAVED",10,muted),lp(top=18))
        styleEngines()
    }
    private fun chooseEngine(name:String) {
        if(engineName==name) return
        recorder.finish("engine_changed")?.let { export(it) }
        engineName=name; styleEngines(); restartCamera()
    }
    private fun styleEngines() {
        for((button,name) in listOf(engineX to "CameraX",engine2 to "Camera2")) {
            button.setTextColor(if(engineName==name) bg else muted)
            button.background=rounded(if(engineName==name) mint else panel)
        }
    }
    private fun updateReadings(events:List<Event>,frames:List<Event>,time:Long) {
        val frame=frames.lastOrNull()?.takeIf { time-it.atNs < 1_500_000_000L }
        fun num(key:String)= (frame?.values?.get(key) as? Number)?.toDouble()
        fun fmt(value:Double?,pattern:String)=value?.let { pattern.format(Locale.US,it) } ?: "—"
        metrics.text="Result FPS ${fmt(num("resultFps"),"%.1f")}   ·   ISO ${num("iso")?.toInt() ?: "—"}\nExposure ${fmt(num("exposureNs")?.div(1e6),"%.2f ms")}   ·   Lens ${fmt(num("focusDiopters"),"%.2f D")}" 
        if(frame==null) { timeline.text="수신 중인 프레임 없음"; return }
        val imageEvents=events.filter { it.session==sessionId && it.kind=="image_available" }
        val matched=frames.asReversed().firstOrNull { r -> r.sensorNs!=null && imageEvents.any { it.sensorNs==r.sensorNs } } ?: frame
        val start=events.lastOrNull { it.session==sessionId && it.kind=="capture_started" && it.frame==matched.frame }
        val image=imageEvents.lastOrNull { it.sensorNs==matched.sensorNs }
        fun offset(e:Event?)=if(e!=null && start!=null) "%+.2f ms".format(Locale.US,(e.atNs-start.atNs)/1e6) else "—"
        timeline.text="Frame #${matched.frame} · observed callbacks\nSTART    ${if(start!=null) "+0.00 ms" else "—"}\nRESULT   ${offset(matched)}\nIMAGE    ${offset(image)}\n센서 시각으로 연결 · HAL 처리 시간과 다름"
    }
    private fun sampleSystem() {
        val elapsed=SystemClock.elapsedRealtime(); val cpu=Process.getElapsedCpuTime()
        val percent=if(elapsed>previousSample) 100.0*(cpu-previousCpu)/(elapsed-previousSample) else 0.0
        previousSample=elapsed; previousCpu=cpu
        val memory=Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss/1024.0
        val thermal=if(Build.VERSION.SDK_INT>=29) getSystemService(PowerManager::class.java).currentThermalStatus else null
        val thermalName=thermal?.let { listOf("NONE","LIGHT","MODERATE","SEVERE","CRITICAL","EMERGENCY","SHUTDOWN").getOrNull(it) ?: "$it" } ?: "N/A"
        system.text="APP CPU ${"%.1f".format(Locale.US,percent)}%* · PSS ${"%.0f".format(Locale.US,memory)} MB · $thermalName"
        recorder.record("app","system_sample",values=mapOf("appCpuPercentOneCore" to percent,"pssMb" to memory,"thermalStatus" to thermal,"thermalName" to thermalName))
    }
    private fun export(incident:Incident) {
        exporting++
        val app=applicationContext; val sessions=telemetry.sessions.toMap()
        io.execute {
            try {
                val file=IncidentExporter(app).export(incident,sessions)
                main.post {
                    exporting--
                    if(!destroyed) { latestFile=file; shareButton.isEnabled=true; toast("저장 완료 · ${file.name}") }
                }
            } catch(e:Exception) { main.post { exporting--; if(!destroyed) toast("ZIP 저장 실패: ${e.message}") } }
        }
    }
    private fun incidentFiles()=File(filesDir,"incidents").listFiles()?.filter { it.extension=="zip" }?.sortedByDescending { it.lastModified() }.orEmpty()
    private fun showIncidents() {
        val files=incidentFiles()
        if(files.isEmpty()) { toast("저장된 incident가 없습니다"); return }
        AlertDialog.Builder(this).setTitle("Incident ZIP · ${files.size}개")
            .setItems(files.map { "${it.name}\n${it.length()/1024} KB" }.toTypedArray()) { _,index ->
                val file=files[index]
                AlertDialog.Builder(this).setTitle(file.name).setItems(arrayOf("공유 / Google Drive","다른 위치에 저장","삭제")) { _,action ->
                    when(action) {
                        0 -> share(file)
                        1 -> { saveFile=file; saveDocument.launch(file.name) }
                        2 -> AlertDialog.Builder(this).setMessage("${file.name}을 기기에서 삭제할까요?").setNegativeButton("취소",null).setPositiveButton("삭제") { _,_ ->
                            if(file.delete()) { latestFile=incidentFiles().firstOrNull(); shareButton.isEnabled=latestFile!=null; toast("삭제했습니다") }
                        }.show()
                    }
                }.show()
            }.setNegativeButton("닫기",null).show()
    }
    private fun share(file:File) {
        val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="application/zip"; putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newRawUri("incident",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent,"Incident 공유 · Google Drive 선택"))
    }
    private fun showNotes() {
        AlertDialog.Builder(this).setTitle("측정 범위 / MVP")
            .setMessage("• Result FPS는 센서 타임스탬프 간격으로 계산합니다. 화면 표시 FPS가 아닙니다.\n\n• *앱 CPU 100%는 CPU 코어 하나의 사용량에 해당하며 100%를 넘을 수 있습니다. HAL 프로세스 CPU는 측정하지 않습니다.\n\n• CameraX와 Camera2의 실제 스트림 크기는 ZIP에 기록됩니다. 동일 조건 A/B 벤치마크는 후속 기능입니다.\n\n• 앱을 나가거나 카메라를 변경하면 진행 중인 incident를 partial 사유와 함께 저장합니다.\n\n• 이미지 픽셀, Perfetto, Simpleperf, AI 분석은 이 MVP에 포함하지 않았습니다.")
            .setPositiveButton("확인",null).show()
    }
    private fun label(text:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply { this.text=text; textSize=size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD) }
    private fun button(text:String,action:()->Unit)=Button(this).apply { this.text=text; isAllCaps=false; textSize=12f; setTextColor(mint); background=rounded(panel); setOnClickListener { action() } }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
    private fun rounded(color:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(10).toFloat() }
    private fun lp(height:Int=-2,top:Int=0)=LinearLayout.LayoutParams(-1,if(height<0) height else dp(height)).apply { topMargin=dp(top) }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
}
