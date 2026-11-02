package app.pi.ui.render.rich

/**
 * The **pure footnote model** (`§十一.1` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * `org.jetbrains:markdown 0.7.9` has no footnote node (the plan records this
 * explicitly), so footnotes cannot be handed to the existing AST. This file is
 * the independent, local replacement for the model half: it finds `[^id]`
 * references and `[^id]: …` definitions, assigns numbers, and turns the body into
 * parts a renderer can draw (plain text plus superscript markers). It is
 * Compose-free on purpose, so `tools/run-app-pure-checks.sh` can compile and run
 * it (`PiFootnoteCheck.kt`).
 *
 * ## The grammar this implements
 *
 * ```
 * 正文引用[^1]，也可以写[^note]。
 *
 * [^1]: 脚注正文，可以缩进续行。
 * [^note]: 另一个脚注。
 * ```
 *
 * - A reference is `[^` + an identifier + `]`. The identifier is 1–64
 *   characters, no whitespace, no `[`/`]`. Everything else — `[x]`, `[1]`,
 *   `[^]`, `[ ^1]`, `[^1 ]` — is **ordinary text** and is left byte-for-byte
 *   alone (`§十一.1` "未定义脚注原文回退", and the task's "不误识别普通 `[x]`").
 * - A definition is `[^id]:` at the start of a line (at most 3 leading spaces,
 *   same as CommonMark), followed by optional text. Continuation lines are lines
 *   indented by at least 2 spaces (up to 4 are stripped); a blank line continues
 *   the definition only when the next non-blank line is indented, which is how a
 *   multi-paragraph footnote is written.
 * - The **first** definition of an id wins; later duplicates are ignored, as
 *   CommonMark does for duplicate link definitions.
 * - A reference whose id has no definition stays literal in the body and is not
 *   numbered. It is still reported in [FootnoteDocument.references] with
 *   `defined = false`, so a caller can style or diagnose it without touching the
 *   text.
 * - Numbering follows first **defined** reference order; definitions that are
 *   never referenced still appear in [FootnoteDocument.definitions] (numbered
 *   after the referenced ones) rather than being silently deleted — the plan's
 *   rule is that model output must not disappear.
 * - Fenced code blocks and inline code spans are not scanned, and `\[^1]` is an
 *   escape, not a reference.
 *
 * ## Known limitation
 *
 * A four-space *indented* code block is treated as normal text: its `[^id]`
 * references would be replaced. Fenced blocks (the form models actually emit for
 * code) are handled; indented code is rare in chat output and distinguishing it
 * from list continuation needs list context this model does not have.
 *
 * ## Failure and bounds
 *
 * Over any bound the whole document falls back to [FootnoteDocument.parts] =
 * one [FootnoteBodyPart.Text] holding the original source, with
 * [FootnoteDocument.failure] set. A partial rewrite would silently move text
 * around, which is worse than showing the source.
 */

/** Why a document was not rewritten. The parts then contain the original source. */
enum class FootnoteFailure {
    TooLarge,
    TooManyDefinitions,
    TooManyReferences,
}

/** The bounds for one document. Parameters so tests can drive them with tiny values. */
data class FootnoteLimits(
    /** Maximum source length in `String` characters (the plan-wide 256 KiB). */
    val maxChars: Int = 256 * 1024,
    /** Maximum number of definitions. */
    val maxDefinitions: Int = 500,
    /** Maximum number of references (defined or not). */
    val maxReferences: Int = 2_000,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxDefinitions > 0) { "maxDefinitions must be positive" }
        require(maxReferences > 0) { "maxReferences must be positive" }
    }
}

/** One footnote definition, already numbered for display. */
data class FootnoteDefinition(
    val id: String,
    /** 1-based number, in first-reference order (unreferenced definitions come last). */
    val number: Int,
    /** The definition's text, continuation lines joined with `\n` and indentation stripped. */
    val text: String,
    /** Whether the body actually referenced this definition. */
    val referenced: Boolean,
    /** 1-based line of the `[^id]:` marker in the original source. */
    val line: Int,
)

/** One reference occurrence. [number] is `null` exactly when [defined] is `false`. */
data class FootnoteReference(
    val id: String,
    val number: Int?,
    val defined: Boolean,
)

