package app.pi.rpc

/**
 * pi's **pre-spawn surface** as data: the CLI flags and environment variables
 * that only exist at the moment `pi --mode rpc` starts.
 *
 * This table is the app's judgement about *which* of those knobs deserve a
 * settings row, written where it can be checked instead of in prose only:
 *
 *  - [APP_EXPOSED_PRE_SPAWN] — the knobs the app hands to pi in
 *    [PiLaunchOptions]. The invariant is that **none of them has a
 *    `settings.json` counterpart**, because a key pi already reads would make
 *    the process row a second source of truth, and the CLI value wins (the
 *    clearest case is `--tools` overriding `defaultTools`,
 *    `core/sdk.ts:258-264`).
 *  - [COVERED_BY_PI_SETTING] — CLI/env knobs pi *also* exposes as a settings
 *    key. The app exposes the key and must not mirror the flag.
 *  - [NOT_EXPOSED_PRE_SPAWN] — CLI/env-only knobs the app deliberately leaves
 *    alone, each with the reason. A reason is mandatory: "we did not get to it"
 *    and "it cannot mean anything here" have to be distinguishable.
 *
 * `file:line` here points into pi's source tree for reading; the *shipped*
 * spelling is asserted by `tools/pi-contract.mjs` against the pinned engine, so
 * a rename in the engine fails the `contract` job with the App file to re-read.
 *
 * Companion narrative: `docs/pre-spawn-config.md`.
 */

/** How a pre-spawn value reaches pi. */
enum class PiPreSpawnChannel {
    /** Only a CLI flag; pi has no environment variable for it. */
    CliFlag,

    /** Only an environment variable; pi has no CLI flag for it. */
    EnvVar,

    /** Either the CLI flag or an equivalent environment variable. */
    CliFlagOrEnv,

    /**
     * Flags whose **names belong to an extension**, not to the app: the row holds
     * text the user writes, parsed with pi's own rules for an unrecognised `--flag`
     * ([ExtensionFlagArgs]) before it becomes argv.
     *
     * `flag`/`envVar` are null for this channel and that is the point — the app
     * cannot know which names exist (pi builds that set from the extensions it
     * loaded, `core/agent-session-services.ts:13-19`), so there is no spelling to
     * write down. The `pre-spawn` harness asserts this knob emits the flags of its
     * fixture instead of looking for one fixed `--flag`.
     */
    ExtensionFlagsPassThrough,
}

/**
 * A knob the app carries into pi's process.
 *
 * @param appKey the settings key that holds the value (user-visible row).
 * @param flag the CLI spelling, or null when the knob is environment-only.
 * @param envVar the environment spelling, or null when the knob is CLI-only.
 * @param piReader pi's parser and first consumer, as `file:line`.
 */
data class PiPreSpawnKnob(
    val appKey: String,
    val flag: String?,
    val envVar: String?,
    val channel: PiPreSpawnChannel,
    val piReader: String,
)

/** A CLI/env knob the app leaves alone on purpose. */
data class PiPreSpawnSkipped(
    val flag: String?,
    val envVar: String?,
    val piReader: String,
    val reason: String,
)

/**
 * A CLI/env knob pi also exposes as a `settings.json` key.
 *
 * `why` is the evidence that the settings key is the app's channel and the flag
 * must not be mirrored — for `--tools` it is that the CLI value *wins* over
 * `defaultTools`, so a process row would silently disable the settings row.
 */
data class PiCliKnobWithSetting(
    val flag: String?,
    val envVar: String?,
    val piReader: String,
    val piSettingKey: String,
    val why: String,
)

/**
 * The knobs the app hands to pi at launch, in [PiLaunchOptions] order.
 *
 * Every entry has `flag` or `envVar` implemented in
 * `PiLaunchOptions.commandLineSuffix()` / `PiLaunchOptions.environment()`; the
 * `pre-spawn` harness fails if one is listed here and not emitted.
 */
