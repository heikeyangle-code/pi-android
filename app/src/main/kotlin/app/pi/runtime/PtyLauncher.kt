package app.pi.runtime

import android.content.Context
import app.pi.terminal.PtySession
import java.io.File

/**
 * Starts a real PTY for the Workbench terminal, without a JNI `forkpty`.
 *
 * ## The problem
 *
 * `pi`'s TUI calls `process.stdin.setRawMode(true)`
 * (`packages/tui/src/terminal.ts:178`) and then renders its own layout. Over a
 * plain pipe three things break at once:
 *
 *  - `setRawMode` throws or is a no-op, because stdin is not a tty;
 *  - `process.stdout.isTTY` is false, so `process.stdout.columns`/`rows` are
 *    `undefined` and every program degrades to "not a terminal" behaviour
 *    (`ls` stops colourising, `git` stops paging, pi's renderer falls back);
 *  - the line discipline that turns `Ctrl+C` into `SIGINT` does not exist.
 *
 * docs/pi-android-app-design.md §20.4 proposed solving this by reusing
 * termux-app's `terminal-emulator` and its `forkpty` JNI. This project does not
 * add a JNI dependency, so the PTY is allocated **inside the guest** instead.
 *
 * ## The chosen approach, and why
 *
 * util-linux `script(1)` allocates a pty *inside the guest*, forks the command on
 * it, and relays between that pty and this process's ordinary pipes:
 *
 * ```
 * script -q -e -f -c "<cmd>" /dev/null
 * ```
 *
 * What that buys:
 *
 *  - **`forkpty` without native code.** The pty is created by a program inside
 *    the guest (which already has a working `forkpty`), not by the app. The app
 *    only ever touches pipes: no JNI, no `libandroid-shmem`, no second ABI.
 *  - **`process.stdin.isTTY === true`** inside the guest, so `setRawMode` works,
 *    echo and canonical mode are the pty's business exactly as on a desktop, and
 *    `Ctrl+C` becomes a real signal through `ISIG`.
 *  - **A correct exit code.** `-e` makes `script` exit with the child's status,
 *    which is what the tab reports when a command ends.
 *  - **No reinterpretation.** `script` relays bytes and does not parse escape
 *    sequences, so pi's output reaches the emulator byte-for-byte.
 *
 * Alternatives considered and rejected:
 *
 *  - **`socat` with a named pty** (`PTY,link=/tmp/tty`): works, but needs a
 *    second process and a socket path for no benefit over `script`.
 *  - **A `forkpty` shim via JNI**: the honest fallback *if* `script` were absent.
 *    It is not, so native code would buy nothing — and the whole point of the
 *    in-guest PTY is to avoid it.
 *  - **`setsid` + `/dev/ptmx`**: nothing in the base userland opens the pty
 *    master for you; `script` is that tool.
 *
 * ## The one cost, stated plainly
 *
 * `script` cannot *resize* the pty it created: it sets the pty's window size once
 * from its own terminal, and this process's "terminal" is a pipe, so the kernel
 * reports `0 0`. That reaches Node as `process.stdout.rows === 0`, which pi's
 * `ProcessTerminal` treats as unknown and replaces with
 * `process.env.LINES`/`COLUMNS` (`packages/tui/src/terminal.ts:481-487`). So the
 * session declares its size in the environment **and** pins the pty with `stty`,
 * and both the guest and the view lay out for that fixed grid.
 *
 * The consequence: resizing the Android view does not reflow the guest TUI. The
 * view keeps painting the declared grid (see `TerminalSurface`), which is why the
 * declared size is chosen to fit a phone rather than the other way round. A
 * future native `forkpty` would remove this limitation; nothing else here
 * depends on it.
 *
 * ## Environment
 *
 * The environment is [ProotCommand.environment] verbatim — the same map the RPC
 * engine gets — minus proot's own host-side variables (its loader, tmp and
 * library paths are meaningless once inside), plus the terminal additions below.
 * Keeping one definition matters: the claim this app makes is that the guest is
 * the guest pi expects on a desktop.
 */
object PtyLauncher {

    /** Host-side proot variables; they configure proot, not the guest. */
    private val PROOT_ONLY_VARS = setOf(
        "PROOT_LOADER",
        "PROOT_LOADER_32",
        "PROOT_TMP_DIR",
        "PROOT_L2S_DIR",
        "LD_LIBRARY_PATH",
    )

    /** What kind of terminal a tab wants. */
    enum class Kind {
        /** An interactive shell in the guest. */
        Shell,

        /** The original pi TUI — the reason this terminal exists at all. */
        PiTui,

        /** Any other command. */
        Custom,
    }

    class Spec(
        val kind: Kind,
        /** Guest-side command; used for [Kind.Custom]. */
        val command: String = "",
        val columns: Int = 80,
        val rows: Int = 26,
    )

    class Prepared(
        val spec: Spec,
        val argv: List<String>,
        val environment: Map<String, String>,
        /** True when the guest's `script(1)` supports `-e` (child exit status). */
        val exitStatusAvailable: Boolean,
        /** True when `script(1)` exists in the guest at all; false is a hard failure. */
        val scriptAvailable: Boolean,
        val guestCommand: String,
    )

