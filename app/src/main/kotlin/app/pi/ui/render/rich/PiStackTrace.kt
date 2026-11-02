package app.pi.ui.render.rich

/**
 * The **pure recognizer** behind the stack-trace card (`§十.1` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The Compose card lives in `PiStackTraceView.kt`; this file is deliberately
 * Compose-free so `tools/run-app-pure-checks.sh` can compile and run it on a bare
 * JVM (the same reason `PiJsonTree.kt` and `PiJsonTreeView.kt` are separate). All
 * recognition, framing and the "is this file/line real?" rule are here.
 *
 * ## What is recognized
 *
 * Exactly five shapes, each by its **own concrete marker** rather than by looking
 * "trace-ish":
 *
 * | kind | marker that has to be present |
 * |---|---|
 * | [StackTraceKind.Python] | a `Traceback (most recent call last):` line |
 * | [StackTraceKind.Rust] | a `thread '…' panicked at …` line |
 * | [StackTraceKind.Go] | a `panic:` / `fatal error:` / `goroutine N [state]:` line |
 * | [StackTraceKind.JavaKotlin] | `at fn(File.kt:42)` frames (`.java`/`.kt`/`.kts`), `Exception in thread "…"`, `Caused by:`, or a `java.`/`javax.`/`kotlin.`/`android.` exception header |
 * | [StackTraceKind.JavaScript] | `at fn (file.js:1:1)` frames (JS/TS extensions or `:line:column`), `Uncaught …Error`, or `<anonymous>`/`native`/`node:` frames |
 *
 * A Java frame and a JavaScript frame look alike at a glance; they are separated
 * by facts in the trace, not by preference: a Java/Kotlin frame names a
 * `.java`/`.kt` file and carries no column, while a V8 frame names a `.js`/`.ts`
 * file (or a `file:`/`node:` location) and carries `line:column`. A tie — both,
 * equally often — is **not** resolved by picking one: the whole input is
 * reported as not recognized.
 *
 * ## Never guess a file or a line
 *
 * `§十.1` allows a file/line only when it can be reliably mapped back to the
 * project. This model therefore stores [StackFrame.file] / [StackFrame.line] as
 * `null` unless the trace itself spelled out `File.kt:42` (Java/Kotlin),
 * `file.js:1:1` (JS/TS), `File "…", line 42` (Python), `file:42:1` (Rust) or
 * `file.go:42` (Go). `(Native Method)`, `(Unknown Source)`, `<anonymous>`,
 * `native`, `index 0` and Python's `<stdin>`/`<string>`/`<frozen …>` are all
 * **not** files: they keep the function (where there is one) and leave file/line
 * null. No click-to-open is offered by the card, because "which file is this"
 * would have to be guessed from a bare basename.
 *
 * ## Failure is the caller's fallback
 *
 * [PiStackTrace.parse] returns [StackTraceResult.Failure] for a streaming block,
 * an empty body, an oversized body ("[StackTraceLimits.maxChars]", 256 KiB like
 * the other structured blocks) or input it does not recognize. The caller keeps
 * the original text in every case; nothing is ever partially rendered.
 */

/** The five trace dialects this build recognizes. */
enum class StackTraceKind(val displayName: String) {
    JavaKotlin("Java / Kotlin"),
    JavaScript("JavaScript / TypeScript"),
    Python("Python"),
    Rust("Rust"),
    Go("Go"),
}

/**
 * The bounds for one recognized trace.
 *
 * A trace is much smaller than the other rich blocks; the 256 KiB ceiling is the
 * plan-wide one (`§十五`) and [maxFrames] keeps a pathological dump from becoming
 * thousands of composables. Over the frame bound the frames are **truncated and
 * reported** ([ParsedStackTrace.framesTruncated]) — unlike the size bound, a
 * prefix of a stack trace is still an honest stack trace, and dropping the whole
 * thing would hide the exception.
 */
data class StackTraceLimits(
    /** Maximum source length in `String` characters (the plan's 256 KiB). */
    val maxChars: Int = 256 * 1024,
    /** Maximum number of [StackFrame]s kept. */
    val maxFrames: Int = 200,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxFrames > 0) { "maxFrames must be positive" }
    }
}

