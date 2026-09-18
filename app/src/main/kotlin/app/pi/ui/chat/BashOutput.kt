package app.pi.ui.chat

/**
 * The `!` command panel's output accumulator, bounded the way pi bounds its own.
 *
 * pi's bash component keeps the whole stream in memory as an array of lines and, on
 * every render, truncates what it hands to the terminal **at the tail** —
 * `modes/interactive/components/bash-execution.js:93-98`:
 *
 * ```js
 * const fullOutput = this.outputLines.join("\n");
 * const contextTruncation = truncateTail(fullOutput, {
 *     maxLines: DEFAULT_MAX_LINES,   // 2000
 *     maxBytes: DEFAULT_MAX_BYTES,   // 50 * 1024
 * });
 * ```
 *
 * That bound is the reason this file exists: the app used to accumulate the stream with
 * `output + delta` and hand the whole thing to a single `Text`, so a chatty command
 * (`yes`, a build log) grew without bound in both memory and layout cost. The tail is
 * what pi keeps, and it is also the half a person reads: a command's outcome is at the
 * end.
 *
 * Pure and Android-free so `tools/run-app-pure-checks.sh` → `bash-output` can pin the
 * window's arithmetic — that the bound holds, that it is the *tail* that survives, and
 * that nothing is invented at the front.
 */

/**
 * pi's own display bound for a bash panel (`core/tools/truncate.js:11`
 * `DEFAULT_MAX_BYTES = 50 * 1024`).
 *
 * The same number the `bash` response is truncated at, so bounding the streamed
 * accumulation by it cannot show the user *less* than the response will: pi's own
 * answer carries at most this much output (`truncateTail` with `DEFAULT_MAX_LINES` /
 * `DEFAULT_MAX_BYTES`) plus the "output truncated, full output in <path>" sentence this
 * app already paints from `BashRun.truncated`.
 *
 * Lines are the other half of pi's bound (`DEFAULT_MAX_LINES = 2000`); it is not
 * reproduced here because 50 KiB of terminal-width lines is already far under 2000 of
 * them, and a line-count rule would have to split the whole buffer to count.
 */
internal const val BASH_OUTPUT_MAX_CHARS = 50 * 1024

/**
 * Append [delta] to [builder], keeping at most [maxChars] characters **at the tail**.
 *
 * The cut lands at a line boundary: the first `'\n'` at or after the character the bound
 * would drop, so the painted text never starts mid-line with the tail of a word. The
 * result is therefore at most [maxChars] characters — never more, which is the property
 * the harness pins.
 *
 * @return true when the front of the buffer was dropped, which is what the caller turns
 *   into the panel's own truncation sentence (`BashRun.truncated`). pi is silent about
 *   this mid-run; this repository's rule is that a truncation a user can see must be
 *   sayable, and the same sentence already exists for the response's own truncation.
 */
internal fun appendTailBounded(builder: StringBuilder, delta: String, maxChars: Int): Boolean {
    if (delta.isEmpty()) return false
    builder.append(delta)
    if (builder.length <= maxChars) return false
    val cut = builder.length - maxChars
    val newline = builder.indexOf("\n", cut)
    // `indexOf` from the cut: the first line boundary the bound has already passed.
    // A buffer with no newline after the cut keeps exactly `maxChars` characters — a
    // single line longer than the whole bound is shown at its tail, which is the same
    // choice `tailLines` makes for the tool cards.
    builder.delete(0, if (newline >= 0) newline + 1 else cut)
    return true
}
