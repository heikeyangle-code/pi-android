package app.pi.packages

/**
 * The one guest path the app and the engine must agree on, and the check that they do.
 *
 * `PiEngineHost` launches pi with the durable agent dir bound over the guest's
 * `/root/.pi/agent` (`PiEngineHost.kt:285-294`) and pins the same path in the
 * environment (`:302-303`). Every other process the app starts *for* pi has to do the
 * same, or the two disagree about which `settings.json`, `npm/` tree, `extensions/`
 * and `trust.json` are pi's — and that failure is silent in the worst way: `pi install`
 * prints `Installed npm:foo`, exits 0, and the running engine reads a directory that
 * never saw it. [GuestCommand] had exactly this hole until its bind was added.
 *
 * This object exists so the agreement is a value that can be checked rather than a
 * literal repeated in two files: [GuestCommand] asserts [misleadingAgentDirBinds] over the bind
 * list it hands to proot, and `PiAgentDirContractCheck` covers the predicate.
 */
object PiAgentDirContract {

    /** `PiEngineHost.guestAgentDir` — pi's agent dir inside the guest. */
    const val GUEST_PATH: String = "/root/.pi/agent"

    /** The variable pi resolves its agent dir from (`config.ts:528-532`). */
    const val ENV_VAR: String = "PI_CODING_AGENT_DIR"

    /** Also set by the engine (`PiEngineHost.kt:303`); pi's default is this too. */
    const val SESSION_ENV_VAR: String = "PI_CODING_AGENT_SESSION_DIR"

    fun sessionDir(guestAgentDir: String): String = "$guestAgentDir/sessions"

    /**
     * The binds in [binds] that would put a **different** host directory on the guest's
     * [GUEST_PATH], or on something under it. Empty is the healthy answer.
     *
     * The agreement used to be "every launch path binds the *same* host directory to this
     * guest path", and the predicate that asserted it was `bindsAgentDir(binds, host)`.
     * Since 2026-09-23 the agreement is **structural** instead: [GUEST_PATH] is the rootfs
     * directory `PiPaths.agentDir`, so a guest command reaches the very directory the
     * engine does **with no bind at all** — and a bind on that path is now the only way to
     * break it, which is what this reports.
     */
    fun misleadingAgentDirBinds(binds: List<Pair<String, String>>): List<Pair<String, String>> =
        binds.filter { (_, guest) -> guest == GUEST_PATH || guest.startsWith("$GUEST_PATH/") }
}
