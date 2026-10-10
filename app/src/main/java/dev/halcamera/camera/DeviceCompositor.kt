package dev.halcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One logical device's scene is drawn identically to its preview, JPEG and video encoder. */
internal class DeviceCompositor(
    context: Context,
    private val view: SurfaceTexture,
    val output: LiveSize,
    private val inputSizes: List<LiveSize>,
    private val cameraId: String,
    private val physicalIds: List<String>,
    private val onReady: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val serviceIds: List<String> = emptyList(),
    initialRects: List<PipRect> = emptyList(),
) {
    private val app = context.applicationContext
    private val thread = HandlerThread("HAL.Compose.$cameraId").apply { start() }
    private val handler = Handler(thread.looper)
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var snapshot = EGL14.EGL_NO_SURFACE
    private var encoder = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    private var viewSurface: Surface? = null
    private val textures = mutableListOf<SurfaceTexture>()
    private val surfaces = mutableListOf<Surface>()
    private val names = IntArray(inputSizes.size)
    private val transforms = List(inputSizes.size) { FloatArray(16) }
    private val received = BooleanArray(inputSizes.size)
    private var program = 0
    private var closed = false
    private var readySent = false
    private val pipIds = physicalIds + serviceIds
    private var rects = PipScene.initial(pipIds.size).mapIndexed { index, fallback ->
        initialRects.getOrNull(index)?.let { PipScene.restore(it,it.x,it.y) } ?: fallback
    }
    private var recorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var videoName = ""
    private var lastVideoNs = 0L
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }

    fun start(ready: (List<Surface>) -> Unit) { handler.post {
        try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,0x3142,1,EGL14.EGL_NONE),
                0, configs, 0, 1, IntArray(1), 0))
            config = checkNotNull(configs[0])
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            view.setDefaultBufferSize(output.width, output.height)
            viewSurface = Surface(view)
            window = createWindow(checkNotNull(viewSurface))
            snapshot = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, output.width, EGL14.EGL_HEIGHT, output.height, EGL14.EGL_NONE), 0)
            check(snapshot != EGL14.EGL_NO_SURFACE)
            current(window)
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; attribute vec2 uv; uniform mat4 transform; uniform mat4 rotation; varying vec2 v; void main(){gl_Position=vec4(p,0.,1.);v=(transform*rotation*vec4(uv,0.,1.)).xy;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 v; void main(){gl_FragColor=texture2D(image,v);}")
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment); GLES20.glLinkProgram(program)
            val linked = IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0); check(linked[0] != 0)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            GLES20.glGenTextures(names.size, names, 0)
            inputSizes.forEachIndexed { index, size ->
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,names[index])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                val texture = SurfaceTexture(names[index]).apply {
                    setDefaultBufferSize(size.width,size.height)
                    setOnFrameAvailableListener({ frame(index) },handler)
                }
                textures += texture; surfaces += Surface(texture)
            }
            ready(surfaces.toList())
        } catch (e: Exception) { closed = true; onError(e) }
    } }

    fun move(index: Int, rect: PipRect) { handler.post { if (!closed && index in rects.indices) {
        rects = rects.toMutableList().also { it[index] = rect.moved(rect.x, rect.y) }
        try { render() } catch (e: Exception) { closed = true; onError(e) }
    } } }

    private fun frame(index: Int) {
        if (closed) return
        try {
            current(window); textures[index].updateTexImage(); textures[index].getTransformMatrix(transforms[index]); received[index] = true
            render()
            if (!readySent && received.all { it }) { readySent = true; onReady() }
        } catch (e: Exception) { closed = true; onError(e) }
    }

    private fun render() {
        if (closed || received.any { !it }) return
        draw(window)
        check(EGL14.eglSwapBuffers(display,window))
        if (encoder != EGL14.EGL_NO_SURFACE) {
            // MediaRecorder audio uses CLOCK_MONOTONIC, not the suspend-inclusive sensor/app clock.
            val ns = maxOf(System.nanoTime(), lastVideoNs + 1)
            if (ns - lastVideoNs >= 33_333_333L) {
                draw(encoder); EGLExt.eglPresentationTimeANDROID(display,encoder,ns)
                check(EGL14.eglSwapBuffers(display,encoder)); lastVideoNs = ns
            }
        }
    }

    private fun draw(target: EGLSurface) {
        current(target)
        // TextureView may resize its native buffer after setDefaultBufferSize.
        val width = IntArray(1); val height = IntArray(1)
        check(EGL14.eglQuerySurface(display, target, EGL14.EGL_WIDTH, width, 0))
        check(EGL14.eglQuerySurface(display, target, EGL14.EGL_HEIGHT, height, 0))
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        textures.indices.forEach { index ->
            val r = if (index == 0) PipRect(0f,0f,1f,1f) else rects[index - 1]
            val w = (r.width * width[0]).toInt().coerceAtLeast(1)
            val h = (r.height * height[0]).toInt().coerceAtLeast(1)
            GLES20.glViewport((r.x * width[0]).toInt(), ((1f-r.y-r.height) * height[0]).toInt(), w, h)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,names[index])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,transforms[index],0)
            val rotation = FloatArray(16); Matrix.setIdentityM(rotation,0)
            Matrix.translateM(rotation,0,.5f,.5f,0f)
            val input = inputSizes[index]
            val m = transforms[index]
            val aspect = PipScene.sourceAspect(input.width,input.height,m[0],m[1],m[4],m[5])
            val targetAspect = w.toFloat()/h
            Matrix.scaleM(rotation,0,minOf(1f,targetAspect/aspect),minOf(1f,aspect/targetAspect),1f)
            Matrix.translateM(rotation,0,-.5f,-.5f,0f)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"rotation"),1,false,rotation,0)
            val p = GLES20.glGetAttribLocation(program,"p"); val uv = GLES20.glGetAttribLocation(program,"uv")
            vertices.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(p)
            vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        }
    }

    fun capture(done: (Result<ConcurrentPhoto>) -> Unit) { handler.post {
        done(runCatching {
            check(!closed && received.all { it }) { "PIP frames not ready" }
            draw(snapshot)
            val bytes = ByteBuffer.allocateDirect(output.width * output.height * 4)
            GLES20.glReadPixels(0,0,output.width,output.height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Composition readback failed" }
            val pixels = IntArray(output.width * output.height)
            for (y in 0 until output.height) for (x in 0 until output.width) {
                val at = ((output.height - 1 - y) * output.width + x) * 4
                pixels[y * output.width + x] = (255 shl 24) or ((bytes.get(at).toInt() and 255) shl 16) or
                    ((bytes.get(at+1).toInt() and 255) shl 8) or (bytes.get(at+2).toInt() and 255)
            }
            val bitmap = Bitmap.createBitmap(pixels,output.width,output.height,Bitmap.Config.ARGB_8888)
            val jpeg = try { ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)); it.toByteArray() } } finally { bitmap.recycle() }
            ConcurrentPhoto(jpeg,textures[0].timestamp,null,output,physicalIds,
                textures.mapIndexed { index, texture -> (if (index == 0) cameraId else pipIds[index-1]) to texture.timestamp }.toMap(), serviceIds)
        })
    } }

    fun startVideo(name: String, audio: Boolean = false, done: (Result<Unit>) -> Unit) { handler.post {
        val result = runCatching {
            check(!closed && recorder == null && received.all { it })
            videoName = "${name}_cam${cameraId.replace(Regex("[^A-Za-z0-9_-]"),"_")}${if (pipIds.isEmpty()) "" else "_PIP"}.mp4"
            videoFile = File(app.cacheDir,videoName)
            @Suppress("DEPRECATION") val recording = MediaRecorder()
            recorder = recording
            if (audio) recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recording.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recording.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audio) { recording.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recording.setAudioEncodingBitRate(128_000); recording.setAudioSamplingRate(48_000) }
            recording.setVideoSize(output.width,output.height); recording.setVideoFrameRate(30)
            recording.setVideoEncodingBitRate((output.width * output.height * 6).coerceAtLeast(2_000_000))
            recording.setOutputFile(checkNotNull(videoFile).absolutePath)
            recording.setOnErrorListener { _, _, _ -> onError(IllegalStateException("Video encoder failed")) }
            recording.prepare(); encoder = createWindow(recording.surface); recording.start(); lastVideoNs = 0L
        }
        if (result.isFailure) discardVideo()
        done(result)
    } }

    fun stopVideo(save: Boolean, done: (Result<String>) -> Unit) { handler.post { done(finishVideo(save)) } }

    private fun finishVideo(save: Boolean): Result<String> = runCatching {
        val recording = checkNotNull(recorder) { "Not recording" }
        if (encoder != EGL14.EGL_NO_SURFACE) { current(window); EGL14.eglDestroySurface(display,encoder); encoder = EGL14.EGL_NO_SURFACE }
        recording.stop(); recording.release(); recorder = null
        check(save) { "Recording cancelled" }
        val library = MediaLibrary(app)
        val uri = library.create(videoName,true)
        try { library.write(uri) { output -> checkNotNull(videoFile).inputStream().use { it.copyTo(output) } }; library.publish(uri) }
        catch (e: Exception) { runCatching { library.resolver.delete(uri,null,null) }; throw e }
        uri.toString()
    }.also { discardVideo() }

    private fun discardVideo() {
        if (encoder != EGL14.EGL_NO_SURFACE) {
            runCatching { current(window); EGL14.eglDestroySurface(display,encoder) }
            encoder = EGL14.EGL_NO_SURFACE
        }
        recorder?.let { runCatching { it.reset() }; runCatching { it.release() } }; recorder = null
        videoFile?.delete(); videoFile = null
    }
    private fun current(target: EGLSurface) { check(EGL14.eglMakeCurrent(display,target,target,eglContext)) }
    private fun createWindow(surface: Surface) = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0).also { check(it != EGL14.EGL_NO_SURFACE) }
    private fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it,source); GLES20.glCompileShader(it)
        val compiled = IntArray(1); GLES20.glGetShaderiv(it,GLES20.GL_COMPILE_STATUS,compiled,0); check(compiled[0] != 0)
    }

    /** CameraDevice.onClosed must precede disposal of its input surfaces. */
    fun close(done: () -> Unit) { handler.post {
        try {
            closed = true; runCatching { discardVideo() }
            textures.forEach { it.setOnFrameAvailableListener(null) }
            surfaces.forEach { it.release() }; textures.forEach { it.release() }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                if (window != EGL14.EGL_NO_SURFACE) {
                    runCatching { current(window); GLES20.glDeleteProgram(program); GLES20.glDeleteTextures(names.size,names,0) }
                }
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
                if (snapshot != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,snapshot)
                EGL14.eglDestroyContext(display,eglContext)
            }
        } finally {
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display); viewSurface?.release()
            thread.quitSafely(); done()
        }
    } }
}
