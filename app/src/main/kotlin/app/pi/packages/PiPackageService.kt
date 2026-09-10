package app.pi.packages

import app.pi.runtime.PtyLauncher
import java.io.File

/**
 * `pi install` / `pi remove` / `pi list`, run in the guest.
 *
 * ## Why the CLI and not `settings.json`
 *
 * `docs/known-gaps.md` B5 originally proposed editing the `packages` array
 * directly. That is wrong, and pi's own source says so: installing is not a
 * settings edit. `DefaultPackageManager.install` (`core/package-manager.ts:1005-1027`)
 * runs `npm install --prefix <agentDir>/npm --legacy-peer-deps`
 * (`:1785-1812`) or `git clone` (`:1831-1863`), and only afterwards does
 * `installAndPersist` append the source to settings (`:1029-1032`). A settings
 * write alone produces a configured package with no files on disk.
 *
 * There is also no RPC command for any of this. Verified against the protocol's
 * own type: `RpcCommand` (`modes/rpc/rpc-types.ts:20-74`) has no `install`,
 * `remove`, `list` or `reload`. So the app must do it at its own layer, and the
 * honest way is to run pi's own CLI, in the guest, and show what it said.
 *
 * ## The exact command line
 *
 * ```
 * <proot argv from ProotCommand.build>  /bin/bash -lc
 *   exec /opt/node/bin/node /opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js install 'npm:foo@1.0.0' -l --approve
 * ```
 *
 * with guest cwd = the workspace, and the workspace bind-mounted. The js entry
 * point and the node path are the same two values `PiEngineHost` uses
 * (`:95`, `:104`), not a second guess. `bash -lc` and the argv are
 * [ProotCommand.build]'s, reused from the engine path.
 *
 * Placement of flags mirrors pi's parser (`package-manager-cli.ts:375-573`):
 * `-l`/`--local` is only meaningful for `install`/`remove` (`:409-416`),
 * `--approve` is accepted by all three (`:454-462`), and `list` accepts neither
 * `source` nor `-l`. The spec is quoted as one shell word
 * ([PtyLauncher.Shell.quote]) so a `@`-bearing spec, a URL with `&`, or a path
 * with a space survives.
 *
 * ## What "silently skip" means here, and why the app refuses to reproduce it
 *
 * pi's trust gate, when it denies, produces **no output at all** — no event, no
 * stderr line (`docs/extension-compatibility.md:574-575`, from `loader.ts:634-637`
 * and `main.ts:775-782`). The one place it does speak is the project-scoped package
 * command:
 *
 * ```
 * Project is not trusted. Use --approve to modify local package config.
 * ```
 *
 * (`package-manager-cli.ts:936-940`, exit code 1). So this service reports that
 * verbatim, and separately flags "project resources were skipped" whenever a
 * command ran with an unresolved trust decision, even when it exited 0. An install
 * that fails silently is worse than one that refuses.
 */
