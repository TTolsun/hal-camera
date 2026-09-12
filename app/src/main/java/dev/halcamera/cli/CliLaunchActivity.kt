package dev.halcamera.cli

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.halcamera.MainActivity

/** A translucent gate checks on main before CLEAR_TOP can stop a running benchmark. */
class CliLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CommandCoordinator.get(this).canOpenLive()) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
