package app.pi.rpc

/**
 * Frame codec for pi's `--mode rpc` transport.
 *
 * pi speaks strict LF-delimited JSONL. Two properties drive this implementation,
 * and getting either wrong corrupts frames in ways that only show up with
 * unusual model output:
 *
 *  1. **Split on `'\n'` only.** `U+2028` (LINE SEPARATOR) and `U+2029`
 *     (PARAGRAPH SEPARATOR) are legal *unescaped* inside JSON strings, and
 *     models do emit them. `BufferedReader.readLine`, `String.lines()` and
 *     `lineSequence()` all treat them as line breaks, so none of them may be
 *     used here. pi's own `src/modes/rpc/jsonl.ts` documents the same trap.
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

    private var pending = StringBuilder()
    private var dropping = false

    /** Number of over-long records thrown away since the last [reset]. */
    var droppedRecords: Long = 0L
        private set

    /** Characters currently buffered for the in-flight record. */
    val bufferedChars: Int get() = pending.length

    /**
     * Consume a chunk of decoded text and return every complete record it
     * completed, in order.
     *
     * Blank records (empty, or whitespace-only after a trailing CR is stripped)
     * are dropped **on purpose, and this is not what pi's reader does**: pi's
     * `attachJsonlLineReader` emits every LF-delimited span including `""`, and
     * `rpc-mode.ts` answers a blank line with a
     * `{"command":"parse","success":false}` error. pi never writes a blank
     * record, so forwarding ours would only manufacture parse failures; the
     * difference is recorded here because the framing tolerance looks identical
     * from the outside.
     */
    fun feed(chunk: CharSequence): List<String> {
        var out: MutableList<String>? = null
        val n = chunk.length
        var i = 0
        while (i < n) {
            // 一段不含换行的区间整段处理：以前逐字符 `append`，一条几十 MB 的记录就是几千万次调用。
            var nl = i
            if (chunk is String) {
                nl = chunk.indexOf('\n', i).let { if (it < 0) n else it }
            } else {
                while (nl < n && chunk[nl] != '\n') nl++
            }
            if (nl > i && !dropping) {
                // 与逐字符版等价：容量刚好够就收下（恰好 maxRecordChars 的记录仍然合法），
                // 多出一个字符就整条丢弃。
                if (nl - i > maxRecordChars - pending.length) {
                    dropping = true
                    clearPending()
                } else {
                    pending.append(chunk, i, nl)
                }
            }
            if (nl < n) {
                val record = if (dropping) {
                    dropping = false
                    droppedRecords++
                    null
                } else {
                    pending.toString().removeSuffix("\r")
                }
                clearPending()
                if (record != null && record.isNotBlank()) {
                    (out ?: ArrayList<String>(4).also { out = it }).add(record)
                }
                i = nl + 1
            } else {
                i = n
            }
        }
        return out ?: emptyList()
    }

    /**
     * 清空缓冲，并且**归还内存**：`setLength(0)` 不会缩小 `StringBuilder` 的底层数组，
     * 一条 40 MB 的记录过后，这个 framer 会在整个会话期间一直占着 ~100 MB 的空数组。
     */
    private fun clearPending() {
        if (pending.capacity() > SHRINK_ABOVE_CHARS) {
            pending = StringBuilder()
        } else {
            pending.setLength(0)
        }
    }

    /**
     * 放弃正在累积的这一条（比如处理它时内存不足）：缓冲清空并释放，
     * 这一行余下的内容直接丢弃，到下一个换行才恢复，并计入 [droppedRecords]。
     */
    fun abandonCurrent() {
        dropping = true
        clearPending()
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

    companion object {
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

        /** 缓冲底层数组超过这个容量（字符）就在记录结束时丢掉重建。 */
        private const val SHRINK_ABOVE_CHARS = 1 shl 20
    }
}
