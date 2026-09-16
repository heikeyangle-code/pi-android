package app.pi.session

import app.pi.rpc.PiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.RandomAccessFile
import java.io.Reader

/**
 * Streams pi's session file as entries, in **bounded windows**.
 *
 * ## Why this exists
 *
 * pi's RPC surface has no pagination: `get_entries` answers with **the whole session
 * inside one JSONL record** and its `since` cursor only moves forwards
 * (`modes/rpc/rpc-mode.ts:638-648` — `entries.slice(sinceIndex + 1)`, no `limit`, no
 * backward cursor). So "open this conversation" used to mean "transport, parse and
 * project the entire history into rows", and the first frame paid for the whole file.
 * Two things the user reported follow directly:
 *
 *  - a record past `JsonlFramer`'s cap is dropped by the frame reader, so the open
 *    fails outright — and an entry carries an inline base64 image at four characters
 *    per three bytes, so **two phone photos are enough**;
 *  - a text-only session of a few hundred turns still takes one to two seconds,
 *    because the work is proportional to the history and not to the screen.
 *
 * The file is the way out, and it is **not a second source of truth: pi *is* the
 * file.** `SessionManager.open` loads exactly this file, and `getEntries()` returns
 * every loaded entry whose `type` is not `session`
 * (`core/session-manager.ts:659-663`, `:1315-1317`) — same entries, same order, same
 * bytes. The `session-replay-cost` harness asserts that walking the file in windows
 * reproduces the whole-file read exactly, entry for entry.
 *
 * ## The design: one primitive, two directions
 *
 * [readRange] reads a **byte range** and returns the complete entries in it. Both
 * directions are built from it:
 *
 *  - [readTail] asks for the last [maxChars] characters of the file, which is what
 *    opening a session needs and what makes first-paint cost independent of the
 *    file's size;
 *  - [readBefore] asks for the range that ends where the loaded range begins, which
 *    is what scrolling up needs.
 *
 * The range is `[from, until)`. `from` is snapped **past** a line boundary, so it
 * never sits inside a line; `until` may land inside one, and the resulting partial
 * line is dropped, because an unterminated line at a range end is not an entry. That
 * is the whole boundary rule, and it is why no window duplicates or loses an entry.
 *
 * ## The bounds
 *
 *  - [readRange] accumulates at most `maxChars` characters, keeping the **newest**,
 *    and at most `maxEntries` entries. Both are real bounds on what one step holds.
 *  - Nothing materialises the whole file: one 8 KiB chunk at a time, decoded
 *    incrementally by an [java.io.InputStreamReader] so a multi-byte character
 *    straddling two reads is decoded correctly (the defect `Utf8StreamDecoder` exists
 *    to prevent on the wire would otherwise reappear here).
 *
 * ## Framing fidelity
 *
 * pi reads a session file with `loadEntriesFromFile` (`core/session-manager.ts:503-556`):
 * `readSync` into a 1 MiB buffer, split the accumulated string on `"\n"`, parse each
 * non-blank line, skip malformed ones, and require the **first** parsed entry to be a
 * `session` header with a string `id`. This reader applies the same rules — LF only, one
 * trailing CR stripped, U+2028/U+2029 are **not** terminators, which is the trap
 * [app.pi.rpc.JsonlFramer] documents for the wire and which `String.lines()` would get
 * wrong on both channels.
 *
 * ## The one difference from pi's own reader, and why it is safe
 *
 * pi's reader has no per-line cap, so a corrupt file can make it allocate without limit;
 * this one drops a line past [DEFAULT_MAX_LINE_CHARS] and reports it in
 * [Window.complete]. The cap is 8 MiB because one image entry can legitimately be that
 * large (see [DEFAULT_MAX_LINE_CHARS]), and [Window.complete] is how a caller finds out
 * rather than silently rendering a session with a missing entry.
 *
 * `leafId` is pi's own rule: the id of the last entry **in file order** (`_buildIndex`
 * sets `leafId = entry.id` for every non-header entry, `core/session-manager.ts:975-990`).
 */
internal object SessionFileReader {

    /** Read size for the backward probe that finds a line boundary. */
    private const val PROBE_BYTES = 8 * 1024

