package app.pi.ui.chat

/**
 * The string half of pi's `@` file-mention completion: what opens it, what a
 * candidate is, what a pick inserts, and how the list is ordered.
 *
 * Nothing here touches Android, the filesystem or the guest, so `app/src/test`
 * can pin all of it on a bare JVM. The candidate *set* comes from the guest's own
 * `fd` binary (see `ui/chat/PiMentionSource.kt`), because pi's candidate
 * semantics **are** fd's semantics: fd is what honours the layered ignore files,
 * `--hidden`, the `.git` exclusion, `--type f --type d --follow` and the result
 * cap (`/root/pi-src/packages/tui/src/autocomplete.ts:132-161`, `:733`, `:750`).
 * Reimplementing that ignore logic in Kotlin would be an approximation of a
 * mature crate; running the same binary with the same argv is not. So this file
 * reproduces only what pi does **after** fd has printed its lines, plus the
 * editing rules. Every rule cites the pi line it was transliterated from;
 * `bbb61e34` is the pinned revision in `docs/gap-disposition.md:16`.
 *
 * ## Three decisions to read before the code
 *
 * 1. **The caret is approximated by the end of the draft.** pi extracts the
 *    prefix from the text before the caret (`autocomplete.ts:296`), so a mention
 *    the caret sits inside can be completed. This app's composer is an
 *    `OutlinedTextField` over a plain `String` (`ui/screens/ChatScreen.kt`,
 *    `Composer`), so there is no caret in state to read. The whole draft is
 *    treated as "text before the caret", i.e. only the mention token ending the
 *    draft opens the list. That is a **deliberate deviation** whose cause is the
 *    missing caret state, not pi; a pick still replaces that trailing token, so
 *    the resulting text is the string pi would produce. Follow-up (recorded in
 *    `docs/known-gaps.md` E4): an `OutlinedTextField` bound to
 *    `TextFieldValue` would carry the caret and remove the approximation.
 *
 * 2. **A closed quoted mention at the end of the draft stays completable.** When
 *    pi completes a path that needs quotes it inserts the closing quote and
 *    leaves the caret *before* it, so typing continues inside the quotes and the
 *    mention stays open (`autocomplete.ts:422-423`, exercised by
 *    `packages/tui/test/autocomplete.test.ts:445-461`). With no caret we cannot
 *    move it, and inserting the quote without moving the caret would close the
 *    mention instead. So the quote is inserted - the draft text matches pi - and
 *    [prefixOf] keeps recognising the whole closed token as the mention being
 *    completed, which is what pi's caret placement achieves. Without this rule,
 *    completing inside a directory whose name contains a space dead-ends, which is
 *    the directory case pi exists to support (`autocomplete.ts:415`).
 *
 * 3. **Absolute paths and `~` are allowed**, exactly as in pi
 *    (`autocomplete.ts:537-543`, `:573-575`): a scoped query whose base is
 *    absolute or starts with `~/` is resolved as written, so guest directories
 *    outside the workspace can be listed. That is parity, not a new opening: the
 *    guest is proot, and pi inside it offers the same list.
 *
 * ## Deliberately not reproduced
 *
 *  - pi runs fd twice per query - a non-recursive pass and a recursive one - and
 *    de-duplicates them (`autocomplete.ts:749-759`). Both passes share the same
 *    scorer and the same total ordering, so the first pass can change the outcome
 *    only when two distinct entries tie on score, depth, length *and* path (which
 *    cannot happen), or when the recursive pass truncates at its cap and a direct
 *    child survived only in the first pass. One recursive pass is kept; that edge
 *    is accepted.
 *  - The last tie-break is `localeCompare` in pi (`autocomplete.ts:783`). Kotlin
 *    here has no ICU collator, so plain `String` ordering is used; names differing
 *    only by case or accent can order differently. Only ties are affected.
 *  - fd's output is split on newlines, so a file name containing a newline shifts
 *    the list. pi does the same (`autocomplete.ts:202`); not fixed.
 */
object PiFileMentions {

