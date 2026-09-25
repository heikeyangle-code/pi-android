package app.pi.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

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
 * terminal component is given the declared grid
 * (`app.pi.ui.terminal.TerminalBridge`), which is why the declared size is
 * chosen to fit a phone rather than the other way round. A future native
 * `forkpty` would remove this limitation; nothing else here depends on it.
 *
 * ## Environment
 *
 * The environment is the one the selected runtime's builder produced **verbatim** —
 * the same map the RPC engine gets (`PiEngineHost` hands it to
 * `PiEngineSession.spawn`) — plus the terminal additions below. Keeping one
 * definition matters: the claim this app makes is that the guest is the guest pi
 * expects on a desktop. That verbatim is load-bearing rather than tidiness;
 * [prepare] says why.
 */
object PtyLauncher {

    class Spec(
        /**
         * Guest-side command. Defaults to an interactive shell, and that is the
         * only thing the app ever asks for now: the terminal page is a plain
         * terminal, and `pi` is reached by typing `pi` in it like any other
         * program on `PATH`.
         */
        val command: String = DEFAULT_COMMAND,
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
        /**
         * The runtime this terminal will be launched with. The terminal is on the
         * daily interactive path, so it takes the opt-in proroot runtime when it is
         * available (`RuntimeSelection`); the engine and this launcher are the two
         * places a user feels a slow container.
         */
        val engine: GuestEngine = GuestEngine.Proot,
        /**
         * The plan's per-launch identity token, non-null exactly on proroot. It is what
         * makes the pid resolved at spawn provably this terminal's
         * ([ProrootLaunchHandle]), which matters because the terminal and the engine can
         * be starting at the same moment.
         */
        val launchToken: String? = null,
    )

