package app.pi.packages

/**
 * pi's project-trust decision, reproduced including the branch that decided it.
 *
 * ## The problem this file exists for
 *
 * In `--mode rpc`, pi's trust prompt has no UI, and with the default
 * `defaultProjectTrust: "ask"` pi **silently skips** project-local `.pi/extensions`.
 * Traced exactly:
 *
 *  - trust decisions are resolved by `resolveProjectTrusted`
 *    (`core/project-trust.ts:46-95`);
 *  - the "no UI" branch is `if (!options.projectTrustContext.hasUI) return false`
 *    (`:86-88`);
 *  - `hasUI` for the CLI is `isInitialRuntime && trustPromptMode === "interactive"`,
 *    and RPC is not interactive (`main.ts:753`), so step 6 always fires;
 *  - the skip itself produces **no wire event, no stderr line and no error**
 *    (`docs/extension-compatibility.md:574-575`, from `loader.ts:634-637` and
 *    `main.ts:775-782`).
 *
 * So the app cannot learn from the engine that resources were skipped. It has to
 * resolve the same decision itself, from the same inputs, and then *say* what it
 * concluded. That is what this object does: [resolve] returns not just true/false
 * but the [Rationale] that produced it, so "project extensions are not loaded" is
 * a sentence the app can show instead of a silence.
 *
 * ## What the app can do about it
 *
 * Writing the same record pi would write (see [TrustRepository]) is the only
 * durable fix, and it is exactly what pi's own prompt does: `Trust` stores
 * `{ "<canonical cwd>": true }` (`trust-manager.ts:69`). The five choices are pi's
 * own five (`:66-96`), with pi's own labels, because a shorter menu would silently
 * drop `Trust parent folder` and the session-only escape hatches.
 */
object ProjectTrust {

    /** `docs/security.md:9-16`, `trust-manager.ts:30-38`. A bare `.pi` is not enough. */
    val TRUST_REQUIRING_CONFIG_ENTRIES = listOf(
        "settings.json",
        "extensions",
        "skills",
        "prompts",
        "themes",
        "SYSTEM.md",
        "APPEND_SYSTEM.md",
    )

    const val CONFIG_DIR_NAME = ".pi"

    /** `APP_NAME` (`config.ts:502`). */
    const val APP_NAME = "pi"

    /** One of pi's five prompt choices (`trust-manager.ts:66-96`). */
    data class Option(
        /** pi's exact label, shown untranslated so a user can match it to pi's docs. */
        val label: String,
        val trusted: Boolean,
        /**
         * The `trust.json` writes this option performs. Empty means session-only:
         * pi stores nothing (`trust-manager.ts:84`, `:93`).
         */
        val updates: List<Pair<String, Boolean?>>,
        /** The key pi would call `savedPath`, when the option persists. */
        val savedPath: String?,
    ) {
        val sessionOnly: Boolean get() = updates.isEmpty()
    }

    /**
     * The five choices, exactly as `getProjectTrustOptions(cwd, {includeSessionOnly:
     * true})` builds them (`trust-manager.ts:66-96`).
     *
     * The Chinese subtitles are the app's own addition and are kept **beside** pi's
     * label, never instead of it: the label is what the user will see in pi's TUI
     * and in pi's docs.
     */
    fun options(canonicalCwd: String): List<Option> {
        val list = mutableListOf(
            Option(
                label = "Trust",
                trusted = true,
                updates = listOf(canonicalCwd to true),
                savedPath = canonicalCwd,
            ),
        )
        TrustFile.parentOf(canonicalCwd)?.let { parent ->
            list += Option(
                label = "Trust parent folder ($parent)",
                trusted = true,
                // Order matters: pi trusts the parent and *clears* the child entry,
                // so a later change to the parent's subtree is re-asked once
                // (`trust-manager.ts:76-80`).
                updates = listOf(parent to true, canonicalCwd to null),
                savedPath = parent,
            )
        }
        list += Option("Trust (this session only)", trusted = true, updates = emptyList(), savedPath = null)
        list += Option(
            label = "Do not trust",
            trusted = false,
            updates = listOf(canonicalCwd to false),
            savedPath = canonicalCwd,
        )
        list += Option("Do not trust (this session only)", trusted = false, updates = emptyList(), savedPath = null)
        return list
    }

    /** `formatProjectTrustPrompt` (`project-trust.ts:24-26`), character for character. */
    fun promptText(cwd: String): String = "Trust project folder?\n$cwd\n\nThis allows $APP_NAME to load " +
        "$CONFIG_DIR_NAME settings and resources, install missing project packages, and execute project extensions."

    /** Which branch of `resolveProjectTrusted` produced the answer. */
    enum class Rationale {
        /** `--approve` / `--no-approve` (`project-trust.ts:47-49`). */
        CliOverride,