class PiPackageService(
    private val layout: AgentLayout,
    private val guest: GuestCommand,
) {

    /**
     * How the project gate is satisfied for this command.
     *
     * `ProjectTrustRepository` owns the decision; this is only the wire form the
     * CLI understands. `--approve` is pi's own "trust project-local files for this
     * command" flag (`package-manager-cli.ts:454-462`), which is exactly the
     * session-only answer: it trusts without persisting anything.
     */
    enum class TrustPass {
        /** No flag. User scope needs none; project scope then fails if untrusted. */
        None,

        /** `--approve`: trust project-local files for this one command. */
        Approve,
    }

    // ------------------------------------------------------------------ results

    data class Listing(
        val entries: List<PiPackageEntry>,
        /** `pi list`'s stdout, verbatim, so a parse failure is still readable. */
        val raw: String,
        val stderr: String,
        val argv: List<String>,
        /**
         * True when the project's own `.pi/settings.json` declares packages that
         * this listing did **not** show, because the project is untrusted.
         *
         * This is the second silent skip in pi's package path, and it is worse than
         * the first because the output looks healthy. `listConfiguredPackages` does
         * read a project document (`package-manager.ts:992-1000`), but
         * `SettingsManager.getProjectSettings()` returns an empty object while
         * `projectTrusted` is false — `loadFromStorage` short-circuits to `{}` for
         * project scope (`settings-manager.ts:405-408`, read by `:501-503`) — so
         * those entries vanish from a command that exited 0. `mutate` already catches the *denial* string for `-l`
         * (`package-manager-cli.ts:936-940`); nothing at all catches this one.
         */
        val projectPackagesHidden: Boolean = false,
    )

    sealed interface Done {
        val argv: List<String>
        val stdout: String
        val stderr: String

        /** Human-readable result line, from pi's stdout or the app's own words. */
        val summary: String

        /**
         * `Warning:` lines pi printed. Present on success too — pi reports broken
         * settings files this way (`package-manager-cli.ts:255-263`, `:737-741`),
         * and dropping them is the silent-failure shape this layer exists to avoid.
         */
        val warnings: List<String>

        /** True when project-scoped resources were skipped, whatever the exit code. */
        val projectResourcesSkipped: Boolean

        /** `Installed npm:foo` / `Removed npm:foo` / `Installed …（有警告）`. */
        data class Ok(
            override val argv: List<String>,
            override val stdout: String,
            override val stderr: String,
            override val summary: String,
            override val warnings: List<String> = emptyList(),
            override val projectResourcesSkipped: Boolean = false,
        ) : Done

        /** pi ran and exited non-zero. [message] is its stderr, verbatim. */
        data class Failed(
            override val argv: List<String>,
            override val stdout: String,
            override val stderr: String,
            val message: String,
            val exitCode: Int?,
            override val warnings: List<String> = emptyList(),
            override val projectResourcesSkipped: Boolean = false,
        ) : Done {
            override val summary: String get() = message
        }

        /** The app refused before spawning anything; [message] mirrors a pi message. */
        data class Refused(override val summary: String) : Done {
            override val argv: List<String> get() = emptyList()
            override val stdout: String get() = ""
            override val stderr: String get() = ""
            override val warnings: List<String> get() = emptyList()
            override val projectResourcesSkipped: Boolean get() = false
        }

        /** Killed on the app's own timeout. Never presented as a package error. */
        data class TimedOut(
            override val argv: List<String>,
            override val stdout: String,
            override val stderr: String,
            val timeoutMs: Long,
            override val projectResourcesSkipped: Boolean = false,
        ) : Done {
            override val summary: String
                get() = "命令超过 ${timeoutMs / 1000} 秒未返回，已终止"
            override val warnings: List<String> get() = emptyList()
        }

        /** The runtime or the engine is missing, so nothing could be run. */
        data class NotReady(override val summary: String) : Done {
            override val argv: List<String> get() = emptyList()
            override val stdout: String get() = ""
            override val stderr: String get() = ""
            override val warnings: List<String> get() = emptyList()
            override val projectResourcesSkipped: Boolean get() = false
        }
    }

    /** What the caller must tell the user after any successful mutation. */
    data class RestartRequired(val changes: List<String>, val detail: String)

    // ---------------------------------------------------------------- commands

    /**
     * `pi install <spec>` (`package-manager-cli.ts:954-957`). On success pi prints
     * `Installed <spec>`; the app does not re-derive that.
     */
    fun install(spec: String, scope: PiPackageScope, trust: TrustPass = TrustPass.None): Done =
        mutate("install", spec, scope, trust)

    /** `pi remove <spec>`; alias `uninstall` exists but the canonical verb is used. */
    fun remove(spec: String, scope: PiPackageScope, trust: TrustPass = TrustPass.None): Done =
        mutate("remove", spec, scope, trust)

    private fun mutate(command: String, spec: String, scope: PiPackageScope, trust: TrustPass): Done {
        PiPackageSource.validate(command, spec)?.let { return Done.Refused(it.message) }
        readiness()?.let { return it }

        val outcome = guest.run(
            guestCommand = commandLine(command, spec, scope, trust),
            cwd = layout.guestWorkspace,
            timeoutMs = GuestCommand.INSTALL_TIMEOUT_MS,
        )
        return classify(outcome, successSummary = { source ->
            if (command == "install") "Installed $source" else "Removed $source"
        }, spec = spec)
    }

    /**
     * `pi list`. Always reads **both** settings documents, so it must run with the
     * workspace as cwd: `listConfiguredPackages` reads `getGlobalSettings()` and
     * `getProjectSettings()`, and the latter is `<cwd>/.pi/settings.json`
     * (`package-manager.ts:977-1003`).
     */
    fun list(trust: TrustPass = TrustPass.None, projectTrusted: Boolean = false): Listing {
        readiness()?.let { return Listing(emptyList(), "", it.summary, emptyList()) }
        val outcome = guest.run(
            guestCommand = commandLine("list", spec = null, scope = PiPackageScope.User, trust = trust),
            cwd = layout.guestWorkspace,
            timeoutMs = GuestCommand.LIST_TIMEOUT_MS,
        )
        return Listing(
            entries = PiListOutput.parse(outcome.stdout).entries,
            raw = outcome.stdout,
            stderr = outcome.stderr,
            argv = outcome.argv,
            projectPackagesHidden = !projectTrusted && projectDeclaresPackages(),
        )
    }

    /**
     * Reads `<cwd>/.pi/settings.json` and answers whether it lists any packages.
     *
     * Reads the **host** copy of the project document, which is the same file pi
     * reads: the workspace is the one thing the engine does bind (`PiEngineHost.kt:116`),
     * so `<hostWorkspace>/.pi/settings.json` and `<cwd>/.pi/settings.json` are the
     * same bytes. This is the one path where that is true, and the difference is
     * worth remembering — see [AgentLayout].
     */
    fun projectDeclaresPackages(): Boolean {
        val file = File(layout.hostProjectConfigDir(), "settings.json")
        if (!file.isFile) return false
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        val document = app.pi.rpc.PiJson.parseObjectOrNull(text) ?: return false
        val packages = app.pi.rpc.SettingsDocument.lookup(document, "packages") ?: return false
        val array = packages as? kotlinx.serialization.json.JsonArray ?: return false
        return array.isNotEmpty()
    }

    /**
     * The exact string `bash -lc` receives. Public because the report and the UI's
     * "what did you actually run" affordance both need it, and because a test can
     * assert on it without a device.
     */
    fun commandLine(command: String, spec: String?, scope: PiPackageScope, trust: TrustPass): String {
        val words = mutableListOf(command)
        if (spec != null) words += PtyLauncher.Shell.quote(spec)
        if (scope.flag.isNotEmpty()) words += scope.flag
        if (trust == TrustPass.Approve) words += "--approve"
        return "exec ${layout.guestNode} ${layout.guestEngineCli} ${words.joinToString(" ")}"
    }

    /** [RestartRequired] for a completed mutation, or null when nothing changed. */
    fun restartRequirement(done: Done): RestartRequired? = when (done) {
        is Done.Ok -> RestartRequired(
            changes = listOf(done.summary),
            detail = "pi 在启动时用 jiti 把扩展加载进进程，运行中的进程不会重新扫描扩展目录。" +
                "新装的包只有在 /reload 或重启引擎之后才生效。",
        )
        else -> null
    }

    // --------------------------------------------------------------- internals

    private fun readiness(): Done.NotReady? {
        if (!layout.runtimeReady()) {
            return Done.NotReady("运行时尚未就绪：rootfs 或 proot 缺失，无法在 guest 里执行 pi。")
        }
        if (!layout.engineInstalled()) {
            return Done.NotReady("引擎未安装：找不到 ${layout.guestEngineCli}。")
        }
        return null
    }

    private fun classify(
        outcome: GuestCommand.Outcome,
        successSummary: (String) -> String,
        spec: String,
    ): Done {
        val warnings = warningsIn(outcome.stderr)
        val skipped = PROJECT_SKIP_MARKERS.any { outcome.stderr.contains(it) }

        if (outcome.launchError != null) {
            return Done.NotReady("无法启动 proot：${outcome.launchError}")
        }
        if (outcome.timedOut) {
            return Done.TimedOut(
                argv = outcome.argv,
                stdout = outcome.stdout,
                stderr = outcome.stderr,
                timeoutMs = GuestCommand.INSTALL_TIMEOUT_MS,
                projectResourcesSkipped = skipped,
            )
        }
        if (outcome.exitCode == 0) {
            // pi's own success line is in stdout; if it is missing (a future pi, or
            // output pi suppressed) fall back to the equivalent pi wording rather
            // than showing an empty result.
            val fromPi = outcome.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Installed ") || it.startsWith("Removed ") }
            val summary = fromPi
                ?: successSummary(spec).let { if (warnings.isEmpty()) it else "$it（有警告）" }
            return Done.Ok(
                argv = outcome.argv,
                stdout = outcome.stdout,
                stderr = outcome.stderr,
                summary = summary,
                warnings = warnings,
                projectResourcesSkipped = skipped,
            )
        }
        return Done.Failed(
            argv = outcome.argv,
            stdout = outcome.stdout,
            stderr = outcome.stderr,
            message = failureMessage(outcome),
            exitCode = outcome.exitCode,
            warnings = warnings,
            projectResourcesSkipped = skipped,
        )
    }

    /**
     * pi's error surface is `console.error(chalk.red(\`Error: ${message}\`))`
     * (`package-manager-cli.ts:1096-1100`) plus the specific trust and
     * no-match lines (`:936-940`, `:960-965`). All of them land on stderr, so the
     * stderr text **is** the message — quoting it is more faithful than
     * reimplementing the branches, and it cannot drift from pi.
     */
    private fun failureMessage(outcome: GuestCommand.Outcome): String = when {
        outcome.stderr.isNotBlank() -> outcome.stderr.trim()
        outcome.stdout.isNotBlank() -> outcome.stdout.trim()
        else -> "pi 退出码 ${outcome.exitCode}，且没有任何输出"
    }

    companion object {
        /**
         * pi's two trust-denial strings for package commands
         * (`package-manager-cli.ts:937`, applied to `install`/`remove`/`list`
         * because `assertProjectTrustedForScope` throws its own message at
         * `package-manager.ts:1741-1745` when scope is project).
         */
        private val PROJECT_SKIP_MARKERS = listOf(
            "Project is not trusted",
            "refusing to access project package storage",
        )

        /** `Warning: …` lines from `reportProjectTrustWarnings`/`reportSettingsErrors`. */
        fun warningsIn(stderr: String): List<String> = stderr.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("Warning:") || it.startsWith("Warning ") }
            .toList()
    }
}