/** A piece of the rewritten body. */
sealed interface FootnoteBodyPart {
    /** Literal Markdown text (may contain undefined `[^id]` references unchanged). */
    data class Text(val text: String) : FootnoteBodyPart

    /** A defined reference, already replaced by its number; the renderer draws it as a superscript. */
    data class Marker(val id: String, val number: Int) : FootnoteBodyPart
}

/**
 * The rewritten document.
 *
 * [parts] is the body in order. [definitions] and [references] are the
 * side-tables; [bodyText] is a convenience that renders markers as Unicode
 * superscript digits (¹²³…), which is what a plain-text consumer (or the harness)
 * sees.
 */
data class FootnoteDocument(
    val parts: List<FootnoteBodyPart>,
    val definitions: List<FootnoteDefinition>,
    val references: List<FootnoteReference>,
    val failure: FootnoteFailure? = null,
) {
    /** `true` only when the document actually contains at least one usable definition. */
    val hasFootnotes: Boolean get() = failure == null && definitions.isNotEmpty()

    /** The body with each defined marker replaced by a Unicode superscript number. */
    fun bodyText(): String = buildString {
        for (part in parts) {
            when (part) {
                is FootnoteBodyPart.Text -> append(part.text)
                is FootnoteBodyPart.Marker -> append(footnoteSuperscript(part.number))
            }
        }
    }

    /** The definition for [id], or `null`. */
    fun definitionFor(id: String): FootnoteDefinition? = definitions.firstOrNull { it.id == id }
}

/** The footnote model. [parse] is total and never throws. */
object PiFootnote {
    val DEFAULT_LIMITS = FootnoteLimits()

    /** Parse [source]; see the file KDoc for the grammar and the fallback contract. */
    fun parse(source: String, limits: FootnoteLimits = DEFAULT_LIMITS): FootnoteDocument {
        if (source.length > limits.maxChars) {
            return fallback(source, FootnoteFailure.TooLarge)
        }

        val lines = source.lines()
        val extraction = extractDefinitions(lines, limits)
        if (extraction is DefinitionExtraction.Failure) {
            return fallback(source, extraction.reason)
        }
        val extracted = extraction as DefinitionExtraction.Success

        // No definitions at all: the body is untouched — not re-serialized, so
        // even a CRLF document keeps its exact bytes.
        if (extracted.definitions.isEmpty()) {
            return FootnoteDocument(
                parts = listOf(FootnoteBodyPart.Text(source)),
                definitions = emptyList(),
                references = emptyList(),
            )
        }

        val rewritten = rewriteBody(extracted.bodyLines, extracted.definitions, limits)
        if (rewritten is BodyRewrite.Fallback) {
            return fallback(source, rewritten.reason)
        }
        val success = rewritten as BodyRewrite.Success

        // Definitions that no reference reached are still listed, after the
        // referenced ones, in source order. Their number is still assigned so the
        // list is stable and can be shown.
        var nextNumber = success.numbers.size + 1
        val byNumber = ArrayList<FootnoteDefinition>(extracted.definitions.size)
        val referencedIds = success.numbers.keys
        for (raw in extracted.definitions) {
            val number = success.numbers[raw.id] ?: nextNumber++
            byNumber += FootnoteDefinition(
                id = raw.id,
                number = number,
                text = raw.text,
                referenced = raw.id in referencedIds,
                line = raw.line,
            )
        }
        byNumber.sortBy { it.number }

        return FootnoteDocument(
            parts = success.parts,
            definitions = byNumber,
            references = success.references,
        )
    }

    private fun fallback(source: String, reason: FootnoteFailure): FootnoteDocument = FootnoteDocument(
        parts = listOf(FootnoteBodyPart.Text(source)),
        definitions = emptyList(),
        references = emptyList(),
        failure = reason,
    )
}

// ---------------------------------------------------------------------------------
// Definitions
// ---------------------------------------------------------------------------------

private data class RawDefinition(
    val id: String,
    val text: String,
    /** 1-based source line. */
    val line: Int,
)

private sealed interface DefinitionExtraction {
    data class Success(
        val bodyLines: List<String>,
        val definitions: List<RawDefinition>,
    ) : DefinitionExtraction

    data class Failure(val reason: FootnoteFailure) : DefinitionExtraction
}