    /** Forward read size, in characters. */
    private const val CHUNK_CHARS = 8 * 1024

    /**
     * A contiguous slice of a session file's entries, oldest first.
     *
     * @param entries every complete parseable line in the range, in file order,
     *   **excluding** the `session` header (exactly what `getEntries()` returns).
     * @param startOffset file byte offset of [entries]' first line. A later [readBefore]
     *   walks back from exactly here, and that is the only reason it is reported.
     * @param reachedStart true when [startOffset] is the file's first entry, i.e. there
     *   is no earlier history. It is **false whenever anything was withheld** — a budget
     *   stop, an entry cap, or a range that did not begin at byte 0 — because a caller
     *   that reads it as "nothing earlier exists" stops walking.
     * @param complete false when a line in the range was dropped for exceeding
     *   [DEFAULT_MAX_LINE_CHARS]. A *parse* failure does not clear it: pi skips a
     *   malformed line silently (`:507-512`), so skipping it is faithful.
     */
    data class Window(
        val entries: List<JsonObject>,
        val startOffset: Long,
        val reachedStart: Boolean,
        val complete: Boolean,
    )

    /** Total bytes in [file], or 0 when it cannot be read. */
    fun sizeOf(file: File): Long = runCatching { file.length() }.getOrDefault(0L)

    /**
     * The `session` header of [file], or null when it is not a session.
     *
     * pi's own admission test (`session-manager.ts:551-556`): the first **parseable**
     * entry must be a `session` header with a string `id`; blank and malformed lines
     * are skipped while looking (`:503-512`). Reading only the header is what makes
     * this cheap enough to run before every open.
     */
    fun readHeader(file: File): JsonObject? {
        if (!file.isFile) return null
        val budget = HEADER_BUDGET
        // The character budget is the real bound here; the entry cap must not apply, or
        // a file with more than a megabyte of entries would have its header trimmed off
        // as though it were the oldest entry.
        val range = readRange(file, 0L, budget.toLong(), budget, Int.MAX_VALUE, budget)
        for (line in range.lines) {
            if (line.isBlank()) continue
            val obj = PiJson.parseObjectOrNull(line) ?: continue
            // The first *parseable* line decides, and pi rejects the file when it is
            // not a header (`:551-556`).
            return if (obj.str("type") == "session" && obj.str("id") != null) obj else null
        }
        return null
    }

    /**
     * The entries in a scanned range: blank lines dropped, malformed lines skipped
     * (pi does the same, `session-manager.ts:507-512`), and the `session` header
     * removed.
     *
     * The header is identified by its **parsed** `type`, not by matching the text:
     * `getEntries()` filters on the field (`core/session-manager.ts:1315-1317`), and a
     * substring test would also reject a message whose text happens to contain
     * `"type":"session"`.
     */
    private fun Range.entries(): List<JsonObject> = lines.asSequence()
        .filterNot(String::isBlank)
        .mapNotNull { PiJson.parseObjectOrNull(it) }
        .filterNot { it.str("type") == "session" }
        .toList()

    /** Whether [file] is a pi session this reader can replay. */
    fun isSessionFile(file: File): Boolean = readHeader(file) != null

    /**
     * The **newest** entries of [file], bounded by [maxChars] characters and
     * [maxEntries] entries.
     *
     * The slice is `getEntries().takeLast(n)` for the `n` returned: contiguous, in
     * order, ending at the file's last entry. Returns null when the file is not a
     * session, so a caller falls back rather than showing an empty conversation.
     */
    fun readTail(
        file: File,
        maxChars: Int,
        maxEntries: Int,
        maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    ): Window? {
        val total = sizeOf(file)
        if (total <= 0L) return null
        if (readHeader(file) == null) return null
        val from = snapPastLineBoundary(file, (total - maxChars.toLong()).coerceAtLeast(0L))
        val range = readRange(file, from, total, maxChars, maxEntries, maxLineChars)
        val entries = range.entries()
        if (entries.isEmpty()) return null
        return Window(
            entries = entries,
            startOffset = range.firstOffset,
            reachedStart = from == 0L && !range.capped && !range.budgetStopped,
            complete = !range.droppedLine,
        )
    }