    /**
     * Build the command line, environment and guest command for [spec].
     *
     * Pure apart from the one-time guest probe, so this is the piece to read when
     * something about the terminal is wrong. The probe asks the guest's own shell
     * which `script` options exist, because assuming `-e` would turn a working
     * terminal into `invalid option` on an older util-linux.
     */
    fun prepare(context: Context, spec: Spec): Prepared {
        val paths = pathsFor(context)
        val storage = android.os.Environment.getExternalStorageDirectory()
        val flags = probe(paths, storage)
        val workspace = workspaceHost(context)

        val guestCommand = buildGuestCommand(spec, flags, guestWorkspace)
        val argv = ProotCommand.build(
            paths = paths,
            guestCommand = guestCommand,
            // A shell starts at home, like a desktop terminal. The workspace is
            // the *bind mount* the chat engine uses, not the cwd.
            cwd = "/root",
            storage = storage,
            extraBinds = listOf(workspace.absolutePath to guestWorkspace),
        )
        val environment = ProotCommand.environment(paths, extra = spec.environment())
            .filterKeys { it !in PROOT_ONLY_VARS }
        return Prepared(
            spec = spec,
            argv = argv,
            environment = environment,
            exitStatusAvailable = flags.exitStatus,
            scriptAvailable = flags.scriptAvailable,
            guestCommand = guestCommand,
        )
    }

    /**
     * Spawn a terminal. The caller owns the returned session and must close it.
     *
     * @param onOutput called on the reader thread for each chunk, in order. The
     *        array is reused between calls, so a consumer that keeps it must copy.
     * @param onExit called once, after the final output chunk.
     */
    fun start(
        context: Context,
        spec: Spec,
        onOutput: (ByteArray, Int) -> Unit = { _, _ -> },
        onExit: (Int) -> Unit = {},
    ): PtySession {
        val prepared = prepare(context, spec)
        return PtySession.spawn(
            argv = prepared.argv,
            environment = prepared.environment,
            workingDirectory = pathsFor(context).runtime,
            greeting = greetingFor(prepared),
            onOutput = onOutput,
            onExit = onExit,
        )
    }

    /**
     * What the tab prints before the guest says anything, so a tab that cannot
     * work explains itself instead of showing an empty screen forever.
     */
    private fun greetingFor(prepared: Prepared): String? = when {
        !prepared.scriptAvailable -> BANNER_MISSING_SCRIPT
        !prepared.exitStatusAvailable -> BANNER_NO_EXIT_STATUS
        else -> null
    }

    // ------------------------------------------------------------------ internals

    private class ScriptFlags(val scriptAvailable: Boolean, val exitStatus: Boolean)

    @Volatile private var cachedFlags: ScriptFlags? = null
    @Volatile private var cachedStamp: String? = null

    private fun pathsFor(context: Context): PiPaths = PiPaths(
        filesDir = context.filesDir,
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
    )

    private fun probe(paths: PiPaths, storage: File): ScriptFlags {
        // One guest exec, cached against the unpacked runtime's stamp so it is
        // re-run when the runtime changes and never inside a hot path.
        val stamp = runCatching { paths.stampFile().readText().trim() }.getOrNull()
        val cached = cachedFlags
        if (cached != null && cachedStamp == stamp) return cached

        val output = runCatching { runGuest(paths, PROBE_SCRIPT, storage) }.getOrNull()
        val flags = parseProbe(output)
        if (flags.scriptAvailable) {
            cachedFlags = flags
            cachedStamp = stamp
        }
        return flags
    }

    private fun runGuest(paths: PiPaths, script: String, storage: File): String {
        val argv = ProotCommand.build(
            paths = paths,
            guestCommand = script,
            cwd = "/root",
            storage = storage,
        )
        val builder = ProcessBuilder(argv).directory(paths.runtime).redirectErrorStream(true)
        builder.environment().putAll(ProotCommand.environment(paths))
        val process = builder.start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return text
    }

    private fun parseProbe(output: String?): ScriptFlags {
        val line = output?.lineSequence()?.firstOrNull { it.startsWith(PROBE_PREFIX) }
            ?: return ScriptFlags(false, false)
        val path = line.removePrefix(PROBE_PREFIX).substringBefore(" flags=")
        val flags = line.substringAfter(" flags=", "")
        return ScriptFlags(path.isNotBlank() && path != PROBE_MISSING, flags.contains("e"))
    }

    private fun buildGuestCommand(spec: Spec, flags: ScriptFlags, workspace: String): String {
        if (!flags.scriptAvailable) return MISSING_SCRIPT_FALLBACK
        // `stty` first: the pty `script` created has no window size of its own, so
        // pin it before anything can read it. Then `exec`, so the command replaces
        // the shell and `script`'s exit status is the command's.
        val pinned = "stty rows ${spec.rows} cols ${spec.columns} 2>/dev/null; exec ${spec.innerCommand()}"
        val mode = if (flags.exitStatus) "-qef" else "-qf"
        return "cd ${Shell.quote(workspace)} && exec script $mode -c ${Shell.quote(pinned)} /dev/null"
    }

