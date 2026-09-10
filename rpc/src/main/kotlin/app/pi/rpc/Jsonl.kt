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

    private val pending = StringBuilder()
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
        for (i in chunk.indices) {
            val c = chunk[i]
            if (c == '\n') {
                val record = if (dropping) {
                    dropping = false
                    droppedRecords++
                    null
                } else {
                    pending.toString().removeSuffix("\r")
                }
                pending.setLength(0)
                if (record != null && record.isNotBlank()) {
                    (out ?: ArrayList<String>(4).also { out = it }).add(record)
                }
                continue
            }
            if (dropping) continue
            if (pending.length >= maxRecordChars) {
                dropping = true
                pending.setLength(0)
                continue
            }
            pending.append(c)
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
            pending.setLength(0)
            droppedRecords++
            return emptyList()
        }
        if (pending.isEmpty()) return emptyList()
        val record = pending.toString().removeSuffix("\r")
        pending.setLength(0)
        return if (record.isNotBlank()) listOf(record) else emptyList()
    }

    fun reset() {
        pending.setLength(0)
        dropping = false
        droppedRecords = 0L
    }

    companion object {
        /**
         * 8 MiB. pi's largest legitimate single record is a tool result, which
         * pi itself truncates at 50 KB / 2000 lines; the headroom is for large
         * image payloads echoed back in transcript entries.
         */
        const val DEFAULT_MAX_RECORD_CHARS = 8 * 1024 * 1024
    }
}
