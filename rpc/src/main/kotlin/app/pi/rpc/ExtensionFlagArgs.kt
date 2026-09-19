package app.pi.rpc

/**
 * The **extension CLI flags** an app-level setting carries into pi's argv, parsed
 * with pi's own rules for an unrecognised `--flag`.
 *
 * ## Why this is a separate, pure object
 *
 * pi's parser has a small, exact table for the flags it does not know
 * (`cli/args.ts:227-241`), and the app has to reproduce it rather than approximate
 * it: the user writes a string, and "what pi would have done with `pi <that
 * string>`" is the whole contract. Every rule below cites the line it copies, and
 * `app/src/test/kotlin/app/pi/rpc/ExtensionFlagArgsCheck.kt` pins each one on a
 * bare JVM (registered as `extension-flags`).
 *
 * ## The table, line by line
 *
 * | pi (`cli/args.ts`) | here |
 * |---|---|
 * | `:23-31` `--` ends flag parsing; the rest are positionals (`@x` → file arg) | [Refusal.EndOfOptions] — refused, see below |
 * | `:217` `else if (arg.startsWith("--"))` | [parse]'s `--` branch |
 * | `:219-222` `=` present → `unknownFlags.set(name, value)` | [parse]: first `=` splits; the value may be empty and is **never** re-examined |
 * | `:224-231` no `=` → next token is the value **iff** it exists and does not start with `-` or `@`, and it is consumed | [parse]: `value = next.takeIf { !it.startsWith("-") && !it.startsWith("@") }` |
 * | `:232` otherwise `unknownFlags.set(name, true)` | [ExtensionFlagArg.value]` == null` |
 * | `:234-235` a single-dash unknown option is `{type:"error"}` | [Refusal.SingleDashOption] |
 * | `:237-238` a bare word is a positional message | [Refusal.Positional] |
 * | `unknownFlags` is a `Map`, so a repeated flag keeps the **last** value and the **first** position | [parse]'s `LinkedHashMap` |
 *
 * ## What the app refuses, and why that is still the same outcome
 *
 * pi does not pass all of the above through silently. A single-dash option is an
 * error diagnostic that makes pi **print and `exit(1)`** (`main.ts:473-481`); an
 * `@file` argument is refused outright in RPC mode (`main.ts:506-509`,
 * "`@file` arguments are not supported in RPC mode", `exit(1)`); `--` switches pi
 * into positional parsing (`:23-31`), whose only effects are a startup message and
 * `@file` arguments — neither is an extension flag. The app refuses those three
 * (and a stray bare word, same reason as `--`) **before the engine starts**, so the
 * user gets a sentence instead of `engine exited with code 1` and a stderr tail.
 * The refusal is a *message difference*, not a behaviour difference: pi would not
 * have accepted them either.
 *
 * ## What the app deliberately does **not** do
 *
 * - **No registration check.** Whether some extension declared `--plan` is knowable
 *   only inside pi, by extension code that runs during startup
 *   (`core/agent-session-services.ts:13-19` builds the registered set from the loaded
 *   extensions). A whitelist here would be an invention: it would have to guess, and
 *   a wrong guess would either block a valid flag or imply a guarantee the app cannot
 *   keep. pi itself only *reads* a value for a flag an extension registered
 *   (`core/extensions/loader.ts:288-292`), and reports the others as an
 *   `Unknown option` diagnostic on stderr (`agent-session-services.ts:37-42`) without
 *   exiting — so the app passes them and stays quiet.
 * - **No default filling.** A flag an extension declared a `default` for gets that
 *   default from pi's registration path, and only when the user did not supply a
 *   value (`core/extensions/loader.ts:264-271`); a user value is applied afterwards
 *   and wins (`agent-session-services.ts:21-35`). Substituting defaults here would be
 *   a second implementation of that ordering.
 *
 * Android-free and dependency-free (`java.io` not even needed): this file is
 * compiled by `tools/run-app-pure-checks.sh`.
 */
