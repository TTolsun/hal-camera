package dev.halcamera.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Handler
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.halcamera.telemetry.Incident
import dev.halcamera.telemetry.IncidentExporter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

/**
 * The incident ZIPs of the Live screen: writing one after a Mark, the saved list with share, save-as and delete,
 * and the dialog that shows the values at the Mark. Main thread only; the ZIP itself is written on [io].
 */
class IncidentActions(
    private val activity: Activity,
    private val io: Executor,
    private val main: Handler,
    private val host: Host,
) {
    interface Host {
        /** The camera sessions the ZIP describes, read when the write starts. */
        val sessions: Map<String, Map<String, Any?>>
        val destroyed: Boolean
        /** The newest ZIP changed; null when none is left. */
        fun latestChanged(file: File?)
        /** Opens the system picker to save [file] elsewhere. */
        fun saveAs(file: File)
    }

    /** The reading at each Mark, held by incident id until that incident's ZIP is written. */
    private val markedReadings = mutableMapOf<String, LiveReading?>()
    /** ZIPs being written; the panel says so while any is. */
    var exporting = 0
        private set
    var latest: File? = files().firstOrNull()
        private set

    /** The reading is captured at the Mark, not when the dialog opens five seconds and a write later. */
    fun marked(id: String, reading: LiveReading?) { markedReadings[id] = reading }

    fun export(incident: Incident) {
        exporting++
        val app = activity.applicationContext; val sessions = host.sessions
        io.execute {
            try {
                val file = IncidentExporter(app).export(incident, sessions)
                main.post {
                    exporting--
                    val marked = markedReadings.remove(incident.id)
                    if (!host.destroyed) {
                        latest = file; host.latestChanged(file); toast("저장 완료 · ${file.name}")
                        if (incident.finishReason == "completed") showSaved(file, marked)
                    }
                }
            } catch (e: Exception) { main.post { exporting--; markedReadings.remove(incident.id); if (!host.destroyed) toast("ZIP 저장 실패: ${e.message}") } }
        }
    }

    fun files() = File(activity.filesDir, "incidents").listFiles()?.filter { it.extension == "zip" }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun showList() {
        val files = files()
        if (files.isEmpty()) { toast("저장된 incident가 없습니다"); return }
        AlertDialog.Builder(activity).setTitle("ZIP Archives · ${files.size}")
            .setItems(files.map { "${it.name}\n${it.length() / 1024} KB" }.toTypedArray()) { _, index ->
                val file = files[index]
                AlertDialog.Builder(activity).setTitle(file.name).setItems(arrayOf("공유", "다른 위치에 저장", "삭제")) { _, action ->
                    when (action) {
                        0 -> share(file)
                        1 -> host.saveAs(file)
                        2 -> AlertDialog.Builder(activity).setMessage("${file.name}을 기기에서 삭제할까요?").setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                            if (file.delete()) { latest = files().firstOrNull(); host.latestChanged(latest); toast("삭제했습니다") }
                        }.show()
                    }
                }.setNegativeButton("취소", null).show()
            }.setNegativeButton("닫기", null).show()
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("incident", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Incident 공유"))
    }

    /**
     * 8.1: the dialog after a MARK keeps raw values only. It used to open with a verdict sentence and evidence
     * lines in consumer words, which claimed more about the recording than the app had measured.
     */
    private fun showSaved(file: File, marked: LiveReading?) {
        if (host.destroyed || activity.isFinishing) return
        fun ms(v: Double?) = v?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"
        val body = if (marked == null) "직전 10초와 이후 5초를 저장했습니다." else listOf(
            "직전 10초와 이후 5초를 저장했습니다.",
            "Mark를 누른 시점의 값입니다.",
            "",
            // The dialog body is proportional, so padding with spaces never lined the columns up; one value per line
            // reads the same on every font.
            "interval: ${ms(marked.intervalMs)}",
            "  기준 p50: ${ms(marked.intervalRefMs)}",
            "partial: ${ms(marked.partialMs)}",
            "  기준 p50: ${ms(marked.baselinePartialMs)}",
            "stall (10s): ${marked.stalls}회"
        ).joinToString("\n")
        AlertDialog.Builder(activity).setTitle(file.name)
            .setMessage(body)
            .setPositiveButton("공유") { _, _ -> share(file) }
            .setNegativeButton("닫기", null).show()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
