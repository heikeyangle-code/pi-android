package app.pi.rpc

/**
 * pi's **pre-spawn** configuration: the process knobs that are decided when
 * `pi --mode rpc` starts and that the RPC command surface cannot change.
 *
 * `docs/rpc.md` §"Starting RPC Mode" documents the CLI flags and pi's
 * `docs/environment-variables.md` §"Pi Process Configuration" documents the
 * variables. These switches are here — rather than in the command builders — for
 * the reason `docs/gap-disposition.md` records against `PiEngineHost`: the map
 * of pi's process configuration was hard-wired, so a client could not ask for
 * any of them.
 *
 *  - [offline] → `PI_OFFLINE=1`. pi's words: "Disable startup network
 *    operations, including update checks, package updates, and install/update
 *    telemetry". Its truthiness differs by call site — `main.ts` and
 *    `core/package-manager.ts` test for `1`/`true`/`yes`, but
 *    `core/model-runtime.ts` passes `process.env.PI_OFFLINE === undefined` as
 *    "model network is enabled". So the variable must be **omitted**, never set
 *    to `"0"`, or setting it to disable telemetry would also disable model
 *    network access. [environment] omits it.
 *  - [longCacheRetention] → `PI_CACHE_RETENTION=long`, "extended provider
 *    prompt caching where supported".
 *  - [systemPrompt] / [appendSystemPrompt] → `--system-prompt <text>` and
 *    `--append-system-prompt <text>` (`src/cli/args.ts`). pi allows the append
 *    flag to repeat; one value is what an app-level setting can carry.
 *  - [noContextFiles] → `--no-context-files` (`src/cli/args.ts:194`), which turns
 *    off pi's discovery of `AGENTS.md` / `CLAUDE.md`. pi has no `settings.json`
 *    key for it, so it is one of the few pre-spawn switches with no duplicate
 *    source of truth (`docs/pre-spawn-config.md`).
 *
 * Every default is "unset", so `PiLaunchOptions()` produces exactly the process
 * pi gets with no options at all.
 */
