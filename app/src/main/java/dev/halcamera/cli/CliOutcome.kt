package dev.halcamera.cli

/**
 * How a request ends. The screen reports a result or a failure; a cancel or the execution deadline may already
 * have been requested. A deadline outranks everything, so a timed-out request never reads as succeeded.
 */
data class CliOutcome(val state: String, val error: CliFailure?, val cancelEffective: Boolean?) {
    /** What a cancel does to a request in a given stored state. */
    enum class CancelStep {
        /** Already saving or finished: the save completes with its actual result. */
        IGNORE,
        /** Not yet running and never reached the screen: fail it here. */
        FAIL,
        /** Not yet running but the screen may be preparing it: stop the screen, then fail it. */
        STOP_SCREEN_AND_FAIL,
        /** Running library work: mark it cancelling and let the work finish. */
        MARK_CANCELLING,
        /** Running on a screen: mark it cancelling and ask the screen to stop. */
        MARK_CANCELLING_AND_STOP_SCREEN
    }

    companion object {
        const val TIMEOUT = "EXECUTION_TIMEOUT"
        const val CANCELLED = "CANCELLED"

        /** The terminal state after the screen reported [failure] (or success) while [cancellation] was pending. */
        fun completed(cancellation: String?, failure: CliFailure?, resultCancelled: Boolean): CliOutcome {
            val state = when {
                cancellation == TIMEOUT -> "failed"
                failure?.code == CANCELLED -> "cancelled"
                failure != null -> "failed"
                cancellation == CANCELLED && resultCancelled -> "cancelled"
                else -> "succeeded"
            }
            val error = if (cancellation == TIMEOUT) CliFailure(TIMEOUT, "App execution deadline exceeded")
                else failure ?: cancellation?.takeIf { state != "succeeded" }?.let { code -> CliFailure(code, code) }
            return CliOutcome(state, error, cancellation?.let { state != "succeeded" })
        }

        /** The code a failure is filed under: once the deadline passed, every failure is the timeout. */
        fun failureCode(code: String, timedOut: Boolean) = if (timedOut) TIMEOUT else code
        fun failureState(code: String) = if (code == CANCELLED) "cancelled" else "failed"

        /** A later cancel cannot hide an earlier timeout. */
        fun cancellation(current: String?, requested: String) = if (current == TIMEOUT) current else requested

        fun cancelStep(state: String?, spec: CliCommandSpec): CancelStep = when (state) {
            null, "saving", in CliStates.terminal -> CancelStep.IGNORE
            "accepted", "preparing" -> if (spec.earlyCancelNotifiesHost) CancelStep.STOP_SCREEN_AND_FAIL else CancelStep.FAIL
            else -> if (spec.target == CliCommandSpec.Target.LIBRARY) CancelStep.MARK_CANCELLING else CancelStep.MARK_CANCELLING_AND_STOP_SCREEN
        }

        /** A screen that leaves without handing over abandons a request it had not started yet. */
        fun abandonedOnDetach(handover: Boolean, state: String?) = !handover && (state == "accepted" || state == "preparing")
    }
}