/**
 * One frame.
 *
 * [raw] is the source line verbatim (minus the model's own trimming of the
 * language's indentation), so the card can show what the model wrote. [function],
 * [file] and [line] are filled only when the frame's own syntax identified them;
 * see the file KDoc. A `null` [function] is real too: a Rust panic site and a Go
 * location-only frame have no function name.
 */
data class StackFrame(
    val raw: String,
    val function: String? = null,
    val file: String? = null,
    val line: Int? = null,
)

/** A fully recognized trace. */
data class ParsedStackTrace(
    val kind: StackTraceKind,
    /** e.g. `java.lang.IllegalStateException`, `TypeError`, `ValueError`; `null` when the language has no type (Rust/Go panic). */
    val exceptionType: String?,
    /** The exception's message exactly as the trace printed it; `null` when absent. */
    val message: String?,
    val frames: List<StackFrame>,
    /** `true` when the trace had more than [StackTraceLimits.maxFrames] frames. */
    val framesTruncated: Boolean = false,
)

/** Why a body did not become a trace card. */
enum class StackTraceFailure {
    NotSettled,
    Empty,
    TooLarge,

    /** No concrete marker of any of the five dialects was found. */
    NotRecognized,
}

/** The result of [PiStackTrace.parse]. */
sealed interface StackTraceResult {
    data class Parsed(val trace: ParsedStackTrace) : StackTraceResult

    data class Failure(val reason: StackTraceFailure) : StackTraceResult
}

/**
 * The recognizer. [parse] is total: it never throws, and every unknown shape is a
 * [StackTraceFailure.NotRecognized] rather than a guess.
 */
object PiStackTrace {
    val DEFAULT_LIMITS = StackTraceLimits()

    /**
     * Parse [source] as one of the five dialects.
     *
     * @param settled `false` while the producer is still streaming; the card waits
     *   for a complete block (`§十四`).
     */
    fun parse(
        source: String,
        settled: Boolean = true,
        limits: StackTraceLimits = DEFAULT_LIMITS,
    ): StackTraceResult {
        if (!settled) return StackTraceResult.Failure(StackTraceFailure.NotSettled)
        if (source.length > limits.maxChars) return StackTraceResult.Failure(StackTraceFailure.TooLarge)
        if (source.isBlank()) return StackTraceResult.Failure(StackTraceFailure.Empty)

        val lines = source.trim().lines()
        val kind = detect(lines) ?: return StackTraceResult.Failure(StackTraceFailure.NotRecognized)
        val trace = when (kind) {
            StackTraceKind.JavaKotlin -> parseJavaKotlin(lines, limits)
            StackTraceKind.JavaScript -> parseJavaScript(lines, limits)
            StackTraceKind.Python -> parsePython(lines, limits)
            StackTraceKind.Rust -> parseRust(lines, limits)
            StackTraceKind.Go -> parseGo(lines, limits)
        }
        if (trace.frames.isEmpty() && trace.exceptionType == null && trace.message == null) {
            return StackTraceResult.Failure(StackTraceFailure.NotRecognized)
        }
        return StackTraceResult.Parsed(trace)
    }
}

// ---------------------------------------------------------------------------------
// Detection
// ---------------------------------------------------------------------------------

private val RUST_NEW = Regex("""^thread '[^']*' panicked at .+:$""")
private val RUST_OLD = Regex("""^thread '[^']*' panicked at '.+', .+$""")
private val EXCEPTION_IN_THREAD = Regex("""^Exception in thread "[^"]*"\s+(.+)$""")
private val JAVA_TYPE_HEADER = Regex("""^([\w.$]*?(?:Exception|Error|Throwable))(?::\s?(.*))?$""")
private val GO_GOROUTINE = Regex("""^goroutine \d+ \[[^\]]*\]:$""")

private enum class FrameDialect { Java, JavaScript, Unknown }

private data class AtFrame(
    val raw: String,
    val function: String?,
    val location: String?,
    val file: String?,
    val line: Int?,
    val dialect: FrameDialect,
)

