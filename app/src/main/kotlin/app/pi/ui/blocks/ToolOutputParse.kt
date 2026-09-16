package app.pi.ui.blocks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * What each built-in tool's result *is*, reduced from the protocol alone.
 *
 * This file is the App's half of pi's renderers (`packages/coding-agent/src/core/tools/
 * renderers/index.ts:34-44` — the dispatch table this mirrors one for one): pi turns a
 * tool call's `args` and its result's `content`/`details` into a rendered component, and so
 * does [ToolOutputParse]. Everything here is a pure function over
 * `toolName`/`args`/`details`/result text — the three fields `tool_execution_start` and
 * `tool_execution_end` carry (`packages/coding-agent/src/core/agent-session.ts:819-844`;
 * the App keeps them at `rpc/src/main/kotlin/app/pi/rpc/Events.kt:478-500` and
 * `.../Transcript.kt:1279-1402`).
 *
 * **No second source of truth.** Nothing here reads a file, runs a command or asks the
 * engine anything: every string a block shows is either pi's own text or a count derived
 * from it. Where pi's renderer has data this app has no channel for — the `edit` renderer
 * *computes* its preview diff by reading the target file (`renderers/edit.ts:184-193`) —
 * the block simply says less.
 *
 * **Nothing may throw.** A tool result is machine text from an engine that may be newer
 * than this build, so every entry point degrades instead of raising; a [Result] that fails
 * or a body that does not recognise the text answers `null`, and the block that asked
 * renders the generic card. That contract is pinned in
 * `app/src/test/kotlin/app/pi/ui/blocks/ToolOutputParseCheck.kt` with hostile input
 * (empty, control characters, an unterminated bracket, megabytes of one line).
 *
 * **Bounded work.** These run inside `remember`, keyed on the value they read, but a
 * `bash` row republishes every 200 ms while it streams (`rpc/.../Transcript.kt:618`) and a
 * result can be megabytes, so the scan stops at [TOOL_SCAN_MAX_CHARS], lines are capped at
 * [TOOL_LINE_MAX_CHARS] (pi's own grep limit, `core/tools/truncate.ts:13`) and every list at
 * [TOOL_LIST_MAX_ENTRIES] / [TOOL_BODY_MAX_LINES]. A cap is always reported to the user,
 * never silently dropped.
 */

/** How much of a result is scanned at all. Past this the body is cut, not re-scanned. */
internal const val TOOL_SCAN_MAX_CHARS = 200_000

/** Body lines a tool block paints at most. */
internal const val TOOL_BODY_MAX_LINES = 200

/** Entries a listed/grouped tool block paints at most. */
internal const val TOOL_LIST_MAX_ENTRIES = 200

/** Per-line cap: pi's `GREP_MAX_LINE_LENGTH` (`core/tools/truncate.ts:13`, `grep.ts:209`). */
internal const val TOOL_LINE_MAX_CHARS = 500

/** How much of a result's tail is scanned for pi's own trailing path sentence. */
internal const val PATH_SCAN_CHARS = 4096

// ----------------------------------------------------------------------- bodies

/**
 * A file's text, as pi's `read` renders it (`renderers/read.ts:111-148`) or as pi's `write`
 * renders the content it was given (`renderers/write.ts:96-127`).
 *
 * pi's terminal prints no line numbers anywhere: the renderers highlight a language
 * (`read.ts:126-127`, `write.ts:111-114`) and the *call* line carries the range
 * (`read.ts:28-33`: `:${startLine}-${endLine}` from `args.offset`/`args.limit`).
 * [startLine] is that same number, so a block can label the first body line without
 * guessing, and a body pi never numbered (`write`) is labelled from line 1.
 *
 * @param path the path pi was asked for, exactly as sent ("" when it sent none).
 * @param startLine pi's `offset`, or 1 when absent (`read.ts:30`).
 * @param lines the body, trailing blank lines trimmed exactly as pi trims them
 *   (`read.ts:38-44`, `write.ts:89-95`), capped at [TOOL_BODY_MAX_LINES].
 * @param totalLines lines the body had before the cap.
 * @param footer pi's own trailing sentence — `[Showing lines X-Y of N. Use offset=Z to
 *   continue.]` (`read.ts:64`/`:66`) or `[N more lines in file. Use offset=Z to
 *   continue.]` (`:73`) — separated from the body so it is never numbered as file text.
 * @param scanCapped the result was longer than [TOOL_SCAN_MAX_CHARS].
 */
internal data class SourceBody(
    val path: String,
    val startLine: Int,
    val lines: List<String>,
    val totalLines: Int,
    val footer: String?,
    val scanCapped: Boolean,
)

/**
 * One `grep` row: the line number pi printed and the text beside it.
 *
 * The file is not repeated here — [GrepGroup] is the file, and every row in it belongs to
 * that heading (`core/tools/grep.ts:211`/`:212` print the path on every row, which is what
 * makes the grouping possible in the first place).
 */
internal data class GrepMatch(
    val line: Int?,
    val text: String,
    val context: Boolean,
)

/** The matches pi's `grep` reported for one file, in output order. */
internal data class GrepGroup(val path: String, val matches: List<GrepMatch>)

/**
 * pi's `grep` result. pi's renderer prints the raw rows (`renderers/grep.ts:36-68`);
 * grouping them by file is this app's arrangement of the same rows, which is what makes a
 * hundred matches legible on a phone.
 *
 * @param notice pi's trailing `[ … limit reached … ]` sentence (`grep.ts:303`), which its
 *   renderer paints as a warning (`renderers/grep.ts:61-66`).
 * @param empty pi answered "No matches found" (`grep.ts:256-259`).
 * @param raw rows that matched neither of pi's two shapes.
 */
internal data class GrepBody(
    val groups: List<GrepGroup>,
    val raw: List<String>,
    /** Matches pi reported, counted before this app's cap. */
    val matchCount: Int,
    /**
     * Distinct files pi reported, counted before the cap — [groups] cannot answer this once
     * it was cut, and a footer that said "200 个文件" for a 500-file search would be wrong.
     */
    val fileCount: Int,
    val omitted: Int,
    val notice: String?,
    val empty: Boolean,
    val scanCapped: Boolean,
)

/**
 * What a listed entry is.
 *
 * `ls` marks directories with a `"/"` suffix (`core/tools/ls.ts:121-129`), so its entries
 * are known. `find` prints bare paths (`find.ts:268-273`) — `fd` lists files *and*
 * directories and pi's formatting adds no marker — so its entries are [Unknown] and a block
 * must not claim otherwise.
 */
internal enum class PathKind { Directory, File, Unknown }

internal data class PathEntry(val name: String, val kind: PathKind)

/** One group of a path list: a directory heading for `find`, a kind heading for `ls`. */
internal data class PathGroup(val label: String?, val entries: List<PathEntry>)

/** Which of pi's two path tools produced a [PathBody]. */
internal enum class PathBodyKind { Find, Ls }

/**
 * pi's `find` result (paths relative to the search root, `find.ts:268-273`) or `ls` result
 * (entry names, directories suffixed, `ls.ts:112-130`).
 *
 * @param notice pi's trailing `[ … limit reached … ]` (`find.ts:292`, `ls.ts:155`).
 * @param empty pi's own "No files found matching pattern" (`find.ts:261`) or
 *   "(empty directory)" (`ls.ts:135`).
 */
internal data class PathBody(
    val kind: PathBodyKind,
    val groups: List<PathGroup>,
    val raw: List<String>,
    val entryCount: Int,
    val omitted: Int,
    val notice: String?,
    val empty: Boolean,
    val scanCapped: Boolean,
)

/**
 * pi's shell tool: the command it was given and the exit code it ended with.
 *
 * pi's `bash` renderer draws the command as the title (`renderers/bash.ts:35-41`: `"$ " +
 * command`) and streams an `Elapsed`/`Took` line (`:117-121`). Its exit code never reaches
 * `details` — `BashToolDetails` is `{truncation, fullOutputPath}` (`core/tools/bash.ts:49-52`)
 * — a non-zero exit *throws*, and the number survives only in the sentence pi appends
 * (`bash.ts:363-364`: `Command exited with code N`), which is where [exitCode] reads it when
 * the protocol carries no structured one.
 */
internal data class ShellBody(val command: String, val timeoutSeconds: Int?, val exitCode: Int?)

// ------------------------------------------------------------------- the parser

internal object ToolOutputParse {

    /** `read`: pi's result text with pi's own footer separated out (`renderers/read.ts:111-148`). */
    fun readBody(args: JsonObject?, output: String): SourceBody {
        val path = argString(args, "file_path", "path").orEmpty()
        return runCatching { parseRead(args, path, output) }.getOrNull() ?: emptySource(path)
    }

    /**
     * `write`: the file's new content, from the *arguments* — pi's write renderer prints
     * `args.content` and shows nothing when the write succeeded (`renderers/write.ts:104-124`,
     * `:128-143`). Empty when pi sent no content string (its own renderer's
     * `[invalid content arg - expected string]` case, `:108-109`) or while it is still
     * arriving.
     */
    fun writeBody(args: JsonObject?): SourceBody {
        val path = argString(args, "file_path", "path").orEmpty()
        val content = argString(args, "content") ?: return emptySource(path)
        return runCatching { parseWrite(path, content) }.getOrNull() ?: emptySource(path)
    }

    /**
     * `grep`: pi's rows, grouped by file.
     *
     * The two row shapes are pi's own (`core/tools/grep.ts:211-212` for a context block and
     * `:273` for the plain case): `relativePath:line: text` for the match,
     * `relativePath-line- text` for a line around it. `No matches found` (`:256-259`) is the
     * empty answer and a trailing `[ … ]` block is pi's notice (`:303`).
     *
     * Null only when the text carries no recognised row *and* is not blank: then the result
     * is not the shape pi's grep writes, and the caller shows the generic card rather than
     * inventing rows. A blank result is the pending case and answers an empty body.
     */
    fun grepBody(output: String): GrepBody? = runCatching { parseGrep(output) }.getOrNull()

    /**
     * `find`: pi's relative paths (`core/tools/find.ts:268-273`), grouped by the directory
     * they live in — the same rows pi prints (`renderers/find.ts:42-53`), arranged so the
     * screen shows a tree instead of one long path per line.
     */
    fun findBody(output: String): PathBody? = runCatching { parsePaths(output, PathBodyKind.Find) }.getOrNull()

    /**
     * `ls`: pi's entry names, directories suffixed with `"/"` (`core/tools/ls.ts:121-129`),
     * split by that suffix into pi's own two kinds.
     */
    fun lsBody(output: String): PathBody? = runCatching { parsePaths(output, PathBodyKind.Ls) }.getOrNull()

    /**
     * The exit code of a shell result: the structured one when the protocol carried it,
     * otherwise the number in pi's own sentence (`core/tools/bash.ts:363-364`).
     *
     * A timeout and an abort print different sentences (`bash.ts:351-357`) and have no code,
     * so none is guessed for them.
     */
    fun shellExitCode(structured: Int?, output: String): Int? {
        if (structured != null) return structured
        val match = EXIT_CODE.find(output.takeLast(PATH_SCAN_CHARS)) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /**
     * pi's elapsed line (`renderers/bash.ts:117-121`): `Elapsed 12.3s` while the command
     * runs, `Took 12.3s` once it has ended.
     *
     * The clock is *read*, never owned: the caller passes the row's age, so this app adds no
     * timer of its own — a streaming `bash` row already recomposes every 200 ms
     * (`rpc/.../Transcript.kt:618`), and a tool that prints nothing simply holds the last
     * number it showed.
     */
    fun elapsedLabel(pending: Boolean, elapsedMs: Long): String =
        (if (pending) "已运行 " else "耗时 ") + formatSeconds(elapsedMs) + " 秒"

    /** pi's `formatDuration` (`renderers/bash.ts:32-34`): one decimal, in seconds. */
    fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1f", ms.coerceAtLeast(0) / 1000.0)

    // ---------------------------------------------------------------- readers

    private fun parseRead(args: JsonObject?, path: String, output: String): SourceBody {
        val (body, footer) = splitReadFooter(bounded(output).trimEnd('\n', '\r'))
        return fileBody(
            path = path,
            startLine = argInt(args, "offset")?.coerceAtLeast(1) ?: 1,
            content = body,
            footer = footer,
            scanCapped = isScanCapped(output),
        )
    }

    private fun parseWrite(path: String, content: String): SourceBody = fileBody(
        path = path,
        startLine = 1,
        content = bounded(content),
        footer = null,
        scanCapped = isScanCapped(content),
    )

    private fun parseGrep(output: String): GrepBody? {
        val text = bounded(output)
        val (body, notice) = splitNotice(text)
        val trimmed = body.trim()
        val capped = isScanCapped(output)
        if (trimmed.isEmpty()) {
            return GrepBody(emptyList(), emptyList(), 0, 0, 0, null, empty = false, scanCapped = capped)
        }
        if (trimmed == NO_MATCHES) {
            return GrepBody(emptyList(), emptyList(), 0, 0, 0, notice, empty = true, scanCapped = capped)
        }

        // One group per file, in the order pi first mentioned the file. pi emits one file's
        // rows contiguously (it formats match by match in one pass, `grep.ts:264-278`), so
        // this is normally the identity; where it is not, the same file still gets one
        // heading instead of two, and no row changes its order within its file.
        val byPath = linkedMapOf<String, MutableList<GrepMatch>>()
        // Every file pi named, counted before the cap: `byPath` is cut at
        // [TOOL_LIST_MAX_ENTRIES], so its size cannot answer "how many files matched" for a
        // wide search, and a footer that said 200 for 500 files would be wrong.
        val allPaths = linkedSetOf<String>()
        val raw = mutableListOf<String>()
        var parsed = 0
        var omitted = 0
        for (line in trimmed.split('\n')) {
            val clean = capLine(line.removeSuffix("\r"))
            if (clean.isEmpty()) continue
            val matched = GREP_MATCH.find(clean)
            val context = if (matched == null) GREP_CONTEXT.find(clean) else null
            val hit = matched ?: context
            if (hit == null) {
                if (raw.size < TOOL_LIST_MAX_ENTRIES) raw += clean
                continue
            }
            val path = hit.groupValues[1]
            allPaths += path
            parsed++
            if (parsed > TOOL_LIST_MAX_ENTRIES) {
                omitted++
                continue
            }
            byPath.getOrPut(path) { mutableListOf() } += GrepMatch(
                line = hit.groupValues[2].toIntOrNull(),
                text = hit.groupValues.getOrElse(3) { "" },
                context = context != null,
            )
        }
        if (byPath.isEmpty()) return null
        return GrepBody(
            groups = byPath.map { (path, matches) -> GrepGroup(path, matches) },
            raw = raw,
            matchCount = parsed,
            fileCount = allPaths.size,
            omitted = omitted,
            notice = notice,
            empty = false,
            scanCapped = capped,
        )
    }

    private fun parsePaths(output: String, kind: PathBodyKind): PathBody? {
        val text = bounded(output)
        val (body, notice) = splitNotice(text)
        val trimmed = body.trim()
        val emptyMessage = if (kind == PathBodyKind.Find) NO_FILES else EMPTY_DIRECTORY
        val capped = isScanCapped(output)
        if (trimmed.isEmpty()) {
            return PathBody(kind, emptyList(), emptyList(), 0, 0, null, empty = false, scanCapped = capped)
        }
        if (trimmed == emptyMessage) {
            return PathBody(kind, emptyList(), emptyList(), 0, 0, notice, empty = true, scanCapped = capped)
        }

        // `find`: one group per directory, in the order the paths first mention it — `fd`
        // does not sort, so two entries of the same directory need not be adjacent, and a
        // run-based grouping would print one directory twice.
        val byDirectory = linkedMapOf<String, MutableList<PathEntry>>()
        // `ls`: pi sorts the entries case-insensitively (`ls.ts:108-109`), so a run-based
        // split would interleave directories and files; two buckets keep pi's alphabetical
        // order inside each of pi's two kinds.
        val directories = mutableListOf<PathEntry>()
        val files = mutableListOf<PathEntry>()
        val raw = mutableListOf<String>()
        var parsed = 0
        var omitted = 0
        for (line in trimmed.split('\n')) {
            val clean = line.removeSuffix("\r").trim()
            if (clean.isEmpty()) continue
            // `ls` marks a directory with a trailing "/" (`ls.ts:124`) and nothing else does.
            val isDirectory = kind == PathBodyKind.Ls && clean.endsWith("/")
            val name = when {
                isDirectory -> clean.dropLast(1)
                kind == PathBodyKind.Ls -> clean
                else -> clean.substringAfterLast('/')
            }
            if (name.isEmpty()) {
                if (raw.size < TOOL_LIST_MAX_ENTRIES) raw += capLine(clean)
                continue
            }
            parsed++
            if (parsed > TOOL_LIST_MAX_ENTRIES) {
                omitted++
                continue
            }
            when (kind) {
                PathBodyKind.Ls -> {
                    val entry = PathEntry(
                        name = capLine(name),
                        kind = if (isDirectory) PathKind.Directory else PathKind.File,
                    )
                    if (isDirectory) directories += entry else files += entry
                }

                PathBodyKind.Find -> {
                    // pi's find output is bare paths: `fd` reports files and directories
                    // alike and pi adds no marker, so the kind is not claimed.
                    val directory = clean.substringBeforeLast('/', "")
                    byDirectory.getOrPut(directory) { mutableListOf() } +=
                        PathEntry(capLine(name), PathKind.Unknown)
                }
            }
        }
        val groups = when (kind) {
            PathBodyKind.Ls -> buildList {
                if (directories.isNotEmpty()) add(PathGroup(LS_DIRS, directories))
                if (files.isNotEmpty()) add(PathGroup(LS_FILES, files))
            }

            PathBodyKind.Find -> byDirectory.map { (directory, entries) -> PathGroup(directory, entries) }
        }
        if (groups.isEmpty()) return null
        return PathBody(
            kind = kind,
            groups = groups,
            raw = raw,
            entryCount = parsed,
            omitted = omitted,
            notice = notice,
            empty = false,
            scanCapped = capped,
        )
    }

    // ---------------------------------------------------------------- shared

    private fun fileBody(
        path: String,
        startLine: Int,
        content: String,
        footer: String?,
        scanCapped: Boolean,
    ): SourceBody {
        val all = content.split('\n')
        var end = all.size
        // pi's `trimTrailingEmptyLines` (`renderers/read.ts:38-44`, `write.ts:89-95`).
        while (end > 0 && all[end - 1].isBlank()) end--
        val kept = all.subList(0, end)
        return SourceBody(
            path = path,
            startLine = startLine,
            lines = kept.take(TOOL_BODY_MAX_LINES).map { capLine(it.removeSuffix("\r")) },
            totalLines = kept.size,
            footer = footer,
            scanCapped = scanCapped,
        )
    }

    private fun emptySource(path: String) = SourceBody(
        path = path,
        startLine = 1,
        lines = emptyList(),
        totalLines = 0,
        footer = null,
        scanCapped = false,
    )
}

// ------------------------------------------------------------ text primitives

/** The scan bound: everything past [TOOL_SCAN_MAX_CHARS] is cut before any split. */
private fun bounded(text: String): String =
    if (text.length <= TOOL_SCAN_MAX_CHARS) text else text.substring(0, TOOL_SCAN_MAX_CHARS)

private fun isScanCapped(text: String): Boolean = text.length > TOOL_SCAN_MAX_CHARS

/** pi's per-line cap (`core/tools/truncate.ts:13`). */
private fun capLine(line: String): String =
    if (line.length <= TOOL_LINE_MAX_CHARS) line else line.substring(0, TOOL_LINE_MAX_CHARS) + "…"

/**
 * pi's trailing notice block, split off the body.
 *
 * All the list-shaped tools append one the same way — `output += "\n\n[" + notices.join(". ")
 * + "]"` (`core/tools/grep.ts:303`, `find.ts:160`/`:292`, `ls.ts:155`) — and pi's renderers
 * paint it as a warning line (`renderers/grep.ts:61-66`), which is where a block puts it.
 *
 * The block is only believed when it *is* a notice: the inner text must name one of pi's two
 * subjects (`limit reached`, `truncated`). A result whose last line is a file named
 * `[notes]` therefore survives as an entry, the same caution
 * [stripFullOutputFooter] documents for the shell sentence.
 */
internal fun splitNotice(text: String): Pair<String, String?> {
    val trimmed = text.trimEnd()
    if (!trimmed.endsWith("]")) return text to null
    val at = trimmed.lastIndexOf("\n\n[")
    if (at < 0) return text to null
    val inner = trimmed.substring(at + 3, trimmed.length - 1).trim()
    if (!NOTICE_SUBJECT.containsMatchIn(inner)) return text to null
    return trimmed.substring(0, at) to inner
}

private val NOTICE_SUBJECT = Regex("limit|truncated")

/**
 * pi's `read` footer, split off the body.
 *
 * Three shapes, all from `core/tools/read.ts`: the truncation report and the byte-limit
 * report are appended after a blank line (`:64`, `:66`), the user-`limit` report the same
 * way (`:73`), and the first-line-exceeds case replaces the body with one bracket sentence
 * (`:56`). The shapes are matched, not the brackets, so a file whose only line is
 * `[dependencies]` keeps that line.
 */
internal fun splitReadFooter(text: String): Pair<String, String?> {
    val at = text.lastIndexOf("\n\n[")
    if (at >= 0 && text.endsWith("]")) {
        val inner = text.substring(at + 3, text.length - 1).trim()
        if (isReadFooter(inner)) return text.substring(0, at) to inner
    }
    val whole = text.trim()
    if (whole.startsWith("[") && whole.endsWith("]")) {
        val inner = whole.substring(1, whole.length - 1).trim()
        if (isReadFooter(inner)) return "" to inner
    }
    return text to null
}

private fun isReadFooter(inner: String): Boolean = when {
    inner.startsWith("Showing lines ") && inner.endsWith("to continue.") -> true
    MORE_LINES.matches(inner) -> true
    inner.startsWith("Line ") && inner.contains(" exceeds ") && inner.contains(" limit.") -> true
    else -> false
}

/** `[N more lines in file. Use offset=Z to continue.]` (`read.ts:73`). */
private val MORE_LINES = Regex("""^\d+ more lines in file\. Use offset=\d+ to continue\.$""")

/** pi's grep match row: `${relativePath}:${line}: ${text}` (`grep.ts:211`, `:273`). */
private val GREP_MATCH = Regex("""^(.+):(\d+): ?(.*)$""")

/** pi's grep context row: `${relativePath}-${line}- ${text}` (`grep.ts:212`). */
private val GREP_CONTEXT = Regex("""^(.+)-(\d+)- ?(.*)$""")

/** pi's sentence for a non-zero exit (`bash.ts:364`), read from the tail of the output. */
private val EXIT_CODE = Regex("""Command exited with code (-?\d+)""")

private const val NO_MATCHES = "No matches found"
private const val NO_FILES = "No files found matching pattern"
private const val EMPTY_DIRECTORY = "(empty directory)"
private const val LS_DIRS = "目录"
private const val LS_FILES = "文件"

// --------------------------------------------------- moved out of ToolCallBlock

/**
 * pi's truncation block for a tool result, or null when it truncated nothing.
 *
 * pi carries the whole `TruncationResult` in `details.truncation` (`core/tools/bash.ts:321`;
 * shape `core/tools/truncate.ts:15-38`), and our projection keeps `details` whole
 * (`rpc/.../Transcript.kt:1223`), so every number below is pi's own. Nothing here is computed
 * from our rendering, which is why a missing field stays null instead of being filled in.
 */
internal data class ToolTruncation(
    val truncatedBy: String?,
    val outputLines: Int?,
    val totalLines: Int?,
    val maxBytes: Int?,
)

internal fun truncationOf(details: JsonElement?): ToolTruncation? {
    val truncation = (details as? JsonObject)?.get("truncation") as? JsonObject ?: return null
    val truncated = (truncation["truncated"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
    if (!truncated) return null
    fun int(key: String) = (truncation[key] as? JsonPrimitive)?.content?.toIntOrNull()
    return ToolTruncation(
        truncatedBy = (truncation["truncatedBy"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        outputLines = int("outputLines"),
        totalLines = int("totalLines"),
        maxBytes = int("maxBytes"),
    )
}

/**
 * The path pi recorded for this call's complete output, or null when it recorded none.
 *
 * Two sources, in the order that prefers structure over text: the `details.fullOutputPath`
 * field pi's bash tool sets when it truncates (`core/tools/bash.ts:321`), then the sentence
 * pi appends to the result text itself (`:326`/`:328`; `core/tools/messages.ts:94-95`).
 *
 * The text scan reads only the tail: pi appends that sentence, and this runs on a row whose
 * output can be megabytes.
 */
internal fun fullOutputPathOf(details: JsonElement?, output: String): String? {
    val structured = (details as? JsonObject)
        ?.get("fullOutputPath")
        ?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
    if (!structured.isNullOrEmpty()) return structured

    val tail = output.takeLast(PATH_SCAN_CHARS)
    val marker = "Full output: "
    val at = tail.lastIndexOf(marker)
    if (at < 0) return null
    return tail.substring(at + marker.length)
        .substringBefore(']')
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * Remove the `[ … Full output: <path>]` sentence pi's tool appended to its own text, so a
 * card prints it once as the notice instead of twice.
 *
 * pi's `rebuildBashResultRenderComponent` verbatim (`renderers/bash.ts:59-64`), including
 * what it refuses to do:
 *
 *  - only when the text *ends* with `]`;
 *  - only the block after the **last** `\n\n[`;
 *  - and only when that block actually names [path].
 */
internal fun stripFullOutputFooter(text: String, path: String?): String {
    if (path == null) return text
    if (!text.endsWith("]")) return text
    val footerStart = text.lastIndexOf("\n\n[")
    if (footerStart < 0) return text
    if (!text.substring(footerStart).contains(path)) return text
    return text.substring(0, footerStart).trimEnd()
}

/**
 * pi's warning line (`core/tools/renderers/bash.ts:100-118`), in this app's language.
 *
 * pi joins the parts with `". "` inside one `[ … ]` and colours the whole line warning; the
 * parts, in pi's order, are `Full output: <path>` when a path exists, then the truncation
 * report — `Truncated: showing X of Y lines` for the line limit, `Truncated: X lines shown
 * (<size> limit)` for the byte limit. The separators are the only thing translated; the
 * numbers are pi's and a part whose number pi did not send is left out rather than
 * estimated. Null when pi would print nothing — its condition is
 * `truncation?.truncated || fullOutputPath`.
 */
internal fun truncationNotice(path: String?, truncation: ToolTruncation?): String? {
    val parts = mutableListOf<String>()
    if (path != null) parts += "完整输出：$path"
    if (truncation != null) {
        val shown = truncation.outputLines
        val total = truncation.totalLines
        parts += when {
            truncation.truncatedBy == "lines" && shown != null && total != null ->
                "已截断：显示 $shown / $total 行"
            truncation.truncatedBy == "lines" && shown != null -> "已截断：显示 $shown 行"
            shown != null && truncation.maxBytes != null ->
                "已截断：显示 $shown 行（上限 ${formatBytes(truncation.maxBytes)}）"
            shown != null -> "已截断：显示 $shown 行"
            else -> "已截断"
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
}

/** pi's `formatSize` for the byte limit: whole KB when it divides evenly. */
internal fun formatBytes(bytes: Int): String =
    if (bytes >= 1024 && bytes % 1024 == 0) "${bytes / 1024} KB" else "$bytes B"

// ------------------------------------------------------------------ list caps

/**
 * The first [max] matches of a grouped result, keeping pi's order and its grouping.
 *
 * The caps here are the app's, not pi's: a `grep` can legitimately return 100 matches
 * (pi's default limit, `core/tools/grep.ts:41`) or 2000 lines, and a phone cannot lay out
 * an unbounded column. Nothing is dropped silently — [GrepBody.omitted] is what the block
 * reports — and a group whose matches were cut keeps the ones that fit.
 */
internal fun capGrepGroups(groups: List<GrepGroup>, max: Int): List<GrepGroup> {
    if (max <= 0) return emptyList()
    val out = mutableListOf<GrepGroup>()
    var used = 0
    for (group in groups) {
        if (used >= max) break
        val take = if (group.matches.size <= max - used) group.matches else group.matches.take(max - used)
        out += if (take.size == group.matches.size) group else group.copy(matches = take)
        used += take.size
    }
    return out
}

/** [capGrepGroups] for a path list: the first [max] entries, grouped as pi printed them. */
internal fun capPathGroups(groups: List<PathGroup>, max: Int): List<PathGroup> {
    if (max <= 0) return emptyList()
    val out = mutableListOf<PathGroup>()
    var used = 0
    for (group in groups) {
        if (used >= max) break
        val take = if (group.entries.size <= max - used) group.entries else group.entries.take(max - used)
        out += if (take.size == group.entries.size) group else group.copy(entries = take)
        used += take.size
    }
    return out
}

/** How many matches a capped grouping actually shows; the rest is the block's "omitted". */
internal fun countMatches(groups: List<GrepGroup>): Int = groups.sumOf { it.matches.size }

/** How many entries a capped grouping actually shows; the rest is the block's "omitted". */
internal fun countEntries(groups: List<PathGroup>): Int = groups.sumOf { it.entries.size }

// -------------------------------------------------------------- argument reads

/** pi's argument spellings: `read`/`ls`/`write` send `path`; `edit` also accepts `file_path`. */
internal fun argString(args: JsonObject?, vararg keys: String): String? {
    if (args == null) return null
    for (key in keys) {
        val value = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

internal fun argInt(args: JsonObject?, key: String): Int? =
    (args?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * pi's `formatReadLineRange` (`core/tools/renderers/read.ts:28-33`): the `:start-end` suffix
 * on a `read` call line, built from `args.offset` and `args.limit`.
 *
 * Empty when the call named neither, which is pi's `return ""`. Otherwise the start is
 * `offset ?? 1` and the end is `start + limit - 1`; an open-ended read prints only the start.
 */
internal fun readRange(args: JsonObject?): String {
    val offset = argInt(args, "offset")
    val limit = argInt(args, "limit")
    if (offset == null && limit == null) return ""
    val start = offset ?: 1
    if (limit == null) return ":$start"
    return ":$start-${start + limit - 1}"
}

/**
 * The part of pi's `grep` call line after the tool name (`renderers/grep.js:16-27`):
 * `/pattern/ in path`, then ` (glob)` and ` limit N` when the call carried them.
 *
 * Returned as pi's own runs, because pi paints them with **two** tokens
 * (`renderers/grep.js:19-26`):
 *
 * ```js
 * let text = theme.fg("toolTitle", theme.bold("grep")) +
 *     " " +
 *     (pattern === null ? invalidArg : theme.fg("accent", `/${pattern || ""}/`)) +
 *     theme.fg("toolOutput", ` in ${path === null ? invalidArg : path}`);
 * if (glob) text += theme.fg("toolOutput", ` (${glob})`);
 * if (limit !== undefined) text += theme.fg("toolOutput", ` limit ${limit}`);
 * ```
 *
 * so the pattern is `accent` and everything after it — the ` in <path>`, the glob and the
 * limit — is `toolOutput`. The text and its order are unchanged.
 *
 * pi shortens the path for display (`shortenPath(rawPath || ".")`, `:20`); this app shows
 * the argument as it was sent, with pi's own `"."` fallback for an absent path.
 */
internal fun grepSubject(args: JsonObject?): List<ToolCallPart> = buildList {
    val pattern = argString(args, "pattern").orEmpty()
    val path = argString(args, "path")?.takeIf { it.isNotEmpty() } ?: "."
    add(ToolCallPart("/$pattern/", ToolCallToken.Accent))
    add(ToolCallPart(" in $path", ToolCallToken.ToolOutput))
    argString(args, "glob")?.let { add(ToolCallPart(" ($it)", ToolCallToken.ToolOutput)) }
    argInt(args, "limit")?.let { add(ToolCallPart(" limit $it", ToolCallToken.ToolOutput)) }
}

/**
 * The part of pi's `find` call line after the tool name (`renderers/find.js:15-25`):
 * `<pattern> in <path>`, then ` (limit N)` when the call carried one.
 *
 * pi's own runs (`renderers/find.js:18-24`) — the pattern in `accent`, then ` in <path>` and
 * the limit in `toolOutput`:
 *
 * ```js
 * let text = theme.fg("toolTitle", theme.bold("find")) +
 *     " " +
 *     (pattern === null ? invalidArg : theme.fg("accent", pattern || "")) +
 *     theme.fg("toolOutput", ` in ${path === null ? invalidArg : path}`);
 * if (limit !== undefined) { text += theme.fg("toolOutput", ` (limit ${limit})`); }
 * ```
 */
internal fun findSubject(args: JsonObject?): List<ToolCallPart> = buildList {
    val pattern = argString(args, "pattern").orEmpty()
    val path = argString(args, "path")?.takeIf { it.isNotEmpty() } ?: "."
    add(ToolCallPart(pattern, ToolCallToken.Accent))
    add(ToolCallPart(" in $path", ToolCallToken.ToolOutput))
    argInt(args, "limit")?.let { add(ToolCallPart(" (limit $it)", ToolCallToken.ToolOutput)) }
}

/**
 * The part of pi's `ls` call line after the tool name (`renderers/ls.js:12-19`): the path
 * (pi's `renderToolPath(..., { emptyFallback: "." })`, i.e. `accent`) and ` (limit N)` in
 * `toolOutput`:
 *
 * ```js
 * const pathDisplay = renderToolPath(str(args?.path), theme, cwd, { emptyFallback: "." });
 * let text = `${theme.fg("toolTitle", theme.bold("ls"))} ${pathDisplay}`;
 * if (limit !== undefined) { text += theme.fg("toolOutput", ` (limit ${limit})`); }
 * ```
 */
internal fun lsSubject(args: JsonObject?): List<ToolCallPart> = buildList {
    val path = argString(args, "path")?.takeIf { it.isNotEmpty() } ?: "."
    add(ToolCallPart(path, ToolCallToken.Accent))
    argInt(args, "limit")?.let { add(ToolCallPart(" (limit $it)", ToolCallToken.ToolOutput)) }
}