private val DEFINITION_START = Regex("""^ {0,3}\[\^([^\]\s\[]{1,64})\]:[ \t]?(.*)$""")

/** A markdown fence currently open while scanning; definitions/refs inside it are code. */
private class OpenFence(private val marker: Char, private val length: Int) {
    fun closes(line: String): Boolean {
        val match = FENCE_CLOSE.matchEntire(line) ?: return false
        val run = match.groupValues[1]
        return run[0] == marker && run.length >= length
    }
}

private val FENCE_OPEN = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
private val FENCE_CLOSE = Regex("""^ {0,3}(`{3,}|~{3,})\s*$""")

private fun openFence(line: String): OpenFence? {
    val match = FENCE_OPEN.matchEntire(line) ?: return null
    val run = match.groupValues[1]
    // CommonMark: a backtick fence's info string may not contain a backtick.
    if (run[0] == '`' && match.groupValues[2].contains('`')) return null
    return OpenFence(run[0], run.length)
}

/**
 * The continuation text of a definition line, or `null` when the line ends the
 * definition. At least two leading spaces are required; up to four are stripped.
 */
private fun continuationText(line: String): String? {
    if (line.startsWith("\t")) return line.substring(1)
    val spaces = line.takeWhile { it == ' ' }.length
    if (spaces < 2) return null
    return line.substring(minOf(spaces, 4))
}

private fun extractDefinitions(lines: List<String>, limits: FootnoteLimits): DefinitionExtraction {
    val consumed = BooleanArray(lines.size)
    val definitions = ArrayList<RawDefinition>()
    val seen = HashSet<String>()
    var fence: OpenFence? = null
    var index = 0

    while (index < lines.size) {
        val line = lines[index]

        if (fence != null) {
            if (fence.closes(line)) fence = null
            index++
            continue
        }
        val opened = openFence(line)
        if (opened != null) {
            fence = opened
            index++
            continue
        }

        val start = DEFINITION_START.matchEntire(line) ?: run {
            index++
            continue
        }
        val id = start.groupValues[1]
        if (id in seen) {
            // Duplicate definitions are ignored (first wins), exactly as
            // CommonMark ignores a duplicate link definition. The text is still
            // removed from the body because it *is* definition syntax.
            val end = consumeDefinition(lines, index)
            for (i in index until end) consumed[i] = true
            index = end
            continue
        }
        seen += id

        var text = start.groupValues[2].trimEnd()
        var cursor = index + 1
        while (cursor < lines.size) {
            val candidate = lines[cursor]
            if (candidate.isBlank()) {
                var next = cursor + 1
                while (next < lines.size && lines[next].isBlank()) next++
                if (next < lines.size && continuationText(lines[next]) != null) {
                    text += "\n"
                    cursor = next
                    continue
                }
                break
            }
            val continuation = continuationText(candidate) ?: break
            text += "\n" + continuation.trimEnd()
            cursor++
        }

        definitions += RawDefinition(id = id, text = text.trimEnd(), line = index + 1)
        for (i in index until cursor) consumed[i] = true
        index = cursor

        if (definitions.size > limits.maxDefinitions) {
            return DefinitionExtraction.Failure(FootnoteFailure.TooManyDefinitions)
        }
    }

    val body = ArrayList<String>(lines.size)
    for (i in lines.indices) {
        if (!consumed[i]) body += lines[i]
    }
    return DefinitionExtraction.Success(bodyLines = body, definitions = definitions)
}

/** The line index just past the definition block starting at [start], for duplicate skipping. */
private fun consumeDefinition(lines: List<String>, start: Int): Int {
    var cursor = start + 1
    while (cursor < lines.size) {
        val candidate = lines[cursor]
        if (candidate.isBlank()) {
            var next = cursor + 1
            while (next < lines.size && lines[next].isBlank()) next++
            if (next < lines.size && continuationText(lines[next]) != null) {
                cursor = next
                continue
            }
            break
        }
        if (continuationText(candidate) == null) break
        cursor++
    }
    return cursor
}

// ---------------------------------------------------------------------------------
// References
// ---------------------------------------------------------------------------------

private sealed interface BodyRewrite {
    data class Success(
        val parts: List<FootnoteBodyPart>,
        val numbers: Map<String, Int>,
        val references: List<FootnoteReference>,
    ) : BodyRewrite