    /**
     * The entries that immediately precede [startOffset] — extend a loaded range
     * **backwards** by up to [maxChars] characters.
     *
     * [readTail] followed by repeated [readBefore] walks the file from its end to its
     * start in bounded steps, which is what progressive history loading is. Returns null
     * at the start of the file or when nothing precedes [startOffset].
     */
    fun readBefore(
        file: File,
        startOffset: Long,
        maxChars: Int,
        maxEntries: Int,
        maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    ): Window? {
        if (startOffset <= 0L) return null
        if (readHeader(file) == null) return null
        val from = snapPastLineBoundary(file, (startOffset - maxChars.toLong()).coerceAtLeast(0L))
        val range = readRange(file, from, startOffset, maxChars, maxEntries, maxLineChars)
        val entries = range.entries()
        if (entries.isEmpty()) return null
        return Window(
            entries = entries,
            startOffset = range.firstOffset,
            // The range's end is the next window's first line, and a line that starts
            // in this range but ends past it is dropped — so `from == 0` is the only
            // way the file's first entry can be in hand. What it is **not** is
            // sufficient, and this is where the first version of this function was
            // wrong: byte 0 says nothing about what the range withheld, and both
            // withholding rules apply here exactly as they do in `readTail` —
            // `maxEntries` (an argument this function takes) and the character budget
            // (a range whose first line alone exceeds `maxChars` stops right after
            // that line, because the first line is always admitted). Either one
            // leaves entries unread between the range's start and `startOffset`;
            // reporting `reachedStart` there tells the caller "no earlier history" and
            // the walk stops, which is precisely the failure mode
            // [Window.reachedStart] documents. Measured, not argued: the
            // `session-replay-cost` harness walks a 14-entry fixture one entry per
            // window and got 2 entries before this was fixed.
            reachedStart = from == 0L && !range.capped && !range.budgetStopped,
            complete = !range.droppedLine,
        )
    }

    /**
     * The whole file as one window.
     *
     * Deliberately **not** what the open path uses — it exists for export and for the
     * equivalence harness, which compares a whole-file read against `get_entries`.
     */
    fun readAll(file: File, maxLineChars: Int = DEFAULT_MAX_LINE_CHARS): Window? {
        val total = sizeOf(file)
        if (total <= 0L) return null
        if (readHeader(file) == null) return null
        val range = readRange(file, 0L, total, null, Int.MAX_VALUE, maxLineChars)
        val entries = range.entries()
        return Window(
            entries = entries,
            startOffset = 0L,
            reachedStart = true,
            complete = !range.droppedLine,
        )
    }

    /**
     * Every entry in [file], delivered to [onEntry] in file order, **sanitised**.
     *
     * This is the whole-session read the session-tree overlay needs without the
     * whole-session *record*: the entries come from the file line by line and each is
     * reduced to the small part that overlay reads — identity, `type`, the printed
     * fields, and a bounded text preview. Two payloads dominate a session file by size
     * and neither is ever printed, so neither is kept:
     *
     *  - an inline image's base64 (`ImageContent.data`, megabytes per photo) becomes an
     *    empty string, which leaves `PiMessage.text` flattening to the same `[image]`
     *    marker it already printed;
     *  - a text block, and an extension's `custom` payload, are capped — the row shows
     *    160 characters and its detail block 400 (`entrySummary` / `entryDetail` in
     *    `SessionTreeScreen`), so anything past [PREVIEW_CHARS] is unreadable there.
     *
     * @return false when a line was dropped for exceeding [maxLineChars], so the caller
     *   can ask pi for the whole list instead of showing a partial log as the whole one.
     */
    fun readEntries(
        file: File,
        maxLineChars: Int = DEFAULT_MAX_SCAN_LINE_CHARS,
        maxEntries: Int = Int.MAX_VALUE,
        onEntry: (JsonObject) -> Unit,
    ): Boolean {
        val total = sizeOf(file)
        if (total <= 0L) return true
        var delivered = 0
        var seenHeader = false
        val range = readRange(file, 0L, total, null, Int.MAX_VALUE, maxLineChars) { line ->
            if (line.isBlank()) return@readRange true
            val parsed = PiJson.parseObjectOrNull(line)
            if (!seenHeader) {
                // A `session` line is not an entry (`getEntries()` filters it out), and
                // nothing before it is either: pi requires the header first.
                seenHeader = true
                if (parsed == null || parsed.str("type") == "session") return@readRange true
            }
            if (delivered >= maxEntries) return@readRange false
            if (parsed == null) return@readRange true
            delivered++
            onEntry(sanitize(parsed))
            true
        }
        return !range.droppedLine
    }