    /** One candidate, mirroring pi's `AutocompleteItem` (`autocomplete.ts:224-228`). */
    data class Item(
        /** The text a pick inserts, already carrying `@` and any quotes. */
        val value: String,
        /** What the row shows: the base name, with a trailing slash for a directory. */
        val label: String,
        /** The workspace-relative path, which is pi's `description` (`:804`). */
        val description: String?,
    )

    /** A `@` prefix with the `@` and any quote stripped, as [parse] returns it. */
    data class Prefix(
        /** The path part being typed, quotes removed. */
        val raw: String,
        /** True when the prefix was written inside `@"` quotes. */
        val quoted: Boolean,
    )

    /** One fd invocation, in the guest's spelling. */
    data class Command(
        /** `--base-directory`: what fd's output is relative to. */
        val baseDir: String,
        /** The fd pattern; empty means "no pattern", i.e. list everything. */
        val query: String,
        /** Re-applied to fd's relative output, or null when the query was not scoped. */
        val displayBase: String?,
        /** True when the query still contains a slash, which makes fd match full paths. */
        val fullPath: Boolean,
    )

    /**
     * The two invocations pi's scope resolution can end in, plus the one guest
     * command that chooses between them.
     *
     * pi decides with `statSync(baseDir).isDirectory()` (`autocomplete.ts:546`): a
     * scope that is not a directory falls back to the whole workspace with the
     * original query. Doing that stat here is impossible - the directory is in the
     * guest - so the choice is made *in* the guest by the shell test in [shell],
     * and reported back on stderr. One proot spawn either way.
     */
    data class Plan(
        val scoped: Command,
        val fallback: Command,
        val shell: String,
    )

    /** The `fd` the runtime installs (`RuntimeProvisioner.kt:464-469`). */
    const val FD: String = "/usr/local/bin/fd"

    /** pi's cap on what fd returns per pass (`autocomplete.ts:733`, `:750`). */
    const val MAX_RESULTS: Int = 100

    /** pi keeps the top 20 scored entries (`autocomplete.ts:785`). */
    const val MAX_ITEMS: Int = 20

    /** `$HOME` inside the guest (`PiRuntime.kt:142`; `AgentLayout.kt:60`). */
    const val GUEST_HOME: String = "/root"

    /**
     * The marker the shell prints to stderr so [items] knows which branch ran.
     * stderr, not stdout, so fd's path list stays exactly the bytes fd wrote.
     */
    const val SCOPE_MARKER: String = "pi-mention-scope="

    private val DELIMITERS = charArrayOf(' ', '\t', '"', '\'', '=')

    // ------------------------------------------------------------------ triggers

    /**
     * The `@` mention prefix the draft ends with, or null when none is open.
     *
     * pi's `extractAtPrefix` (`autocomplete.ts:467-482`) over the text before the
     * caret, with two transliterated helpers: `PATH_DELIMITERS` (`:7`), which is
     * why `@` only opens at a token boundary, and `extractQuotedPrefix`
     * (`:74-92`), which is why `@"a b` is one token. The extra rule for a *closed*
     * `@"..."` at the very end is decision 2 in this object's KDoc.
     */
    fun prefixOf(text: String): String? {
        val quoted = extractQuotedPrefix(text)
        if (quoted != null && quoted.startsWith("@\"")) return quoted

        val delimiter = lastDelimiter(text)
        val tokenStart = if (delimiter == -1) 0 else delimiter + 1
        if (tokenStart < text.length && text[tokenStart] == '@') return text.substring(tokenStart)

        return closedQuotedAtEnd(text)
    }

    /**
     * `parsePathPrefix` (`autocomplete.ts:94-105`), plus one thing pi never needs:
     * a trailing quote on a closed `@"..."` token is stripped too, because
     * [prefixOf] hands that form over (decision 2).
     */
    fun parse(prefix: String): Prefix = when {
        prefix.startsWith("@\"") -> Prefix(prefix.substring(2).removeSuffix("\""), quoted = true)
        prefix.startsWith("@") -> Prefix(prefix.substring(1), quoted = false)
        else -> Prefix(prefix, quoted = false)
    }

