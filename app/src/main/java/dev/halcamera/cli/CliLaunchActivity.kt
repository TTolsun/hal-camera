package dev.halcamera.cli

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.halcamera.MainActivity

/** A translucent gate checks on main before CLEAR_TOP can stop a running benchmark. */
// This is a command gate with no splash UI; it must check BUSY before changing the task.
@android.annotation.SuppressLint("CustomSplashScreen")
class CliLaunchActivity : Activity() {
    private var stopped = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CommandCoordinator.get(this).prepareLiveLaunch {
                if (!stopped && !isFinishing && !isDestroyed) startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }) finish()
    }
    override fun onStop() { stopped = true; super.onStop() }
}
