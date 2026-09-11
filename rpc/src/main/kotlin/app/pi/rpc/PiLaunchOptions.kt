package app.pi.rpc

/**
 * pi's **pre-spawn** configuration: the process knobs that are decided when
 * `pi --mode rpc` starts and that the RPC command surface cannot change.
 *
 * `docs/rpc.md` §"Starting RPC Mode" documents the CLI flags and pi's
 * `docs/environment-variables.md` §"Pi Process Configuration" documents the
 * variables. These three are here — rather than in the command builders — for
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
     */
    fun commandLineSuffix(): String = buildString {
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            append(" --system-prompt ").append(quote(it))
        }
        appendSystemPrompt?.takeIf { it.isNotBlank() }?.let {
            append(" --append-system-prompt ").append(quote(it))
        }
    }

    private companion object {
        /** POSIX single-quoting: an embedded `'` is closed, escaped, reopened. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
