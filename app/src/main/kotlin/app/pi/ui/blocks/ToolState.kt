package app.pi.ui.blocks

import app.pi.rpc.ToolCall
import app.pi.rpc.ToolDiff
import app.pi.rpc.ToolStatus
import app.pi.rpc.TranscriptItem

/**
 * 工具卡的两个**纯**词表，和它们唯一的消费者：一个 run 的分组计划。
 *
 * ## 为什么这些住在 `ToolBlockChrome.kt` 之外
 *
 * 它们的每一个字都决定卡片上看到什么（状态词、状态字形、「这次没跑」的判定），
 * 而决定它们的那份代码在 Compose 里 —— 本机编译不了 Compose，CI 才编译得动
 * （`tools/run-app-pure-checks.sh` 的前言）。把**一个 import 都不需要 Compose** 的
 * 词表搬到这里，就能在裸 JVM 上把这些字钉死；`widget-payload` 那一项用的是同一个
 * 手法（它编译 `ToolOutputParse.kt`，也是 Compose-free 的纯文件）。
 *
 * 反过来，**上色**的函数（`toolStateTone` / `toolContainerColor` / `toolAccentColor`）
 * 留在 `ToolBlockChrome.kt`：它们拿 `PiPalette` 和 `Color`，搬过来只会让这里编译不过。
 * 两个文件同属 `app.pi.ui.blocks`，调用点一个都不用改。
 */

/**
 * The four states a tool card can be in — three from pi, one the app has to read.
 *
 * pi's wire carries three ([ToolStatus]). The fourth is what a call a **policy or
 * approval layer stopped before it ran** looks like: pi has no "rejected" axis at
 * all, and reports the blocker's own sentence as an *error* result with no execution
 * behind it (`packages/agent/src/agent-loop.ts:644-655`; the app's own gate is the
 * blocker in this build, `app/src/main/assets/pi-extensions/pi-android-permission-gate.ts`).
 * `06 §4` gives that case its own word, glyph and colour because it is not a
 * failure — nothing ran — and `docs/extension-compatibility.md` §6.2 already asks the
 * app to render it as policy rather than error. This is the rendering half of that
 * gap; the other half (a transcript item that carries the distinction) would live in
 * the protocol layer.
 */
internal enum class ToolState { Running, Success, Failed, Rejected }

/** Status is a word, never only a colour (docs/pi-android-ui-spec.md §9). */
internal fun toolStateLabel(state: ToolState): String = when (state) {
    ToolState.Running -> "运行中"
    ToolState.Success -> "成功"
    ToolState.Failed -> "失败"
    ToolState.Rejected -> "被拒"
}

/** The glyph beside the status word, for the same reason (`06 §4`). */
internal fun toolStateGlyph(state: ToolState): String = when (state) {
    ToolState.Running -> "…"
    ToolState.Success -> "✓"
    ToolState.Failed -> "✗"
    ToolState.Rejected -> "⊘"
}

/**
 * The sentences that mean "this call never ran", in the words the layer that stopped
 * it used.
 *
 * pi's own fallback is `Tool execution was blocked` (`agent-loop.ts:644`), and the
 * app's permission gate writes the rest — its deny branch, its hard shell refusal,
 * its no-dialog branch and its headless branch. They are matched as a **prefix** of
 * the result text, which is exactly what pi puts there (`createErrorToolResult` sets
 * the whole text to the reason), so a tool that merely *mentions* one of these
 * sentences in its output cannot be mistaken for a blocked call.
 *
 * The list is deliberately conservative: an unrecognised error is a failure
 * (`06 §4`'s `✗ 失败`), never a guess at 被拒.
 */
private val BLOCKED_REASONS = listOf(
    "Tool execution was blocked",
    "用户拒绝了这个设备操作：",
    "设备策略拒绝这条 Shell 命令",
    "确认对话框不可用",
    "没有确认通道（ctx.hasUI=false）时默认拒绝",
)

/** Whether a result text is one of [BLOCKED_REASONS] — see that list for the rule. */
internal fun toolBlocked(output: String): Boolean {
    val text = output.trimStart()
    return BLOCKED_REASONS.any { text.startsWith(it) }
}

/**
 * Which of the four states a row is in.
 *
 * A blocked call reaches the app as `isError = true` (`docs/extension-compatibility.md`
 * §6.2), so the only signal separating [ToolState.Rejected] from [ToolState.Failed] is
 * the blocker's sentence — the same kind of read pi's text already needs elsewhere in
 * `ToolOutputParse.kt`, and the one this doc recommends.
 */
