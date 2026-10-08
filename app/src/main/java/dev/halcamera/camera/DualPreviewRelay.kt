package dev.halcamera.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One CameraX preview stream, copied to its view and optional encoder without composition. */
internal class DualPreviewRelay(
    private val texture: SurfaceTexture,
    private val size: LiveSize,
    private val onError: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("DualPreviewRelay").apply { start() }
    private val handler = Handler(thread.looper)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private lateinit var config: EGLConfig
    private var window = EGL14.EGL_NO_SURFACE
    private var encoder = EGL14.EGL_NO_SURFACE
    private var viewSurface: Surface? = null
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var program = 0
    private var textureId = 0
    private var closed = false
    private val transform = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }

    fun start(ready: (Surface) -> Unit) { handler.post {
        try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                0x3142,1,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
            config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            texture.setDefaultBufferSize(size.width,size.height)
            viewSurface = Surface(texture)
            window = createWindow(checkNotNull(viewSurface))
            check(EGL14.eglMakeCurrent(display,window,window,context))
            val names = IntArray(1); GLES20.glGenTextures(1,names,0); textureId = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            val vertex = shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 uv; uniform mat4 transform; varying vec2 v; void main(){gl_Position=vec4(p,0.,1.);v=(transform*vec4(uv,0.,1.)).xy;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 v; void main(){gl_FragColor=texture2D(image,v);}")
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            inputTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(size.width,size.height)
                setOnFrameAvailableListener({ render() },handler)
            }
            inputSurface = Surface(inputTexture)
            ready(checkNotNull(inputSurface))
        } catch (e: Exception) { onError(e) }
    } }

    fun encode(surface: Surface, done: () -> Unit) { handler.post {
        try { encoder = createWindow(surface); done() } catch (e: Exception) { onError(e) }
    } }

    private fun createWindow(surface: Surface): EGLSurface = EGL14.eglCreateWindowSurface(display,config,surface,
        intArrayOf(EGL14.EGL_NONE),0).also { check(it != EGL14.EGL_NO_SURFACE) }

    private fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it,source); GLES20.glCompileShader(it)
        val compiled = IntArray(1); GLES20.glGetShaderiv(it,GLES20.GL_COMPILE_STATUS,compiled,0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(it) }
    }

    private fun render() {
        if (closed) return
        try {
            check(EGL14.eglMakeCurrent(display,window,window,context))
            val input = checkNotNull(inputTexture)
            input.updateTexImage(); input.getTransformMatrix(transform)
            draw(window,input.timestamp)
            if (encoder != EGL14.EGL_NO_SURFACE) draw(encoder,input.timestamp)
        } catch (e: Exception) { closed = true; onError(e) }
    }

    private fun draw(target: EGLSurface, timestamp: Long) {
        check(EGL14.eglMakeCurrent(display,target,target,context))
        GLES20.glViewport(0,0,size.width,size.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,transform,0)
        val p = GLES20.glGetAttribLocation(program,"p"); val uv = GLES20.glGetAttribLocation(program,"uv")
        vertices.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(p)
        vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        if (target == encoder) EGLExt.eglPresentationTimeANDROID(display,target,timestamp)
        check(EGL14.eglSwapBuffers(display,target))
    }

    /** Called once CameraX returns ownership of its input surface. */
    fun close(done: () -> Unit) { handler.post {
        closed = true
        inputTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release(); inputTexture?.release()
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(display,window,window,context)
            GLES20.glDeleteProgram(program); GLES20.glDeleteTextures(1,intArrayOf(textureId),0)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (encoder != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,encoder)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
            EGL14.eglDestroyContext(display,context)
        }
        EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        viewSurface?.release(); thread.quitSafely(); done()
    } }
}
