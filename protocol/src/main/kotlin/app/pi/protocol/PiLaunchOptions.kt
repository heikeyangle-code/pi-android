package app.pi.protocol

/**
 * SDK Host process & `session.open` launch options: the process-level knobs
 * decided when the SDK Host boots or opens a session (`OpenArgsFactory`).
 *
 * Environment variables follow pi's `docs/environment-variables.md` §"Pi Process
 * Configuration"; resource suppression switches map directly to SDK Host
 * `session.open` arguments. These switches are here — rather than in the command builders — for
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
 *  - **`systemPrompt` / `appendSystemPrompt` are no longer here.** They were
 *    `--system-prompt` / `--append-system-prompt` once; pi reads the same two
 *    things from `<agentDir>/SYSTEM.md` and `<agentDir>/APPEND_SYSTEM.md`, and
 *    passing the flags **short-circuits** the file discovery
 *    (`dist/core/resource-loader.js:475`: `this.systemPromptSource ??
 *    this.discoverSystemPromptFile()`), which made a `SYSTEM.md` the user wrote
 *    dead while a settings row was non-empty. The settings rows now edit those
 *    files directly (`app.pi.ui.PromptFileSettingsStore`), so this process
 *    configuration has nothing to say about prompts and does not emit either flag.
 *  - [noContextFiles] → `--no-context-files` (`src/cli/args.ts:194`), which turns
 *    off pi's discovery of `AGENTS.md` / `CLAUDE.md`. pi has no `settings.json`
 *    key for it, so it is one of the few pre-spawn switches with no duplicate
 *    source of truth.
 *  - [noExtensions] / [noSkills] / [noPromptTemplates] / [noThemes] →
 *    `--no-extensions` / `--no-skills` / `--no-prompt-templates` / `--no-themes`
 *    (`src/cli/args.ts:169`, `:188`, `:190`, `:192`), the four resource-discovery
 *    suppressions. **1:1 with pi, no exceptions.** `--no-extensions` is *not*
 *    paired with `-e`: the app's shipped extensions are ordinary extensions in
 *    `<agentDir>/extensions/`, so disabling discovery disables them too — that is
 *    what the switch means (`core/resource-loader.ts:452`/`:556`: only
 *    `cliEnabledExtensions` survive, and the app passes none). The App's own
 *    "keep the shipped ones, drop the rest" wish is a different app-side feature;
 *    it does not belong in this flag.
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
     * `-e`.
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
    /** SDK `tools` whitelist (non-null = emitted into `session.open` `args.tools`). */
    val tools: List<String>? = null,
    /** SDK `excludeTools` blacklist (non-null = emitted into `session.open` `args.excludeTools`). */
    val excludeTools: List<String>? = null,
    /** SDK `noTools`: `"all"` / `"builtin"`, or null when omitted. */
    val noTools: String? = null,
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

    companion object {
        /**
         * Maps raw setting values + session resume target into structured [PiLaunchOptions]
         * consumed by `OpenArgsFactory.toOpenArgs()` (SDK Host `session.open`) and
         * [environment] (guest process env).
         */
        fun fromSettingValues(
            offline: Boolean?,
            cacheRetention: String?,
            noContextFiles: Boolean?,
            extensionArgs: String? = null,
            noExtensions: Boolean? = null,
            noSkills: Boolean? = null,
            noPromptTemplates: Boolean? = null,
            noThemes: Boolean? = null,
            continueSessionId: String? = null,
            resumeSessionPath: String? = null,
            continueMostRecent: Boolean = false,
            tools: List<String>? = null,
            excludeTools: List<String>? = null,
            noTools: String? = null,
        ): PiLaunchOptions {
            val parsed = ExtensionFlagArgs.parse(extensionArgs)
            return PiLaunchOptions(
                offline = offline ?: false,
                longCacheRetention = cacheRetention == "long",
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
                tools = tools,
                excludeTools = excludeTools,
                noTools = noTools,
            )
        }
    }
}
