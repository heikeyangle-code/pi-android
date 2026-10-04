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
 *  - [noExtensions] / [noSkills] / [noPromptTemplates] / [noThemes] →
 *    `--no-extensions` / `--no-skills` / `--no-prompt-templates` / `--no-themes`
 *    (`src/cli/args.ts:169`, `:188`, `:190`, `:192`), the four resource-discovery
 *    suppressions. **1:1 with pi, no exceptions.** `--no-extensions` is *not*
 *    paired with `-e`: the app's shipped extensions are ordinary extensions in
 *    `<agentDir>/extensions/`, so disabling discovery disables them too — that is
 *    what the switch means (`core/resource-loader.ts:452`/`:556`: only
 *    `cliEnabledExtensions` survive, and the app passes none). The App's own
 *    "keep the shipped ones, drop the rest" wish is a different app-side feature;
 *    it does not belong in this flag (`docs/pre-spawn-config.md` §2.2).
 *
 *  - [continueSessionId] → `--session-id <id>`: which conversation this **process**
 *    starts on. pi's own semantics are exactly the ones this app needs — *"Use exact
 *    project session ID, **creating it if missing**"* (`cli/args.ts:288`) — because
 *    `createSessionManager` opens the file when `findById` finds that id
 *    (`main.ts:435-441` → `SessionManager.open`, which **appends**:
 *    `core/session-manager.ts:938-956` — `loadEntriesFromFile` + `loadedEntries` +
 *    `flushed = true`) and otherwise creates a new session **with that id**
 *    (`main.ts:442-447`). So "the first launch creates it, every later launch throws
 *    a new conversation into the same file" is pi's behaviour, not something this app
 *    has to arrange with `switch_session`.
 *
 *    **Space form only, never `--session-id=<id>`.** pi matches this flag on the
 *    exact token `--session-id` and takes the next one (`cli/args.ts:126-128`); an
 *    `=`-spelled token falls into the generic "unknown flag" branch (`:227-234`),
 *    which pi hands to extensions — the id would be **silently dropped** and pi would
 *    start a brand-new random session. That is the one failure this app could not
 *    see, so [renderFlag] refuses these names outright.
 *
 *    **That asymmetry has a second half, and it is [resumeSessionPath]**:
 *    `--session <path>` is emitted **only** when the id form cannot resolve, and only
 *    for a file the caller has already read as that very session. It is the one
 *    parameter without a cwd filter (`main.ts:337-345` → `SessionManager.open`, which
 *    **appends**, and whose recorded cwd becomes the new runtime's cwd,
 *    `:695-700` — the same state `switch_session` puts the engine in). It is not the
 *    default because a path that exists and is **not a valid pi session** makes
 *    `_setSessionFile` throw (`:945-951`) and `openSessionOrExit` answer with
 *    `process.exit(1)` (`main.ts:337-345`) — the engine would not start at all, on
 *    every retry, where today a refused `switch_session` is one snackbar and the
 *    engine keeps running. The id form has no such branch: an id that cannot be found
 *    is created. So the path is passed only after the caller has read that file's
 *    header and found the session id it is on; anything else stays on the id form.
 *
 *    Two edges of that asymmetry, and after [resumeSessionPath] only the first is left
 *    to it:
 *    the session file was deleted or renamed ⇒ `findById` finds nothing ⇒ pi creates
 *    a new session **with the same id** (`main.ts:442-447`), no error, and the old
 *    file (if it still exists under another name) is still in the list — that one is
 *    wanted (the conversation keeps its identity), so it stays on the id form; and a
 *    cwd `findById` cannot match ⇒ the caller **must not** pass the id, because that
 *    is the case that creates a *second* file under an id that already names a
 *    session. `findById` filters by cwd whenever `--session-dir` is not that cwd's
 *    default directory (`:1732-1744`, which is this app's case: `PiEngineHost` passes
 *    the flat `<agentDir>/sessions`), so a **workspace switch** passes no id at all
 *    and the other spellings of the same cwd ([resumeSessionPath]'s KDoc) switch to
 *    the path form.
 *  - [continueMostRecent] → `-c` / `--continue` (`cli/args.ts:100-101`): pi's
 *    `SessionManager.continueRecent(cwd, sessionDir)` (`main.ts:431-433`), which is
 *    `findMostRecentSession(dir, cwd)` — the `.jsonl` files of `--session-dir` sorted
 *    by **file mtime** descending, first one whose header cwd matches
 *    (`core/session-manager.ts:660-679`; the cwd filter is on because the app passes
 *    an explicit `--session-dir`, `:1653-1654`). This is what an **App-level** start
 *    uses, where the process has no memory of which file it was on; the switch
 *    `app.sessions.resumeLast` decides whether it is passed at all (default **on**).
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
     * `--no-extensions`: stop pi's extension **discovery**, exactly as pi defines it.
     *
     * pi keeps the explicit `-e` sources and drops everything discovery would have
     * found (`core/resource-loader.ts:569-571`:
     * `noExtensions ? cliEnabledExtensions : mergePaths(cliEnabledExtensions, enabledExtensions)`).
     * The app passes no `-e` — not even for its own shipped extensions — so this
     * switch turns **all** of them off, device layer included. That is deliberate:
     * the flag is exposed 1:1, and "keep the app's own extensions while hiding the
     * user's" is a separate app-side feature that must not be smuggled in through
     * `-e` (`docs/pre-spawn-config.md` §2.2).
     *
     * **0.99.0 changed what "all of them" covers.** pi's own built-in extensions —
     * `mcp`, `codemode`, `tool-search` and `llama.cpp` — used to sit outside this
     * gate: 0.87.1's `llama.cpp` was an inline factory, so this flag left it running.
     * Since 0.99.0 they are ordinary resources registered under `builtin:<name>`
     * (`core/package-manager.ts:972-983`, `core/source-info.ts`'s `BUILTIN_PATH_PREFIX`)
     * and collected into the same `enabledExtensions` list the ternary above reads, so
     * the same line drops them too. Measured on both releases: with this flag set,
     * 0.87.1's `get_commands` still answered `/llama`, and 0.99.2 answers nothing.
     *
     * The user-visible consequence is that turning this on also removes the
     * local-model provider and `/mcp` — which is why the settings row that exposes it
     * (`ui/settings/PiSettingsRegistry.kt`) must say so instead of promising that only
     * discovery stops.
     */
    val noExtensions: Boolean = false,
    /** `--no-skills`: stop pi's skill discovery and loading. */
    val noSkills: Boolean = false,
    /** `--no-prompt-templates`: stop pi's prompt-template discovery and loading. */
    val noPromptTemplates: Boolean = false,
    /** `--no-themes`: stop pi's theme discovery and loading. */
    val noThemes: Boolean = false,
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
    /**
     * The conversation this process must start on, as pi's session **id** (not a path —
     * see the class KDoc for why a path is not offered here). Emitted as
     * `--session-id <id>`, in the **space** form: pi creates the session when the id is
     * not found and appends to it when it is, so passing the id the app is already on
     * makes an engine (re)start land in the same conversation with no `switch_session`
     * round trip.
     *
     * Null means "no opinion" — then [continueMostRecent] decides, and if that is false
     * too pi creates a brand-new session exactly as it always did.
     */
    val continueSessionId: String? = null,
    /**
     * The conversation this process must start on, named by its **file**
     * (`--session <path>`), or null to let [continueSessionId] decide.
     *
     * This is the second half of [continueSessionId]'s story, not an alternative to it,
     * and it exists because pi's id lookup has a condition this app cannot satisfy from
     * the outside: `SessionManager.findById` requires **id ∧ `header.cwd` == this
     * process's cwd ∧ the file is a direct child of `--session-dir`**, and when any of
     * the three misses, pi does not fail — it creates a **second file with the same id**
     * (`main.ts:442-447`) and appends there from then on. One conversation then lives in
     * two files that both claim one id; a client that lists by id shows whichever half
     * it prefers, with the display name taken from **that half's** first message. The cwd
     * condition really does miss in the field: proroot's `getcwd()` leaks the host
     * spelling of the workspace (`docs/proroot-mode-audit.md:20-48`), so a session written
     * under one runtime — or before the workspace moved into the rootfs, or under another
     * workspace name — is not found under another spelling of the same directory.
     *
     * `--session <path>` has no cwd filter: it opens exactly the file the caller names,
     * and `main.js:695-700` builds the runtime around **that session's own cwd**, i.e. the
     * same state the app already reaches when the user opens a conversation from the list
     * (`PiSessionViewModel.switchSession`). The reason it is not the only form is the
     * `process.exit(1)` branch described under [continueSessionId]; the caller therefore
     * passes a path only for a file it has just read as that session.
     *
     * **Which of the two is passed is one decision, made in one place**:
     * `app.pi.session.resumeTargetFor` (`SessionResume.kt`) owns it and
     * `SessionResumeCheck` pins it — including the property that the ordinary case
     * (`header.cwd` equal, flat layout) is still spelled `--session-id`, byte for byte.
     * Setting both fields is not a way to ask for both: pi reads `parsed.session` first
     * (`main.ts:310-345`), so the id would be ignored.
     */
    val resumeSessionPath: String? = null,
    /**
     * Whether to pass `-c` (`--continue`) so a process with no known session resumes the
     * most recent one for its cwd (`core/session-manager.ts:660-679`).
     *
     * Ignored when [continueSessionId] is set: a known id is exact and `-c` is a
     * heuristic (mtime), so the exact answer wins.
     */
    val continueMostRecent: Boolean = false,
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
        // The four discovery suppressions, each emitted exactly as pi defines it and
        // nothing else — no `-e` companion (see [noExtensions]).
        if (noExtensions) append(" --no-extensions")
        if (noSkills) append(" --no-skills")
        if (noPromptTemplates) append(" --no-prompt-templates")
        if (noThemes) append(" --no-themes")
        // The session this process starts on. **Appended as its own two tokens** (never
        // through [renderFlag], which emits the `=` form neither the `--session-id` nor the
        // `--session` branch of pi's parser recognises — see [continueSessionId] and
        // [resumeSessionPath]). The path wins when both are set, because that is what pi
        // does (`parsed.session` before `parsed.sessionId`, `main.ts:310-345`); the caller
        // is expected to set exactly one.
        //
        // It sits here, before the extension pass-through, for the reason the KDoc's
        // "Order" section gives: every app flag comes first, and the extension flags —
        // where a bare `--flag` is possible — are last, so nothing of ours can be
        // swallowed as someone else's value.
        val sessionPath = resumeSessionPath
        val sessionId = continueSessionId
        if (sessionPath != null) {
            append(" --session ").append(quoteIfNeeded(sessionPath))
        } else if (sessionId != null) {
            append(" --session-id ").append(quoteIfNeeded(sessionId))
        } else if (continueMostRecent) {
            append(" -c")
        }
        extensionFlags.forEach { append(' ').append(renderFlag(it.name, it.value)) }
    }

    companion object {
        /**
         * The pure part of the App's settings → launch mapping.
         *
         * `PiSessionViewModel.launchOptions()` reads the raw values out of the
         * settings document — nine pi keys plus the app's own sidecar text for
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
            noExtensions: Boolean? = null,
            noSkills: Boolean? = null,
            noPromptTemplates: Boolean? = null,
            noThemes: Boolean? = null,
            /**
             * The conversation to start on ([PiLaunchOptions.continueSessionId]). Not a
             * setting: the caller passes the session the app is *already* on, or null.
             */
            continueSessionId: String? = null,
            /**
             * The conversation to start on, named by file ([PiLaunchOptions.resumeSessionPath]).
             * Not a setting either: the caller passes it only when the id form cannot
             * resolve (see that field's KDoc), and otherwise passes null.
             */
            resumeSessionPath: String? = null,
            /** [PiLaunchOptions.continueMostRecent], normally the `app.sessions.resumeLast` switch. */
            continueMostRecent: Boolean = false,
        ): PiLaunchOptions {
            val parsed = ExtensionFlagArgs.parse(extensionArgs)
            return PiLaunchOptions(
                offline = offline ?: false,
                longCacheRetention = cacheRetention == "long",
                systemPrompt = systemPrompt?.takeIf { it.isNotBlank() },
                appendSystemPrompt = appendSystemPrompt?.takeIf { it.isNotBlank() },
                noContextFiles = noContextFiles ?: false,
                noExtensions = noExtensions ?: false,
                noSkills = noSkills ?: false,
                noPromptTemplates = noPromptTemplates ?: false,
                noThemes = noThemes ?: false,
                extensionFlags = parsed.flags,
                extensionArgsRefusal = parsed.refusal,
                continueSessionId = continueSessionId,
                resumeSessionPath = resumeSessionPath,
                continueMostRecent = continueMostRecent,
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
            // **The two session flags are not allowed through here.** They take their value
            // as the *next token* (`cli/args.ts:123-128`), so the `=` form this function
            // emits would land in pi's unknown-flag branch (`:227-234`) and be handed to
            // extensions — the id (or the path) would vanish and pi would open a new
            // session, silently. Both are emitted as their own two tokens by
            // [PiLaunchOptions.commandLineSuffix] instead; `--session <path>` carries a
            // second reason to be careful (a path that is not a valid session makes pi
            // exit(1), see [PiLaunchOptions.resumeSessionPath]), which is why it is passed
            // only for a file the caller has already vouched for.
            require(name != SESSION_ID_FLAG && name != SESSION_FLAG) {
                "「--$name」必须发空格形式，不能用 `=`：pi 只把 `--$name` 当成这个标志" +
                    "（cli/args.ts:123-128），`--$name=<value>` 会掉进未知标志分支被吞掉（:227-234）。"
            }
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

        /** The flag names [renderFlag] must never be used for (see its `require`). */
        private const val SESSION_ID_FLAG = "session-id"
        private const val SESSION_FLAG = "session"

        private val SAFE_SHELL_CHARS: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '-', '.', '/', ':', '@', '+', '=', ',', '%')).toSet()
    }
}
