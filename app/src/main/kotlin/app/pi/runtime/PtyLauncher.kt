package app.pi.runtime

import android.content.Context
import java.io.File

/**
 * Builds direct kernel-PTY launch plans for Pi's built-in Ubuntu terminal
 * (`com.termux.terminal.TerminalSession` / `libtermux.so` `forkpty` + live
 * `TIOCSWINSZ` / `SIGWINCH` resizing).
 *
 * The terminal runs inside the exact same Ubuntu rootfs, `~/.pi/agent` directory,
 * workspace mounts, and runtime selection (`tawcroot` / `proroot` / `proot`) as
 * `PiEngineHost`, so files, packages, models, sessions, and `pi` CLI state are
 * shared seamlessly while the terminal process stays independent of the chat engine.
 */
object PtyLauncher {

    /**
     * Real kernel PTY launch plan for Termux `TerminalSession` (`libtermux.so` `forkpty` +
     * `TIOCSWINSZ` / `SIGWINCH`).
     *
     * `tawcroot`'s `-s` flag is intentionally omitted because `libtermux.so`'s `createSubprocess`
     * already runs `setsid()` + `ioctl(pts, TIOCSCTTY, 0)` before `execvp`, so a second `setsid()`
     * would detach the shell from its controlling PTY.
     */
    class DirectPtyPlan(
        val executablePath: String,
        val hostCwd: String,
        val argv: Array<String>,
        val env: Array<String>,
        val guestCwd: String,
        val engine: GuestEngine,
        val prorootTmp: File?,
        val launchToken: String?,
    )

    fun prepareDirectPty(
        context: Context,
        guestCwdOverride: String? = null,
        command: String = DEFAULT_COMMAND,
        allowProroot: Boolean = true,
    ): DirectPtyPlan {
        val paths = pathsFor(context)
        val storage = android.os.Environment.getExternalStorageDirectory()
        val workspace = workspaceHost(context)
        val projectGuestPath = GuestWorkspacePath.hostToGuest(paths.rootfs, workspace)
        val targetGuestCwd = guestCwdOverride
            ?.trim()
            ?.takeIf { it.startsWith("/") }
            ?: projectGuestPath

        workspace.mkdirs()
        paths.agentDir.mkdirs()
        val externalEntries = runCatching { WorkspaceStore.list(context.applicationContext ?: context) }
            .getOrDefault(emptyList())
            .filter { it.external && it.available && it.host.isDirectory }
        val extraBinds = buildList {
            if (GuestWorkspacePath.needsBind(paths.rootfs, workspace, projectGuestPath)) {
                add(workspace.absolutePath to projectGuestPath)
            }
            for (entry in externalEntries) {
                if (!GuestWorkspacePath.needsBind(paths.rootfs, entry.host, entry.guestPath)) continue
                val pair = entry.host.absolutePath to entry.guestPath
                if (pair !in this) add(pair)
            }
            forEach { pair -> File(paths.rootfs, pair.second.removePrefix("/")).mkdirs() }
        }

        val inner = command.ifBlank { DEFAULT_COMMAND }
        val guestCmd = "cd ${Shell.quote(targetGuestCwd)} 2>/dev/null || cd ${Shell.quote(projectGuestPath)} 2>/dev/null || cd /root; exec $inner"
        val directEnv = buildMap {
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
            put("LC_ALL", "C.UTF-8")
            put("PI_CODING_AGENT_DIR", guestAgentDir)
            put("PI_CODING_AGENT_SESSION_DIR", "$guestAgentDir/sessions")
            put("PI_ANDROID_BRIDGE_FILE", "/root/.pi/device-bridge.json")
            put("NODE_COMPILE_CACHE", "/tmp/pi-node-compile-cache")
            put("PI_TUI_ESC_TIMEOUT", "150")
            put("PI_HYPERLINKS", "0")
            put("PI_IMAGE_PROTOCOL", "none")
            put("PI_SKIP_VERSION_CHECK", "1")
        }

        val selection = RuntimeSelection.of(context, paths)
        val plan = selection.plan(
            guestCommand = guestCmd,
            cwd = "/root",
            storage = storage,
            extraBinds = extraBinds,
            extraEnv = directEnv,
            allowProroot = allowProroot,
        )

        // Strip `-s` from tawcroot's argv because libtermux.so's createSubprocess already called
        // setsid() + TIOCSCTTY on /dev/pts/N before execvp.
        val finalArgv = if (plan.engine == GuestEngine.Tawcroot) {
            val sep = plan.argv.indexOf("--")
            plan.argv.filterIndexed { idx, arg -> !(arg == "-s" && (sep < 0 || idx < sep)) }
        } else {
            plan.argv
        }

        val hostCwdDir = GuestCommandLine.hostCwd(paths, plan.engine).also { it.mkdirs() }
        val envArray = plan.environment.entries.map { (k, v) -> "$k=$v" }.toTypedArray()

        return DirectPtyPlan(
            executablePath = finalArgv.first(),
            hostCwd = hostCwdDir.absolutePath,
            argv = finalArgv.toTypedArray(),
            env = envArray,
            guestCwd = targetGuestCwd,
            engine = plan.engine,
            prorootTmp = if (plan.engine == GuestEngine.Proroot) paths.prorootTmp else null,
            launchToken = plan.launchToken,
        )
    }

    /**
     * The current workspace host directory, resolved from [WorkspaceStore].
     * Single source of truth shared by the chat engine, built-in terminal, `@`
     * completion, package manager, and device shell write boundary.
     */
    fun workspaceHost(context: Context): File =
        WorkspaceStore.currentHost(context.applicationContext ?: context)

    private fun pathsFor(context: Context): PiPaths = PiPaths(
        filesDir = context.filesDir,
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
    )

    private const val guestAgentDir = "/root/.pi/agent"

    /** The interactive shell every terminal tab runs inside the Ubuntu rootfs. */
    private const val DEFAULT_COMMAND = "bash -i"

    /** POSIX single-quote escaping, forwarded to [ShellQuote]. */
    internal object Shell {
        fun quote(value: String): String = ShellQuote.quote(value)
    }
}
