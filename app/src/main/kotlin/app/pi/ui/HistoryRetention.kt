package app.pi.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the retained-history budget counts one session entry.
 *
 * ## Why this is a file of its own
 *
 * `PiSessionViewModel` is an `AndroidViewModel`, so nothing in it can be executed on
 * this machine (`tools/typecheck.sh` has no Android runtime). The measured cost of this
 * function is the reason the view model changed at all — it serialises an entry to take
 * the length of the result — so the *arithmetic* the cap depends on lives here, where
 * `tools/run-app-pure-checks.sh` → `history-retention` can pin it on a bare JVM.
 *
 * ## What the number is
 *
 * **It is the serialised length.** Measuring an entry means rendering it back to JSON
 * and taking that string's length, because the question the cap answers is "how much
 * would holding this entry cost" — and an inline image's base64 is the single biggest
 * thing an entry carries, whether or not its own text field is the thing that holds it.
 * The constant term covers the ids and envelope of a small entry, which the transcript
 * row also holds.
 *
 * ## Why the caller must stay off the frame thread
 *
 * The measurement is expensive: rendering one 6 MiB base64 entry costs ~135 ms and a
 * 4000-entry text window ~460 ms on a desktop JVM (measured), and a phone is slower. All
 * three call sites used to sum it on the frame thread — `viewModelScope` is
 * `Dispatchers.Main.immediate` — so opening a session, and every later "load earlier"
 * (which re-summed the whole retained set), blocked the UI for hundreds of milliseconds
 * to seconds. A cheaper measure would have to re-implement kotlinx's JSON escaping and
 * would silently change what the budget means, so the cost is paid on
 * `Dispatchers.IO` instead.
 */
internal fun entryChars(entry: JsonObject): Long {
    val type = (entry["type"] as? JsonPrimitive)?.content
    val message = entry["message"] as? JsonObject
    if (type == "message" && message != null) {
        return message.toString().length.toLong() + ENTRY_ENVELOPE_CHARS
    }
    return entry.toString().length.toLong() + ENTRY_ENVELOPE_CHARS
}

/**
 * [entryChars] over a window.
 *
 * **This is additive by construction**, and that is the property the view model relies
 * on when a batch arrives: `expandEarlierHistory` used to re-sum the whole retained list
 * on every step (quadratic in how far the reader had scrolled); it now adds the newly
 * read window's own count, which is only the same number because
 * `entryCharsOf(a) + entryCharsOf(b) == entryCharsOf(a + b)`. The harness pins exactly
 * that rather than trusting it.
 *
 * A loop rather than `sumOf`: the sum is over thousands of elements and this avoids one
 * boxed accumulator step per element.
 */
internal fun entryCharsOf(entries: List<JsonObject>): Long {
    var total = 0L
    for (entry in entries) total += entryChars(entry)
    return total
}

/**
 * Characters added to every entry's serialised length.
 *
 * The envelope the transcript row holds besides the payload the two branches above
 * measure: the entry's own `id`, `type` and `timestamp`, plus the row's own fields.
 * A constant rather than a measurement because it is an allowance, not a reading.
 */
private const val ENTRY_ENVELOPE_CHARS = 64L

// --------------------------------------------------------------- backward readings
//
// 「聊天内容多了，回到聊天顶部，之前的内容都被截掉了，都没了。」
//
// That report is this file's other half. `expandEarlierHistory` used to collapse two
// different answers into one: "the budget is reached" and "the range above could not be
// read" both left the step empty-handed, and the caller read *any* empty-handed step as
// "this is the start of the file" — so it set `reachedStart = true`, `hasEarlier` went
// false, the 「加载更早」 row disappeared for the rest of the session and every entry
// above the loaded range became unreachable. A read that failed impersonated a file
// that had nothing above it, which is the one thing this repository's four readings
// (a count / nothing found / cannot read / not read yet) are not allowed to do.
//
// So the readings are named here, one variant each, and [cursorAfter] is the *only*
// place that turns one into a cursor. The budget is deliberately **not** a reading: see
// [RetainedChunk] and `evictablePrefix` below for what it does instead.

/**
 * What one backward step found, as `SessionFileReader.readBefore`'s contract describes
 * it (`app/src/main/kotlin/app/pi/session/SessionFileReader.kt:218-260`).
 *
 * The reader's own `Window.reachedStart` is trustworthy — it is
 * `from == 0L && !range.capped && !range.budgetStopped` (`:257`), so a budget stop
 * inside the reader clears it — and it is the *only* flag that may establish the start
 * of the file. Everything the reader answers with `null` is a separate reading here.
 */
internal sealed interface BackwardRead {

