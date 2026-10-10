package dev.halcamera.ui

import android.app.AlertDialog
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import dev.halcamera.camera.PipSource
import dev.halcamera.camera.readPipSources
import dev.halcamera.camera.CameraEndpointResolver
import dev.halcamera.camera.CameraLabel

/** The caller applies a source in the current Live preview. */
internal class PhysicalPipPicker(private val context: Context, private val manager: CameraManager) {
    private val cache = mutableMapOf<String,List<PipSource>>()
    fun supported(id: String): List<PipSource> {
        if (Build.VERSION.SDK_INT < 30 || id.isEmpty()) return emptyList()
        return cache.getOrPut(id) { runCatching { readPipSources(manager,id) }.getOrDefault(emptyList()) }
    }

    fun show(id: String,engine: String,selected: PipSource? = null,open: (String,PipSource?) -> Unit) {
        val sources = supported(id)
        if (sources.isEmpty()) return
        val endpoints = CameraEndpointResolver(manager).resolve()
        val choices = sources.map { source ->
            val endpoint = endpoints.firstOrNull {
                if (source.physical) it.logicalCameraId == id && it.physicalCameraId == source.id
                else it.logicalCameraId == source.id && it.physicalCameraId == null
            }
            val name = endpoint?.let { listOfNotNull(CameraLabel.facing(it.role,it.facing),
                CameraLabel.lens(it.role),CameraLabel.angle(it.role,it.equivalentFocalMm)).joinToString(" · ") }
                ?.takeIf { it.isNotEmpty() } ?: "Camera"
            LiveChoiceSheet.Choice(name,"${if (source.physical) "Physical" else "Service"} · ID ${source.id}")
        }
        LiveChoiceSheet.show(context,"PIP",choices,sources.indexOfFirst { it.key == selected?.key },
            clear = if (selected != null) ({ open(id,null) }) else null) { index ->
                val selected = sources[index]
                if (engine == "CameraX") AlertDialog.Builder(context).setTitle("PIP · Camera2")
                    .setMessage("Open PIP with Camera2?").setPositiveButton("Open") { _,_ -> open(id,selected) }.setNegativeButton("Cancel",null).show()
                else open(id,selected)
            }
    }
}
