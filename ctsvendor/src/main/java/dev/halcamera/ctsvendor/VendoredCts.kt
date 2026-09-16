package dev.halcamera.ctsvendor

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.hardware.camera2.CameraDevice
import android.os.Bundle
import androidx.test.InstrumentationRegistry

/**
 * The process-wide state the vendored CTS sources expect cts-tradefed to have set up: an Instrumentation to
 * take the target context from, the instrumentation arguments, the activity ActivityTestRule would have
 * launched, and the test instance currently running (so the host can pull the camera from under it to abort).
 */
object VendoredCts {
    /** Upstream builds this tree with min_sdk 34; below that it calls APIs that do not exist. */
    const val MIN_SDK = 34

    @Volatile private var installed = false
    @Volatile private var hostActivity: Activity? = null
    @Volatile private var runningTest: Any? = null

    /**
     * Registers the in-app Instrumentation once. Must run before any vendored test class is loaded, because
     * CameraParameterizedTestCase reads the arguments in a static initializer. `perf-measure=on` limits the
     * Parameterized rows to adoptShellPerm=false: a normal app cannot adopt the shell identity.
     */
    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val args = Bundle().apply { putString("perf-measure", "on") }
            InstrumentationRegistry.registerInstance(AppInstrumentation(context.applicationContext), args)
            installed = true
        }
    }

    @JvmStatic fun currentActivity(): Activity? = hostActivity
    @JvmStatic fun attachActivity(activity: Activity) { hostActivity = activity }
    @JvmStatic fun detachActivity(activity: Activity) { if (hostActivity === activity) hostActivity = null }

    /** Called from the patched Camera2SurfaceViewTestCase.setUp and tearDown. */
    @JvmStatic fun attachTest(test: Any) { runningTest = test }
    @JvmStatic fun detachTest(test: Any) { if (runningTest === test) runningTest = null }

    /**
     * Closes the CameraDevice the running test holds in its protected `mCamera` field, if any. The test then
     * fails on its next camera call instead of recording on for minutes after the user pressed 중단.
     */
    @JvmStatic
    fun closeRunningCamera(): Boolean {
        val test = runningTest ?: return false
        var cls: Class<*>? = test.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField("mCamera") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                val device = field.get(test) as? CameraDevice ?: return false
                runCatching { device.close() }
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    private class AppInstrumentation(private val app: Context) : Instrumentation() {
        override fun getTargetContext(): Context = app
        override fun getContext(): Context = app
        override fun getUiAutomation(): UiAutomation? = null
        override fun getUiAutomation(flags: Int): UiAutomation? = null
    }
}