    private fun isTokenStart(text: String, index: Int): Boolean =
        index == 0 || text[index - 1] in DELIMITERS

    private fun lastDelimiter(text: String): Int {
        for (index in text.length - 1 downTo 0) {
            if (text[index] in DELIMITERS) return index
        }
        return -1
    }

    private fun unclosedQuoteStart(text: String): Int? {
        var inQuotes = false
        var quoteStart = -1
        for (index in text.indices) {
            if (text[index] == '"') {
                inQuotes = !inQuotes
                if (inQuotes) quoteStart = index
            }
        }
        return if (inQuotes) quoteStart else null
    }

    /** `extractQuotedPrefix` (`autocomplete.ts:74-92`). */
    private fun extractQuotedPrefix(text: String): String? {
        val quoteStart = unclosedQuoteStart(text) ?: return null

        if (quoteStart > 0 && text[quoteStart - 1] == '@') {
            if (!isTokenStart(text, quoteStart - 1)) return null
            return text.substring(quoteStart - 1)
        }

        if (!isTokenStart(text, quoteStart)) return null
        return text.substring(quoteStart)
    }

    /**
     * A `@"..."` token that ends the draft and is already closed (decision 2).
     *
     * The body must contain no quote, so the final quote really closes this token
     * rather than opening another one.
     */
    private fun closedQuotedAtEnd(text: String): String? {
        if (!text.endsWith("\"")) return null
        val at = text.lastIndexOf("@\"")
        if (at < 0 || !isTokenStart(text, at)) return null
        if (text.substring(at + 2, text.length - 1).contains('"')) return null
        return text.substring(at)
    }

    // ----------------------------------------------------------------- requests

    /**
     * The two invocations and the shell command that runs the one pi would.
     *
     * [workspace] is the guest spelling of the engine's cwd (the `/workspace`
     * bind, `PiEngineHost.kt:548-555`), which is pi's `basePath`
     * (`interactive-mode.ts:727-733`).
     */
    fun plan(prefix: String, workspace: String, home: String = GUEST_HOME): Plan {
        val scoped = command(prefix, workspace, home)
        val raw = toDisplayPath(parse(prefix).raw)
        val fallback = Command(
            baseDir = workspace,
            query = raw,
            displayBase = null,
            fullPath = raw.contains('/'),
        )

        val shell = buildString {
            append("if [ -d ")
            append(shellQuote(scoped.baseDir))
            append(" ]; then echo ")
            append(shellQuote(SCOPE_MARKER + "scoped"))
            append(" >&2; ")
            append(fdCommand(scoped))
            append("; else echo ")
            append(shellQuote(SCOPE_MARKER + "unscoped"))
            append(" >&2; ")
            append(fdCommand(fallback))
            append("; fi")
        }
        return Plan(scoped = scoped, fallback = fallback, shell = shell)
    }

    /**
     * `resolveScopedFuzzyQuery` + `expandHomePath` (`autocomplete.ts:514-562`):
     * a query with a slash is split into a base directory and a remainder, so
     * `@tui/src/auto` searches inside that directory and the results are shown
     * with the directory prefix again (`:790-794`).
     */
    private fun command(prefix: String, workspace: String, home: String): Command {
        val raw = toDisplayPath(parse(prefix).raw)
        val slash = raw.lastIndexOf('/')
        if (slash < 0) {
            return Command(baseDir = workspace, query = raw, displayBase = null, fullPath = false)
        }

        val displayBase = raw.substring(0, slash + 1)
        val query = raw.substring(slash + 1)
        val baseDir = when {
            displayBase.startsWith("~/") -> posixJoin(home, displayBase.substring(2))
            displayBase.startsWith("/") -> displayBase
            else -> posixJoin(workspace, displayBase)
        }
        return Command(
            baseDir = baseDir,
            query = query,
            displayBase = displayBase,
            fullPath = query.contains('/'),
        )
    }