data class PiLaunchOptions(
    val offline: Boolean = false,
    val longCacheRetention: Boolean = false,
    /** Replaces pi's assembled system prompt (`--system-prompt`). */
    val systemPrompt: String? = null,
    /** Appended to pi's assembled system prompt (`--append-system-prompt`). */
    val appendSystemPrompt: String? = null,
    /** Disables pi's `AGENTS.md` / `CLAUDE.md` discovery (`--no-context-files`). */
    val noContextFiles: Boolean = false,
    /**
     * Flags an **extension** registered, passed through verbatim
     * ([ExtensionFlagArgs]).
     *
     * pi accepts a `--flag` it does not recognise and hands it to whichever
     * extensions declared it (`main.ts:737` → `core/agent-session-services.ts:107-111`),
     * and the app cannot know those names — so this is the one knob whose spelling
     * belongs to the user rather than to the app.
     */
    val extensionFlags: List<ExtensionFlagArg> = emptyList(),
    /**
     * Why [extensionFlags] is empty although the user wrote something: the text hit a
     * rule pi would have rejected outright ([ExtensionFlagArgs.Refusal]). Carried
     * instead of logged so the launch path can say it in words.
     */
    val extensionArgsRefusal: String? = null,
) {

    /**
     * The environment variables to add to pi's process.
     *
     * Only ever adds keys; nothing is set to a false-y string, because several
     * of pi's flags are tested for *presence* rather than truth (see the class
     * KDoc).
     */
    fun environment(): Map<String, String> = buildMap {
        if (offline) put("PI_OFFLINE", "1")
        if (longCacheRetention) put("PI_CACHE_RETENTION", "long")
    }

    /**
     * The CLI flags as a shell-ready suffix, or `""` when nothing is set.
     *
     * The guest command is executed by `bash -lc` inside the rootfs
     * (`ProotCommand.build` appends it as the `-c` argument), so a value with a
     * space — a system prompt certainly has those — must be quoted or bash would
     * split it into separate arguments. Single-quote quoting is used because it
     * is the one POSIX form with no escape processing inside it.
     *
     * ## Order (asserted by the `extension-flags` and `pre-spawn` harnesses)
     *
     * The app's own flags first, the extension pass-through **last**, and every
     * extension flag emitted as `--name` or `--name=<value>` — see [renderFlag] for
     * why the `=` form is the only one that round-trips. Two reasons for the
     * position:
     *
     *  - `--mode rpc` and `--session-dir <path>` are emitted by `PiEngineHost`
     *    *before* this suffix, so a user flag can never be mistaken for one of their
     *    values: by the time the user's tokens start, both of those already have
     *    their arguments;
     *  - a bare `--flag` (pi's `true`) at the end of the command line has no next
     *    token to eat, so it cannot swallow anything of ours.
     *
     * Only tokens that need it are quoted ([quoteIfNeeded]), so an ordinary
     * `--plan` reaches pi spelled exactly as the user typed it — which also keeps
     * the argv readable in the diagnostic report.
     */
    fun commandLineSuffix(): String = buildString {
        systemPrompt?.takeIf { it.isNotBlank() }?.let { append(renderPromptFlag("--system-prompt", it)) }
        appendSystemPrompt?.takeIf { it.isNotBlank() }?.let {
            append(renderPromptFlag("--append-system-prompt", it))
        }
        if (noContextFiles) append(" --no-context-files")
        extensionFlags.forEach { append(' ').append(renderFlag(it.name, it.value)) }
    }

    companion object {
        /**
         * The pure part of the App's settings → launch mapping.
         *
         * `PiSessionViewModel.launchOptions()` reads the raw values out of the
         * settings document — five pi keys plus the app's own sidecar text for
         * [extensionArgs] — and this function turns them into the process inputs, so
         * the normalisation rules — a blank prompt is "unset", not an empty flag;
         * only the exact value `long` enables long cache retention; a missing
         * boolean is false, never a truth-y string — live where the bare-JVM
         * harness can execute them (`tools/run-app-pure-checks.sh`, harnesses
         * `pre-spawn` and `extension-flags`). The caller keeps only the file IO.
         *
         * Each parameter is `null` when the key is absent from the document,
         * which is how pi's sparse settings work (`settings-manager.ts`).
         *
         * @param extensionArgs the extension-flag text, or null/blank when unset. It
         *        is parsed here — not by the caller — because a refusal has to travel
         *        with the options that were *not* built from it
         *        ([PiLaunchOptions.extensionArgsRefusal]).
         */
        fun fromSettingValues(
            offline: Boolean?,
            cacheRetention: String?,
            systemPrompt: String?,
            appendSystemPrompt: String?,
            noContextFiles: Boolean?,
            extensionArgs: String? = null,
        ): PiLaunchOptions {
            val parsed = ExtensionFlagArgs.parse(extensionArgs)
            return PiLaunchOptions(
                offline = offline ?: false,
                longCacheRetention = cacheRetention == "long",
                systemPrompt = systemPrompt?.takeIf { it.isNotBlank() },
                appendSystemPrompt = appendSystemPrompt?.takeIf { it.isNotBlank() },
                noContextFiles = noContextFiles ?: false,
                extensionFlags = parsed.flags,
                extensionArgsRefusal = parsed.refusal,
            )
        }

        /**
         * One flag as a single shell word: `--name` for pi's `true`, `--name=value`
         * otherwise.
         *
         * **The `=` form, not `--name value`, and that is a correctness fix rather
         * than a style choice.** pi's space form only consumes the next token when it
         * does not start with `-` or `@` (`cli/args.ts:225`), so a value that *does*
         * start with one of those cannot be expressed that way at all: emitting
         * `--at @host` would make pi see `at = true` plus a stray `@host` — which is
         * a file argument, and RPC mode exits on those (`main.ts:506-509`). The `=`
         * form takes its value unconditionally (`:219-222`), so
         * `parse(tokenize(commandLineSuffix()))` is the identity for every value.
         *
         * Quoting is per part ([quoteIfNeeded]) and stays inside the word: bash joins
         * adjacent quoted and unquoted segments, so `'--a b'=x` is one token.
         */
        internal fun renderFlag(name: String, value: String?): String {
            val head = quoteIfNeeded("--$name")
            return if (value == null) head else "$head=${quoteIfNeeded(value)}"
        }

        /**
         * The two prompt flags, in whichever form round-trips.
         *
         * Normally the space form (`--system-prompt 'text'`), which is what this file
         * has always emitted and what the `pre-spawn` harness asserts. But a prompt
         * that *starts* with `-` or `@` hits the same pi guard as an extension value,
         * so for exactly those it switches to the `=` form — otherwise pi would see
         * the flag as `true` and treat the prompt as an option or a file argument.
         */
        internal fun renderPromptFlag(flag: String, value: String): String =
            if (value.startsWith("-") || value.startsWith("@")) {
                " $flag=${quoteIfNeeded(value)}"
            } else {
                " $flag ${quote(value)}"
            }

        /** POSIX single-quoting: an embedded `'` is closed, escaped, reopened. */
        internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /**
         * [quote] only when the shell would otherwise change the token.
         *
         * The guest command goes through `bash -lc`, so an unquoted token is safe
         * exactly while it contains no character bash acts on. Everything outside this
         * set is quoted: whitespace (the split), `'`/`"`/`\`/`$`/backtick (expansion or
         * escaping), and the glob/redirection characters. Quoting only on demand is
         * what keeps `--plan` spelled as the user wrote it in the argv a diagnostic
         * report prints.
         */
        internal fun quoteIfNeeded(token: String): String =
            if (token.isNotEmpty() && token.all { it in SAFE_SHELL_CHARS }) token else quote(token)

        private val SAFE_SHELL_CHARS: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '-', '.', '/', ':', '@', '+', '=', ',', '%')).toSet()
    }
}
