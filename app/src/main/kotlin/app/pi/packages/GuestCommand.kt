package app.pi.packages

import android.os.Environment
import app.pi.runtime.ProotCommand
import java.io.InputStream
import java.util.concurrent.TimeUnit

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
 * The argv comes from [ProotCommand.build] and the environment from
 * [ProotCommand.environment] — the same two functions [app.pi.engine.PiEngineHost]
 * and `PtyLauncher` use. There is no second proot invocation recipe in this file:
 * it adds environment variables for a batch run and nothing else. That matters,
 * because a divergence between how the engine is launched and how `pi install` is
 * launched would be invisible until a user installed a package that then behaved
 * differently from the same package installed by hand.
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
        /** The exact argv handed to proot; reported so the user can reproduce it. */
        val argv: List<String>,
        /** The guest command string, i.e. what `bash -lc` received. */
        val guestCommand: String,
        /** Null when the process never started or was killed on timeout. */
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        /** Non-null when proot itself could not be started. */
        val launchError: String? = null,
    ) {
        val ok: Boolean get() = launchError == null && !timedOut && exitCode == 0

        /** Everything the guest said, in the order a terminal would show it. */
        val combined: String get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trimEnd())
            }
        }

        /** True when the guest produced no output at all. */
        val silent: Boolean get() = combined.isBlank()
    }

    /**
     * @param guestCommand a command string evaluated by `bash -lc` **inside** the
     *        rootfs, so it may name guest paths (`/opt/node/bin/node`, `/workspace`).
     * @param cwd the guest cwd. For anything pi does with project settings this
     *        must be the workspace, because pi resolves `<cwd>/.pi/settings.json`
     *        and writes the trust key from `process.cwd()`.
     * @param timeoutMs hard cap. On expiry the process tree is killed
     *        (`ProotCommand.build` passes `--kill-on-exit`) and
     *        [Outcome.timedOut] is set — the app never reports a timeout as success.
     */
    fun run(
        guestCommand: String,
        cwd: String = layout.guestWorkspace,
        timeoutMs: Long = INSTALL_TIMEOUT_MS,
        extraEnv: Map<String, String> = emptyMap(),
    ): Outcome {
        val argv = ProotCommand.build(
            paths = layout.paths,
            guestCommand = guestCommand,
            cwd = cwd,
            storage = Environment.getExternalStorageDirectory(),
            extraBinds = bindList(),
        )
        val env = ProotCommand.environment(
            layout.paths,
            extra = ENV + agentDirEnv() + extraEnv,
        )

        val process = try {
            ProcessBuilder(argv)
                .directory(layout.paths.runtime)
                .also { it.environment().putAll(env) }
                .start()
        } catch (error: Throwable) {
            return Outcome(
                argv = argv,
                guestCommand = guestCommand,
                exitCode = null,
                stdout = "",
                stderr = "",
                timedOut = false,
                launchError = "${error::class.java.simpleName}: ${error.message}",
            )
        }

        // Both pipes must be drained concurrently. A single-threaded read of stdout
        // followed by stderr deadlocks as soon as the guest fills the other pipe's
        // buffer — and `npm install` on a phone prints far more than one pipe
        // buffer's worth.
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = pump(process.inputStream, out)
        val errThread = pump(process.errorStream, err)

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            // Give the readers a moment to drain what the guest already wrote; a
            // killed process closes its pipes, so this cannot block indefinitely.
            process.waitFor(5, TimeUnit.SECONDS)
        }
        outThread.join(READER_JOIN_MS)
        errThread.join(READER_JOIN_MS)

        return Outcome(
            argv = argv,
            guestCommand = guestCommand,
            exitCode = if (finished) process.exitValue() else null,
            stdout = out.toString(),
            stderr = err.toString(),
            timedOut = !finished,
        )
    }

    private fun pump(stream: InputStream, into: StringBuilder): Thread {
        val thread = Thread {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(into) { into.append(buffer, 0, read) }
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
