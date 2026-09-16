package dev.halcamera.cts

import dev.halcamera.cts.onoff.FastOnOffRunner
import dev.halcamera.cts.recording.BasicRecordingRunner
import dev.halcamera.cts.switching.SwitchingRunner

/** The device side of [CtsCatalog]: which runner performs each case id. Unknown ids are a programming error. */
object CtsRunners {
    fun create(caseId: String, env: CaseEnvironment): CtsRunner = when (caseId) {
        CtsCatalog.BASIC_RECORDING -> BasicRecordingRunner(env)
        CtsCatalog.FAST_ON_OFF -> FastOnOffRunner(env)
        CtsCatalog.SWITCHING -> SwitchingRunner(env)
        else -> throw IllegalArgumentException("unknown CTS case: $caseId")
    }
}
