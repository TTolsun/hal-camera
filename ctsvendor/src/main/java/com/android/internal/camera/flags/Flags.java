package com.android.internal.camera.flags;

import android.os.Build;

/**
 * Stand-in for the aconfig-generated camera platform flags. A device running the release that introduced a
 * feature has it enabled, so each flag maps to the API level where the feature became public.
 */
public final class Flags {
    private Flags() {}

    /** CameraDevice.CameraDeviceSetup shipped with Android 15. */
    public static boolean cameraDeviceSetup() {
        return Build.VERSION.SDK_INT >= 35;
    }

    /** CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY shipped with Android 15. */
    public static boolean cameraAeModeLowLightBoost() {
        return Build.VERSION.SDK_INT >= 35;
    }

    /** EXTENSION_NIGHT_MODE_INDICATOR shipped with Android 16. */
    public static boolean nightModeIndicator() {
        return Build.VERSION.SDK_INT >= 36;
    }

    /** CONTROL_AE_PRIORITY_MODE shipped with Android 16. */
    public static boolean aePriority() {
        return Build.VERSION.SDK_INT >= 36;
    }
}