        /** Nothing in the cwd needs trust (`:50-52`). */
        NoTrustRequiringResources,

        /** A saved `trust.json` entry for the nearest ancestor (`:72-75`). */
        SavedDecision,

        /** `defaultProjectTrust` was `"always"` or `"never"` (`:77-84`). */
        DefaultAlways,
        DefaultNever,

        /** `!hasUI` — the RPC case, where pi returns false without asking (`:86-88`). */
        NoUiRefused,

        /** The user answered the app's prompt. */
        UserAnswer,
    }

    /** Inputs to the resolution, gathered from the real filesystem by the caller. */
    data class Inputs(
        val cwd: String,
        val trustOverride: Boolean? = null,
        val hasTrustRequiringResources: Boolean,
        val savedDecision: Boolean? = null,
        /** `SettingsManager.getDefaultProjectTrust()`, which coerces to `"ask"`. */
        val defaultProjectTrust: String = "ask",
        val hasUI: Boolean,
        /** Null when the app has already asked and the user answered this call. */
        val userAnswer: Boolean? = null,
    )

    data class Resolution(
        val trusted: Boolean,
        val rationale: Rationale,
        /**
         * One sentence for the user when [trusted] is false and resources will be
         * skipped. This is the app's substitute for pi's silent skip
         * (`docs/extension-compatibility.md:574-575`).
         */
        val explanation: String?,
    )

    /**
     * The branch order is `resolveProjectTrusted`'s (`project-trust.ts:46-95`).
     *
     * Note the deliberate omission: pi's step 3 is a `project_trust` extension
     * handler, which the app cannot run because it only fires inside a pi process
     * that is already loading extensions (`:54-70`). The app has no such handler,
     * so the step does not exist here. Where pi would have asked an extension, the
     * app asks the user — which is strictly more conservative and never trusts
     * something pi would have refused.
     */
    fun resolve(inputs: Inputs): Resolution {
        inputs.trustOverride?.let {
            return Resolution(it, Rationale.CliOverride, if (it) null else skipNote(inputs.cwd, Rationale.CliOverride))
        }
        if (!inputs.hasTrustRequiringResources) {
            return Resolution(true, Rationale.NoTrustRequiringResources, null)
        }
        inputs.savedDecision?.let {
            return Resolution(it, Rationale.SavedDecision, if (it) null else skipNote(inputs.cwd, Rationale.SavedDecision))
        }
        when (inputs.defaultProjectTrust) {
            "always" -> return Resolution(true, Rationale.DefaultAlways, null)
            "never" -> return Resolution(false, Rationale.DefaultNever, skipNote(inputs.cwd, Rationale.DefaultNever))
        }
        inputs.userAnswer?.let {
            return Resolution(it, Rationale.UserAnswer, if (it) null else skipNote(inputs.cwd, Rationale.UserAnswer))
        }
        if (!inputs.hasUI) {
            return Resolution(false, Rationale.NoUiRefused, skipNote(inputs.cwd, Rationale.NoUiRefused))
        }
        // hasUI and no answer yet: the caller must show [promptText] and call again
        // with `userAnswer`. Returning false here would be pi's dismissal
        // behaviour (`project-trust.ts:90-95` maps "no selection" to false), and the
        // app does not want to conflate "dismissed" with "not asked yet".
        return Resolution(false, Rationale.NoUiRefused, skipNote(inputs.cwd, Rationale.NoUiRefused))
    }

    /**
     * The sentence shown when project resources are skipped. It names the concrete
     * consequence instead of the mechanism, because the mechanism
     * (`hasUI === false`) means nothing to a user.
     */
    fun skipNote(cwd: String, rationale: Rationale): String = when (rationale) {
        Rationale.NoUiRefused ->
            "项目 $cwd 未获信任，而 pi 在非交互模式（--mode rpc）下不会弹窗询问，只会静默跳过项目本地的 " +
                ".pi/extensions、.pi/skills、.pi/prompts、.pi/settings.json。没有错误，也没有任何提示——" +
                "这就是你现在看到的行为。要做的事：在下方做出信任决定，它会写入 trust.json。"
        Rationale.DefaultNever ->
            "全局设置 defaultProjectTrust = \"never\"，因此项目资源始终被忽略。"
        Rationale.CliOverride ->
            "本次调用显式指定了 --no-approve，项目资源被忽略。"
        Rationale.SavedDecision ->
            "trust.json 里保存了 $cwd 的拒绝决定，项目资源被忽略。选择「Trust」会覆盖它。"
        Rationale.UserAnswer ->
            "你选择了不信任 $cwd，项目资源被忽略。"
        Rationale.NoTrustRequiringResources -> ""
        Rationale.DefaultAlways -> ""
    }
}