internal fun toolStateOf(item: ToolCall): ToolState = when {
    item.status == ToolStatus.Pending -> ToolState.Running
    item.status != ToolStatus.Error -> ToolState.Success
    toolBlocked(item.output) -> ToolState.Rejected
    else -> ToolState.Failed
}

/**
 * A row's contribution to its **run**'s ring colour — the four states above, plus the
 * diff card, which is the one node on the rail that is not a state (`±`).
 *
 * ## Why a second enum instead of [ToolState]
 *
 * The run shell's ring has to answer one question — 「这一段里的行是不是同一个状态?」 —
 * and the diff card is a fifth answer that is not a tool state (a diff has no
 * success/failure of its own: `DiffBlock` draws it `borderMuted` by definition). Keeping it
 * as its own tiny enum means the plan below compares values without knowing anything about
 * tools, and the two places that turn a value into a colour (`railRingColor` in the Compose
 * chrome) get an exhaustive `when` from the compiler.
 */
internal enum class RailState { Running, Success, Failed, Rejected, Diff }

/**
 * What one transcript row contributes to its run, or null when the row is **not on the
 * rail** — i.e. anything that is not a `ToolCall`/`ToolDiff` (a thinking row, prose, a user
 * bubble, a date separator). A null row breaks the run, exactly as
 * `firstOfRun = previous !is ToolCall && previous !is ToolDiff` does at the call site
 * (`screens/ChatScreen.kt`, and `BlockRenderer`'s KDoc).
 */
internal fun railStateOf(item: TranscriptItem): RailState? = when (item) {
    is ToolCall -> when (toolStateOf(item)) {
        ToolState.Running -> RailState.Running
        ToolState.Success -> RailState.Success
        ToolState.Failed -> RailState.Failed
        ToolState.Rejected -> RailState.Rejected
    }
    is ToolDiff -> RailState.Diff
    else -> null
}

/**
 * One rail row's place in its run: whether it is the run's first/last row, and the state the
 * **run** paints its ring with — or null when the run mixes states.
 *
 * [ring] is a property of the whole run, not of the row, which is why it cannot be derived
 * from the row's neighbours one at a time: 「一个 run 里全是同一状态 → 用该状态色 @35%；
 * 混着成功/失败 → 退回中性」 (`差异表` §2 第 9 行, and the v5 board's `.run[data-ring=…]`).
 * The one place that can answer it is the list that holds the order.
 */
internal data class ToolRunSlot(
    val firstOfRun: Boolean,
    val lastOfRun: Boolean,
    val ring: RailState?,
)

/**
 * The run plan for a whole transcript: one slot per row, in order.
 *
 * A run is the maximal sequence of consecutive non-null rows (`BlockRenderer`'s KDoc: a
 * `ToolCall`/`ToolDiff` run), and its ring is the single state **every** row in it shares —
 * otherwise null (mixed). That is the whole function: one pass, no per-row rescan, so the
 * cost is O(rows) with no allocation per row beyond the returned list. It runs once per
 * transcript publication (keyed on the list's identity in `ChatScreen`), and it compares
 * enum references only — it never touches a row's text, which is what makes it cheap next to
 * the search scan the same screen deliberately does *not* run per publication.
 *
 * Every slot of a run carries the **same** [ToolRunSlot.ring]: a run's ring is not a property
 * of its first row that the others inherit by position (a LazyColumn composes rows out of
 * order as the reader scrolls, so a row cannot look "back" at the run's first row and be sure
 * it has been computed).
 */
internal fun toolRunPlan(rows: List<RailState?>): List<ToolRunSlot> {
    val plan = MutableList(rows.size) { ToolRunSlot(firstOfRun = true, lastOfRun = true, ring = null) }
    var start = 0
    while (start < rows.size) {
        val first = rows[start]
        if (first == null) {
            start++
            continue
        }
        var end = start
        var uniform = true
        while (end + 1 < rows.size && rows[end + 1] != null) {
            end++
            if (rows[end] != first) uniform = false
        }
        val ring = if (uniform) first else null
        for (i in start..end) {
            plan[i] = ToolRunSlot(firstOfRun = i == start, lastOfRun = i == end, ring = ring)
        }
        start = end + 1
    }
    return plan
}
