package app.pi.packages

import android.os.Environment
import app.pi.runtime.GuestTreeReaper
import app.pi.runtime.ProrootLaunchHandle
import app.pi.runtime.RuntimeSelection
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one non-interactive command inside the guest, and reports exactly what
 * happened.
 *
 * ## Why this is not [app.pi.runtime.PtyLauncher]
 *
 * `PtyLauncher` allocates a real pty with the guest's `script(1)` so pi's TUI can
 * work. That is the wrong tool here, and specifically so:
 *
 *  - pi decides whether it may prompt for project trust with
 *    `process.stdin.isTTY && process.stdout.isTTY`
 *    (`package-manager-cli.ts:733-735` → `createProjectTrustContext({hasUI: appMode
 *    === "interactive"})`, `:779-784`). **Under a pty that is `true`**, so
 *    `resolveProjectTrusted` reaches `ctx.ui.select` and blocks on
 *    `showStartupSelector` — an unseen TUI menu waiting for a keypress that will
 *    never come (`core/project-trust.ts:86-95`). Over a pipe it is `false`, the
 *    gate returns `false` deterministically (`:86-88`), and `-l` then fails with
 *    pi's own clear message instead of hanging.
 *  - `chalk` colours its output on a pty, which would put SGR sequences in
 *    messages the app shows the user. Over a pipe chalk emits plain text; `NO_COLOR`
 *    is set anyway so the outcome cannot depend on chalk's detection.
 *  - An install is a batch job. A pty buys nothing and costs determinism.
 *
 * The one thing a pty would help with is an interactive credential prompt
 * (`git clone` over SSH asking for a passphrase). That case is **not** handled by
 * hanging: [ENV] sets `GIT_TERMINAL_PROMPT=0` and a `BatchMode` `GIT_SSH_COMMAND`,
 * which is pi's own recommendation for non-interactive runs (`docs/packages.md:89`
 * — "For non-interactive runs (for example CI), you can set `GIT_TERMINAL_PROMPT=0`
 * to disable credential prompts and set `GIT_SSH_COMMAND` … to fail fast"). A
 * package that needs an interactive prompt therefore fails with git's message, and
 * the user is pointed at the Workbench terminal tab, where the same command can be
 * run under a real pty.
 *
 * ## What is reused
 *
 * The argv and environment come from [app.pi.runtime.RuntimeSelection] — the same
 * decision point [app.pi.engine.PiEngineHost] and `PtyLauncher` use, which in turn
 * delegates the recipe to `ProotCommand`/`ProrootCommand` over the shared
 * `GuestRecipe`. There is no second invocation recipe in this file: it adds
 * environment variables for a batch run and nothing else. That matters, because a
 * divergence between how the engine is launched and how `pi install` is launched
 * would be invisible until a user installed a package that then behaved differently
 * from the same package installed by hand.
 *
 * That divergence had already happened, and this is where it was fixed: the engine
 * binds the durable agent dir over guest `/root/.pi/agent` (`PiEngineHost.kt:285-294`,
 * with `PI_CODING_AGENT_DIR` at `:302`), and this command did not — so `pi install`
 * wrote a `settings.json` the running engine never read, reported success, and
 * changed nothing. [bindList] now passes the same two binds and [agentDirEnv] the
 * same two variables, and asserts the agreement rather than restating it.
 */
class GuestCommand(private val layout: AgentLayout) {

