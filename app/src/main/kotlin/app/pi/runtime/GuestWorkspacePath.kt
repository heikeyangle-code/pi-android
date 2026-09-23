package app.pi.runtime

import java.io.File

/**
 * The workspace's location — on the host and in the guest — in **one** place.
 *
 * Four launch paths bind this one host directory into the guest (the RPC engine,
 * the terminal's pty, the package commands, and `fd` for the `@` completion), and
 * every one of them used to work out the guest spelling for itself. That is the
 * shape that produced two shipped bugs — a session file path the app could not
 * find, and an agent-dir bind one launch path did not pass — because a rule five
 * call sites re-derive is a rule with five chances to differ. So the rule lives
 * here and the call sites read it.
 *
 * ## One rule, and one *current* workspace for the process
 *
 * The rule has two halves and this object owns both:
 *
 *  1. **Where** a workspace lives: under the base this object mirrors at [GUEST_ROOT] — that
 *     is `PiPaths.workspaceBase`, `<rootfs>/workspace`, so the directory is
 *     `<rootfs>/workspace/pi/workspaces/<name>` and pi sees it as
 *     `/workspace/pi/workspaces/<name>`. (Until 2026-09-23 the base was the app's files
 *     directory; the workspace moved into the rootfs and the base moved with it, while every
 *     guest spelling stayed byte-identical.) [ROOT_RELATIVE] is the per-install subdirectory,
 *     one workspace each, and [DEFAULT_RELATIVE] is the workspace an install that has never
 *     chosen one uses — `workspace-1`, the one this app shipped with, never migrated and never
 *     rebuilt.
 *  2. **Which** workspace is current: there is exactly one per process, because
 *     there is exactly one engine, one terminal and one write boundary. That name
 *     is persisted in the app's own settings key and resolved by
 *     [WorkspaceStore]; this object only holds the *resolved* answer, in
 *     [RELATIVE], and [adoptRelative] is how the store publishes a decision.
 *
 * [RELATIVE] used to be a `const` spelling of `workspace-1`. It is a `val` with a
 * getter now so that every existing reader — including two screens this batch may
 * not touch (`ui/screens/SessionsScreen.kt`, `ui/screens/ProjectResources.kt`,
 * which compare pi's recorded cwd against this prefix to label a session) —
 * follows the current workspace without a second edit. A reader that kept a copy
 * of the old constant would show the *previous* workspace's sessions under the
 * new engine, which is exactly the "引擎在一个目录、你在另一个目录" split this
 * object exists to prevent.
 *
 * ## The engine spelling, and why it is not `/workspace`
 *
 * `PiEngineHost` mirrors the *workspace base* at [GUEST_ROOT] and mounts the
 * workspace at the path that remains after that base's prefix ([under]). With the
 * workspace at `<rootfs>/workspace/pi/workspaces/workspace-1` the guest sees it at
 *
 *     /workspace/pi/workspaces/workspace-1
 *
 * which is also the cwd pi is started with, the key pi writes into `trust.json`,
 * and what a relative path in a tool call resolves against.
 *
 * ## The terminal's spelling, which differs on purpose and costs something
 *
 * `PtyLauncher` mounts the **same host directory** one level up, at plain
 * `/workspace` ([TERMINAL_GUEST_PATH]). So the guest spells one directory two ways:
 *
 *     engine    /workspace/pi/workspaces/workspace-1   == the workspace
 *     terminal  /workspace                             == the workspace
 *
 * and they are not interchangeable: in the terminal
 * `/workspace/pi/workspaces/workspace-1` is a *subdirectory of the workspace*
 * that does not exist, while in the engine `/workspace` is an empty directory
 * that is not the workspace. This object does not merge them — both mounts are
 * load-bearing today and changing either is a behaviour change that needs a
 * device pass — it makes the disagreement a named constant instead of a comment
 * that can be wrong. It was wrong: `PtyLauncher`'s KDoc called its `/workspace`
 * "the path [PiEngineHost] maps a workspace to". `bridge/DeviceWorkspace` and the
 * `/app/health` payload publish both spellings, so nothing has to guess.
 *
 * The terminal's mount point is fixed; what it *binds there* is the current
 * workspace and moves with it. That is the whole point of the split: the guest
 * path `/workspace` is the terminal's contract with the user and must not change,
 * while the host directory behind it must.
 */
object GuestWorkspacePath {

    /**
     * The workspace an install that has never chosen one uses.
     *
     * `workspace-1`, the directory this app has always had. Kept as a constant
     * because it is also the fallback target of [WorkspaceStore]'s recovery rule:
     * a persisted choice that no longer names a real workspace must land here, and
     * landing on a *default* rather than on "whichever directory is left" is what
     * keeps the failure explainable.
     */
    const val DEFAULT_RELATIVE: String = "pi/workspaces/workspace-1"

    /** The directory every workspace is a child of, under [PiPaths.workspaceBase]. */
    const val ROOT_RELATIVE: String = "pi/workspaces"

