package app.pi.service

/**
 * Process-wide handle to the engine.
 *
 * Placeholder with the final shape: the runtime layer (proot + rootfs + Node)
 * and the RPC client are wired in here. It is deliberately an object rather than
 * part of the service so the chat screen can observe it without binding.
 *
 * See docs/pi-android-app-design.md §7 for the supervisor design this will
 * implement: pi runs as a child of the foreground service, its stdout is read on
 * a dedicated dispatcher, and the UI re-attaches with `get_entries { since }`
 * rather than re-deriving state.
 */
object PiEngineController {

    enum class State { NotProvisioned, Starting, Ready, Failed, Stopped }

    @Volatile
    var state: State = State.NotProvisioned
        private set

    fun stop() {
        state = State.Stopped
    }
}