    /**
     * A **count**: the window arrived, and `reachedStart` is the reader's own flag on it.
     *
     * `true` means the range reached byte 0 with nothing withheld, i.e. the reader is
     * holding the file's first entry and there is genuinely nothing above.
     */
    data class Window(val reachedStart: Boolean) : BackwardRead

    /**
     * **Cannot read**: the range withheld a line longer than
     * `SessionFileReader.DEFAULT_MAX_LINE_CHARS` (`Window.complete == false`, `:258`).
     *
     * Not "nothing found" and not a count: `complete = !range.droppedLine` says there is
     * something above it that did not arrive. It must not stop the walk as though the
     * file ended, and it must not be silent either — the row above the transcript says so.
     */
    data object WithheldLine : BackwardRead

    /**
     * **Nothing found**, established honestly: the loaded range already begins at byte 0,
     * so nothing can be above it.
     *
     * This is the only null answer that may set `reachedStart`: byte 0 is the file.
     */
    data object AtFileStart : BackwardRead

    /**
     * **Cannot read**: `readBefore` returned null with the loaded range starting *past*
     * byte 0, so there are bytes above it that it did not deliver.
     *
     * This is the reading the defect collapsed into "the conversation starts here". The
     * reader answers null for a file that is no longer a session, for a range whose every
     * line is unparseable, and for a range that is one withheld line — none of which is
     * the start of the file.
     */
    data object UnreadableRange : BackwardRead

    /**
     * **Cannot read**: the read itself threw — a file that vanished, was truncated, or
     * lost its permission while pi was rewriting it. Carries the short reason that goes
     * on screen.
     */
    data class ReadFailed(val detail: String) : BackwardRead
}

/**
 * Why the last backward step delivered nothing, in words a reader of the chat can act on.
 *
 * A non-null stop on the cursor is what makes `HistoryCursor.hasEarlier` false, which is
 * what stops the scroll effect from re-asking forever after a failure
 * (`ChatScreen.kt:1312`). It is **not** a claim about the file: `reachedStart` stays
 * false, so the 「加载更早」 row stays on screen and the reader can press it again.
 *
 * Public rather than internal because it is a field of the public `HistoryCursor`; the
 * rest of this half of the file stays internal.
 */
data class HistoryStop(val reason: String)

/** What the cursor must become after [BackwardRead]. */
internal data class CursorDecision(val reachedStart: Boolean, val stop: HistoryStop? = null)

/**
 * The whole rule the defect was about, in one place a bare JVM can run.
 *
 * **`reachedStart` may only come from the two readings that establish it**: a window the
 * reader flagged `reachedStart`, or a range that already begins at byte 0. No failure
 * reading sets it, and the budget is not a reading at all — which is what makes
 * "could not read" stop impersonating "there is nothing above" structurally rather than
 * by review.
 */
internal fun cursorAfter(read: BackwardRead): CursorDecision = when (read) {
    is BackwardRead.Window -> CursorDecision(reachedStart = read.reachedStart)
    BackwardRead.AtFileStart -> CursorDecision(reachedStart = true)
    BackwardRead.WithheldLine -> CursorDecision(
        reachedStart = false,
        stop = HistoryStop("上面有一段内容太大，读不出来"),
    )
    BackwardRead.UnreadableRange -> CursorDecision(
        reachedStart = false,
        stop = HistoryStop("这个文件读不出更早的内容了"),
    )
    is BackwardRead.ReadFailed -> CursorDecision(
        reachedStart = false,
        stop = HistoryStop("读取失败：${read.detail}"),
    )
}

/**
 * The 「加载更早」 row's label — the one place the four readings become words — or null
 * when there is no row to draw at all.
 *
 * The row is an overlay, not list item 0 (`docs/scroll-diagnosis.md` §3.4, D51), but its
 * text is unchanged by that: it still owns the `hiddenCount > 0` case, where the rows
 * above are already in memory and no file read is involved.
 *
 * Why this is a function here and not a `when` in the screen: the defect was a *label*
 * claiming something the reader's own state did not believe ("加载更早" is fine, "已到开头"
 * was never said out loud but `hasEarlier = false` deleted the row), so the mapping from
 * reading to words is pinned by the `history-retention` harness rather than left to
 * review. Two properties are load-bearing:
 *
 *  - **at the start of the file there is no row**, so the screen can never offer to load
 *    earlier when the reader believes it is at the start;
 *  - **"cannot read" and "loadable" are different sentences**, so a failure is visible
 *    instead of looking like an ordinary next step (and instead of looking like the end
 *    of the conversation).
 *
 * The label never mentions the retention budget: where the retained copy lives is an
 * internal matter, and the walk continues past it (see `evictablePrefix`).
 *
 * @param hiddenCount rows already in memory above the rendered window — a local reveal,
 *   not a read, and the one label that carries a count.
 * @param cursorKnown false when the screen has no `HistoryCursor` yet (a transcript from
 *   a source that is not this reader). Then there is nothing to read and no row: the same
 *   answer as before this function existed, where `hasEarlier` was only ever true with a
 *   cursor (`PiSessionViewModel.kt:397`).
 */