/**
 * Pick the dialect, using only concrete markers.
 *
 * The Python/Rust/Go markers are unambiguous, so they win immediately. Java and
 * JavaScript are separated by their frames' own file/line syntax; headers only
 * break the remaining tie for traces whose frames carry no recognizable location
 * (`(Native Method)`, `<anonymous>`, `native`, …). A genuine 50/50 mix is
 * reported as unrecognized rather than assigned to one side.
 */
private fun detect(lines: List<String>): StackTraceKind? {
    var python = false
    var rust = false
    var goPanic = false
    var goGoroutine = false
    var goFrames = 0
    var javaFrames = 0
    var jsFrames = 0
    var jsishUnknownFrames = 0
    var atFrames = 0
    var javaStrongMarker = false
    var firstNonEmpty: String? = null

    for (line in lines) {
        val trimmed = line.trim()
        if (firstNonEmpty == null && trimmed.isNotEmpty()) firstNonEmpty = trimmed
        when {
            trimmed == "Traceback (most recent call last):" -> python = true
            RUST_NEW.matches(trimmed) || RUST_OLD.matches(trimmed) -> rust = true
            trimmed.startsWith("panic: ") || trimmed.startsWith("fatal error: ") -> goPanic = true
            GO_GOROUTINE.matches(trimmed) -> goGoroutine = true
            trimmed.startsWith("Exception in thread \"") -> javaStrongMarker = true
            trimmed.startsWith("Caused by: ") -> javaStrongMarker = true
        }
        if (GO_LOCATION.matches(line)) goFrames++
        parseJavaHeader(trimmed)?.let { header ->
            if (isJavaNamespace(header.type)) javaStrongMarker = true
        }

        val frame = parseAtFrame(line) ?: continue
        atFrames++
        when (frame.dialect) {
            FrameDialect.Java -> javaFrames++
            FrameDialect.JavaScript -> jsFrames++
            FrameDialect.Unknown -> if (frame.location.isJsOnlyLocation()) jsishUnknownFrames++
        }
    }

    if (python) return StackTraceKind.Python
    if (rust) return StackTraceKind.Rust
    // `panic:` alone is accepted only when the trace carries the machinery around
    // it (`.go` frames, a goroutine dump) or when it is the block's first line. A
    // prose sentence that merely starts with "panic:" mid-block stays prose.
    if (goPanic && (goGoroutine || goFrames > 0 || firstNonEmpty?.startsWith("panic:") == true ||
            firstNonEmpty?.startsWith("fatal error:") == true)
    ) {
        return StackTraceKind.Go
    }
    if (goGoroutine && goFrames > 0) return StackTraceKind.Go

    when {
        javaFrames > 0 && jsFrames == 0 -> return StackTraceKind.JavaKotlin
        jsFrames > 0 && javaFrames == 0 -> return StackTraceKind.JavaScript
        javaFrames > 0 && jsFrames > 0 ->
            return when {
                javaFrames > jsFrames -> StackTraceKind.JavaKotlin
                jsFrames > javaFrames -> StackTraceKind.JavaScript
                else -> null
            }
    }

    if (atFrames == 0) return null
    // Only frames whose location carries no file at all: let a strong header decide.
    if (javaStrongMarker) return StackTraceKind.JavaKotlin
    if (jsishUnknownFrames > 0) return StackTraceKind.JavaScript
    return null
}

/** A location only V8 prints: not a source file, but a reliable JavaScript marker. */
private fun String?.isJsOnlyLocation(): Boolean {
    val value = this ?: return false
    return value == "<anonymous>" || value == "native" || value.startsWith("node:") ||
        Regex("""^index \d+$""").matches(value)
}

// ---------------------------------------------------------------------------------
// `at …` frames (Java/Kotlin and JavaScript/TypeScript share the prefix)
// ---------------------------------------------------------------------------------

private val JAVA_LOCATION = Regex("""^(.+\.(?:java|kt|kts|scala|groovy))(?::(\d+)(?::\d+)?)?$""")
private val JS_COLUMN_LOCATION = Regex("""^(.+):(\d+):(\d+)$""")
private val JS_LINE_LOCATION = Regex("""^(.+\.(?:js|mjs|cjs|jsx|ts|mts|cts|tsx|vue|svelte)):(\d+)$""")

