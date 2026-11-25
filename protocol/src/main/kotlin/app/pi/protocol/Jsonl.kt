package app.pi.protocol

/**
 * Frame codec for SDK Host stdio frames and JSONL session files.
 *
 * Both the SDK Host wire protocol and pi's session logs use strict LF-delimited
 * JSONL. Two properties drive this implementation, and getting either wrong
 * corrupts frames in ways that only show up with unusual model output:
 *
 *  1. **Split on `'\n'` only.** `U+2028` (LINE SEPARATOR) and `U+2029`
 *     (PARAGRAPH SEPARATOR) are legal *unescaped* inside JSON strings, and
 *     models do emit them. `BufferedReader.readLine`, `String.lines()` and
 *     `lineSequence()` all treat them as line breaks, so none of them may be
 *     used here.
 *
 *  2. **Strip a single trailing `'\r'`.** Some transports (and pipes on
 *     Windows) produce CRLF; the record itself is the LF-delimited span.
 *
 * The framer is also the last line of defence against an unbounded buffer: a
 * record longer than [maxRecordChars] is discarded rather than accumulated,
 * and counted in [droppedRecords] so the UI can say so instead of dying.
 *
 * Instances are **not** thread-safe; drive one from a single reader coroutine.
 */
class JsonlFramer(private val maxRecordChars: Int = DEFAULT_MAX_RECORD_CHARS) {

    private var pending = StringBuilder(INITIAL_CAPACITY)
    private var dropping = false

    /** Number of over-long records thrown away since the last [reset]. */
    var droppedRecords: Long = 0L
        private set

    /** Characters currently buffered for the in-flight record. */
    val bufferedChars: Int get() = pending.length

    fun feed(chunk: CharSequence): List<String> {
        if (chunk.isEmpty()) return emptyList()
        var out: MutableList<String>? = null
        val text = chunk as? String
        val len = chunk.length
        var start = 0
        while (start < len) {
            val nl = if (text != null) text.indexOf('\n', start) else indexOfLf(chunk, start, len)
            if (nl < 0) {
                if (!dropping) {
                    val remaining = len - start
                    if (pending.length + remaining > maxRecordChars) {
                        dropping = true
                        clearPending()
                    } else {
                        pending.append(chunk, start, len)
                    }
                }
                break
            }
            if (dropping) {
                dropping = false
                droppedRecords++
                clearPending()
            } else {
                val end = if (nl > start && chunk[nl - 1] == '\r') nl - 1 else nl
                val record = if (pending.isEmpty()) {
                    if (end - start > maxRecordChars) {
                        droppedRecords++
                        null
                    } else {
                        chunk.subSequence(start, end).toString()
                    }
                } else {
                    val segLen = end - start
                    if (pending.length + segLen > maxRecordChars) {
                        droppedRecords++
                        clearPending()
                        null
                    } else {
                        if (segLen > 0) pending.append(chunk, start, end)
                        val s = if (segLen == 0 && pending.isNotEmpty() && pending[pending.length - 1] == '\r') {
                            pending.substring(0, pending.length - 1)
                        } else {
                            pending.toString()
                        }
                        clearPending()
                        s
                    }
                }
                if (record != null && record.isNotBlank()) {
                    (out ?: ArrayList<String>(4).also { out = it }).add(record)
                }
            }
            start = nl + 1
        }
        return out ?: emptyList()
    }

    /**
     * Flush at EOF. pi always terminates its records with LF, so a non-empty
     * tail here means the engine died mid-write; it is returned once so the
     * caller can attempt a parse (and report a framing error if it fails).
     */
    fun flush(): List<String> {
        if (dropping) {
            dropping = false
            clearPending()
            droppedRecords++
            return emptyList()
        }
        if (pending.isEmpty()) return emptyList()
        val record = pending.toString().removeSuffix("\r")
        clearPending()
        return if (record.isNotBlank()) listOf(record) else emptyList()
    }

    fun reset() {
        clearPending()
        dropping = false
        droppedRecords = 0L
    }

    /**
     * Reset [pending] and release oversized backing `char[]` storage so a single large image or
     * session-replay record cannot permanently pin tens of megabytes in heap.
     */
    private fun clearPending() {
        if (pending.capacity() > SHRINK_THRESHOLD_CHARS) {
            pending = StringBuilder(INITIAL_CAPACITY)
        } else {
            pending.setLength(0)
        }
    }

    private fun indexOfLf(cs: CharSequence, from: Int, len: Int): Int {
        for (i in from until len) {
            if (cs[i] == '\n') return i
        }
        return -1
    }

    companion object {
        private const val INITIAL_CAPACITY = 4 * 1024
        private const val SHRINK_THRESHOLD_CHARS = 256 * 1024
        /**
         * 64 MiB. pi's largest legitimate single record is a tool result, which pi
         * itself truncates at 50 KB / 2000 lines; this cap exists for the one
         * payload pi does *not* truncate: an inline base64 image, at four
         * characters per three bytes, echoed back inside a `message_start` /
         * `message_end` event and inside one entry of a `get_entries` response.
         *
         * The number is derived from what the composer is allowed to send, not
         * chosen: the app compresses every attachment to the model's own inline
         * limits (`maxWidth`/`maxHeight` and a base64 ceiling — pi's defaults are 2000
         * and 4.5 MB, `utils/image-resize-core.ts` in the pinned engine, and since 0.87
         * they are per-model) and caps the base64 of **one message's** images at this
         * cap minus a framing allowance, so a legal message can be 14 pi-default-maximum
         * images and its record approaches 64 MiB. The sender's arithmetic lives in
         * `AttachmentBudget` (`app.pi.ui.screens`); if this constant moves, that one moves
         * with it, because a legal message whose own echo is over this cap is a message
         * the app cannot read back.
         *
         * The cost of the size is real and is not paid only by legal records: this
         * is a **cumulative** bound on one record, so a malformed or over-long
         * record buffers up to 64 MiB before [JsonlFramer] gives up on it. The
         * buffering is incremental (one `feed` chunk at a time) and the record is
         * discarded, not truncated, once it passes — [JsonlFramer.droppedRecords]
         * counts it — but the peak allocation is 64 MiB of `pending` plus the chunk
         * that crossed the line, not the chunk size.
         *
         * **This number has now moved twice for the same reason, and the history is the
         * argument for not nudging it again without one.** It was 8 MiB, which was too
         * small to read back what the app was allowed to send — two 5 MB photos were
         * 16 MB of base64, so the user's own message could not be read and the
         * conversation would not open. It became 32 MiB to buy that back. It is 64 MiB
         * now: the bound it trades away is **peak allocation on one malformed record**,
         * and what it buys is 14 images in a message instead of 7. Both halves of that
         * trade are real, and the memory half is the one nobody notices until it bites.
         */
        const val DEFAULT_MAX_RECORD_CHARS = 64 * 1024 * 1024
    }
}