    // ------------------------------------------------------------------ scanning

    /** What one [readRange] call produced. */
    private class Range(
        val lines: List<String>,
        val firstOffset: Long,
        val capped: Boolean,
        val budgetStopped: Boolean,
        val droppedLine: Boolean,
    )

    /**
     * The complete lines in `[from, until)`, oldest first.
     *
     * @param maxChars characters to accumulate; null means everything. The **oldest**
     *   lines are dropped first, because every caller wants the newest entries.
     * @param maxEntries entries to keep; the oldest are dropped first.
     * @param onLine when given, each line is handed to it and the list is not built.
     *   Returning false stops the scan. Used by [readEntries] so a whole-session scan
     *   never holds the list.
     */
    private inline fun readRange(
        file: File,
        from: Long,
        until: Long,
        maxChars: Int?,
        maxEntries: Int,
        maxLineChars: Int,
        noinline onLine: ((String) -> Boolean)? = null,
    ): Range {
        val collected = ArrayList<String>(256)
        var firstOffset = from
        var seenChars = 0L
        var stopped = false
        var dropped = false
        var eof = false

        val reader = openAt(file, from, until)
        try {
            val chunk = CharArray(CHUNK_CHARS)
            val current = StringBuilder()
            var lineStartBytes = 0L
            var skipping = false
            // `lineStartBytes` is the offset of the line currently being accumulated,
            // relative to the range start; the first line's is 0.
            var firstLine = true

            while (!stopped) {
                val got = reader.read(chunk, 0, chunk.size)
                if (got < 0) {
                    eof = true
                    break
                }
                if (got == 0) continue
                var index = 0
                while (index < got) {
                    val c = chunk[index]
                    index++
                    if (c == '\n') {
                        if (skipping) {
                            skipping = false
                            current.setLength(0)
                            lineStartBytes = reader.offsetOf(chunk, index)
                            continue
                        }
                        val line = current.toString().removeSuffix("\r")
                        current.setLength(0)
                        val thisOffset = lineStartBytes
                        lineStartBytes = reader.offsetOf(chunk, index)
                        firstLine = false
                        if (line.isBlank()) continue
                        if (maxChars != null && !collected.isEmpty() &&
                            seenChars + line.length > maxChars
                        ) {
                            stopped = true
                            break
                        }
                        // `thisOffset` is relative to the range start, so the file
                        // offset is the base plus it. Getting this wrong is how a
                        // backward window ends up 15 KB off and re-delivers entries the
                        // next window already had.
                        if (collected.isEmpty()) firstOffset = from + thisOffset
                        seenChars += line.length
                        collected += line
                        if (onLine != null && !onLine(line)) {
                            stopped = true
                            break
                        }
                        continue
                    }
                    if (skipping) continue
                    if (current.length >= maxLineChars) {
                        skipping = true
                        current.setLength(0)
                        dropped = true
                        continue
                    }
                    current.append(c)
                }
            }
        } finally {
            runCatching { reader.close() }
        }

        // "The budget ran out" is only true when the range still had bytes left: when
        // the budget-hitting line was also the last one, the scan had already read
        // everything. Reporting a budget stop there would make `readTail` refuse to say
        // `reachedStart` for a session it had just read in full, and the screen would
        // keep offering an earlier-history row with nothing behind it.
        val budgetStopped = stopped && !eof
        val capped = collected.size > maxEntries
        if (!capped) {
            return Range(
                lines = collected,
                firstOffset = firstOffset,
                capped = false,
                budgetStopped = budgetStopped,
                droppedLine = dropped,
            )
        }
        // The entry cap trims the **front**, because every caller wants the newest
        // entries, and the reported offset has to follow the trim: it is the byte the
        // next backward window will end at, so an offset left on a dropped line would
        // make that window re-deliver it.
        val kept = collected.subList(collected.size - maxEntries, collected.size).toList()
        var trimmedOffset = firstOffset
        for (i in 0 until collected.size - maxEntries) {
            trimmedOffset += collected[i].toByteArray(Charsets.UTF_8).size + 1L
        }
        return Range(
            lines = kept,
            firstOffset = trimmedOffset,
            capped = true,
            budgetStopped = budgetStopped,
            droppedLine = dropped,
        )
    }