private fun parseAtFrame(line: String): AtFrame? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("at ")) return null
    val rest = trimmed.substring(3).trim()
    if (rest.isEmpty()) return null

    var function: String? = null
    var location: String
    val open = rest.lastIndexOf('(')
    if (open >= 0 && rest.endsWith(")")) {
        function = rest.substring(0, open).trim().ifEmpty { null }
        location = rest.substring(open + 1, rest.length - 1).trim()
    } else {
        location = rest
    }

    val java = JAVA_LOCATION.matchEntire(location)
    if (java != null) {
        return AtFrame(
            raw = line.trim(),
            function = function,
            location = location,
            file = java.groupValues[1],
            line = java.groupValues[2].toIntOrNull(),
            dialect = FrameDialect.Java,
        )
    }

    // V8's `line:column` is the discriminator; a Java/Kotlin frame never has one.
    val jsColumn = JS_COLUMN_LOCATION.matchEntire(location)
    if (jsColumn != null && jsColumn.groupValues[1].none { it.isWhitespace() }) {
        return AtFrame(
            raw = line.trim(),
            function = function,
            location = location,
            file = jsColumn.groupValues[1],
            line = jsColumn.groupValues[2].toIntOrNull(),
            dialect = FrameDialect.JavaScript,
        )
    }

    val jsLine = JS_LINE_LOCATION.matchEntire(location)
    if (jsLine != null) {
        return AtFrame(
            raw = line.trim(),
            function = function,
            location = location,
            file = jsLine.groupValues[1],
            line = jsLine.groupValues[2].toIntOrNull(),
            dialect = FrameDialect.JavaScript,
        )
    }

    return AtFrame(
        raw = line.trim(),
        function = function,
        location = location,
        file = null,
        line = null,
        dialect = FrameDialect.Unknown,
    )
}

/** The card only ever shows frames of the dialect the trace was recognized as. */
private fun AtFrame.toStackFrame(kind: StackTraceKind): StackFrame {
    val trusted = when (kind) {
        StackTraceKind.JavaKotlin -> dialect == FrameDialect.Java
        StackTraceKind.JavaScript -> dialect == FrameDialect.JavaScript
        else -> true
    }
    return StackFrame(
        raw = raw,
        function = function,
        file = if (trusted) file else null,
        line = if (trusted) line else null,
    )
}

// ---------------------------------------------------------------------------------
// Java / Kotlin
// ---------------------------------------------------------------------------------

private data class Header(val type: String?, val message: String?)

private fun isJavaNamespace(type: String?): Boolean {
    val value = type ?: return false
    return value.startsWith("java.") || value.startsWith("javax.") ||
        value.startsWith("kotlin.") || value.startsWith("android.") ||
        value.startsWith("dalvik.")
}

/**
 * One Java/Kotlin exception header line, or `null`.
 *
 * Handles `Exception in thread "main" java.lang.X: msg`, `java.lang.X: msg`,
 * `Caused by: java.lang.X: msg` and `Suppressed: …`. The type part is required to
 * end in `Exception`/`Error`/`Throwable`, and may not contain whitespace — so
 * `An error: happened` (a prose sentence) is not a header.
 */
private fun stripJavaHeaderPrefixes(line: String): String {
    var text = line.trim()
    if (text.startsWith("Caused by: ")) text = text.substringAfter("Caused by: ")
    if (text.startsWith("Suppressed: ")) text = text.substringAfter("Suppressed: ")
    val inThread = EXCEPTION_IN_THREAD.matchEntire(text)
    if (inThread != null) text = inThread.groupValues[1].trim()
    return text
}

private fun parseJavaHeader(line: String): Header? {
    val text = stripJavaHeaderPrefixes(line)
    val match = JAVA_TYPE_HEADER.matchEntire(text) ?: return null
    val type = match.groupValues[1].ifEmpty { null }
    val message = match.groupValues[2].takeIf { it.isNotEmpty() }
    return Header(type, message)
}