val APP_EXPOSED_PRE_SPAWN: List<PiPreSpawnKnob> = listOf(
    PiPreSpawnKnob(
        appKey = "app.runtime.offline",
        flag = "--offline",
        envVar = "PI_OFFLINE",
        channel = PiPreSpawnChannel.CliFlagOrEnv,
        piReader = "cli/args.ts:223 (parse); main.ts:569-572 (offlineMode sets PI_SKIP_VERSION_CHECK too); " +
            "core/package-manager.ts:54; core/model-runtime.ts:197; docs/environment-variables.md:84",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.systemPrompt",
        flag = "--system-prompt",
        envVar = null,
        channel = PiPreSpawnChannel.CliFlag,
        piReader = "cli/args.ts:110 (parse); main.ts:776 (into the resource loader); " +
            "core/resource-loader.ts:526 (used only when set — otherwise SYSTEM.md is discovered)",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.appendSystemPrompt",
        flag = "--append-system-prompt",
        envVar = null,
        channel = PiPreSpawnChannel.CliFlag,
        piReader = "cli/args.ts:112-114 (parse, repeatable); main.ts:777; " +
            "core/resource-loader.ts:533-534 (used only when set — otherwise APPEND_SYSTEM.md is discovered); " +
            "core/agent-session.ts:1122-1123 (joined with a blank line); core/system-prompt.ts:64",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.cacheRetention",
        flag = null,
        envVar = "PI_CACHE_RETENTION",
        channel = PiPreSpawnChannel.EnvVar,
        piReader = "packages/ai/src/api/anthropic-messages.ts:64; openai-completions.ts:293; " +
            "openai-responses.ts:62; pi-messages.ts:352; bedrock-converse-stream.ts:819; " +
            "docs/environment-variables.md:87",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.noContextFiles",
        flag = "--no-context-files",
        envVar = null,
        channel = PiPreSpawnChannel.CliFlag,
        piReader = "cli/args.ts:194 (parse, alias -nc); main.ts:775; core/resource-loader.ts:273/:516",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.noExtensions",
        flag = "--no-extensions",
        envVar = null,
        channel = PiPreSpawnChannel.CliFlag,
        piReader = "cli/args.ts:169 (parse, alias -ne; help :303); main.ts:771; " +
            "core/resource-loader.ts:452/:556 (only `-e` sources survive; discovery and packages do not). " +
            "The app passes no `-e`, so its shipped extensions stop too — deliberately 1:1 with pi",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.noSkills",
        flag = "--no-skills",
        envVar = null,
        channel = PiPreSpawnChannel.CliFlag,
        piReader = "cli/args.ts:188 (parse, alias -ns); main.ts:772; core/resource-loader.ts:468/:674",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.noPromptTemplates",
        flag = "--no-prompt-templates",
        envVar = null,
        channel = PiPreSpawnChannel.CliFlag,
        piReader = "cli/args.ts:190 (parse, alias -np); main.ts:773; core/resource-loader.ts:483/:697",
    ),
    PiPreSpawnKnob(
        appKey = "app.runtime.noThemes",
        flag = "--no-themes",
        envVar = null,
        channel = PiPreSpawnChannel.CliFlag,
        piReader = "cli/args.ts:192 (parse, no short alias); main.ts:774; core/resource-loader.ts:502/:721",
    ),
    PiPreSpawnKnob(
        appKey = "app.extensions.args",
        // No spelling: the names are the extensions'. See the channel's KDoc.
        flag = null,
        envVar = null,
        channel = PiPreSpawnChannel.ExtensionFlagsPassThrough,
        piReader = "cli/args.ts:217-233 (any unrecognised `--flag` → unknownFlags: `=` form, space form, " +
            "bare → true, repeat wins); main.ts:741 (parsed.unknownFlags → extensionFlagValues); " +
            "core/agent-session-services.ts:8-43 (only a flag an extension registered is written; a boolean " +
            "flag takes `true`; a registered non-boolean given `true` is an error diagnostic; unregistered " +
            "names are reported as `Unknown option` on stderr without exiting); " +
            "core/extensions/loader.ts:288-292 (getFlag returns a value only for a registered name)",
    ),
    PiPreSpawnKnob(
        appKey = "app.extensions.args",
        // No spelling: the names are the extensions'. See the channel's KDoc.
        flag = null,
        envVar = null,
        channel = PiPreSpawnChannel.ExtensionFlagsPassThrough,
        piReader = "cli/args.ts:217-233 (any unrecognised `--flag` → unknownFlags: `=` form, space form, " +
            "bare → true, repeat wins); main.ts:737 (parsed.unknownFlags → extensionFlagValues); " +
            "core/agent-session-services.ts:8-43 (only a flag an extension registered is written; a boolean " +
            "flag takes `true`; a registered non-boolean given `true` is an error diagnostic; unregistered " +
            "names are reported as `Unknown option` on stderr without exiting); " +
            "core/extensions/loader.ts:288-292 (getFlag returns a value only for a registered name)",
    ),
)

