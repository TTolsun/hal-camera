package com.android.compatibility.common.util;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

/**
 * The one method of compatibility-device-util-axt's MediaUtils that the vendored camera tests call.
 * Upstream lives in cts/common/device-side/util-axt; the rest of that class is not needed here.
 */
public final class MediaUtils {
    private static final String TAG = "MediaUtils";

    private MediaUtils() {}

    /** True when at least one regular codec of the given kind ("video" or "audio") exists on the device. */
    public static boolean checkCodecForDomain(boolean encoder, String domain) {
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for (MediaCodecInfo info : infos) {
            if (info.isEncoder() != encoder) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.startsWith(domain + "/")) return true;
            }
        }
        Log.i(TAG, "SKIPPING test: no " + domain + (encoder ? " encoders" : " decoders"));
        return false;
    }
}