    /**
     * Build the command line, environment and guest command for [spec].
     *
     * Pure apart from the one-time guest probe, so this is the piece to read when
     * something about the terminal is wrong. The probe asks the guest's own shell
     * which `script` options exist, because assuming `-e` would turn a working
     * terminal into `invalid option` on an older util-linux.
     *
     * ## The environment is passed verbatim, on purpose
     *
     * The runtime's `environment(...)` mixes three things: shell basics (`HOME`,
     * `PATH`, `TERM`) and the guest's CA bundle variables (from
     * [GuestRecipe.environment], shared by both runtimes), plus the **runtime's
     * own** variables — proot's `PROOT_LOADER` / `PROOT_TMP_DIR` / `PROOT_L2S_DIR`
     * / `LD_LIBRARY_PATH`, or proroot's `PROROOT_*`. It
     * is tempting to filter the last group out here, on the theory that they are
     * host paths "meaningless once inside", and an earlier revision of this file
     * did exactly that. It is wrong, and it broke the terminal on a real device
     * with `CANNOT LINK EXECUTABLE …: library "libtalloc.so.2" not found`.
     *
     * The reason is that [PtySession.spawn] puts this map into the **proot
     * process's** `ProcessBuilder.environment()`, not into the guest's. proot
     * reads two of these before it ever enters the rootfs:
     *
     *  - `LD_LIBRARY_PATH` is the only thing that makes `PiPaths.lib` — where
     *    `PiPaths.prepareLibraryAliases()` creates `libtalloc.so.2 → libtalloc.so`
     *    — searchable, so without it the dynamic loader cannot resolve the name;
     *  - `PROOT_LOADER` is the pre-extracted loader; without it proot unpacks one
     *    into a temp directory, which Android 10+ W^X forbids executing from
     *    (`PiRuntime`'s KDoc records that constraint).
     *
     * The engine path is the control group and shows the correct shape:
     * `PiEngineHost` passes the plan's environment to `PiEngineSession.spawn`
     * **unfiltered**, which is why chat worked while the terminal did not. Nothing
     * strips these for the guest either — both runtimes forward their environment
     * into the rootfs, so the guest on the working engine path already sees them,
     * and a second, guest-level `unset` here would only make the two paths differ
     * again for no demonstrated benefit.
     *
     * `allowProroot = false` forces this launch onto proot. It exists for exactly one
     * caller — [start]'s retry after a proroot spawn failed — so that the terminal uses the
     * same three-layer fallback the engine (`PiEngineHost`) and the package commands
     * (`GuestCommand.run`) already use, instead of surfacing a raw spawn failure in a tab.
     */
    fun prepare(context: Context, spec: Spec, allowProroot: Boolean = true): Prepared {
        val paths = pathsFor(context)
        val storage = android.os.Environment.getExternalStorageDirectory()
        val flags = probe(paths, storage)
        val workspace = workspaceHost(context)

        val guestCommand = buildGuestCommand(spec, flags, guestWorkspace)
        // proot binds nothing that does not exist on the host side, and the
        // terminal can legitimately be the first thing the user opens — before any
        // engine boot has created the agent dir or the workspace. `PiRuntime`'s own
        // getters create their directories the same way, the engine creates the
        // workspace before binding it (`PiEngineHost`, `val workspace = …; workspace.mkdirs()`),
        // and `AgentLayout.ensureAgentMirrorDir` exists for exactly this reason on
        // the package path.
        workspace.mkdirs()
        paths.agentDir.mkdirs()
        // The terminal's argv comes from the same decision point the engine uses, so
        // the terminal and the chat page cannot end up on different runtimes without
        // one of them saying so. `allowProroot` stays at its default (true): this is
        // the interactive path, which is exactly what proroot is for.
        val selection = RuntimeSelection.of(context, paths)
        val plan = selection.plan(
            guestCommand = guestCommand,
            // A shell starts at home, like a desktop terminal. The workspace is
            // the *bind mount* the chat engine uses, not the cwd.
            cwd = "/root",
            storage = storage,
            extraBinds = listOf(
                // The terminal's `/workspace` is the **one bind left**, and it stays
                // because it is the one *different spelling* of a directory that both
                // sides already agree on: the engine's cwd is
                // `/workspace/pi/workspaces/<name>` (a plain rootfs path now), while a
                // shell wants the short `/workspace`. Only a bind can name one directory
                // twice.
                //
                // Its host side moved into the rootfs with everything else
                // (`PiPaths.workspaces`) — but the rootfs **is** `<files>/pi/runtime/rootfs`,
                // so this bind's source is *still* an app-private directory, which is the
                // property every measured failing bind shared (`docs/proroot-scandir-defect.md`
                // §3.1): a **native** tool run here — `git init`, `gcc` — is therefore still in
                // that defect's range, since `/opt/pi/scandir-fix.mjs` only wraps Node's `fs`.
                // (Not separately measured: §3.1's `/workspace` row was read in the **engine**,
                // where that path is a plain rootfs directory and not this mount point.)
                // What the workspace move changed is the blast radius: `git` run from the
                // **engine's** cwd is on a plain rootfs path (no bind) and works.
                workspace.absolutePath to guestWorkspace,
                // The agent dir is **not** bound any more (2026-09-23). It lives at
                // `<rootfs>/root/.pi/agent`, which is exactly the guest's
                // `/root/.pi/agent`, so the guest reaches it through the rootfs prefix and
                // the app addresses the same directory through `PiPaths.agentDir`. The
                // earlier reason for binding it — that the terminal's copy was a second,
                // volatile agent dir with different sessions, settings and credentials —
                // is now answered by there being only one directory at all.
            ),
            extraEnv = spec.environment(),
            // The one caller that ever passes `false` is [start]'s retry, after a proroot
            // launch failed to come up: the same "count it, then bring it up on proot"
            // discipline the engine and the package commands already use.
            allowProroot = allowProroot,
        )
        return Prepared(
            spec = spec,
            argv = plan.argv,
            environment = plan.environment,
            exitStatusAvailable = flags.exitStatus,
            scriptAvailable = flags.scriptAvailable,
            guestCommand = guestCommand,
            engine = plan.engine,
            launchToken = plan.launchToken,
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
        val paths = pathsFor(context)
        val selection = RuntimeSelection.of(context, paths)
        return try {
            spawn(prepared, paths, onOutput, onExit)
        } catch (startFailure: java.io.IOException) {
            // proroot could not even start the terminal's process. Same discipline as the
            // engine (`PiEngineHost` layers ②/③) and the package commands
            // (`GuestCommand.run`): count the failure, then bring **this** launch up on proot
            // rather than leaving the user with a dead tab.
            //
            // Only `IOException` is retried, and that is a deliberate boundary rather than
            // convenience: `ProcessBuilder.start()` raises exactly that when the launcher
            // cannot be executed, whereas anything thrown *after* the process exists (a
            // thread that will not start, an OOM) would mean a second retry leaks the first
            // child — proroot has no `--kill-on-exit`. An unattributable failure therefore
            // propagates, with the first process's fate unchanged.
            // Only the opt-in engines are counted: proot is the floor of the fallback
            // chain, so a proot launch that failed has nowhere to fall to and is not a
            // strike against a switch the user did not turn on.
            if (!prepared.engine.usesOptInPlumbing) throw startFailure
            selection.recordFailure(
                prepared.engine,
                "${startFailure::class.java.simpleName}: ${startFailure.message}",
            )
            spawn(prepare(context, spec, allowProroot = false), paths, onOutput, onExit)
        }
    }

    /** The one `PtySession.spawn` call shape; [start] owns the fallback around it. */
    private fun spawn(
        prepared: Prepared,
        paths: PiPaths,
        onOutput: (ByteArray, Int) -> Unit,
        onExit: (Int) -> Unit,
    ): PtySession = PtySession.spawn(
        argv = prepared.argv,
        environment = prepared.environment,
        workingDirectory = paths.runtime,
        greeting = greetingFor(prepared),
        engine = prepared.engine,
        // proroot's scratch directory, so the session can identify this launch by
        // the config table it writes (the platform has no `Process.pid()` here —
        // see `ProrootLaunchHandle`) — and then reap its tree on close.
        prorootTmp = when (prepared.engine) {
            GuestEngine.Proroot -> paths.prorootTmp
            GuestEngine.Bxroot -> paths.bxrootTmp
            GuestEngine.Proot -> null
        },
        launchToken = prepared.launchToken,
        // The `.proroot-config-<pid>` table this launch created. Deleting it at
        // stop is the same cleanup `RuntimeSelection.sweepProrootConfigs` would
        // do on the next launch, only earlier and with the pid still known.
        onStopped = { handle -> handle?.deleteConfig() },
        onOutput = onOutput,
        onExit = onExit,
    )

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
        // The `script(1)` capability probe runs on **proot**, deliberately. It is a
        // one-time measurement cached against the runtime stamp, it has no
        // performance value, and it must not become a reason to start the
        // closed-source runtime on a device where the gate has not run yet. This is
        // the same division of labour `RuntimeSelection` draws for install and
        // maintenance work.
        val argv = GuestCommandLine.build(paths, GuestEngine.Proot, script, cwd = "/root", storage = storage)
        val builder = ProcessBuilder(argv).directory(paths.runtime).redirectErrorStream(true)
        builder.environment().putAll(GuestCommandLine.environment(paths, GuestEngine.Proot))
        val process = builder.start()
        // **Wait first, then read** — and with a bound. stderr is merged into stdout
        // (`redirectErrorStream(true)`), so there is only one pipe and no two-pipe
        // deadlock; what was missing was any limit at all: reading to EOF before
        // waiting means a guest that never writes and never exits blocks this call for
        // good, and this call is on the path that opens the terminal. `waitFor` first
        // converts that into a bounded, reportable answer.
        val finished = runCatching { process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            return "$PROBE_PREFIX$PROBE_MISSING flags=\n"
        }
        return runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
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

    /**
     * The host directory bind-mounted as the guest's `/workspace` — **the current
     * workspace**, resolved from [WorkspaceStore].
     *
     * This is the one accessor the chat engine, the terminal, the `@` completion, the
     * package commands and the device shell's write boundary all read (see
     * `GuestWorkspacePath`), which is why the directory is **created here** rather
     * than at each call site: every consumer needs it to exist — proot will not bind
     * a host path that does not exist, and `<workspace>/.pi` is where project
     * settings, skills, prompt templates, themes and extensions are read from — and
     * before this line only the engine and the terminal created it. It is app-private
     * storage, so "it was there last launch" is not a guarantee.
     *
     * The two steps are ordered and both are load-bearing: [WorkspaceStore.refresh]
     * decides *which* workspace is current (reading the persisted choice, falling
     * back to the default with an explanation when that choice no longer names a
     * real directory, and publishing the answer to [GuestWorkspacePath]), and
     * `ensureHost` then creates that directory. Resolving must not be skipped here:
     * this function is the funnel every one of those five consumers goes through,
     * so it is the one place where "which workspace" is answered once for all of
     * them. A caller that kept a `File` from before a switch is holding the previous
     * workspace — which is why the package screens re-key their `remember` on the
     * resolved path.
     */
    fun workspaceHost(context: Context): File =
        WorkspaceStore.currentHost(context.applicationContext ?: context)

    /**
     * The workspace, as this launcher mounts it: the *same host directory* the
     * engine mounts, at a **different guest path**.
     *
     * `GuestWorkspacePath` owns both spellings and explains the difference; the
     * earlier comment here claimed this was "the path [PiEngineHost] maps a
     * workspace to, so a terminal tab and a chat session look at one directory",
     * and the first half of that was simply false (`bridge/DeviceWorkspace`'s
     * KDoc had already caught it). They do look at one *directory* — the same
     * host directory — but not at one path, and `/app/health` publishes both
     * spellings so nothing has to infer which one it holds.
     */
    private val guestWorkspace: String = GuestWorkspacePath.TERMINAL_GUEST_PATH

    /**
     * pi's agent dir inside the guest — the guest spelling of `PiPaths.agentDir`.
     *
     * The same value as `PiAgentDirContract.GUEST_PATH`
     * (`app/src/main/kotlin/app/pi/packages/PiPackageModel.kt`, which also spells it
     * for `GuestCommand`), kept as a local constant rather than imported so that
     * this file stays inside `runtime`: it is the only place in `runtime` that
     * needs it, and the guest path, its bind source and the two environment
     * variables below are one statement that must not be split across packages.
     *
     * The **bind** in [prepare] is what makes the name mean the durable directory.
     * Read the next paragraph for why that was missing and what it cost.
     */
    private const val guestAgentDir = "/root/.pi/agent"

    private fun Spec.environment(): Map<String, String> = buildMap {
        // The same file surface as a desktop install: sessions, settings, skills,
        // extensions, themes. These variables name [guestAgentDir]; the bind in
        // [prepare] is the other half of the same statement, and without it these
        // two lines would point pi at a directory the app cannot see.
        put("PI_CODING_AGENT_DIR", guestAgentDir)
        put("PI_CODING_AGENT_SESSION_DIR", "$guestAgentDir/sessions")
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
        // Everything below is unconditional, and it used to be gated behind "this
        // tab is pi's own TUI". That gate is gone with the tab: the terminal page
        // opens a plain shell, and the user starts `pi` themselves by typing it.
        // From pi's point of view nothing changed — it still sees an unknown
        // terminal and still cannot detect any of this — so the capability
        // statements have to be in the environment of the shell that will run it,
        // or the `pi` the user types gets the wrong idea about the terminal it is
        // drawing into.
        //
        // True colour is implemented — libvterm parses SGR 38/48 with 24-bit
        // values and the view paints each cell's own RGB — but pi cannot detect
        // it: pi guesses from TERM/TERM_PROGRAM/branding variables, and this
        // process's parent is an Android app, so detection always concludes
        // "unknown terminal".
        put("COLORTERM", "truecolor")
        // OSC 8 hyperlinks are NOT implemented by the terminal component as
        // pinned (org.connectbot:termlib 0.0.13): its OSC parser handles 52, 133
        // and 1337 only, and its URL/hyperlink scan landed in a later release
        // whose Kotlin metadata this project's compiler cannot read (see the
        // ceiling note in gradle/libs.versions.toml). Advertising "1" would make
        // pi emit links nothing can open, so this says "0"; pi's
        // `parseBooleanCapabilityOverride` maps exactly "0" to false.
        put("PI_HYPERLINKS", "0")
        // Inline images are the other capability it does not have (upstream lists
        // them as planned). Saying so is required: "auto" would let pi try to
        // draw a picture with escape sequences nothing renders.
        put("PI_IMAGE_PROTOCOL", "none")
        // `TERMUX_VERSION` is deliberately NOT set. If it were, pi would skip its
        // full redraw when the terminal height changes
        // (`packages/tui/src/tui-main-screen.ts:347`) — and the height change here
        // is the software keyboard, where a stale viewport is exactly what you
        // would see. Leaving it unset also keeps pi's clipboard on OSC 52, which
        // the terminal component does implement (its OSC 52 handler feeds the
        // Android clipboard), instead of `termux-clipboard-*`.
        put("PI_SKIP_VERSION_CHECK", "1")
    }

    private fun Spec.innerCommand(): String = command.ifBlank { DEFAULT_COMMAND }

    /** The shell every terminal tab runs. `pi` is a program inside it, not the tab's purpose. */
    private const val DEFAULT_COMMAND = "bash -i"

    private const val PROBE_PREFIX = "script="
    private const val PROBE_MISSING = "missing"

    /**
     * How long the `script(1)` capability probe may take.
     *
     * The probe is a whole proot launch of a guest `bash`, which is why it is cached
     * ([probe]); on a phone it is normally well under a second even cold, and this
     * bound exists for the wedged case, not the slow one. Reaching it falls back to
     * the plain-pipe terminal with the same banner a missing `script(1)` produces —
     * a terminal that works without a pty is a better answer than a screen that never
     * finishes starting.
     */
    private const val PROBE_TIMEOUT_MS = 8_000L

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
            "当前 rootfs 没有它，或探测命令 ${PROBE_TIMEOUT_MS / 1000} 秒内没有返回，" +
            "已回退到普通管道：全屏交互程序（如 pi TUI）可能显示异常。\n\n"

    private const val BANNER_NO_EXIT_STATUS =
        "提示：guest 的 script(1) 不支持 -e，命令的退出码不会被上报。\n\n"

    /**
     * POSIX single-quote quoting for a shell word.
     *
     * Forwarded to [ShellQuote], which is the single definition: the proroot raw
     * probe builds a shell script too, and it has to stay Android-free so the
     * bare-JVM harness can compile it — so the rule moved somewhere both can reach
     * instead of being spelled twice.
     */
    internal object Shell {
        fun quote(value: String): String = ShellQuote.quote(value)
    }
}