    /**
     * An [InputStreamReader] over `file`'s bytes `[start, end)`, with a byte count and
     * an offset query.
     *
     * Bounding the reader rather than the caller's loop is what makes the range exact:
     * a `FileInputStream` after a seek has no end of its own, and
     * `Channels.newReader` reads ahead through a buffer, so a caller that only counted
     * characters would accept entries from past the range.
     */
    private class BoundedFileReader(
        private val raf: RandomAccessFile,
        private val start: Long,
        private val end: Long,
    ) : Reader() {
        private val delegate = java.nio.channels.Channels.newReader(raf.channel, Charsets.UTF_8)

        /** Bytes decoded, relative to [start]. */
        private var consumed: Long = 0L

        /** Bytes decoded before the chunk currently being walked. */
        private var beforeChunk: Long = 0L

        /**
         * The offset of the character at [index] in the chunk being walked, **relative
         * to [start]**. The caller rebases onto the file by adding its own `from`, and
         * that base is added exactly once — the same byte counted twice is what makes a
         * window hand the next window an entry it had already delivered.
         */
        fun offsetOf(chunk: CharArray, index: Int): Long {
            var bytes = 0L
            for (i in 0 until index) bytes += utf8Width(chunk[i])
            return beforeChunk + bytes
        }

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            beforeChunk = consumed
            if (start + consumed >= end) return -1
            // Clamp the **request**, not just the result: asking the buffered decoder
            // for a full chunk near the end of the range hands back characters decoded
            // from bytes past it. One byte of slack lets a multi-byte character that
            // straddles the boundary arrive whole; the decoder keeps any partial
            // sequence for the next call.
            val want = minOf(len.toLong(), end - start - consumed + 1L).toInt().coerceAtLeast(1)
            val got = delegate.read(cbuf, off, want)
            if (got <= 0) return got
            var bytes = 0L
            for (i in off until off + got) bytes += utf8Width(cbuf[i])
            consumed += bytes
            return got
        }

