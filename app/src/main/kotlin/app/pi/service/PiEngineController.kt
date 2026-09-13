package app.pi.service

/**
 * Process-wide handle to the engine.
 *
 * ## What it is now
 *
 * The engine process is spawned and owned by `PiEngineHost`, which lives in
 * `PiSessionViewModel` — not in this object, and not in `PiEngineService`. The
 * foreground service therefore has no way to reach the engine it exists for, and
 * this object is the one place the two halves meet: the owner registers a handler
 * ([registerStopHandler]) and [stop] invokes it.
 *
 * That gap was user-visible before this: the notification's 停止 action called
 * [stop], which only set [state] — so tapping 「停止」 removed the notification,
 * released the wake lock and left pi running with nothing keeping its process alive.
 * Every reader of [state] was a search hit in this file, which is what made the
 * silence possible.
 *
 * ## What it is not
 *
 * [state] is **not** the authority on whether an engine is alive. The engine's own
 * `PiEngineSession.state` (collected by the ViewModel and published as
 * `UiState.engine`) is, because the process can die without anyone asking this
 * object, and a second copy of "is it running" is exactly the shape
 * `docs/pi-sourced-lists.md` warns about. [state] records what has been *asked of*
 * the engine; it has no reader today and is kept as the handle a future
 * service-side supervisor would need.
 *
 * See docs/pi-android-app-design.md §7 for the supervisor design this belongs to.
 */
object PiEngineController {

    enum class State { NotProvisioned, Starting, Ready, Failed, Stopped }

    @Volatile
    var state: State = State.NotProvisioned
        private set

    /**
     * The engine's own teardown, registered by whoever owns `PiEngineHost`.
     *
     * Volatile and a single slot on purpose: there is one engine per app process
     * (`PiEngineHost`'s lifecycle lock is process-wide for the same reason), and the
     * last registrant is the only one that can still be holding it.
     */
    @Volatile
    private var stopHandler: (() -> Unit)? = null

    /** Called by the engine's owner, after the host exists. */
    fun registerStopHandler(handler: () -> Unit) {
        stopHandler = handler
    }

    /**
     * Called by the owner when it goes away. Identity-checked so a *newer* owner's
     * handler is never dropped by an older one's teardown — the same reasoning as
     * `PiSessionViewModel`'s "only the engine that is still current may write state".
     */
    fun unregisterStopHandler(handler: () -> Unit) {
        if (stopHandler === handler) stopHandler = null
    }

    /**
     * Ask the engine to stop. Idempotent; a no-op when nothing is registered (the
     * app has no engine owner in this process — there is nothing to stop).
     *
     * Non-blocking by contract: the handler settles the turn on a scope that outlives
     * the ViewModel, because a caller here is a service callback on the main thread.
     */
    fun stop() {
        state = State.Stopped
        stopHandler?.invoke()
    }
}