    /**
     * pi's fd argv for one pass (`autocomplete.ts:132-161`): the same flags, in the
     * same order, with the binary named by [FD] and every argument quoted for the
     * `bash -lc` that `app.pi.packages.GuestCommand` runs.
     *
     * fd's own defaults are what make the list correct and are *not* overridden:
     * no `--no-ignore`, so the layered ignore files still apply, and `--hidden`
     * with the `.git` exclusion reproduces pi's visible-hidden-files-but-never-git
     * rule (`:143-149`, `:209-211`; `packages/tui/test/autocomplete.test.ts:315-335`).
     */
    private fun fdCommand(command: Command): String = buildList {
        add(FD)
        add("--base-directory")
        add(command.baseDir)
        add("--max-results")
        add(MAX_RESULTS.toString())
        add("--type")
        add("f")
        add("--type")
        add("d")
        add("--follow")
        add("--hidden")
        add("--exclude")
        add(".git")
        add("--exclude")
        add(".git/*")
        add("--exclude")
        add(".git/**")
        if (command.fullPath) add("--full-path")
        if (command.query.isNotEmpty()) add(command.query)
    }.joinToString(" ") { shellQuote(it) }

    /**
     * Single-quote an argument for the guest's `bash -lc`.
     *
     * The query is user input: unquoted, a `$`, a backtick or a glob would be
     * expanded by the shell before fd ever saw it, so every argument goes through
     * here - exactly like pi, which passes argv directly and therefore never lets a
     * shell near it.
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ results

    /**
     * Score, order and shape the lines fd printed.
     *
     * [stderr] is where the shell marker was written ([SCOPE_MARKER]); a marker
     * naming the fallback branch selects it, so the display prefix and the scoring
     * query match the branch fd actually ran.
     */
    fun items(stdout: String, stderr: String, plan: Plan, prefix: String): List<Item> {
        val branch = if (stderr.contains(SCOPE_MARKER + "unscoped")) plan.fallback else plan.scoped
        val quoted = parse(prefix).quoted

        val entries = parseFdOutput(stdout)
        val scored = entries
            // pi scores an empty query as a flat 1 instead of calling the scorer
            // (`autocomplete.ts:767`), which is what makes a bare `@` list the
            // shallowest entries rather than the ones whose names sort first.
            .map { entry -> entry to if (branch.query.isEmpty()) 1 else score(entry.path, branch.query, entry.isDirectory) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<Entry, Int>> { (_, score) -> score }
                    .thenBy { (entry, _) -> depth(entry.path) }
                    .thenBy { (entry, _) -> entry.path.length }
                    .thenBy { (entry, _) -> entry.path },
            )

        return scored.take(MAX_ITEMS).map { (entry, _) ->
            val pathWithoutSlash = if (entry.isDirectory) entry.path.dropLast(1) else entry.path
            val displayPath = branch.displayBase
                ?.let { displayBase -> scopedPathForDisplay(displayBase, pathWithoutSlash) }
                ?: pathWithoutSlash
            val completionPath = if (entry.isDirectory) "$displayPath/" else displayPath

            Item(
                value = buildCompletionValue(completionPath, quoted),
                label = baseName(pathWithoutSlash) + if (entry.isDirectory) "/" else "",
                description = displayPath,
            )
        }
    }

    /** One line of fd's output: a path relative to the base directory, and its kind. */
    data class Entry(val path: String, val isDirectory: Boolean)

    /**
     * fd's stdout, read the way pi reads it (`autocomplete.ts:202-217`).
     *
     * fd appends a separator to directories, and pi calls `toDisplayPath` to make
     * Windows separators consistent. The `.git` filter is applied twice on purpose
     * in pi - `--exclude` and this loop - so a `.git` whose children were named
     * explicitly cannot slip through.
     */
    fun parseFdOutput(stdout: String): List<Entry> {
        val lines = stdout.trim().split("\n").filter { it.isNotEmpty() }
        val entries = mutableListOf<Entry>()
        for (line in lines) {
            val displayLine = toDisplayPath(line)
            val isDirectory = displayLine.endsWith("/")
            val normalized = if (isDirectory) displayLine.dropLast(1) else displayLine
            if (normalized == ".git" || normalized.startsWith(".git/") || normalized.contains("/.git/")) continue
            entries += Entry(path = displayLine, isDirectory = isDirectory)
        }
        return entries
    }