/**
 * CLI/env knobs pi also exposes as a settings key.
 *
 * The settings key is the app's channel for all of these; none may appear in
 * [APP_EXPOSED_PRE_SPAWN] (the `pre-spawn` harness asserts the disjointness).
 */
val COVERED_BY_PI_SETTING: List<PiCliKnobWithSetting> = listOf(
    PiCliKnobWithSetting(
        flag = "--tools",
        envVar = null,
        piReader = "cli/args.ts:137-141; main.ts:538-539; core/sdk.ts:258-264",
        piSettingKey = "defaultTools",
        why = "the CLI allowlist wins over `defaultTools` (`options.tools ?? configuredDefaultToolNames`), " +
            "so a process row would silently disable the settings row.",
    ),
    PiCliKnobWithSetting(
        flag = "--models",
        envVar = null,
        piReader = "cli/args.ts:131-132; main.ts:792",
        piSettingKey = "enabledModels",
        why = "`parsed.models ?? settingsManager.getEnabledModels()` — the CLI value wins.",
    ),
    PiCliKnobWithSetting(
        flag = "--provider",
        envVar = null,
        piReader = "cli/args.ts:104-105; main.ts:472-516",
        piSettingKey = "defaultProvider",
        why = "the saved default is what the app's 模型与推理 rows write; RPC `set_model` changes it live.",
    ),
    PiCliKnobWithSetting(
        flag = "--model",
        envVar = null,
        piReader = "cli/args.ts:106-107; main.ts:472-516",
        piSettingKey = "defaultModel",
        why = "same as --provider; also settable live through RPC `set_model`.",
    ),
    PiCliKnobWithSetting(
        flag = "--thinking",
        envVar = null,
        piReader = "cli/args.ts:147-156; main.ts:515-516; core/sdk.ts:237-255",
        piSettingKey = "defaultThinkingLevel",
        why = "pi has `defaultThinkingLevel` / `modelThinkingLevels` and RPC `set_thinking_level`.",
    ),
    PiCliKnobWithSetting(
        flag = "--approve / --no-approve",
        envVar = null,
        piReader = "cli/args.ts:219-222; main.ts:729-748",
        piSettingKey = "defaultProjectTrust",
        why = "the `defaultProjectTrust` key is the persistent form; the flag is a one-run override.",
    ),
    PiCliKnobWithSetting(
        flag = "--session-dir",
        envVar = "PI_CODING_AGENT_SESSION_DIR",
        piReader = "cli/args.ts:129-130; main.ts:676-679; cli/args.ts:431",
        piSettingKey = "sessionDir",
        why = "the app passes both the flag and the variable, which outrank the setting — a settings row " +
            "could never be read (docs/settings-review.md §7.5).",
    ),
    PiCliKnobWithSetting(
        flag = "--extension",
        envVar = null,
        piReader = "cli/args.ts:166-168; main.ts:713",
        piSettingKey = "extensions",
        why = "the settings array is the persistent form of the same additional-paths input.",
    ),
    PiCliKnobWithSetting(
        flag = "--skill",
        envVar = null,
        piReader = "cli/args.ts:171-173; main.ts:714",
        piSettingKey = "skills",
        why = "same additional-paths shape as `extensions`.",
    ),
    PiCliKnobWithSetting(
        flag = "--prompt-template",
        envVar = null,
        piReader = "cli/args.ts:174-176; main.ts:715",
        piSettingKey = "prompts",
        why = "same additional-paths shape as `extensions`.",
    ),
    PiCliKnobWithSetting(
        flag = "--theme",
        envVar = null,
        piReader = "cli/args.ts:177-179; main.ts:716",
        piSettingKey = "themes",
        why = "same additional-paths shape as `extensions`.",
    ),
    PiCliKnobWithSetting(
        flag = "--use-theme",
        envVar = null,
        piReader = "cli/args.ts:180-187; main.ts:666-667",
        piSettingKey = "theme",
        why = "the flag is a one-run override of the `theme` key.",
    ),
    PiCliKnobWithSetting(
        flag = null,
        envVar = "VISUAL / EDITOR",
        piReader = "core/settings-manager.ts:986-988",
        piSettingKey = "externalEditor",
        why = "the setting takes precedence over both variables.",
    ),
    PiCliKnobWithSetting(
        flag = null,
        envVar = "HTTP_PROXY / HTTPS_PROXY",
        piReader = "main.ts:587 / :854 applyHttpProxySettings",
        piSettingKey = "httpProxy",
        why = "the setting is applied into those variables at process start; the row is already RestartEngine.",
    ),
)

