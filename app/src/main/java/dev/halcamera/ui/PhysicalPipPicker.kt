package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import dev.halcamera.camera.readConcurrentCamera

/** Read-only capability lookup. The caller closes Live before launching the selected PIP session. */
internal class PhysicalPipPicker(private val context: Context, private val manager: CameraManager) {
    private val cache = mutableMapOf<String,List<String>>()
    fun supported(id: String): List<String> {
        if (Build.VERSION.SDK_INT < 30 || id.isEmpty()) return emptyList()
        return cache.getOrPut(id) { runCatching { readConcurrentCamera(manager,id).physicalIds }.getOrDefault(emptyList()) }
    }

    fun show(id: String,engine: String,open: (String,List<String>) -> Unit) {
        val ids = supported(id)
        if (ids.isEmpty()) return
        val checked = BooleanArray(ids.size)
        val dialog = AlertDialog.Builder(context).setTitle("Camera $id · PIP")
            .setMultiChoiceItems(ids.map { "Physical $it" }.toTypedArray(),checked) { dialog,index,value ->
                checked[index] = value
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
            }.setPositiveButton("Open",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = ids.filterIndexed { index,_ -> checked[index] }
                if (selected.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                if (engine == "CameraX") AlertDialog.Builder(context).setTitle("PIP · Camera2")
                    .setMessage("Open PIP with Camera2?").setPositiveButton("Open") { _,_ -> open(id,selected) }.setNegativeButton("Cancel",null).show()
                else open(id,selected)
            }
        }
        dialog.show()
    }
}
