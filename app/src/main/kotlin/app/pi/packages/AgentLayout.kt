package app.pi.packages

import android.content.Context
import app.pi.engine.PiEngineHost
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import java.io.File

/**
 * Where pi's user files actually are, on the host and in the guest.
 *
 * ## There is one agent directory now (2026-09-23)
 *
 * `PiPaths.agentDir` is `<rootfs>/root/.pi/agent`, which **is** the guest's
 * `/root/.pi/agent`. So the engine, the terminal and this package's guest commands all
 * read and write the same directory with **no bind at all**, and an app-side reader
 * (`ui/PiSessionViewModel`'s settings store) addresses it through the same accessor.
 *
 * Before that the directory lived at `<files>/pi/.pi/agent` and **every** launch path had
 * to bind it over `/root/.pi/agent`, because a guest's default resolution landed on the
 * rootfs copy instead. [PiPackageService]'s `pi install` wrote a `settings.json` the
 * engine never read until [GuestCommand] added its own bind. That entire class of
 * disagreement is gone: there is only one directory, and the guest's own path is it.
 *
 * The two names below survive because three files reference them. They are the same value.
 */
class AgentLayout(
    context: Context,
    /** Host workspace directory; the guest spelling is derived from it. */
    val hostWorkspace: File,
) {

    val paths = PiPaths(
        filesDir = context.filesDir,
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
    )

    /** pi's `PI_CODING_AGENT_DIR` inside the guest (`PiEngineHost.kt:170`, `:302`). */
    val guestAgentDir: String = PiAgentDirContract.GUEST_PATH

    /**
     * `$HOME` inside the guest. `ProotCommand.environment` pins it to `/root`
     * (`PiRuntime.kt:142`), and `hasTrustRequiringProjectResources` compares
     * against `$HOME/.agents/skills` to decide which skills directory is the
     * trusted user one (`trust-manager.ts:186-188`), so the value has to come from
     * there rather than from Android's notion of home.
     */
    val guestHome: String = "/root"

    /**
     * The agent dir. Same directory as [agentMirrorDir]; the name survives from when the
     * rootfs copy and the bound directory were two different places.
     */
    val agentTruthDir: File = paths.agentDir

    /** Same directory as [agentTruthDir] — see the class KDoc. */
    val agentMirrorDir: File = paths.agentDir

    /**
     * The guest spelling of the workspace, computed by the shared rule
     * ([GuestWorkspacePath.under]) that `PiEngineHost` itself now calls, so the
     * package commands, the engine and the `@` completion cannot disagree about
     * which guest path is the engine's cwd.
     *
     * It matters here because `pi install -l` writes `<cwd>/.pi/settings.json` and
     * `hasTrustRequiringProjectResources(cwd)` inspects `<cwd>/.pi`, both using
     * the **guest** cwd — which is also the key pi writes into `trust.json`.
     */
    val guestWorkspace: String = GuestWorkspacePath.under(
        paths.workspaceBase.absolutePath,
        hostWorkspace.absolutePath,
    )

    /** The one bind this layer needs when it runs a command in the workspace. */
    fun workspaceBind(): Pair<String, String> = hostWorkspace.absolutePath to guestWorkspace

    // `agentDirBind()` was here until 2026-09-23: it bound `agentMirrorDir` over
    // `/root/.pi/agent` so a guest command and the engine would agree on which directory
    // is pi's. There is nothing to bind any more — `paths.agentDir` *is* the guest's
    // `/root/.pi/agent`, a rootfs path — and emitting it would put a bind mountpoint
    // exactly where `npm install` writes, which is where proroot's
    // `scandir`/`mkstemp`/`mkdtemp` fail (`docs/proroot-scandir-defect.md`).
    // [app.pi.packages.PiAgentDirContract.misleadingAgentDirBinds] is the replacement
    // check: the agreement is now structural, so the only thing worth asserting is that
    // nothing redirects that guest path somewhere else.

    /**
     * Create the agent dir if it is missing. A package command can run before any engine
     * boot has created it (`PiEngineHost.migrateGuestAgentDir` normally does, `:449-460`),
     * and the rootfs copy of a fresh install does not contain it.
     */
    fun ensureAgentMirrorDir(): Boolean = agentMirrorDir.isDirectory || agentMirrorDir.mkdirs()

    /** The engine's cli.js, exactly as `PiEngineHost` computes it (`:95`). */
    val guestEngineCli: String
        get() = "${PiEngineHost.ENGINE_GUEST_ROOT}/node_modules/${PiEngineHost.PI_PACKAGE}/dist/cli.js"

    /** Node inside the guest (`RuntimeProvisioner.extractNode`, `:115`). */
    val guestNode: String get() = "/opt/node/bin/node"

    /**
     * The host-side `<cwd>/.pi` for this workspace — pi's project config directory,
     * which holds `settings.json` and the four resource directories.
     *
     * Its KDoc said `<cwd>/.pi/settings.json` for a while, which named a file this
     * function never returned; the path is `PiProjectConfig`'s, so the citation and
     * the value now come from the same place.
     */
    fun hostProjectConfigDir(): File = PiProjectConfig.root(hostWorkspace)

    /** True when the runtime is unpacked far enough to run anything. */
    fun runtimeReady(): Boolean = paths.rootfs.isDirectory && paths.prootBinary().isFile && paths.prootLoader().isFile

    /** True when the engine cli.js the guest will exec exists on the host side. */
    fun engineInstalled(): Boolean = File(paths.rootfs, guestEngineCli.removePrefix("/")).isFile
}
