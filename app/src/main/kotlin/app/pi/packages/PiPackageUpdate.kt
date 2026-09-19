package app.pi.packages

/**
 * `pi update` as data: which argv words the app emits, which rows may offer the
 * action, and what a result is allowed to claim.
 *
 * ## Where this comes from
 *
 * pi's `update` subcommand is parsed at `package-manager-cli.ts:375-573` and run at
 * `:1006-1094`. The two shapes the app emits are:
 *
 * ```
 * pi update --extensions        ->  packageManager.update(undefined)   (:1013-1019)
 * pi update <source>            ->  packageManager.update(<source>)    (:1013-1019)
 * ```
 *
 * A third shape exists and is **deliberately unreachable here**: a bare
 * `pi update`, `pi update self` and `pi update pi` all resolve to
 * `{type: "self"}` (`:534-536`, `:549-555`), whose handler calls
 * `getSelfUpdatePlan` and then reinstalls pi itself (`:1033-1092`). The engine this
 * app runs is a pinned payload (`tools/fetch-runtime.mjs`'s `PI_VERSION`, extracted
 * and revision-checked by `RuntimeProvisioner`), so a self-update would replace the
 * very artifact the app's contract is asserted against. [one] therefore refuses
 * `self`/`pi` instead of passing them on.
 *
 * ## The two things the result must not pretend to know
 *
 * 1. **Whether anything actually changed.** `handlePackageCommand` prints
 *    `Updated packages` / `Updated <source>` **unconditionally** on this path
 *    (`:1013-1021`), whatever `update()` did. What `update()` does is:
 *    non-pinned npm sources are compared against the registry and reinstalled only
 *    when the target is newer (`package-manager.ts:1103-1106`, `:1150-1163`);
 *    pinned npm sources are skipped (`:1104`); git sources are always reconciled
 *    (`:1107-1109`, `:1137-1145`). So pi exits 0 with one sentence for all of
 *    "installed a new version", "there was nothing newer", "pinned, skipped",
 *    "no packages configured", and even for offline mode (`:1092` returns before
 *    doing anything). The screen says exactly that instead of translating pi's
 *    sentence into a claim pi did not make.
 * 2. **Whether a newer version exists.** pi *can* answer this — `checkForAvailableUpdates`
 *    (`package-manager.ts:1186-1250`) — but nothing on the CLI path calls it; its only
 *    caller is pi's own terminal UI (`modes/interactive/interactive-mode.ts:1054`,
 *    `:1143`). There is therefore no honest way to show "N updates available" here,
 *    and [PackageStrings.UPDATE_NOTE] says the check is not available rather than
 *    inventing a spinner for a query that cannot be made.
 *
 * ## Why `--approve` is not this file's business
 *
 * For `update`, pi reads **only the saved** trust decision
 * (`package-manager-cli.ts:932` → `:753-756`), and `--approve` still overrides it
 * (`:755`). Which flag to pass is the caller's existing trust rule
 * ([PiPackageService.TrustPass]), not a second policy: this file only builds the
 * command and the source.
 *
 * Pure: no Android, no fs, no Compose — so `tools/run-app-pure-checks.sh` can compile
 * it with its harness (see `app/src/test/kotlin/app/pi/packages/PiPackageUpdateCheck.kt`).
 */
object PiPackageUpdate {

    /** pi's own word for "every configured package" (`package-manager-cli.ts:427-434`). */
    const val ALL_ARG = "--extensions"

    /** What "all packages" is called in a user-facing sentence. */
    const val ALL_WHAT = "全部资源包"

    /** pi's subcommand word; kept here so the service does not spell it twice. */
    const val COMMAND = "update"

    /**
     * A resolved update request.
     *
     * [source] is the **raw** source string exactly as `settings.json` (or `pi list`)
     * spells it — null means "every configured package". It is deliberately not
     * shell-quoted here: quoting is `PtyLauncher.Shell.quote`'s one job, so [words]
     * takes the quoter as an argument instead of importing one.
     */
    sealed interface Plan {
        data class Run(val source: String?, val what: String) : Plan
        data class Refused(val message: String) : Plan
    }

    /** pi's two words that mean "update pi itself" (`package-manager-cli.ts:534-536`). */
    private val SELF_TARGETS = setOf("self", "pi")

    /**
     * The plan for a source the user named, or for every package when it is null.
     *
     * The only refusals are input the app must not turn into a command: an empty
     * source, and pi's self-update spellings. Everything else is handed to pi
     * verbatim — if the source matches nothing, pi's own
     * `No matching package found for …` (`package-manager.ts:1396-1402`) is the
     * message the user sees, which is more faithful than a second matcher here.
     */
    fun plan(source: String?): Plan {
        if (source == null) return Plan.Run(source = null, what = ALL_WHAT)
        val raw = source.trim()
        if (raw.isEmpty()) return Plan.Refused("没有要更新的来源。")
        if (raw in SELF_TARGETS) return Plan.Refused(PackageStrings.UPDATE_REFUSED_SELF)
        return Plan.Run(source = raw, what = raw)
    }

    /**
     * The CLI words after the js entry point, in pi's order, with [quote] applied to
     * the positional source (pi quotes it as one shell word for the same reason
     * `install` does: a `@`-bearing spec, a URL with `&`, or a path with a space).
     *
     * `--approve` is appended by the caller, the way [PiPackageService.commandLine]
     * already does it for install/remove; this function owns the command and the
     * source only.
     */
    fun words(plan: Plan.Run, quote: (String) -> String): List<String> = listOf(
        COMMAND,
        plan.source?.let(quote) ?: ALL_ARG,
    )

    /**
     * Whether offering the action for [source] can change anything at all.
     *
     * This is not a second reading of pi's semantics, it is pi's own line:
     * `updateConfiguredSources` puts an npm source in the candidate list **only when
     * it is not pinned** (`package-manager.ts:1104`) and never puts a local path
     * there (`:1099-1110` — only `npm` and `git` are collected). A button for those
     * would produce pi's `Updated …` line over a guaranteed no-op, which is exactly
     * the dishonesty this screen is not allowed to ship.
     */
    fun canUpdate(source: PiPackageSource): Boolean = skipReason(source) == null

    /** The sentence a row shows instead of the button, or null when it can update. */
    fun skipReason(source: PiPackageSource): String? = when (source) {
        is PiPackageSource.Npm -> if (source.pinned) PackageStrings.UPDATE_SKIP_PINNED else null
        is PiPackageSource.Local -> PackageStrings.UPDATE_SKIP_LOCAL
        is PiPackageSource.Git -> null
    }

    /**
     * The result line when pi printed none of its own.
     *
     * Only reached on exit code 0 with no `Installed `/`Removed `/`Updated ` line —
     * a future pi, or output pi suppressed. It states that pi printed nothing rather
     * than inventing a completed update, because that is the one fact we have.
     */
    fun fallbackSummary(what: String): String = "pi 完成，但没有打印它自己的结果行：$what"

    /**
     * pi's own result line from stdout, or null.
     *
     * All three verbs share one reader because all three are the same shape:
     * `package-manager-cli.ts:956` (`Installed …`), `:966` (`Removed …`), `:1017` /
     * `:1019` (`Updated …`). Matching the verb rather than "the first non-empty line"
     * matters: `pi update` also streams `Updating <source>...` progress lines
     * (`:946-950`, from `setProgressCallback`), and those are not the result.
     */
    fun resultLine(stdout: String): String? = stdout.lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            line.startsWith("Installed ") || line.startsWith("Removed ") || line.startsWith("Updated ")
        }
}
