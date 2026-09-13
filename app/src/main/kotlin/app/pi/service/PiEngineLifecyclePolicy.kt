package app.pi.service

/**
 * The foreground service's and the engine's lifecycle decisions, as pure functions.
 *
 * ## Why they are out here
 *
 * Every one of these is a *decision* that used to be inlined in the middle of an
 * Android class, and every one of them has a wrong answer that is silent:
 *
 *  - a service that outlives the engine it exists for keeps a notification saying
 *    "PI 正在运行" and a CPU wake lock nobody needs (`START_STICKY` re-creating a
 *    service whose process — and therefore whose engine — is gone);
 *  - a wake lock taken for the service's whole lifetime both expires after six hours
 *    while a turn is still running and keeps the CPU awake for hours while pi is
 *    idle;
 *  - a countdown that polls five times a second for one displayed second of text.
 *
 * Compose and Android cannot be compiled on every machine this repository is edited
 * on, but these three can: they touch no Android type at all. That is what lets
 * `app/src/test/kotlin/app/pi/service/PiEngineLifecyclePolicyCheck.kt` pin their
 * truth tables on a bare JVM (see `tools/run-app-pure-checks.sh`, harness
 * `lifecycle-policy`).
 *
 * ## Where pi comes in
 *
 * pi has no Android lifecycle at all: its "lifecycle" is a process, and nothing in
 * `packages/coding-agent` reacts to a screen going off (`pi 无对应物（App 的决定）`
 * for every rule here). The parts of pi that *do* exist and are comparable are the
 * two places pi has its own timer, and both are cited where they are used:
 * the TUI's countdown (`modes/interactive/components/countdown-timer.ts:21`) and
 * the dialog deadline pi resolves on its own
 * (`modes/rpc/rpc-mode.ts:115-120`).
 */
object PiEngineLifecyclePolicy {

    /**
     * The notification's stop action.
     *
     * Lives here rather than in `PiEngineService` so that [startCommand] can be
     * compiled and run without Android — the service's own `ACTION_STOP` is this
     * value, so the two cannot drift.
     */
    const val ACTION_STOP = "app.pi.action.STOP_ENGINE"

    /** What one `onStartCommand` means. */
    enum class StartCommand { StartEngine, StopEngine }

    /**
     * What a start command means, including the one the framework sends by itself.
     *
     * `intent == null` is not "the app asked for the service": it is the system
     * re-creating a `START_STICKY` service whose process was killed. The engine lived
     * in that process, so there is nothing left to keep alive — and answering such a
     * call with a notification and a wake lock is a claim about a running agent that
     * is not true. `PiEngineController`/the ViewModel can start a new engine later;
     * the service cannot, and must not pretend it did.
     */
    fun startCommand(action: String?, systemRestart: Boolean): StartCommand = when {
        action == ACTION_STOP -> StartCommand.StopEngine
        systemRestart -> StartCommand.StopEngine
        else -> StartCommand.StartEngine
    }

    /**
     * Whether the CPU must stay awake: while the runtime is being unpacked and pi is
     * starting (both are CPU-bound with nothing on screen), or while a turn is
     * running (a model call plus unbounded tool calls — the whole reason the service
     * exists).
     *
     * Deliberately **not** "while the service runs": an idle engine that is merely
     * alive needs no CPU, and a lock held for hours is how a phone loses battery to
     * an app that is doing nothing. It is also what makes the six-hour cap
     * (`PiEngineService.WAKE_LOCK_TIMEOUT_MS`) harmless for every ordinary case: the
     * lock is taken per busy period, so it only ever expires inside a single
     * six-hour-long turn.
     */
    fun shouldHoldWakeLock(booting: Boolean, turnRunning: Boolean): Boolean = booting || turnRunning

    /**
     * Whether the foreground service should exist right now.
     *
     * `keepAlive` is the user's switch (`app.runtime.keepAlive`); the other two say
     * whether there is anything to protect — an attached engine, or a boot in
     * progress. After the engine exits on its own, this is false: a foreground
     * service with no engine is a permanent notification about a process that does
     * not exist, and the honest state is the engine's own (`Boot.Failed` + 重试).
     */
    fun shouldServiceRun(keepAlive: Boolean, engineAttached: Boolean, bootInProgress: Boolean): Boolean =
        keepAlive && (engineAttached || bootInProgress)

    /**
     * How long a one-second countdown may sleep: up to the moment its displayed
     * number changes, never past the deadline it is counting down to.
     *
     * pi's own countdown ticks with `setInterval(..., 1000)`
     * (`modes/interactive/components/countdown-timer.ts:21`, disposed as soon as the
     * count reaches zero at `:26-29`), i.e. once per displayed second. This app polls
     * at a fixed 200 ms for the same display, which is five wake-ups per second that
     * show the same number; aligning the sleep to the boundary keeps the display
     * exact and the deadline exact (the caller breaks as soon as `remaining <= 0`)
     * at one wake-up per displayed second.
     *
     * @return 0 when there is nothing left to wait for, otherwise 1..1000 ms.
     */
    fun nextCountdownDelayMs(remainingMs: Long): Long {
        if (remainingMs <= 0L) return 0L
        // What the countdown displays now — the same rounding the caller uses.
        val shownSeconds = (remainingMs + 999L) / 1000L
        val untilNextSecond = remainingMs - (shownSeconds - 1L) * 1000L
        return untilNextSecond.coerceIn(1L, MAX_COUNTDOWN_SLEEP_MS)
    }

    /**
     * A hard ceiling for [nextCountdownDelayMs], so a bad input (a clock that jumped,
     * a timeout measured in days) cannot turn the loop into "never wake up again"
     * and leave the countdown frozen on screen.
     */
    const val MAX_COUNTDOWN_SLEEP_MS = 1000L
}
