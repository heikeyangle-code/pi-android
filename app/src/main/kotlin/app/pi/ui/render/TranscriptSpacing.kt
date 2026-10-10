package app.pi.ui.render

import androidx.compose.ui.unit.Dp
import app.pi.rpc.DateSeparator
import app.pi.rpc.TranscriptItem
import app.pi.rpc.UserMessage
import app.pi.ui.theme.PiTypographyProfile

/**
 * 转录里一行**上方**的"消息级间距"是哪一档。
 *
 * 三档来自 `app.appearance.typography`（`PiTypographyProfile` 的三个值）：
 * 同一条消息内部用列表自己的 `blockSpacing`，换人那一行用更大的间距 ——
 * 也就是每个聊天界面都在用的"同一发送者小间距、换人时大间距"
 * （Flutter 的 `chatview` 把它做成 `messageGroupSpacing`）。pi 的终端没有这一层，
 * 所以这三个数**是本 App 自己的决定**，依据写在 `PiTypographyProfile` 的表里；
 * 经典档三个数都是 8，等于这一层不存在。
 */
internal enum class TranscriptGap {
    /** 一条用户消息之前。 */
    UserMessage,

    /** 一条助手消息的**第一行**之前。 */
    AssistantMessage,

    /** 同一条消息内部。 */
    SameMessage,
}

/**
 * [items] 第 [index] 行的间距档。
 *
 * ## 为什么按"顺序"判定，而不是按行 key 的前缀
 *
 * 任务书建议用 key（"助手那一组行共用同一个前缀，例如 `a1` / `a1-1`"）。那个形状
 * **只存在于历史重放那一条路**：`rpc/Transcript.kt` 的 `onHistoryMessage` 让一个
 * pi 条目的多个块共用 `entryId`，所以是 `a1`、`a1-1`、`a1-2`。实时那条路给**每个块
 * 一个新 key**（`Transcript.kt:987` 的 `nextKey`：前缀 + 当前毫秒 + 自增序号，
 * `AssistantText` / `ThinkingBlock` 都在 `:1379`、`:1398` 用它），同一个助手回合的
 * 两行因此是 `assistant-1712345678901-41` 与 `assistant-1712345678901-42`，
 * 没有任何共同前缀可以剥。按前缀判定会在实时流式时把**每一行**都当成一条新消息，
 * 而用户看到的问题恰好发生在流式的时候。
 *
 * 两路都成立的事实是**顺序**：pi 的一条助手回合就是紧跟一条用户消息之后的那一段行。
 * 所以判据是"这一行在列表里的前一行是不是用户消息"，而这不是猜——它就是
 * `LazyColumn` 渲染的那张表本身（`ChatScreen` 传的是 `visibleItems`，与
 * `firstOfRun` / `lastOfRun` 用的是同一份数据、同一个下标）。
 *
 * 日期分隔行**自己**永远取 [TranscriptGap.SameMessage]：它是分隔符，不是任何一条消息的
 * 第一行。判据里说的"透明"是另一件事 —— 找"这一行之前的上一条消息"时**往回跳过**连续的
 * [DateSeparator]（它们由 `maybeDaySeparator` 插在消息之前），所以"用户消息 → 日期分隔 →
 * 助手首行"这一段里的助手首行仍然算换人。两件事分开写，是为了让换人那一格**恰好出现一次**：
 * 若分隔行也取换人间距，它和它下面那条消息的第一行会各要大间距，用户看到的是两格。
 *
 * 列表第一行没有上一行 → [TranscriptGap.SameMessage]，即今天的间距：会话顶部不该
 * 凭空多出一段"换人间距"。
 */
internal fun transcriptGap(items: List<TranscriptItem>, index: Int): TranscriptGap {
    val item = items.getOrNull(index) ?: return TranscriptGap.SameMessage
    if (item is DateSeparator) return TranscriptGap.SameMessage
    if (item is UserMessage) return TranscriptGap.UserMessage
    var previousIndex = index - 1
    while (previousIndex >= 0 && items[previousIndex] is DateSeparator) previousIndex--
    return when (items.getOrNull(previousIndex)) {
        null -> TranscriptGap.SameMessage
        is UserMessage -> TranscriptGap.AssistantMessage
        else -> TranscriptGap.SameMessage
    }
}

/**
 * 这一档在 [profile] 下的间距。
 *
 * 换人的两档**同一个数**：用户消息之前与助手首行之前没有理由不同，业界也只有一个
 * `messageGroupSpacing`（见 `PiTypographyProfile` 表下面那段）。三档因此是
 * "同一条消息内部 < 段间距 < 换人间距"（经典档除外，它就是今天的一个 8）。
 *
 * 一个扩展函数而不是 `TranscriptGap` 上的字段：枚举属于转录（`ui/render`），
 * 数值属于预设（`ui/theme`），而 `ui/render` 本来就依赖 `ui/theme`（`PiPalette`），
 * 反过来不成立 —— 这个方向没有环。
 */
internal fun PiTypographyProfile.gapFor(gap: TranscriptGap): Dp = when (gap) {
    TranscriptGap.UserMessage -> messageSpacing
    TranscriptGap.AssistantMessage -> messageSpacing
    TranscriptGap.SameMessage -> blockSpacing
}