    data class Fallback(val reason: FootnoteFailure) : BodyRewrite
}

private fun rewriteBody(
    bodyLines: List<String>,
    definitions: List<RawDefinition>,
    limits: FootnoteLimits,
): BodyRewrite {
    val definedIds = definitions.mapTo(HashSet()) { it.id }
    val numbers = LinkedHashMap<String, Int>()
    val references = ArrayList<FootnoteReference>()
    val parts = ArrayList<FootnoteBodyPart>()
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isNotEmpty()) {
            parts += FootnoteBodyPart.Text(buffer.toString())
            buffer.setLength(0)
        }
    }

    var fence: OpenFence? = null
    for (lineIndex in bodyLines.indices) {
        val line = bodyLines[lineIndex]

        if (fence != null) {
            buffer.append(line)
            if (fence.closes(line)) fence = null
            if (lineIndex != bodyLines.lastIndex) buffer.append('\n')
            continue
        }
        val opened = openFence(line)
        if (opened != null) {
            fence = opened
            buffer.append(line)
            if (lineIndex != bodyLines.lastIndex) buffer.append('\n')
            continue
        }

        var i = 0
        while (i < line.length) {
            val char = line[i]
            if (char == '\\' && i + 1 < line.length) {
                // An escaped character (including `\[`) is literal.
                buffer.append(char).append(line[i + 1])
                i += 2
                continue
            }
            if (char == '`') {
                // Skip a whole inline code span, so `[^1]` inside `` ` `` is code.
                var run = 0
                while (i + run < line.length && line[i + run] == '`') run++
                val closing = findBacktickRun(line, i + run, run)
                if (closing < 0) {
                    buffer.append(line, i, i + run)
                    i += run
                } else {
                    val end = closing + run
                    buffer.append(line, i, end)
                    i = end
                }
                continue
            }
            if (char == '[') {
                val reference = parseReferenceAt(line, i)
                if (reference != null) {
                    val id = reference.first
                    val end = reference.second
                    if (id in definedIds) {
                        flush()
                        val number = numbers.getOrPut(id) { numbers.size + 1 }
                        parts += FootnoteBodyPart.Marker(id, number)
                    } else {
                        // Undefined: keep the exact source spelling, not a marker.
                        buffer.append(line, i, end)
                    }
                    references += FootnoteReference(
                        id = id,
                        number = numbers[id],
                        defined = id in definedIds,
                    )
                    i = end
                    continue
                }
                buffer.append(char)
                i++
                continue
            }
            buffer.append(char)
            i++
        }
        if (lineIndex != bodyLines.lastIndex) buffer.append('\n')

        if (references.size > limits.maxReferences) {
            return BodyRewrite.Fallback(FootnoteFailure.TooManyReferences)
        }
    }
    flush()

    return BodyRewrite.Success(parts = parts, numbers = numbers, references = references)
}

/** `(id, indexJustPastTheClosingBracket)` for a valid `[^id]` at [start], else `null`. */
private fun parseReferenceAt(line: String, start: Int): Pair<String, Int>? {
    if (start + 1 >= line.length || line[start] != '[' || line[start + 1] != '^') return null
    var end = start + 2
    while (end < line.length && end - (start + 2) <= 64) {
        val char = line[end]
        if (char == ']') {
            val id = line.substring(start + 2, end)
            if (id.isEmpty() || id.any { it.isWhitespace() } || '[' in id) return null
            return id to end + 1
        }
        if (char == '[') return null
        end++
    }
    return null
}

/** Index of the next run of exactly [length] backticks at or after [from], or `-1`. */
private fun findBacktickRun(line: String, from: Int, length: Int): Int {
    var index = from
    while (index < line.length) {
        if (line[index] != '`') {
            index++
            continue
        }
        var run = 0
        while (index + run < line.length && line[index + run] == '`') run++
        if (run == length) return index
        index += run
    }
    return -1
}

/** 1 → ¹, 23 → ²³. Used only for the plain-text view; the Compose view uses [FootnoteBodyPart.Marker]. */
private fun footnoteSuperscript(number: Int): String {
    val digits = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    return buildString {
        for (digit in number.toString()) append(digits[digit - '0'])
    }
}
