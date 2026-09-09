package dev.cameradoctor.benchmark

/**
 * Six-axis build identity comparison between two runs (docs/PLAN-BenchMarker-v0.3.md 5.4, 7.4).
 * A single "same build" boolean would hide the case that matters most in camera HAL work: the same Android
 * fingerprint with a different vendor binary. Nullable axes are null when either side lacks the information.
 */
data class BuildIdentityComparison(
    val sameSystemFingerprint: Boolean,
    val sameVendorFingerprint: Boolean?,
    val sameCameraInfoVersion: Boolean?,
    val sameAppVersion: Boolean,
    val sameSubjectLabel: Boolean?,
    val sameSubjectCommit: Boolean?
) {
    /**
     * "Camera build" line of the UI. Any known axis that differs makes it false; true only when every known axis
     * agrees; null when neither vendor fingerprint nor INFO_VERSION is known on both sides. A same vendor
     * fingerprint must not hide a different INFO_VERSION (PR #11 follow-up review).
     */
    val sameCameraBuild: Boolean?
        get() {
            val known = listOfNotNull(sameVendorFingerprint, sameCameraInfoVersion)
            return if (known.isEmpty()) null else known.all { it }
        }

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "same_system_fingerprint" to sameSystemFingerprint, "same_vendor_fingerprint" to sameVendorFingerprint,
        "same_camera_info_version" to sameCameraInfoVersion, "same_app_version" to sameAppVersion,
        "same_subject_label" to sameSubjectLabel, "same_subject_commit" to sameSubjectCommit
    )

    companion object {
        fun fromJsonMap(m: Map<String, Any?>?) = BuildIdentityComparison(
            m?.get("same_system_fingerprint") as? Boolean ?: false, m?.get("same_vendor_fingerprint") as? Boolean,
            m?.get("same_camera_info_version") as? Boolean, m?.get("same_app_version") as? Boolean ?: false,
            m?.get("same_subject_label") as? Boolean, m?.get("same_subject_commit") as? Boolean
        )
    }
}

/** The identity-bearing parts of a run, so the comparison does not need whole BenchmarkRun objects. */
data class BuildIdentity(val device: DeviceInfo, val app: AppInfo, val subject: SubjectLabel) {
    companion object {
        fun of(run: BenchmarkRun) = BuildIdentity(run.device, run.app, run.subject)

        fun compare(a: BuildIdentity, b: BuildIdentity): BuildIdentityComparison = BuildIdentityComparison(
            sameSystemFingerprint = a.device.fingerprint == b.device.fingerprint,
            sameVendorFingerprint = both(a.device.vendorFingerprint, b.device.vendorFingerprint),
            sameCameraInfoVersion = both(a.device.cameraInfoVersion, b.device.cameraInfoVersion),
            sameAppVersion = a.app.versionName == b.app.versionName && a.app.versionCode == b.app.versionCode,
            sameSubjectLabel = both(a.subject.subjectBuildLabel, b.subject.subjectBuildLabel),
            sameSubjectCommit = both(a.subject.subjectCommit, b.subject.subjectCommit)
        )

        /** Equal only when both sides carry a value; blank counts as missing. */
        private fun both(x: String?, y: String?): Boolean? =
            if (x.isNullOrBlank() || y.isNullOrBlank()) null else x == y
    }
}