data class ExtensionFlagArgs(
    /** The flags pi would see in `unknownFlags`, in the user's order. */
    val flags: List<ExtensionFlagArg>,
    /** Which rule refused the text, or null when it parsed. */
    val reason: Refusal? = null,
    /** Non-null when the text cannot be handed to pi; [flags] is then empty. */
    val refusal: String? = null,
) {

    val ok: Boolean get() = refusal == null

    /**
     * The argv tokens these flags contribute, **unquoted** — exactly what pi's
     * `parseArgs` must receive for the value map to come out identical.
     *
     * A bare flag is one token (`--plan`); a flag with a value is two (`--ssh`,
     * `user@host:/path`). Emitting the space form rather than `--name=value` is what
     * makes both spellings the user may have typed round-trip to the same map, and it
     * is what [PiLaunchOptions.commandLineSuffix] quotes.
     */
    fun argvTokens(): List<String> = flags.flatMap { it.argvTokens() }

    companion object {

        val EMPTY = ExtensionFlagArgs(emptyList())

        /**
         * Parse the text a user typed into the settings row.
         *
         * The string is **not** the command line pi sees: it is split into argv
         * tokens by [tokenize] first, because pi's rules are defined over tokens
         * ("the next token", `cli/args.ts:225`). A quoted value therefore behaves the
         * way a shell would have made it behave, which is what makes
         * `--system-prompt "a b"` and `--system-prompt=a b`-style input predictable.
         */
        fun parse(text: String?): ExtensionFlagArgs {
            val tokens = tokenize(text.orEmpty())
            if (tokens.isEmpty()) return EMPTY

            val flags = LinkedHashMap<String, String?>()
            var index = 0
            while (index < tokens.size) {
                val token = tokens[index]
                when {
                    // `cli/args.ts:23-31`: everything after `--` is positional (messages
                    // or `@file`s). Neither is an extension flag, and `@file` is a hard
                    // error in RPC mode (`main.ts:506-509`).
                    token == "--" -> return refused(
                        Refusal.EndOfOptions,
                        "「--」是命令行里「后面都当作输入文件/消息」的分隔符，不是扩展参数；" +
                            "在 RPC 模式下它后面的内容会被 pi 当成启动消息，所以这里不接受它。",
                    )

                    token.startsWith("--") -> {
                        val eq = token.indexOf('=')
                        if (eq != -1) {
                            // `cli/args.ts:219-222`: the first `=` splits, and the value
                            // is taken verbatim (it may be empty; `@` and `-` are not
                            // special in this form).
                            val name = token.substring(2, eq)
                            if (name.isEmpty()) return refusedEmptyName(token)
                            flags[name] = token.substring(eq + 1)
                        } else {
                            val name = token.substring(2)
                            if (name.isEmpty()) return refusedEmptyName(token)
                            // `cli/args.ts:224-231`: only a *following* token that is not
                            // another option and not an `@file` is consumed as the value.
                            val next = tokens.getOrNull(index + 1)
                            if (next != null && !next.startsWith("-") && !next.startsWith("@")) {
                                flags[name] = next
                                index++
                            } else {
                                flags[name] = null
                            }
                        }
                    }

                    // `cli/args.ts:234-235`: an error diagnostic, and `main.ts:473-481`
                    // exits on an error diagnostic — so pi would refuse this run.
                    token.startsWith("-") -> return refused(
                        Refusal.SingleDashOption,
                        "「$token」不是 pi 认识的单横线参数。pi 的规则是：扩展参数必须是双横线" +
                            "（例如 --plan）；单横线的未知参数会让 pi 直接报错退出。",
                    )

                    // `cli/args.ts:215-216` pushes a file argument, and RPC mode refuses
                    // file arguments (`main.ts:506-509`).
                    token.startsWith("@") -> return refused(
                        Refusal.AtFileArgument,
                        "「$token」是 pi 的输入文件写法（@ 开头）。RPC 模式不支持输入文件" +
                            "（pi 会报 “@file arguments are not supported in RPC mode” 并退出）。",
                    )

                    // `cli/args.ts:237-238`: a bare word becomes a positional message,
                    // which is not an extension flag either.
                    else -> return refused(
                        Refusal.Positional,
                        "「$token」不是一个参数。扩展参数要写成 --名字（值可以直接跟在后面，" +
                            "或写成 --名字=值）。",
                    )
                }
                index++
            }

            return ExtensionFlagArgs(flags.map { (name, value) -> ExtensionFlagArg(name, value) })
        }

        /**
         * Split [text] into argv tokens the way a shell would, because that is the
         * only reading of "the same string you would type on the command line" that
         * keeps quoted values in one piece.
         *
         * Supported: whitespace between tokens; `'…'` (literal, no escapes inside);
         * `"…"` (with `\"` and `\\` escapes); `\x` outside quotes. An unterminated
         * quote is **not** an error here — the rest of the text becomes the token —
         * because pi never sees this string and a half-typed value must not become a
         * refusal the user cannot explain; the harness pins that choice.
         */
        fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var started = false
            var index = 0
            var quote: Char? = null
            while (index < text.length) {
                val c = text[index]
                when {
                    quote == '\'' -> if (c == '\'') quote = null else current.append(c)
                    quote == '"' -> when (c) {
                        '"' -> quote = null
                        '\\' -> {
                            val next = text.getOrNull(index + 1)
                            if (next == '"' || next == '\\') {
                                current.append(next)
                                index++
                            } else {
                                current.append(c)
                            }
                        }

                        else -> current.append(c)
                    }

                    c == '\'' || c == '"' -> {
                        quote = c
                        started = true
                    }

                    c == '\\' -> {
                        val next = text.getOrNull(index + 1)
                        if (next != null) {
                            current.append(next)
                            index++
                        } else {
                            current.append(c)
                        }
                        started = true
                    }

                    c.isWhitespace() -> if (started) {
                        tokens += current.toString()
                        current.setLength(0)
                        started = false
                    }

                    else -> {
                        current.append(c)
                        started = true
                    }
                }
                index++
            }
            if (started) tokens += current.toString()
            return tokens
        }

        private fun refused(reason: Refusal, message: String) =
            ExtensionFlagArgs(emptyList(), reason, "扩展启动参数没有生效：$message")

        private fun refusedEmptyName(token: String) = refused(
            Refusal.EmptyName,
            "「$token」缺少参数名。扩展参数要写成 --名字（值可以直接跟在后面，或写成 --名字=值）。",
        )
    }
}

/** One parsed flag. [value] null is pi's `true` (`cli/args.ts:232`). */
data class ExtensionFlagArg(val name: String, val value: String?) {

    /** `--name`, or `--name` followed by the value, as unquoted argv tokens. */
    fun argvTokens(): List<String> =
        if (value == null) listOf("--$name") else listOf("--$name", value)

    /** `--name` or `--name=value`, for a log line or a diagnostic — not for argv. */
    fun describe(): String = if (value == null) "--$name" else "--$name=$value"
}

/** The shapes the app refuses, each a pi rule that would have ended the run. */
enum class Refusal {
    /** `cli/args.ts:23-31` — the end-of-options marker. */
    EndOfOptions,

    /** `cli/args.ts:234-235` + `main.ts:473-481` — an error diagnostic, `exit(1)`. */
    SingleDashOption,

    /** `cli/args.ts:215-216` + `main.ts:506-509` — refused in RPC mode, `exit(1)`. */
    AtFileArgument,

    /** `cli/args.ts:237-238` — a positional message, not a flag. */
    Positional,

    /** `--` / `--=x`: no name to look up. */
    EmptyName,
}