    /**
     * The leaf name of the default workspace (`workspace-1`).
     *
     * Derived from [DEFAULT_RELATIVE] rather than written again: the two are the
     * same fact, and a second literal is how they would come apart.
     */
    val DEFAULT_NAME: String = DEFAULT_RELATIVE.substringAfterLast('/')

    /** The mount point every launch path except the terminal uses. */
    const val GUEST_ROOT: String = "/workspace"

    /**
     * The path the terminal mounts the same directory at.
     *
     * Equal to [GUEST_ROOT] by value and **not** by meaning — see the class KDoc.
     * Its own constant so that a reader of `PtyLauncher` sees a named decision
     * rather than a bare literal.
     */
    const val TERMINAL_GUEST_PATH: String = "/workspace"

    /**
     * The workspace in effect for this process, relative to [PiPaths.workspaceBase].
     *
     * Defaults to [DEFAULT_RELATIVE] and is changed only by [adoptRelative], which
     * [WorkspaceStore] calls once it has resolved (and, when necessary, corrected)
     * the persisted choice. `@Volatile` because the store may adopt from the main
     * thread while `DeviceWorkspace.refresh` — which the bridge's request threads
     * call — reads it.
     */
    @Volatile
    private var adopted: String = DEFAULT_RELATIVE

    /** [adopted], as a property so every existing reader follows it. */
    val RELATIVE: String get() = adopted

    /**
     * Publish [relative] as the process's current workspace.
     *
     * Public because [WorkspaceStore] lives in this package and Kotlin's
     * `internal` is module-wide rather than package-wide, so there is no narrower
     * modifier that would actually restrict it. **Call it only from
     * `WorkspaceStore`**: it is the object that has resolved the persisted choice,
     * validated the directory, and is about to (or has just) moved the engine to
     * it. A blank or relative-less value is ignored rather than adopted, because
     * the one thing worse than a wrong workspace is a workspace that is not one.
     */
    fun adoptRelative(relative: String) {
        if (relative.isBlank()) return
        adopted = relative
    }

    /** The workspace directory itself, for the current workspace. */
    fun host(base: File): File = host(base, RELATIVE)

    /** The workspace directory [relative] names, relative to [base]. */
    fun host(base: File, relative: String): File = File(base, relative)

    /**
     * [host], **created if it is missing**, and returned either way.
     *
     * The app cannot assume this directory exists. It is app-private storage, so
     * Android may clear it between launches, and nothing creates it at install time;
     * only two of the paths that need it used to create it — the engine before it
     * binds `workspace.absolutePath` (`PiEngineHost.kt:257-258`) and the terminal
     * before the same bind (`PtyLauncher.prepare`). Everything else that resolves a
     * path under it assumed it was there: proot binds nothing that does not exist on
     * the host side, `pi install -l` writes `<cwd>/.pi`, and `export_html` writes a
     * workspace-relative file. One `mkdirs()` here is what makes every caller agree.
     *
     * Idempotent — a `mkdirs()` on an existing directory is a no-op — and it creates
     * the intermediate `pi/workspaces` too, which nothing else creates. A failure is
     * reported by the return value rather than thrown: these callers are UI paths
     * that must keep working on a full disk, and each already handles a missing
     * directory (an empty `@` list, a setting that does not persist). What they could
     * not handle was a directory that looked configured and did not exist.
     *
     * **No fallback here.** If the current workspace's directory was deleted
     * externally, this recreates *that* directory rather than quietly moving to
     * another one. Deciding that the user must be moved back to the default is
     * [WorkspaceStore.refresh]'s job, and it says so in words; a `mkdirs()` that
     * picked a different directory would be the silent switch that rule forbids.
     */
    fun ensureHost(base: File): File = ensureHost(base, RELATIVE)

    /** [ensureHost] for an explicit workspace, which may be one that is not current. */
    fun ensureHost(base: File, relative: String): File {
        val dir = host(base, relative)
        dir.mkdirs()
        return dir
    }

    /**
     * The engine's guest spelling of [hostWorkspace]: [base] is mirrored at [GUEST_ROOT], so
     * the spelling is what remains after that prefix is removed. An empty remainder is the
     * root itself.
     *
     * [base] is `PiPaths.workspaceBase` (`<rootfs>/workspace`) — it was `<filesDir>` until
     * the workspace moved into the rootfs on 2026-09-23. **The rule and every guest spelling
     * it produces are unchanged**; only the host base moved, which is why pi's
     * `trust.json` keys survive the change.
     *
     * A pure function of two strings, not of `File`, so a bare-JVM harness can pin it and so
     * the rule reads identically at every call site.
     */
    fun under(base: String, hostWorkspace: String): String {
        val relative = hostWorkspace.removePrefix(base).trimStart('/')
        return if (relative.isEmpty()) GUEST_ROOT else "$GUEST_ROOT/$relative"
    }
}
