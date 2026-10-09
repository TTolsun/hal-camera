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
                        latest = file; host.latestChanged(file); toast("Events saved. Evidence bagged. · ${file.name}")
                        if (incident.finishReason == "completed") showSaved(file, marked)
                    }
                }
            } catch (e: Exception) { main.post { exporting--; markedReadings.remove(incident.id); if (!host.destroyed) toast("ZIP save failed: ${e.message}") } }
        }
    }

    fun files() = File(activity.filesDir, "incidents").listFiles()?.filter { it.extension == "zip" }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun showList() {
        val files = files()
        if (files.isEmpty()) { toast("No saved events yet. Nothing to investigate."); return }
        if (Look.isLight(activity)) {
            LabDialog(activity, "ZIP Archives · ${files.size}").apply {
                files.forEach { file ->
                    entry("${file.name}\n${file.length() / 1024} KB") { showLabFile(file) }
                }
                action("Close", primary = true)
                show()
            }
            return
        }
        AlertDialog.Builder(activity).setTitle("ZIP Archives · ${files.size}")
            .setItems(files.map { "${it.name}\n${it.length() / 1024} KB" }.toTypedArray()) { _, index ->
                val file = files[index]
                AlertDialog.Builder(activity).setTitle(file.name).setItems(arrayOf("Share", "Save as", "Delete")) { _, action ->
                    when (action) {
                        0 -> share(file)
                        1 -> host.saveAs(file)
                        2 -> AlertDialog.Builder(activity).setMessage("${file.name}: delete this file from the device?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                            if (file.delete()) { latest = files().firstOrNull(); host.latestChanged(latest); toast("Deleted.") }
                        }.show()
                    }
                }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Close", null).show()
    }

    private fun showLabFile(file: File) {
        LabDialog(activity, "ZIP Archive").apply {
            group {
                addView(Look.text(context, file.name, 15, Look.ink).apply { setTextIsSelectable(true) })
                addView(Look.text(context, "${file.length() / 1024} KB", 13, Look.inkMuted))
            }
            action("Share", primary = true) { share(file) }
            action("Save as") { host.saveAs(file) }
            action("Delete", destructive = true) {
                LabDialog(activity, "Delete Archive").apply {
                    group { addView(Look.text(context, "${file.name}: delete this file from the device?", 15, Look.ink)) }
                    action("Delete", destructive = true) {
                        if (file.delete()) {
                            latest = files().firstOrNull()
                            host.latestChanged(latest)
                            toast("Deleted.")
                        } else toast("Could not delete the ZIP.")
                    }
                    action("Cancel")
                    show()
                }
            }
            action("Close")
            show()
        }
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("incident", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Share incident"))
    }

    /**
     * 8.1: the dialog after a MARK keeps raw values only. It used to open with a verdict sentence and evidence
     * lines in consumer words, which claimed more about the recording than the app had measured.
     */
    private fun showSaved(file: File, marked: LiveReading?) {
        if (host.destroyed || activity.isFinishing) return
        fun ms(v: Double?) = v?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"
        val savedMessage = "진단 ZIP을 앱에 저장했습니다.\nLab → ZIP Archives에서 다시 열 수 있습니다.\nZIP Archives에서 Save as를 선택하면 원하는 외부 위치에 복사합니다.\n\nSaved the previous 10 and next 5 seconds."
        val body = if (marked == null) savedMessage else listOf(
            savedMessage,
            "Values recorded when you tapped Save Events.",
            "",
            // The dialog body is proportional, so padding with spaces never lined the columns up; one value per line
            // reads the same on every font.
            "interval: ${ms(marked.intervalMs)}",
            "  Reference p50: ${ms(marked.intervalRefMs)}",
            "partial: ${ms(marked.partialMs)}",
            "  Reference p50: ${ms(marked.baselinePartialMs)}",
            "stall (10s): ${marked.stalls} events"
        ).joinToString("\n")
        AlertDialog.Builder(activity).setTitle(file.name)
            .setMessage(body)
            .setPositiveButton("Share") { _, _ -> share(file) }
            .setNegativeButton("Close", null).show()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
