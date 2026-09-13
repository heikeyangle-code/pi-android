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
 * ## The engine spelling, and why it is not `/workspace`
 *
 * `PiEngineHost` mirrors the *files directory* at [GUEST_ROOT] and mounts the
 * workspace at the path that remains after the files-directory prefix
 * ([under]). With the workspace at `<files>/pi/workspaces/workspace-1` the guest
 * sees it at
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
 */
object GuestWorkspacePath {

    /** Where the workspace lives under the files directory. */
    const val RELATIVE: String = "pi/workspaces/workspace-1"

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

    /** The workspace directory itself. */
    fun host(filesDir: File): File = File(filesDir, RELATIVE)

    /**
     * [host], **created if it is missing**, and returned either way.
     *
     * The app cannot assume this directory exists. It is app-private storage, so
     * Android may clear it between launches, and nothing creates it at install time;
     * only two of the paths that need it used to create it — the engine before it
     * binds `workspace.absolutePath` (`PiEngineHost.kt:259-260`) and the terminal
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
     */
    fun ensureHost(filesDir: File): File {
        val dir = host(filesDir)
        dir.mkdirs()
        return dir
    }

    /**
     * The engine's guest spelling of [hostWorkspace]: the files directory is
     * mirrored at [GUEST_ROOT], so the spelling is what remains after the prefix
     * is removed. An empty remainder is the root itself.
     *
     * A pure function of two strings, not of `File`, so a bare-JVM harness can pin
     * it and so the rule reads identically at every call site.
     */
    fun under(filesDir: String, hostWorkspace: String): String {
        val relative = hostWorkspace.removePrefix(filesDir).trimStart('/')
        return if (relative.isEmpty()) GUEST_ROOT else "$GUEST_ROOT/$relative"
    }
}