/**
 * A **display-only** fallback for a custom throwable class.
 *
 * A Java trace prints the exception's class name whatever it is, so
 * `org.example.CustomFailure: boom` is a header — but the strict pattern only
 * accepts names ending in `Exception`/`Error`/`Throwable`. This looser parse is
 * only consulted after [StackTraceKind.JavaKotlin] was already decided by the
 * `at …(File.java:42)` frames, and only on the first non-frame line, so it can
 * add a type to a real trace but cannot turn arbitrary text into one.
 */
private val LOOSE_JAVA_HEADER = Regex("""^([\w.$]+)(?::\s?(.*))?$""")

private fun parseLooseJavaHeader(line: String): Header? {
    val text = stripJavaHeaderPrefixes(line)
    val match = LOOSE_JAVA_HEADER.matchEntire(text) ?: return null
    val token = match.groupValues[1]
    // A single all-lowercase word ("trace", "error") is prose, not a class name.
    if (token.none { it.isUpperCase() } && '.' !in token) return null
    return Header(token, match.groupValues[2].takeIf { it.isNotEmpty() })
}

private fun parseJavaKotlin(lines: List<String>, limits: StackTraceLimits): ParsedStackTrace {
    val frames = ArrayList<StackFrame>()
    var truncated = false
    var header: Header? = null
    // The first non-frame line is the header candidate for the loose fallback;
    // it is only used if no strict header was found anywhere.
    var firstNonFrameLine: String? = null

    for (line in lines) {
        val at = parseAtFrame(line)
        if (at != null) {
            if (frames.size < limits.maxFrames) {
                frames += at.toStackFrame(StackTraceKind.JavaKotlin)
            } else {
                truncated = true
            }
            continue
        }
        if (header == null) {
            val candidate = parseJavaHeader(line.trim())
            if (candidate != null) {
                header = candidate
            } else if (firstNonFrameLine == null && line.isNotBlank()) {
                firstNonFrameLine = line
            }
        }
    }
    if (header == null) {
        firstNonFrameLine?.let { header = parseLooseJavaHeader(it) }
    }

    return ParsedStackTrace(
        kind = StackTraceKind.JavaKotlin,
        exceptionType = header?.type,
        message = header?.message,
        frames = frames,
        framesTruncated = truncated,
    )
}

// ---------------------------------------------------------------------------------
// JavaScript / TypeScript
// ---------------------------------------------------------------------------------

private val JS_HEADER = Regex(
    """^(?:Uncaught\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)(\s*\[[^\]]*\])?(?::\s?(.*))?$""",
)
private val JS_ERROR_SUFFIX = Regex("""(?:Error|Exception)$""")

private fun parseJavaScriptHeader(line: String): Header? {
    val match = JS_HEADER.matchEntire(line.trim()) ?: return null
    val token = match.groupValues[1]
    val code = match.groupValues[2].trim()
    if (!JS_ERROR_SUFFIX.containsMatchIn(token) && code.isEmpty()) return null
    val type = if (code.isEmpty()) token else "$token $code"
    val message = match.groupValues[3].takeIf { it.isNotEmpty() }
    return Header(type, message)
}

private fun parseJavaScript(lines: List<String>, limits: StackTraceLimits): ParsedStackTrace {
    val frames = ArrayList<StackFrame>()
    var truncated = false
    var header: Header? = null

    for (line in lines) {
        val at = parseAtFrame(line)
        if (at != null) {
            if (frames.size < limits.maxFrames) {
                frames += at.toStackFrame(StackTraceKind.JavaScript)
            } else {
                truncated = true
            }
            continue
        }
        if (header == null) {
            val candidate = parseJavaScriptHeader(line)
            if (candidate != null) header = candidate
        }
    }

    return ParsedStackTrace(
        kind = StackTraceKind.JavaScript,
        exceptionType = header?.type,
        message = header?.message,
        frames = frames,
        framesTruncated = truncated,
    )
}

// ---------------------------------------------------------------------------------
// Python
// ---------------------------------------------------------------------------------