    /**
     * `scoreEntry` (`autocomplete.ts:702-722`): 100 for an exact base name, 80 for
     * a prefix, 50 for a base-name substring, 30 for a full-path substring, and a
     * bonus of 10 that pushes directories up among the matches.
     *
     * Only meaningful for a non-empty query; an empty one never reaches the scorer
     * in pi (`:767`), and [items] keeps that short circuit.
     */
    fun score(filePath: String, query: String, isDirectory: Boolean): Int {
        val name = baseName(filePath).lowercase()
        val needle = query.lowercase()
        var score = when {
            name == needle -> 100
            name.startsWith(needle) -> 80
            name.contains(needle) -> 50
            filePath.lowercase().contains(needle) -> 30
            else -> 0
        }
        if (isDirectory && score > 0) score += 10
        return score
    }

    /**
     * `buildCompletionValue` (`autocomplete.ts:107-121`) for the mention case,
     * where `isAtPrefix` is always true: `@path`, quoted as `@"path"` when the
     * prefix was already quoted or the path contains a space.
     */
    fun buildCompletionValue(path: String, quoted: Boolean): String =
        if (quoted || path.contains(' ')) "@\"$path\"" else "@$path"

    /**
     * Insert a picked candidate: replace the mention token the draft ends with.
     *
     * pi's `@` branch of `applyCompletion` (`autocomplete.ts:412-429`): a file is
     * followed by a space and a directory is not, so `@dir/` keeps completing
     * (`:415`). pi additionally fixes up a quote that is already after the caret;
     * with the caret at the end there is never one, and the token replaced here
     * includes any quote it carried.
     */
    fun apply(text: String, prefix: String, item: Item): String {
        if (!text.endsWith(prefix)) return text
        val isDirectory = item.label.endsWith("/")
        val suffix = if (isDirectory) "" else " "
        return text.dropLast(prefix.length) + item.value + suffix
    }

    private fun scopedPathForDisplay(displayBase: String, relativePath: String): String =
        if (displayBase == "/") "/$relativePath" else displayBase + relativePath

    private fun depth(path: String): Int = toDisplayPath(path).split('/').count { it.isNotEmpty() }

    /**
     * Node's `path.basename`, which is what pi scores and labels with
     * (`autocomplete.ts:703`, `:793`): a trailing separator is not part of the
     * name, so a directory entry, whose path fd prints with a trailing separator
     * (`:205-217`), still scores on its own name.
     */
    private fun baseName(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return if (path.startsWith("/")) "/" else ""
        val slash = trimmed.lastIndexOf('/')
        return if (slash < 0) trimmed else trimmed.substring(slash + 1)
    }

    private fun toDisplayPath(value: String): String = value.replace('\\', '/')

    /**
     * Node's `path.join` for POSIX, which is what pi's scope resolution calls
     * (`autocomplete.ts:542`, `:519`): empty and `.` segments drop, `..` pops, and
     * a trailing separator survives - so `@../outside/` resolves outside the
     * workspace exactly as pi's own test expects
     * (`packages/tui/test/autocomplete.test.ts:258-264`).
     */
    fun posixJoin(base: String, relative: String): String {
        val parts = listOf(base, relative).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "."
        return posixNormalize(parts.joinToString("/"))
    }

    private fun posixNormalize(path: String): String {
        val absolute = path.startsWith("/")
        val trailing = path.length > 1 && (path.endsWith("/") || path.endsWith("/.") || path.endsWith("/.."))
        val out = mutableListOf<String>()
        for (segment in path.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." ->
                    if (out.isNotEmpty() && out.last() != "..") {
                        out.removeAt(out.size - 1)
                    } else if (!absolute) {
                        out += ".."
                    }
                else -> out += segment
            }
        }
        val joined = out.joinToString("/")
        return when {
            absolute -> "/$joined" + (if (trailing && joined.isNotEmpty()) "/" else "")
            joined.isEmpty() -> if (trailing) "./" else "."
            else -> joined + (if (trailing) "/" else "")
        }
    }
}
