package dev.halcamera.ui

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
    private val positions = context.getSharedPreferences("pip_picker_scroll", Context.MODE_PRIVATE)
    fun supported(id: String): List<PipSource> {
        if (Build.VERSION.SDK_INT < 30 || id.isEmpty()) return emptyList()
        return cache.getOrPut(id) { runCatching { readPipSources(manager,id) }.getOrDefault(emptyList()) }
    }

    fun show(id: String,engine: String,selected: PipSource? = null,
        sources: List<PipSource> = supported(id), open: (String,PipSource?) -> Unit) {
        if (sources.isEmpty()) return
        val ordered = sources.sortedWith(compareBy<PipSource> { !it.physical }.thenBy(CameraLabel.idOrder) { it.id })
        val endpoints = CameraEndpointResolver(manager).resolve()
        val choices = ordered.map { source ->
            val endpoint = endpoints.firstOrNull {
                if (source.physical) it.logicalCameraId == id && it.physicalCameraId == source.id
                else it.logicalCameraId == source.id && it.physicalCameraId == null
            }
            val name = endpoint?.let { listOfNotNull(CameraLabel.facing(it.role,it.facing),
                CameraLabel.lens(it.role),CameraLabel.angle(it.role,it.equivalentFocalMm)).joinToString(" · ") }
                ?.takeIf { it.isNotEmpty() } ?: "Camera"
            LiveChoiceSheet.Choice("${if (source.physical) "Physical" else "Service"} · ID ${source.id}",name)
        }
        LiveChoiceSheet.show(context,"PIP",choices,ordered.indexOfFirst { it.key == selected?.key },
            clear = if (selected != null) ({ open(id,null) }) else null, edge = LiveChoiceSheet.Edge.TOP,
            scrollPosition = if (positions.contains("$engine.$id")) positions.getInt("$engine.$id",0)
                else if (engine == "Camera2" && positions.contains(id)) positions.getInt(id,0) else null,
            saveScrollPosition = { positions.edit().putInt("$engine.$id",it).apply() }) { index ->
                open(id,ordered[index])
            }
    }
}
