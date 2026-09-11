package app.pi.packages

/**
 * Puts [ExtensionLifecycle] and the real engine restart together, so the "never
 * restart silently" rule is code rather than an intention.
 *
 * The two halves cannot enforce it alone:
 *
 *  - [ExtensionLifecycle] knows what is pending but not whether a turn is running;
 *  - [app.pi.engine.PiEngineHost.restart] knows the turn state and can refuse, but
 *    has no idea a resource change is waiting to be picked up.
 *
 * This coordinator is the only place that calls one with the other's answer in
 * hand, and it passes `allowInterrupt = false` **always** — even after the user has
 * confirmed. That is deliberate: confirmation is not a standing licence to kill
 * whatever the engine happens to be doing later. A queued follow-up can start a new
 * turn between the tap and the call, and the host's own check is what catches it. If
 * it does, the request goes back to "waiting for the turn to end" rather than being
 * reported as a failure, because nothing was attempted.
 */
class EngineRestartCoordinator(
    private val lifecycle: ExtensionLifecycle,
    /** Live turn state, normally `{ engineHost.turnRunning }`. */
    private val isTurnRunning: () -> Boolean,
    /** The real restart, normally `{ reason, allow -> engineHost.restart(reason, ...) }`. */
    private val restartEngine: suspend (reason: String, allowInterrupt: Boolean) -> Outcome,
) {

    /** The outcome of a restart attempt, decoupled from `PiEngineHost.Restart`. */
    sealed interface Outcome {
        data object Ok : Outcome

        /** Nothing was changed; the engine is still running. */
        data class Refused(val message: String) : Outcome

        /** The engine was stopped and could not be brought back. */
        data class Failed(val message: String) : Outcome
    }

    /**
     * Step 1: the user pressed 重启 (or an install completed and the UI offered it).
     *
     * This never restarts anything. It returns what the UI must do next:
     * show the confirmation, or tell the user a turn is in the way.
     */
    fun request(reason: String): ExtensionLifecycle.RequestOutcome =
        lifecycle.requestRestart(turnRunning = isTurnRunning())

    /**
     * Step 2: the user confirmed. Only now is the engine touched.
     *
     * Safety rails, in order:
     *
     *  1. `restartStarted()` refuses unless the state machine is genuinely in
     *     [ExtensionLifecycle.State.AwaitingConfirmation], so a second tap, a
     *     stale dialog or a dismissed-then-replayed callback cannot restart twice.
     *  2. The host is asked with `allowInterrupt = false`, so it re-checks the turn
     *     independently and refuses if one appeared after the confirmation.
     *  3. Every non-`Ok` outcome is recorded on the state machine, so the UI shows a
     *     pending restart with a reason instead of looking done.
     */
    suspend fun confirm(reason: String): Outcome {
        if (!lifecycle.restartStarted()) {
            return Outcome.Refused("当前没有待确认的重启请求，已忽略。")
        }
        val outcome = restartEngine(reason, false)
        when (outcome) {
            is Outcome.Ok -> lifecycle.restartSucceeded()
            is Outcome.Failed -> lifecycle.restartFailed(outcome.message)
            is Outcome.Refused -> {
                // Not a failure: the engine was never touched. Go back to the waiting
                // state the confirmation came from, carrying the engine's own sentence
                // so the UI can say why. This must not go through `requestRestart`:
                // from `Restarting` that answers BusyWithPackageCommand and changes
                // nothing, which left the screen reading "正在重启引擎…" with no button
                // to leave it.
                lifecycle.restartRefused(outcome.message)
            }
        }
        return outcome
    }
}

/**
 * Map the engine's richer result onto [EngineRestartCoordinator.Outcome].
 *
 * `RefusedNeedsProvisioning` is folded into [EngineRestartCoordinator.Outcome.Failed]
 * rather than `Refused`, because the runtime genuinely does need re-provisioning and
 * the user has to be told: `RuntimeProvisioner.wipe()` deletes the guest's
 * `/root/.pi/agent`, taking `trust.json` and every installed package with it. Calling
 * that "refused, nothing to see" would be the quiet version of a destructive event.
 */
fun app.pi.engine.PiEngineHost.Restart.asOutcome(): EngineRestartCoordinator.Outcome = when (this) {
    is app.pi.engine.PiEngineHost.Restart.Ok -> EngineRestartCoordinator.Outcome.Ok
    is app.pi.engine.PiEngineHost.Restart.RefusedTurnRunning ->
        EngineRestartCoordinator.Outcome.Refused(detail)
    is app.pi.engine.PiEngineHost.Restart.RefusedNeedsProvisioning ->
        EngineRestartCoordinator.Outcome.Failed(detail)
    is app.pi.engine.PiEngineHost.Restart.Failed ->
        EngineRestartCoordinator.Outcome.Failed(
            if (detail.isNullOrBlank()) message else "$message\n$detail",
        )
}
