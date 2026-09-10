package app.pi.packages

/**
 * A parser for `pi list`'s stdout.
 *
 * The output is a rendering, not a protocol, so this is deliberately tolerant and
 * always keeps the raw text next to what it managed to parse. The shape is fixed
 * by `package-manager-cli.ts:970-1004`:
 *
 * ```
 * User packages:
 *   npm:foo
 *     /root/.pi/agent/npm/node_modules/foo
 *   git:github.com/u/r (filtered)
 *
 * Project packages:
 *   npm:bar
 * ```
 *
 * `(filtered)` is appended when the settings entry is the object form
 * (`:981`); the indented line under an entry is `installedPath` (`:983-985`).
 * An empty install prints `No packages installed.` and no headers (`:975-978`).
 * Both are matched on the **trimmed** line, after ANSI stripping, so chalk's
 * bold header cannot defeat the parser.
 */
object PiListOutput {

    private const val USER_HEADER = "User packages:"
    private const val PROJECT_HEADER = "Project packages:"
    private const val NO_PACKAGES = "No packages installed."

    data class Parsed(
        val entries: List<PiPackageEntry>,
        /** True when pi said there are no packages, rather than the parse failing. */
        val empty: Boolean,
        /** True when output was non-empty but nothing was recognised. */
        val unrecognised: Boolean,
    )

    fun parse(stdout: String): Parsed {
        val text = app.pi.rpc.Ansi.strip(stdout)
        val lines = text.lines()
        if (lines.any { it.trim() == NO_PACKAGES }) {
            return Parsed(emptyList(), empty = true, unrecognised = false)
        }

        val entries = mutableListOf<PiPackageEntry>()
        var scope: PiPackageScope? = null
        var lastIndex = -1

        for (raw in lines) {
            if (raw.isBlank()) continue
            val trimmed = raw.trim()
            when (trimmed) {
                USER_HEADER -> {
                    scope = PiPackageScope.User
                    continue
                }
                PROJECT_HEADER -> {
                    scope = PiPackageScope.Project
                    continue
                }
            }
            val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
            val activeScope = scope ?: continue
            if (indent == 0) continue

            if (indent <= ENTRY_INDENT) {
                val filtered = trimmed.endsWith(FILTERED_SUFFIX)
                val source = if (filtered) trimmed.removeSuffix(FILTERED_SUFFIX).trim() else trimmed
                if (source.isEmpty()) continue
                entries += PiPackageEntry(
                    source = PiPackageSource.parse(source),
                    scope = activeScope,
                    filtered = filtered,
                    installedPath = null,
                )
                lastIndex = entries.lastIndex
                continue
            }

            // Deeper than an entry: pi's `installedPath` continuation line.
            if (lastIndex >= 0) {
                entries[lastIndex] = entries[lastIndex].copy(installedPath = trimmed)
            }
        }

        val nonHeaderContent = lines.count { line ->
            val t = line.trim()
            t.isNotEmpty() && t != USER_HEADER && t != PROJECT_HEADER
        }
        return Parsed(
            entries = entries,
            empty = entries.isEmpty() && nonHeaderContent == 0,
            unrecognised = entries.isEmpty() && nonHeaderContent > 0,
        )
    }

    /** pi prints entries with two leading spaces (`package-manager-cli.ts:982`). */
    private const val ENTRY_INDENT = 2

    private const val FILTERED_SUFFIX = " (filtered)"
}