        override fun close() {
            runCatching { delegate.close() }
            runCatching { raf.close() }
        }
    }

    private fun utf8Width(c: Char): Long = when {
        c.code < 0x80 -> 1L
        c.code < 0x800 -> 2L
        // A surrogate pair is two `Char`s and four bytes.
        c.isHighSurrogate() || c.isLowSurrogate() -> 2L
        else -> 3L
    }

    private fun openAt(file: File, from: Long, until: Long): BoundedFileReader {
        val raf = RandomAccessFile(file, "r")
        raf.seek(from)
        return BoundedFileReader(raf, from, until)
    }

    /**
     * The first byte **after** the first LF at or after [target], or [target] when
     * there is none.
     *
     * Beginning a slice here means the partial line [target] may sit inside is not read
     * at all — no fragment to drop, and therefore no way to lose an entry to that
     * dropping. Starting *after* the byte target also guarantees the budget is covered:
     * a character is at most four bytes, so `maxChars` bytes spans at most `maxChars`
     * characters.
     */
    private fun snapPastLineBoundary(file: File, target: Long): Long {
        if (target <= 0L) return 0L
        RandomAccessFile(file, "r").use { raf ->
            var pos = target
            val buf = ByteArray(PROBE_BYTES)
            while (true) {
                raf.seek(pos)
                val got = raf.read(buf, 0, buf.size)
                if (got <= 0) return target
                for (i in 0 until got) {
                    if (buf[i] == '\n'.code.toByte()) return pos + i + 1
                }
                pos += got.toLong()
            }
        }
    }

    // ------------------------------------------------------------------ sanitising

    /**
     * The parts of one persisted entry the tree overlay reads, with the bulky parts
     * replaced by placeholders — see [readEntries].
     *
     * Deliberately *not* a new entry shape: the object keeps exactly the fields
     * `parseSessionEntry` expects, so there is still one parser and one entry union.
     * Nothing here invents, renames or reorders a field.
     */
    internal fun sanitize(entry: JsonObject): JsonObject {
        val message = entry["message"] as? JsonObject
        if (message != null) {
            val content = message["content"]
            val sanitized = if (content is JsonArray) sanitizeContent(content) else JsonArray(emptyList())
            return JsonObject(entry + ("message" to JsonObject(message + ("content" to sanitized))))
        }
        val data = entry["data"] as? JsonObject
        if (data != null && data.toString().length > PREVIEW_CHARS) {
            return JsonObject(entry + ("data" to buildJsonObject { put("truncated", JsonPrimitive(true)) }))
        }
        return entry
    }

    /**
     * One `content` array, bounded.
     *
     * An image keeps its `type` and `mimeType` (so the flattened preview still says
     * `[image]` and the block is not mistaken for something else) and loses its `data`.
     * Text keeps at most [PREVIEW_CHARS] characters in total. Thinking loses its
     * provider signature, which is opaque and unreadable here. Anything else passes
     * through, because the overlay reads `type` and `role` from blocks this does not
     * know about.
     */
    private fun sanitizeContent(content: JsonArray): JsonArray {
        var remaining = PREVIEW_CHARS
        val out = ArrayList<JsonElement>(content.size)
        for (element in content) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> {
                    val keep = block.str("text").orEmpty().take(remaining)
                    remaining -= keep.length
                    out += JsonObject(block + ("text" to JsonPrimitive(keep)))
                }
                "image" -> out += JsonObject(
                    block + ("data" to JsonPrimitive("")) + ("mimeType" to (block["mimeType"] ?: JsonPrimitive(""))),
                )
                "thinking" -> out += JsonObject(block + ("signature" to JsonPrimitive("")))
                else -> out += block
            }
        }
        return JsonArray(out)
    }

    /** pi's own `MAX_SESSION_HEADER_SCAN_BYTES` (`session-manager.ts:487-489`). */
    private const val HEADER_BUDGET = 1 shl 20

    /**
     * Per-line cap for a **replay** read: 8 MiB.
     *
     * A bound, not a policy — it exists so a corrupted file cannot make the open path
     * allocate without limit. It has to be large because of what a session contains: one
     * entry carries an inline base64 image at four characters per three bytes, so a
     * 2.5 MB phone photo is a single **3.4 MB line** and two of them in one turn are
     * 6.7 MB. A cap near that (4 MiB was the first attempt) drops the very entries the
     * reader exists to read, and does it silently: the window comes back without them.
     */
    const val DEFAULT_MAX_LINE_CHARS = 8 shl 20

    /**
     * Per-line cap for the **entry-log scan** ([readEntries]): 512 KiB.
     *
     * Deliberately far below [DEFAULT_MAX_LINE_CHARS], because that scan keeps nothing
     * but a bounded preview. A line bigger than this is in practice a single inline
     * image, whose entry is still *representable* without it — the row shows a role and
     * 160 characters of text. Dropping it reports `complete = false`, which is the
     * caller's signal to ask pi for the full list, so the row is never silently missing;
     * what is saved is holding a 6 MB line only to throw its content away.
     */
    const val DEFAULT_MAX_SCAN_LINE_CHARS = 512 shl 10

    /**
     * Characters kept from one text block, and from an extension's `custom` payload.
     *
     * 4 000: far past the 160-character row summary and the 400-character detail block
     * the only reader of this data prints, and small enough that a session of ten
     * thousand entries stays a few megabytes of retained strings rather than the session
     * file's own size. A longer block is truncated **in the preview only** — the file is
     * untouched, and the transcript (which reads the file itself) shows the full text.
     */
    private const val PREVIEW_CHARS = 4_000

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