/**
 * CLI/env-only knobs the app does not expose, with the reason.
 *
 * The bar for a row is "pi has no settings key for it **and** it means something
 * on a phone". Anything either covered by a key or meaningless here belongs in
 * [COVERED_BY_PI_SETTING] / this list instead of the settings screen.
 */
val NOT_EXPOSED_PRE_SPAWN: List<PiPreSpawnSkipped> = listOf(
    PiPreSpawnSkipped(
        flag = "--exclude-tools",
        envVar = null,
        piReader = "cli/args.ts:142-146; main.ts:541-542; core/sdk.ts:260-264",
        reason = "CLI-only (pi has no settings key), but the tool universe is built-ins plus whatever " +
            "extensions register at runtime, so the settings screen cannot offer an accurate list; " +
            "`defaultTools` already covers turning built-in tools off, and a second overlapping control " +
            "would be the two-truths shape this repository has paid for.",
    ),
    PiPreSpawnSkipped(
        flag = "--no-session",
        envVar = null,
        piReader = "cli/args.ts:121-122",
        reason = "CLI-only. The app's session list, resume and export are all built on session files; there is " +
            "no ephemeral-session surface. Adding one is a feature, not this pass.",
    ),
    PiPreSpawnSkipped(
        flag = "--name",
        envVar = null,
        piReader = "cli/args.ts:115-120",
        reason = "RPC has `set_session_name` (rpc-types.ts:62) and the app already renames live " +
            "(PiSessionViewModel.setSessionName), so a launch flag would duplicate a runtime control.",
    ),
    PiPreSpawnSkipped(
        flag = "--verbose",
        envVar = null,
        piReader = "cli/args.ts:217-218; main.ts:946",
        reason = "only reaches pi's interactive TUI startup; nothing in RPC mode reads it.",
    ),
    PiPreSpawnSkipped(
        flag = "--tui-mode",
        envVar = null,
        piReader = "cli/args.ts:203-216; main.ts:947",
        reason = "pi's own TUI layout. The engine here runs --mode rpc; there is no TUI to lay out " +
            "(the `tuiMode` settings key is the persistent form and is equally TUI-only).",
    ),
    PiPreSpawnSkipped(
        flag = "--api-key",
        envVar = null,
        piReader = "cli/args.ts:108-109; main.ts:810-818",
        reason = "a one-run plaintext override. The app's credential surface writes auth.json/models.json " +
            "instead, which also keeps the key out of the process argument list.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_TELEMETRY",
        piReader = "core/telemetry.ts:8-10",
        reason = "the variable overrides the `enableInstallTelemetry` settings key; the app exposes the key, " +
            "and a second switch for one value would be two truths.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_SHARE_VIEWER_URL",
        piReader = "config.ts:515-519",
        reason = "/share is an interactive-TUI built-in that is not on the RPC surface; nothing on this " +
            "platform can call it.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_PACKAGE_DIR",
        piReader = "config.ts:391",
        reason = "the payload decides where the engine lives (RuntimeProvisioner extracts it by revision); " +
            "it is not a user preference.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_SKIP_VERSION_CHECK",
        piReader = "core/version-check (main.ts:569-572 sets it under --offline)",
        reason = "the app pins it to 1 in the engine environment because update checks are the app's job " +
            "(PiEngineHost.kt:305-306), not a user switch.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_CODING_AGENT_DIR",
        piReader = "config.ts:528-533",
        reason = "the app binds the agent directory into the guest so its readers and pi see the same files " +
            "(PiEngineHost.kt:307); changing it would split them.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_CODING_AGENT_SESSION_DIR",
        piReader = "main.ts:676-679; cli/args.ts:431",
        reason = "same reason as --session-dir: the app pins the session root so its list and pi agree.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_HARDWARE_CURSOR / PI_HYPERLINKS / PI_IMAGE_PROTOCOL / PI_TRUE_COLOR / PI_TUI_ESC_TIMEOUT",
        piReader = "docs/environment-variables.md:90-94 (pi's terminal renderer)",
        // This used to say "pi's TUI never runs here", which is false and was load-bearing:
        // the terminal page opens a plain shell and the user starts `pi` themselves, so the
        // TUI *does* run. That is exactly why `PtyLauncher.Spec.environment()` pins the
        // capability statements for it (`COLORTERM=truecolor`, `PI_HYPERLINKS=0`,
        // `PI_IMAGE_PROTOCOL=none`, `PI_TUI_ESC_TIMEOUT=150`). Two different reasons, not one:
        //  - the capability ones have a `settings.json` twin (`terminal.hyperlinks` /
        //    `terminal.images` / `terminal.trueColor`), and pi spreads the setting **after**
        //    environment detection (`packages/tui/src/terminal-image.ts:160-170`), so such a row
        //    would be a second control that can contradict the terminal it draws into;
        //  - `PI_HARDWARE_CURSOR` has no twin here at all: its settings key `showHardwareCursor`
        //    is a TUI cursor knob, and the terminal page's cursor is the app's own concern.
        reason = "the capability statements are pinned by the terminal itself, from the one place that " +
            "knows what it can render (`PtyLauncher.Spec.environment()`), and pi applies the matching " +
            "`terminal.*` setting over them — a row would be a second, contradictable control. " +
            "`PI_HARDWARE_CURSOR`/`showHardwareCursor` is a TUI cursor knob instead; not exposed.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_STARTUP_BENCHMARK",
        piReader = "main.ts:918-921",
        reason = "a development benchmark switch; it even refuses to run outside interactive mode.",
    ),
    PiPreSpawnSkipped(
        flag = null,
        envVar = "PI_RADIUS_GATEWAY / PI_SERVER_DIR / PI_SERVER_ID",
        piReader = "experimental/radius-auth.ts:9; experimental/server.ts:51-52",
        reason = "source-only experimental features, not part of a distributed build.",
    ),
)

/**
 * The settings keys the app's pre-spawn surface is built from, in table order.
 *
 * Two groups show them, not one: `运行时与诊断 → 进程` carries the engine knobs
 * (offline / cache retention / context files) and `提示词` carries the two prompt
 * flags (`app.runtime.systemPrompt` / `app.runtime.appendSystemPrompt`), which
 * moved there because a user looks for them next to the model — pi's CLI keeps
 * `--model` and `--system-prompt` adjacent (`cli/args.ts:108-112`). The
 * `pre-spawn` harness asserts every key here lives in one of those two groups.
 */
fun appExposedPreSpawnKeys(): Set<String> = APP_EXPOSED_PRE_SPAWN.map { it.appKey }.toSet()

/** The knob for [appKey], or null when the key is not a pre-spawn row. */
fun piPreSpawnKnob(appKey: String): PiPreSpawnKnob? =
    APP_EXPOSED_PRE_SPAWN.firstOrNull { it.appKey == appKey }