internal fun earlierRowText(
    hiddenCount: Int,
    cursorKnown: Boolean,
    loading: Boolean,
    reachedStart: Boolean,
    stop: HistoryStop?,
): String? {
    if (hiddenCount > 0) return "加载更早的 $hiddenCount 条"
    if (!cursorKnown) return null
    if (loading) return "正在读取更早的内容…"
    if (stop != null) return "读不到更早的内容（${stop.reason}）"
    return if (reachedStart) null else "加载更早的内容"
}

// --------------------------------------------------------------- the retained copy
//
// 「为什么有 64 兆的上限呢？没有上限不行吗？」
//
// The session file has no limit — every entry pi ever wrote is on disk, and nothing here
// deletes any of it. What is bounded is how much of it this process holds *in RAM at
// once*, because an unbounded transcript is how the app gets OOM-killed, and that is how
// the reader really loses the conversation. The bound is on a **copy**: the entry list
// `PiSessionViewModel` keeps to re-seed the projection (`loadedHistory`, `:700-714`).
// The rows on screen are never dropped, and the evicted copy is read back from the file
// the next time the projection is rebuilt — so the walk up is never a dead end.

/**
 * One window of retained entries, as it was read: the entries, where they start in the
 * file, and what they cost against the budget.
 *
 * @param startOffset the byte offset of [entries]' first line. It is the only reason this
 *   type exists besides the entries: an evicted chunk's offset is the *low* end of the
 *   range that has to be read back, and it is a line start because it came from
 *   `SessionFileReader.Window.startOffset` (`SessionFileReader.kt:118`).
 * @param chars [entryCharsOf] over [entries], carried so the running total can be
 *   adjusted by subtraction instead of re-summing the whole retained list (the
 *   additivity the harness pins).
 */
internal data class RetainedChunk(
    val entries: List<JsonObject>,
    val startOffset: Long,
    val chars: Long,
)

/**
 * How many of the retained chunks, counting from the **oldest** end, an eviction may
 * never touch.
 *
 * The reader is at the top of the loaded range on the frame a backward step runs — the
 * screen asks for one only at `atTop` with nothing left to reveal
 * (`ChatScreen.kt:1305-1313`), and the press path only when `hiddenCount == 0`
 * (`:2131-2135`) — so the rows currently on screen are the **oldest** retained entries.
 * One chunk is the whole of that guarantee the ViewModel can make without knowing the
 * viewport: the chunk it is reading is never a candidate, and the approach the reader is
 * heading into is above the retained range entirely, so it is not in the list at all.
 * (The screen's own window and the visible rows are pinned by the `tail-follow` harness;
 * this function only has to promise that a step never drops the chunk the reader is on.)
 */
internal const val HISTORY_PROTECTED_CHUNKS = 1

/**
 * How many of the **newest** retained chunks must be dropped to bring what is left inside
 * [budgetChars] — 0 when nothing may or need be dropped.
 *
 * Chunks are passed newest first, and eviction is from that end because that is the end
 * *farther from the reader*: a backward step is the reader walking towards older entries,
 * so everything below the viewport is what they have left behind, and it is also what the
 * file can hand back cheapest — the range is contiguous and its low end is a line start.
 *
 * The protected tail of [chunks] (the oldest `protectedOldest` of them) is never a
 * candidate, whatever the budget says: dropping the chunk under the reader's eyes is the
 * one loss no bookkeeping could make good, because the rebuild would simply not have
 * those rows. If that leaves the retained copy over budget, it stays over budget — a
 * correct screen beats a smaller number.
 *
 * This is a pure function with no file and no Android in it for the reason the rest of
 * this file is: the harness feeds it a visible range, an approach zone and a candidate
 * set, and a counterexample (a visible chunk among the victims) has to fail.
 */
internal fun evictablePrefix(
    chunks: List<RetainedChunk>,
    budgetChars: Long,
    protectedOldest: Int = HISTORY_PROTECTED_CHUNKS,
): Int {
    val droppable = (chunks.size - protectedOldest).coerceAtLeast(0)
    if (droppable == 0) return 0
    var total = 0L
    for (chunk in chunks) total += chunk.chars
    var dropped = 0
    while (dropped < droppable && total > budgetChars) {
        total -= chunks[dropped].chars
        dropped++
    }
    return dropped
}