private val PYTHON_FRAME = Regex("""^\s*File\s+"([^"]*)",\s*line\s+(\d+)(?:,\s+in\s+(.+?))?\s*$""")
private val PYTHON_EXCEPTION = Regex("""^([A-Za-z_][\w.]*)(?::\s?(.*))?$""")

private fun parsePython(lines: List<String>, limits: StackTraceLimits): ParsedStackTrace {
    val frames = ArrayList<StackFrame>()
    var truncated = false
    var exceptionType: String? = null
    var message: String? = null

    for (line in lines) {
        val frame = PYTHON_FRAME.matchEntire(line)
        if (frame != null) {
            if (frames.size < limits.maxFrames) {
                val printedFile = frame.groupValues[1]
                // `<stdin>`, `<string>`, `<frozen importlib._bootstrap>` are not files.
                val file = printedFile.takeUnless { it.startsWith("<") && it.endsWith(">") }
                frames += StackFrame(
                    raw = line.trim(),
                    function = frame.groupValues[3].trim().ifEmpty { null },
                    file = file,
                    line = frame.groupValues[2].toIntOrNull(),
                )
            } else {
                truncated = true
            }
            continue
        }

        // The top-level exception is the last unindented `Name` / `Name: message`
        // line; chained-exception prose contains spaces and is rejected by the
        // pattern, and `[Previous line repeated …]` starts with `[`.
        if (line.isNotEmpty() && !line.first().isWhitespace()) {
            val candidate = PYTHON_EXCEPTION.matchEntire(line.trim()) ?: continue
            exceptionType = candidate.groupValues[1]
            message = candidate.groupValues[2].takeIf { it.isNotEmpty() }
        }
    }

    return ParsedStackTrace(
        kind = StackTraceKind.Python,
        exceptionType = exceptionType,
        message = message,
        frames = frames,
        framesTruncated = truncated,
    )
}

// ---------------------------------------------------------------------------------
// Rust
// ---------------------------------------------------------------------------------

private val RUST_OLD_LOCATION = Regex("""^thread '[^']*' panicked at '(.*)', (.+)$""")
private val RUST_NEW_LOCATION = Regex("""^thread '[^']*' panicked at (.+):$""")
private val RUST_FILE_LINE_COLUMN = Regex("""^(.+?):(\d+):(\d+)$""")
private val RUST_FILE_LINE = Regex("""^(.+?):(\d+)$""")
private val RUST_BACKTRACE_FRAME = Regex("""^\s*(\d+):\s+(.+)$""")
private val RUST_BACKTRACE_AT = Regex("""^\s+at\s+(.+)$""")
private val RUST_HASH_SUFFIX = Regex("""::h[0-9a-f]{16}$""")