    /** The host directory bind-mounted as the guest's `/workspace`. */
    fun workspaceHost(context: Context): File = File(context.filesDir, WORKSPACE_RELATIVE)

    /**
     * The path [app.pi.engine.PiEngineHost] maps a workspace to, so a terminal tab
     * and a chat session look at one directory. [PiEngineHost] derives it from the
     * same relative path.
     */
    private const val WORKSPACE_RELATIVE = "pi/workspaces/workspace-1"
    private const val guestWorkspace = "/workspace"

    private fun Spec.environment(): Map<String, String> = buildMap {
        val agentDir = "/root/.pi/agent"
        // The same file surface as a desktop install: sessions, settings, skills,
        // extensions, themes.
        put("PI_CODING_AGENT_DIR", agentDir)
        put("PI_CODING_AGENT_SESSION_DIR", "$agentDir/sessions")
        // pi's tty output goes to a pipe under proot and is relayed by `script`,
        // which adds latency to a byte-at-a-time escape sequence. Its default
        // reassembly window for a lone ESC is 10 ms, tuned for a local terminal;
        // 150 ms is the value it uses for SSH, and the alternative is an Escape
        // being eaten whenever the pipe is busy.
        put("PI_TUI_ESC_TIMEOUT", "150")
        // `COLUMNS`/`LINES` are not terminal additions, they are the *only* size
        // the guest can see: the pty reports 0 rows (see the class KDoc), and pi
        // falls back to these. They must match what `stty` pinned.
        put("COLUMNS", columns.toString())
        put("LINES", rows.toString())
        if (kind != Kind.PiTui) return@buildMap
        // This emulator implements OSC 8 and true colour, and pi cannot detect
        // either: it guesses from TERM/TERM_PROGRAM/branding variables, and this
        // process's parent is an Android app, so detection always concludes
        // "unknown terminal" and disables both.
        put("PI_HYPERLINKS", "1")
        put("COLORTERM", "truecolor")
        // Inline images are the one capability this emulator does not have (see
        // TerminalEmulator's KDoc). Saying so is required: "auto" would let pi try
        // to draw a picture with escape sequences we consume.
        put("PI_IMAGE_PROTOCOL", "none")
        // `TERMUX_VERSION` is deliberately NOT set. If it were, pi would skip its
        // full redraw when the terminal height changes
        // (`packages/tui/src/tui-main-screen.ts:347`) — and the height change here
        // is the software keyboard, where a stale viewport is exactly what you
        // would see. Leaving it unset also keeps pi's clipboard on OSC 52, which
        // this emulator implements, instead of `termux-clipboard-*`.
        put("PI_SKIP_VERSION_CHECK", "1")
    }

    private fun Spec.innerCommand(): String = when (kind) {
        Kind.Shell -> "bash -i"
        // `pi` is a Node CLI the runtime puts on PATH. TUI mode is its default
        // when stdout is a terminal, which is now true.
        Kind.PiTui -> "pi"
        Kind.Custom -> if (command.isBlank()) "bash -i" else command
    }

    private const val PROBE_PREFIX = "script="
    private const val PROBE_MISSING = "missing"

    /**
     * Ask the guest shell what its `script(1)` supports. Runs in the same rootfs
     * as the terminal itself and prints exactly one probe line.
     */
    private val PROBE_SCRIPT: String = buildString {
        // Written with concatenation rather than `${'$'}` escapes: raw-string
        // escaping around `$` is exactly the kind of thing that reads as shell
        // syntax when it is Kotlin, and vice versa.
        append("S=").append('$').append("(command -v script 2>/dev/null)\n")
        append("F=\"\"\n")
        append("if [ -n \"").append('$').append("S\" ]; then\n")
        append("  \"").append('$').append("S\" -qfc true /dev/null >/dev/null 2>&1 && F=q\n")
        append("  if [ -n \"").append('$').append("F\" ]; then \"")
            .append('$').append("S\" -qefc true /dev/null >/dev/null 2>&1 && F=qe; fi\n")
        append("fi\n")
        append("printf '%s%s flags=%s\\n' '").append(PROBE_PREFIX).append("' \"")
            .append('$').append("{S:-").append(PROBE_MISSING).append("}\" \"")
            .append('$').append("F\"\n")
    }

    private const val MISSING_SCRIPT_FALLBACK =
        "printf '工作区终端需要 guest 里的 util-linux script(1)，当前 rootfs 没有。\\n'; exec bash -i"

    private const val BANNER_MISSING_SCRIPT =
        "工作区终端需要 guest 里的 util-linux script(1)（用于在内部分配真实 PTY）。\n" +
            "当前 rootfs 没有它，已回退到普通管道：全屏交互程序（如 pi TUI）可能显示异常。\n\n"

    private const val BANNER_NO_EXIT_STATUS =
        "提示：guest 的 script(1) 不支持 -e，命令的退出码不会被上报。\n\n"

    /** POSIX single-quote quoting for a shell word. */
    internal object Shell {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
