package dev.halcamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.MotionEvent
import android.view.TextureView
import android.widget.FrameLayout
import dev.halcamera.camera.*

/** Adds composition inside the existing Live preview host; never navigates or replaces its engine. */
internal class LivePipController(
    private val context: Context,
    private val host: () -> FrameLayout,
    private val engine: () -> Camera2Engine?,
    private val changed: () -> Unit,
    private val frame: () -> Unit,
    private val notice: (String) -> Unit,
    private val cameraId: () -> String,
) {
    var selected: PipSource? = null
        private set
    var busy = false
        private set
    private var view: TextureView? = null
    private val positions = PipPositionStore(context, "live")
    private var rect = PipScene.liveDefault
    private var generation = 0

    fun reset() { generation++; selected = null; busy = false; view = null }

    @SuppressLint("ClickableViewAccessibility")
    fun select(source: PipSource?) {
        if (Build.VERSION.SDK_INT < 30 || busy) return
        val current = engine() ?: return
        val parent = cameraId()
        rect = positions.read(parent, PipScene.liveDefault)
        val token = ++generation
        busy = true; changed()
        fun complete(result: Result<Unit>) {
            if (token != generation || engine() !== current) return
            busy = false
            selected = source.takeIf { result.isSuccess }
            if (selected == null) { view?.let { host().removeView(it) }; view = null }
            else view?.alpha = 1f
            result.exceptionOrNull()?.let { notice(it.message ?: "PIP unavailable") }
            changed()
        }
        if (source == null) { current.setPip(null,null,null,done=::complete); return }
        fun start(target: TextureView) {
            if (token != generation) return
            val scale = minOf(1f,1280f/maxOf(target.width,target.height))
            val output = LiveSize(((target.width*scale).toInt()/2*2).coerceAtLeast(2),((target.height*scale).toInt()/2*2).coerceAtLeast(2))
            current.setPip(source,target.surfaceTexture,output,position=rect,done=::complete)
        }
        view?.let { start(it); return }
        val target = TextureView(context).apply { alpha = 0f; contentDescription = "PIP preview" }
        view = target
        var dragging = false; var x = 0f; var y = 0f; var original = rect
        target.setOnTouchListener { _, event ->
            val nx = event.x/target.width; val ny = event.y/target.height
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = !busy && rect.contains(nx,ny); x=nx; y=ny; original=rect; dragging }
                MotionEvent.ACTION_MOVE -> { if (dragging) { rect=original.moved(original.x+nx-x,original.y+ny-y); current.movePip(rect) }; dragging }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> dragging.also {
                    if (dragging) positions.save(parent,rect)
                    dragging=false
                }
                else -> dragging
            }
        }
        target.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture,width: Int,height: Int) = start(target)
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture,width: Int,height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = frame()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
        }
        host().addView(target,FrameLayout.LayoutParams(-1,-1))
    }
}