private fun parseRust(lines: List<String>, limits: StackTraceLimits): ParsedStackTrace {
    val frames = ArrayList<StackFrame>()
    var truncated = false
    var message: String? = null
    var panicFile: String? = null
    var panicLine: Int? = null
    var panicRaw: String? = null
    var panicIndex = -1

    for (index in lines.indices) {
        val line = lines[index].trim()
        val old = RUST_OLD_LOCATION.matchEntire(line)
        if (old != null) {
            message = old.groupValues[1]
            val (file, number) = parseRustLocation(old.groupValues[2])
            panicFile = file
            panicLine = number
            panicRaw = lines[index].trim()
            panicIndex = index
            break
        }
        val new = RUST_NEW_LOCATION.matchEntire(line)
        if (new != null) {
            val (file, number) = parseRustLocation(new.groupValues[1])
            panicFile = file
            panicLine = number
            panicRaw = lines[index].trim()
            panicIndex = index
            // Rust 1.73+: the message is the next non-empty line, unless the
            // next line is already the backtrace/note machinery.
            for (next in (index + 1) until lines.size) {
                val candidate = lines[next].trim()
                if (candidate.isEmpty()) continue
                if (candidate.startsWith("note:") || candidate.startsWith("stack backtrace:") ||
                    candidate.startsWith("thread '")
                ) {
                    break
                }
                message = candidate
                break
            }
            break
        }
    }

    if (panicRaw != null) {
        frames += StackFrame(
            raw = panicRaw,
            function = null,
            file = panicFile,
            line = panicLine,
        )
    }

    if (panicIndex >= 0) {
        var pendingSymbol: String? = null
        for (index in (panicIndex + 1) until lines.size) {
            val line = lines[index]
            val frame = RUST_BACKTRACE_FRAME.matchEntire(line)
            if (frame != null) {
                pendingSymbol = frame.groupValues[2].trim().replace(RUST_HASH_SUFFIX, "")
                // A symbol line may not be followed by `at`; commit it as-is.
                if (index + 1 >= lines.size || RUST_BACKTRACE_AT.matchEntire(lines[index + 1]) == null) {
                    if (frames.size < limits.maxFrames) {
                        frames += StackFrame(raw = line.trim(), function = pendingSymbol)
                    } else {
                        truncated = true
                    }
                    pendingSymbol = null
                }
                continue
            }
            val at = RUST_BACKTRACE_AT.matchEntire(line)
            if (at != null) {
                val (file, number) = parseRustLocation(at.groupValues[1].trim())
                if (frames.size < limits.maxFrames) {
                    frames += StackFrame(
                        raw = line.trim(),
                        function = pendingSymbol,
                        file = file,
                        line = number,
                    )
                } else {
                    truncated = true
                }
                pendingSymbol = null
            }
        }
    }

    return ParsedStackTrace(
        kind = StackTraceKind.Rust,
        exceptionType = null,
        message = message,
        frames = frames,
        framesTruncated = truncated,
    )
}

private fun parseRustLocation(location: String): Pair<String?, Int?> {
    RUST_FILE_LINE_COLUMN.matchEntire(location)?.let { match ->
        return match.groupValues[1] to match.groupValues[2].toIntOrNull()
    }
    RUST_FILE_LINE.matchEntire(location)?.let { match ->
        return match.groupValues[1] to match.groupValues[2].toIntOrNull()
    }
    return null to null
}

// ---------------------------------------------------------------------------------
// Go
// ---------------------------------------------------------------------------------

private val GO_LOCATION = Regex("""^\s+(.+?\.go):(\d+)(?:\s+\+0x[0-9a-fA-F]+)?\s*$""")
private val GO_CREATED_BY = Regex("""^created by (.+?)(?: in goroutine \d+)?$""")

private fun parseGo(lines: List<String>, limits: StackTraceLimits): ParsedStackTrace {
    val frames = ArrayList<StackFrame>()
    var truncated = false
    var message: String? = null
    var pendingFunction: String? = null

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("panic: ") && message == null) {
            message = trimmed.removePrefix("panic: ")
        } else if (trimmed.startsWith("fatal error: ") && message == null) {
            message = trimmed.removePrefix("fatal error: ")
        }

        val location = GO_LOCATION.matchEntire(line)
        if (location != null) {
            if (frames.size < limits.maxFrames) {
                frames += StackFrame(
                    raw = trimmed,
                    function = pendingFunction,
                    file = location.groupValues[1],
                    line = location.groupValues[2].toIntOrNull(),
                )
            } else {
                truncated = true
            }
            pendingFunction = null
            continue
        }

        // Go prints `fn(args)` at column 0 and its location on the next,
        // tab-indented line. `created by main.main in goroutine 1` is a frame
        // header of the same shape (a goroutine start), without parentheses.
        val createdBy = GO_CREATED_BY.matchEntire(trimmed)
        if (createdBy != null) {
            pendingFunction = createdBy.groupValues[1].trim()
            continue
        }
        if (line.isNotEmpty() && !line.first().isWhitespace() && trimmed.endsWith(")") &&
            !trimmed.startsWith("panic:") && !trimmed.startsWith("fatal error:") &&
            !trimmed.startsWith("goroutine ")
        ) {
            pendingFunction = trimmed.substringBefore('(').trim().ifEmpty { null }
        }
    }

    return ParsedStackTrace(
        kind = StackTraceKind.Go,
        exceptionType = null,
        message = message,
        frames = frames,
        framesTruncated = truncated,
    )
}