    /** One completed (or abandoned) guest command. */
    data class Outcome(
        /** The exact argv handed to the runtime; reported so the user can reproduce it. */
        val argv: List<String>,
        /** The guest command string, i.e. what `bash -c` received. */
        val guestCommand: String,
        /** Null when the process never started or was killed on timeout. */
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        /** Non-null when the runtime itself could not be started. */
        val launchError: String? = null,
        /**
         * What the runtime selection did around this command: which engine ran it,
         * and — when a proroot attempt had to be retried on proot — that sentence.
         *
         * This is the *fallback* half of the three-layer contract reaching a surface
         * that can show it (the diagnostic report and the log today; the callers that
         * render `stdout`/`stderr` are outside this change's scope). A list rather
         * than a string, because a retry has two facts: the failure, then the
         * decision to fall back.
         */
        val notes: List<String> = emptyList(),
        /**
         * True when the guest wrote more than [MAX_STREAM_CHARS] to stdout (`stdout`
         * or `stderr`). The kept text is a **prefix**, and the caller must say so: a
         * partially shown npm log that looks complete is the silent-failure shape this
         * field exists to remove.
         */
        val outputTruncated: Boolean = false,
    ) {
        val ok: Boolean get() = launchError == null && !timedOut && exitCode == 0

        /** Everything the guest said, in the order a terminal would show it. */
        val combined: String get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trimEnd())
            }
            // Last, so the sentence is not itself cut off, and only when something
            // was: the UI's whole promise here is that what it shows is what happened.
            if (outputTruncated) {
                if (isNotEmpty()) append('\n')
                append("（输出过长，已截断：只保留每个流的前 ${MAX_STREAM_CHARS / 1024} KB）")
            }
        }

        /** True when the guest produced no output at all. */
        val silent: Boolean get() = combined.isBlank()
    }

    /**
     * @param guestCommand a command string evaluated by `bash -c` **inside** the
     *        rootfs, so it may name guest paths (`/opt/node/bin/node`, `/workspace`).
     * @param cwd the guest cwd. For anything pi does with project settings this
     *        must be the workspace, because pi resolves `<cwd>/.pi/settings.json`
     *        and writes the trust key from `process.cwd()`.
     * @param timeoutMs hard cap. On expiry the process tree is stopped —
     *        `--kill-on-exit` on proot, [GuestTreeReaper] on proroot, because
     *        proroot rejects that option (`docs/proroot-research.md` §4.2) — and
     *        [Outcome.timedOut] is set. The app never reports a timeout as success.
     *
     * ## The runtime, and the one retry
     *
     * The argv/environment come from [RuntimeSelection], the app's single decision
     * point. Package installs and the `@` mention scan are both on the daily path, so
     * they take the opt-in proroot runtime when it is available — this is not an
     * install-path caller in the sense that matters (`RuntimeProvisioner` extracts
     * the runtime itself, in Kotlin, and never goes through here).
     *
     * If a proroot launch fails **before doing any work** — the process will not
     * start, or the launcher exits non-zero with its own diagnostic inside the start
     * grace — the failure is counted against the persistent streak and the command is
     * retried **once** on proot, so the user's install still happens. Both sentences
     * travel in [Outcome.notes].
     */
    fun run(
        guestCommand: String,
        cwd: String = layout.guestWorkspace,
        timeoutMs: Long = INSTALL_TIMEOUT_MS,
        extraEnv: Map<String, String> = emptyMap(),
    ): Outcome {
        val selection = RuntimeSelection.of(layout.paths)
        val binds = bindList()
        val env = ENV + agentDirEnv() + extraEnv
        val storage = Environment.getExternalStorageDirectory()
        val plan = selection.plan(guestCommand, cwd, storage, binds, env)
        val first = execute(plan, guestCommand, timeoutMs)
        val failure = first.failure
        if (plan.usingProroot && failure != null) {
            // Layer ②: count it, then run the same command on proot so the user's
            // intent still happens. The retry is forced — `allowProroot = false` —
            // rather than re-decided, because a re-decision would consult the gate
            // and could pick proroot again for a command that just failed on it.
            selection.recordProrootFailure(failure)
            val retryPlan = selection.plan(guestCommand, cwd, storage, binds, env, allowProroot = false)
            val second = execute(retryPlan, guestCommand, timeoutMs)
            // The retry plan's own notes are deliberately *not* included: it is
            // built with `allowProroot = false`, whose reason is "the install/maintenance
            // path", and that would be a lie about why this run is on proot. The
            // sentence below is the true one.
            return second.outcome.copy(
                notes = plan.notes + "proroot 启动失败（$failure），本次已用 proot 重试一次",
            )
        }
        // A command that ran at all proves the runtime works, whatever the command's
        // own exit code was: the streak counts *launch* failures, and clearing it on a
        // failed `npm install` would let a broken runtime look healthy.
        if (plan.usingProroot && first.outcome.launchError == null) selection.recordProrootSuccess()
        return first.outcome.copy(notes = plan.notes)
    }

    /** One attempt, plus the launch handle whose pid only a proroot launch has. */
    private class Attempt(
        val outcome: Outcome,
        val handle: ProrootLaunchHandle?,
        val failure: String?,
    )

    private fun execute(
        plan: RuntimeSelection.Plan,
        guestCommand: String,
        timeoutMs: Long,
    ): Attempt {
        // Armed **before** the process starts: the handle identifies this launch by
        // the config table that appears after this moment, which is the only pid the
        // platform will let the app learn (`ProrootLaunchHandle` says why).
        val handle = if (plan.usingProroot) {
            ProrootLaunchHandle.arm(layout.paths.prorootTmp, plan.launchToken)
        } else {
            null
        }
        val process = try {
            ProcessBuilder(plan.argv)
                .directory(layout.paths.runtime)
                .also { it.environment().putAll(plan.environment) }
                .start()
        } catch (error: Throwable) {
            return Attempt(
                outcome = Outcome(
                    argv = plan.argv,
                    guestCommand = guestCommand,
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    timedOut = false,
                    launchError = "${error::class.java.simpleName}: ${error.message}",
                ),
                handle = handle,
                failure = if (plan.usingProroot) {
                    "无法启动 proroot：${error::class.java.simpleName}: ${error.message}"
                } else {
                    null
                },
            )
        }
        // Both pipes must be drained concurrently, and **before** anything waits: a
        // single-threaded read of stdout followed by stderr deadlocks as soon as the
        // guest fills the other pipe's buffer — and `npm install` on a phone prints far
        // more than one pipe buffer's worth. Resolving the pid also blocks (up to
        // `ProrootLaunchHandle.DEFAULT_RESOLVE_TIMEOUT_MS`), so it happens after the
        // readers exist rather than before them.
        val out = StringBuilder()
        val err = StringBuilder()
        // Per stream, because "stdout was cut" and "stderr was cut" are different
        // facts for whoever reads the outcome; both are reported.
        val outOverflow = AtomicBoolean(false)
        val errOverflow = AtomicBoolean(false)
        val outThread = pump(process.inputStream, out, outOverflow)
        val errThread = pump(process.errorStream, err, errOverflow)

        // The launcher's pid, from its own config table plus this launch's token. Null
        // when it never appeared; see [ProrootLaunchHandle] for why nothing is guessed
        // in that case.
        handle?.resolveLauncherPid()

        // proroot's launcher refuses a bad argument set **immediately** and exits with
        // its usage line (`docs/proroot-research.md` §4.2). Sampling a short grace
        // window is what turns that into a fallback instead of a failed install: a
        // real command that is still running after the grace costs nothing extra,
        // because the long wait below then starts where this one left off.
        val finished = if (plan.usingProroot) {
            process.waitFor(PROROOT_START_GRACE_MS, TimeUnit.MILLISECONDS) ||
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        if (!finished) {
            // **Capture before killing anything.** The tree is only visible through the
            // launcher's parent-child edges, and killing the launcher reparents its
            // children to init, after which they cannot be told apart from any other
            // process (see [GuestTreeReaper.capture]). Killing first and reaping second —
            // the obvious order, and the one this code had — leaks exactly the tree the
            // reaper exists for.
            val captured = if (plan.usingProroot) {
                val pid = handle?.launcherPid
                if (pid == null) {
                    err.append("\n超时后无法回收 guest 进程树：proroot 的 .proroot-config 表没有出现，拿不到 launcher pid")
                    null
                } else {
                    runCatching {
                        GuestTreeReaper.capture(pid, expectedStartTime = handle?.launcherStartTime)
                    }.getOrNull()
                        ?: run {
                            err.append("\n超时后无法回收 guest 进程树：launcher pid 已不存在或已被回收")
                            null
                        }
                }
            } else {
                null
            }
            process.destroyForcibly()
            // Give the readers a moment to drain what the guest already wrote; a
            // killed process closes its pipes, so this cannot block indefinitely.
            process.waitFor(5, TimeUnit.SECONDS)
            // The launcher is gone; its captured descendants are matched by
            // `(pid, starttime)`, so this is what actually stops the guest tree.
            if (captured != null) {
                val report = runCatching { GuestTreeReaper.reap(captured) }.getOrNull()
                if (report != null && !report.clean) {
                    err.append("\n停止超时命令后仍有 guest 进程存活：${report.survivors.joinToString()}")
                }
            }
        }
        outThread.join(READER_JOIN_MS)
        errThread.join(READER_JOIN_MS)

        // This launch is over (finished, killed, or killed and reaped above), so the
        // `.proroot-config-<pid>` table it wrote is garbage. Deleting it here is the
        // "delete the stopped guest's own table" half of the cleanup contract; the
        // sweep before the next launch is the other half, and it is the one that
        // covers a launch whose pid could never be resolved.
        handle?.deleteConfig()

        val exitCode = if (finished) runCatching { process.exitValue() }.getOrNull() else null
        val failure = if (plan.usingProroot && exitCode != null && exitCode != 0) {
            prorootRefusal("$out\n$err")
        } else {
            null
        }
        return Attempt(
            outcome = Outcome(
                argv = plan.argv,
                guestCommand = guestCommand,
                exitCode = exitCode,
                stdout = out.toString(),
                stderr = err.toString(),
                timedOut = !finished,
                outputTruncated = outOverflow.get() || errOverflow.get(),
            ),
            handle = handle,
            failure = failure,
        )
    }

    /**
     * Whether a fast, non-zero exit came from proroot's own launcher rather than from
     * the guest command.
     *
     * Both strings are shapes upstream prints for a rejected argument set
     * (`[proroot] unknown option: -L`, `Usage: libproroot.so [-r rootfs] …` —
     * `docs/proroot-research.md` §4.2). Requiring one of them is what keeps a
     * *command* that merely failed and mentioned "proroot" from being counted as a
     * runtime failure and retried.
     */
    private fun prorootRefusal(text: String): String? {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val fromLauncher = text.contains("[proroot]") || text.contains("libproroot.so")
        return if (fromLauncher) firstLine.ifEmpty { "proroot 退出码非 0" } else null
    }

    /**
     * Drain one pipe into [into] forever, keeping at most [MAX_STREAM_CHARS] of it.
     *
     * The reader must never stop reading — a full pipe blocks the guest mid-command,
     * and `npm install` prints far more than one pipe buffer — but nothing bounds what
     * it *keeps*: `pi install` runs npm, whose output on a bad dependency tree (or a
     * package that prints in a loop) is unbounded, and the app process would be holding
     * every byte of it as strings until the command finished. This is the same
     * reasoning `DeviceShell.readCapped` (50 KiB) and `PiEngineSession`'s stderr drain
     * (64 KB) already apply; the cap here is generous because these strings become the
     * user-visible result of an install.
     *
     * Past the cap the bytes are still read and dropped, and [Outcome.outputTruncated]
     * says so: a silent truncation would let the UI present a partial npm log as the
     * whole one.
     */
    private fun pump(stream: InputStream, into: StringBuilder, overflow: AtomicBoolean): Thread {
        val thread = Thread {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(into) {
                            if (into.length >= MAX_STREAM_CHARS) {
                                overflow.set(true)
                                return@synchronized
                            }
                            into.append(buffer, 0, read)
                        }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * The extra binds: the workspace **and the agent dir**, the same two the engine
     * passes and in the same order (`PiEngineHost.kt:285-294`). `-l` writes
     * `<cwd>/.pi/settings.json`, so the workspace bind is required; `pi install`
     * without `-l` writes `<agentDir>/settings.json` and installs into
     * `<agentDir>/npm`, so the agent bind is required too.
     *
     * ## Why this is not redundant with the engine's bind
     *
     * It is a *different process*. proot binds are per-invocation, so this command
     * starts with the rootfs's own `/root/.pi/agent` unless it says otherwise — while
     * the running engine, launched with its own bind, reads the durable directory.
     * Before this bind existed the app therefore installed into one directory and the
     * engine read another: `pi install 'npm:foo'` printed `Installed npm:foo`, exited
     * 0, wrote the `packages` entry where the engine would never look, and nothing
     * happened. The assertion below is what keeps that from coming back quietly.
     */
    private fun bindList(): List<Pair<String, String>> {
        layout.ensureAgentMirrorDir()
        val binds = listOf(layout.workspaceBind(), layout.agentDirBind())
        check(PiAgentDirContract.bindsAgentDir(binds, layout.agentMirrorDir.absolutePath)) {
            // No source citation in this text: an invariant message can reach a log or a
            // crash report, and the rule for strings is the same in both places. The
            // reasoning is in this method's KDoc.
            "guest 命令与引擎的 agent 目录绑定不一致：$binds；" +
                "这会让 pi install/list 写到一个引擎不读的目录"
        }
        return binds
    }

    /**
     * `PI_CODING_AGENT_DIR` and `PI_CODING_AGENT_SESSION_DIR`, exactly as the engine
     * sets them (`PiEngineHost.kt:302-303`).
     *
     * The bind already puts the right directory at `/root/.pi/agent`; the variables
     * make pi's own resolution explicit instead of depending on `HOME`
     * (`config.ts:528-532` falls back to `homedir()/.pi/agent`), so the two processes
     * cannot drift if that ever changes. (The *bind* is what a reader can check after
     * the fact: proot's `-b host:guest` pairs are in [Outcome.argv], which the screen
     * prints as the command it actually ran. Environment variables are not.)
     */
    private fun agentDirEnv(): Map<String, String> = mapOf(
        PiAgentDirContract.ENV_VAR to layout.guestAgentDir,
        PiAgentDirContract.SESSION_ENV_VAR to PiAgentDirContract.sessionDir(layout.guestAgentDir),
    )

    companion object {
        /**
         * npm/git over a phone network. Long enough that a slow but working install
         * is not killed, short enough that a wedged one does not hold the UI
         * forever. The app states the timeout in the failure message rather than
         * presenting it as a package error.
         */
        const val INSTALL_TIMEOUT_MS: Long = 10 * 60 * 1000L

        /** `pi list` reads two JSON files; it should never take a minute. */
        const val LIST_TIMEOUT_MS: Long = 60 * 1000L

        /**
         * How much of each stream is kept, in characters.
         *
         * Generous, because these strings are the user-visible result of an install
         * and a real `npm install` log is tens of KB. It exists only to put a ceiling
         * on a command whose output is unbounded: past it the reader keeps draining
         * (a full pipe blocks the guest) and stops keeping, and
         * [Outcome.outputTruncated] says so.
         */
        const val MAX_STREAM_CHARS: Int = 2 * 1024 * 1024

        /**
         * How long a proroot launch gets to fail before we believe it started.
         *
         * proroot's launcher rejects an argument set immediately (`docs/proroot-
         * research.md` §4.2), so a refusal is visible within milliseconds; the grace
         * exists so that a *successful* fast command (`true`, `pi --version`) is not
         * mistaken for one, and it costs nothing when the process is still running —
         * the full timeout is then entered with the grace already elapsed.
         */
        const val PROROOT_START_GRACE_MS: Long = 1_500L

        private const val READER_JOIN_MS = 2_000L

        /**
         * Batch-mode environment. Each entry is a decision, not a default:
         *
         *  - `NO_COLOR` — chalk must not emit SGR into text the app displays.
         *  - `GIT_TERMINAL_PROMPT=0`, `GIT_SSH_COMMAND=...BatchMode=yes` —
         *    `docs/packages.md:89`, so a credential prompt fails fast instead of
         *    hanging a pipe with no reader. `ConnectTimeout=5` bounds a dead host.
         *  - `PI_SKIP_VERSION_CHECK=1` — the engine already sets this
         *    (`PiEngineHost.kt:127`); a package command must not add a surprise
         *    update check.
         *  - `CI=1` — suppresses npm's interactive-ish output formatting, which is
         *    what makes `pi list`'s and npm's stdout stable to parse.
         */
        val ENV: Map<String, String> = mapOf(
            "NO_COLOR" to "1",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_SSH_COMMAND" to "ssh -o BatchMode=yes -o ConnectTimeout=5",
            "PI_SKIP_VERSION_CHECK" to "1",
            "CI" to "1",
        )
    }
}
