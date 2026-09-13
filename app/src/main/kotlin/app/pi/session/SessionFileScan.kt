package app.pi.session

import java.io.Reader

/**
 * A **bounded** line scan over a session file: at most [budget] characters are read
 * from the reader, and no single line is ever materialised beyond `maxLineChars`.
 *
 * ## Why this is not `BufferedReader.readLine()`
 *
 * `readLine()` has no size argument: it accumulates until it finds a terminator, so
 * the peak allocation is whatever the longest line happens to be. The session index
 * reader capped its *total* scan (`PiSessionStore.headerScanBudget`, pi's own
 * `MAX_SESSION_HEADER_SCAN_BYTES`, `core/session-manager.ts:487-489`) but checked
 * that cap only *after* `readLine()` returned — i.e. after the whole line was in
 * memory, twice (the reader's buffer and the `String`), and then a third time as a
 * `JsonObject` tree. One line is enough to defeat the budget: pi's session files are
 * JSONL of *messages*, and a single `message` entry legitimately carries a tool
 * result (pi's own cap is 50 KB each) or an inline base64 image — multi-megabyte
 * lines are normal content, not corruption.
 *
 * The same scan also runs per file on the resume path
 * ([PiSessionStore.mostRecentForResume] → `readHeaderCwd`), where it is the *only*
 * thing standing between "list the sessions" and "read everything".
 *
 * ## The three bounds
 *
 *  - [budget] counts **everything** consumed from the reader, not just the lines
 *    that were parsed, so the work and the allocation are both capped at `budget`
 *    (+ one read chunk). A file that is one enormous line therefore ends the scan,
 *    which is the honest meaning of "the scan was truncated".
 *  - `maxLineChars` is the per-line cap. A line longer than it is **skipped**: its
 *    characters are dropped as they arrive, its terminator is consumed, and the scan
 *    continues with the next line. Nothing is reported for it, because there is no
 *    honest way to answer "which entry was it" from a prefix — and for a *summary*
 *    (counts, timestamps, the first user text) an entry too large to hold is not
 *    worth holding.
 *  - the reader is closed by the caller (this takes an already-open [Reader]).
 *
 * ## Framing, and the one deliberate difference
 *
 * Lines end at `'\n'`, with a single trailing `'\r'` stripped — the same rule
 * [app.pi.rpc.JsonlFramer] applies to the wire, and the one pi's own writer produces
 * (`modes/rpc/jsonl.ts:11`; `session-manager.ts` appends with a trailing newline).
 * `BufferedReader.readLine` additionally treats a lone `'\r'` as a terminator, which
 * JSONL written by pi never contains (a CR inside a JSON string is escaped). Stated
 * because that is the one input where the two readers disagree.
 *
 * ## Testability
 *
 * Nothing here touches Android, a `File` or a global: it is `java.io` plus
 * arithmetic, so `tools/run-app-pure-checks.sh`'s `sessions` harness drives it
 * directly on a bare JVM, including the over-long-line case a device is needed to
 * *observe* but not to *prove*.
 */
internal object SessionFileScan {

    /** Read size. Small enough to keep the transient array negligible on a phone. */
    private const val CHUNK_CHARS = 8 * 1024

    /**
     * Scan [reader] line by line.
     *
     * @param budget characters to consume in total; the scan stops once this many
     *   have been read (including characters of skipped lines).
     * @param maxLineChars lines longer than this are dropped rather than accumulated.
     * @param onLine called once per complete, non-dropped line, with the `'\r'`
     *   stripped and without the terminator. Returning `false` stops the scan
     *   immediately (used by the header probe, which wants one line and nothing
     *   after it).
     * @return the number of characters actually consumed, so the caller can decide
     *   whether the scan reached the end of the file or its budget (which is how
     *   [PiSessionStore] chooses between "the last activity I saw" and "the file's
     *   mtime").
     */
    fun forEachLine(
        reader: Reader,
        budget: Int,
        maxLineChars: Int,
        onLine: (String) -> Boolean,
    ): Long {
        val chunk = CharArray(CHUNK_CHARS)
        val current = StringBuilder()
        var consumed = 0L
        var skippingLongLine = false
        var reachedEof = false

        while (consumed < budget) {
            val want = minOf(chunk.size.toLong(), budget - consumed).toInt()
            val read = runCatching { reader.read(chunk, 0, want) }.getOrDefault(-1)
            if (read < 0) {
                reachedEof = true
                break
            }
            // No progress with room to read: not EOF, but nothing more to take. Stop
            // rather than spin, and leave the partial tail unflushed — a line that was
            // never terminated must not be handed to the caller as if it were one.
            if (read == 0) break
            consumed += read

            var index = 0
            while (index < read) {
                val c = chunk[index]
                index++
                if (c == '\n') {
                    if (skippingLongLine) {
                        skippingLongLine = false
                        current.setLength(0)
                        continue
                    }
                    val line = current.toString().removeSuffix("\r")
                    current.setLength(0)
                    if (!onLine(line)) return consumed
                    continue
                }
                if (skippingLongLine) continue
                if (current.length >= maxLineChars) {
                    skippingLongLine = true
                    current.setLength(0)
                    continue
                }
                current.append(c)
            }
        }

        // An unterminated tail exists only at EOF. A scan that stopped on the budget
        // must not hand out a half line: the caller's contract is "this was truncated".
        if (reachedEof && !skippingLongLine && current.isNotEmpty()) {
            onLine(current.toString().removeSuffix("\r"))
        }
        return consumed
    }
}
