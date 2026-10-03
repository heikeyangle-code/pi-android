package app.pi.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the retained-history budget counts one session entry.
 *
 * ## Why this is a file of its own
 *
 * `PiSessionViewModel` is an `AndroidViewModel`, so nothing in it can be executed on
 * this machine (`tools/typecheck.sh` has no Android runtime). The *arithmetic* the cap
 * depends on lives here — how big one entry counts as, and how much of the retained copy
 * may be evicted — so `tools/run-app-pure-checks.sh` → `history-retention` can pin it on
 * a bare JVM.
 *
 * ## What the number is
 *
 * **It is the serialised length.** The question the cap answers is "how much would
 * holding this entry cost", and an inline image's base64 is the single biggest thing an
 * entry carries, whether or not its own text field is the thing that holds it. The
 * constant term covers the ids and envelope of a small entry, which the transcript row
 * also holds.
 *
 * ## 为什么数字仍然是「序列化长度」，而不再是「序列化一次」
 *
 * 这个数喂的是保留预算与驱逐时机（[evictablePrefix]），所以它**必须逐位不变**。改前的写法是
 * `entry.toString()` / `message.toString()` —— 把整棵 JSON 写成一个 `String` 再取 `.length`。
 * 数字是对的，代价却是：一条 6 MB 内联图片的消息要**先物化一个 6 MB 的串**（还有 `StringBuilder`
 * 扩容过程中的那份翻倍拷贝）。窗口里每条这样的 entry 都付一次，而它只是要一个长度。
 *
 * 现在改为 [serializedLength]：走一遍 [JsonElement]，**按 kotlinx 写 JSON 的同一条规则累加字符
 * 数**，一个字节的 payload 都不复制。输出与 `element.toString()` 逐字符相同 —— 转义规则是
 * kotlinx 的 `StringJsonWriter` 那一条（`"` `\` 与 `\t\b\n\r\f` 两字符、其余 `< 0x20` 的六字符
 * `\u00xx`、非 ASCII 原样输出），对象是 `{` + `"key":value` + `,` + `}`、数组是 `[` + 值 + `,` + `]`、
 * 非字符串 primitive 原样写 `content`（`JsonNull` 的 content 就是 `null`）。这不是论证，是
 * `history-retention` harness 里对随机 JSON（转义、控制字符、非 ASCII、代理对、嵌套、大数组）
 * 做的**恒等 fuzz**：`serializedLength(e) == e.toString().length` 每一条都要成立。
 *
 * 由此带来的另一个后果是：这个测量本身已经可以放在帧线程上还不心疼。**但三个调用点仍然留在
 * `Dispatchers.IO`** —— 它们与一次文件读或一次 RPC 回落在同一个 `withContext` 里（“要不要保留
 * 这个窗口”本来就是同一个决定），把它们搬回帧线程不是这次改动的目标，也不该顺手做。
 */
internal fun entryChars(entry: JsonObject): Long {
    val type = (entry["type"] as? JsonPrimitive)?.content
    val message = entry["message"] as? JsonObject
    if (type == "message" && message != null) {
        return serializedLength(message) + ENTRY_ENVELOPE_CHARS
    }
    return serializedLength(entry) + ENTRY_ENVELOPE_CHARS
}

/**
 * [element] 被 kotlinx 序列化后的字符数，**不物化那个字符串**。
 *
 * 与 `element.toString().length` 恒等；等价性的依据与 fuzz 见 [entryChars] 的 KDoc 与
 * `HistoryRetentionCheck`。
 *
 * `internal` 而不是 `private`：fuzz 要直接对着这个函数断言，而不是对着一个测试专用的副本 ——
 * 副本绿而生产红正是这类改动最容易出的假绿。
 */
internal fun serializedLength(element: JsonElement): Long = when (element) {
    is JsonObject -> {
        // `{}` 加每个 `"key":value`，再加 n-1 个逗号。
        var total = 2L
        var first = true
        for ((key, value) in element) {
            if (!first) total += 1L
            first = false
            total += 2L + escapedChars(key) + 1L + serializedLength(value)
        }
        total
    }
    is JsonArray -> {
        var total = 2L
        var first = true
        for (item in element) {
            if (!first) total += 1L
            first = false
            total += serializedLength(item)
        }
        total
    }
    // `JsonNull` 也走这里：它不是字符串，`content` 就是 `null` 四个字符。`JsonElement` 只有这
    // 三个子类（sealed），所以这里没有兜底分支 —— 将来 kotlinx 多一个子类时，编译会红着要一个
    // 答案，而不是悄悄走一条算错的默认值。
    is JsonPrimitive -> if (element.isString) 2L + escapedChars(element.content) else element.content.length.toLong()
}

/**
 * [value] 写进 JSON 字符串字面量后、两个引号之间的字符数。
 *
 * 逐字符对应 kotlinx `StringJsonWriter` 的转义表：`"` 与 `\` 两字符；`\t \b \n \r \f` 用短转义
 * 两字符；其余码位 `< 0x20` 的控制字符写成 `\u00xx` 六字符；**别的都原样输出** —— 包括 `0x7F`、
 * 非 ASCII、U+2028/U+2029 与落单的代理项（`String.length` 按 UTF-16 code unit 数，所以那个宽度
 * 正好是 1）。
 *
 * 初始值就是 `value.length`（「全部原样输出」），只有 ASCII 里那几个字符要**加**：两字符转义加
 * 1、`\u00xx` 加 5。所以这是一张 128 项的「额外字符数」表加一次遍历 —— 会话窗口里最大的一串是
 * 内联图片的 base64，一个要转义的字符都没有，走的就是「每个字符查一次 0」这条最快的路。
 * （实测 2.5 MB 串：`when` 分支版 8.43 ms → 查表版 3.59 ms，本机 amd64。）
 */
private fun escapedChars(value: String): Long {
    var total = value.length.toLong()
    for (c in value) {
        val code = c.code
        // 128 及以上一律原样输出（含所有多字节字符与代理项），表不覆盖它们。
        if (code < 128) total += ESCAPED_EXTRA[code]
    }
    return total
}

/**
 * 每个 ASCII 码位写进 JSON 字符串后**比原字符多出**几个字符。
 *
 * 0 = 原样；1 = `\"` `\\` `\t` `\b` `\n` `\r` `\f` 这类两字符短转义；5 = 其余控制字符的
 * `\u00xx`（六字符）。表就是上面那条规则的展开，fuzz 断的是整条链路的恒等，不是这张表本身。
 */
private val ESCAPED_EXTRA = IntArray(128) { code ->
    val c = code.toChar()
    when {
        c == '"' || c == '\\' -> 1
        c == '\t' || c == '\b' || c == '\n' || c == '\r' || c == '\u000C' -> 1
        code < 0x20 -> 5
        else -> 0
    }
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
 * it (`app/src/main/kotlin/app/pi/session/SessionFileReader.kt:232-275`).
 *
 * The reader's own `Window.reachedStart` is trustworthy — it is
 * `from == 0L && !range.capped && !range.budgetStopped` (`:272`), so a budget stop
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
     * `SessionFileReader.DEFAULT_MAX_LINE_CHARS` (`Window.complete == false`, `:273`).
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
