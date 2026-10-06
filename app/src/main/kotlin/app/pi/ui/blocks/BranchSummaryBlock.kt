package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.BranchSummary
import app.pi.ui.components.PiBilledCostLine
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing

/**
 * `branch-summary` (docs/pi-android-ui-spec.md §7.4): a `customMessageBg` card
 * labelled 分支摘要 with the branch id. Clicking either jumps to that branch
 * (when the host provides [onClick]) or expands the summary in place.
 *
 * 标签下面是**一句说清这张卡是什么的话**（「跳转离开一条分支时留下的摘要，不是分叉。」）。
 * 它补的是用户的原话「不知道那是干啥的」：`分支摘要` 四个字说的是它属于哪一族卡片，没有说它是
 * 什么时候、被什么动作写下来的，而"分叉"与"分支"又只差一个字。上游 TUI 在同一个位置也有
 * 一句关于这张卡自己的话（`branch-summary-message.ts:46-56`），只是那句讲的是怎么展开。
 *
 * The summary is a two-line plain-text preview while collapsed and markdown once
 * expanded — the same split pi's `branch-summary-message.ts:41-56` makes.
 *
 * [showBilledCost] is pi's `showCacheMissNotices`; when on, pi prints the
 * summarization's own usage as a `branch_summary` billing row
 * (`modes/interactive/interactive-mode.ts:4053-4067`, dispatched at `:3953`, fed on replay at `:4035`).
 * See [PiBilledCostLine] for why the text is pi's English verbatim.
 */
@Composable
fun BranchSummaryBlock(
    item: BranchSummary,
    modifier: Modifier = Modifier,
    onClick: ((BranchSummary) -> Unit)? = null,
    showBilledCost: Boolean = false,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(false) }

    BlockColumn(modifier) {
        BlockCard(
            color = palette.customMessageBg,
            borderColor = palette.customMessageLabel.copy(alpha = 0.35f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (onClick != null) "跳转分支" else "展开摘要") {
                        if (onClick != null) onClick(item) else expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentStripe(palette.customMessageLabel, PiSpacing.accentStripe)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "分支摘要",
                        // The custom card's label slot is the machine face, which is what makes
                        // this card family read as one thing: `06 §3` 构件 9 says the 自定义消息卡
                        // （分支摘要 / 技能 / 扩展条目共用）carries a 「左 3px customLabel 条 + **等宽
                        // 标签**」, and v2's `CustomHead` renders that label `mono t12` for both of
                        // the Chinese ones it draws (`direction-b-v2.html:943-951`, used at
                        // `:2601` 「分支摘要」 and `:2622` 「技能」). The two siblings already did
                        // this — `SkillInvocationBlock.kt:78` and `HookMessageBlock.kt:57` both use
                        // `monoSmall`. `labelLarge` was the only UI-face label in the family, so
                        // the same slot had two voices depending on which card you looked at.
                        style = PiTheme.text.monoSmall,
                        color = palette.customMessageLabel,
                    )
                    // 一句话说清这张卡是什么 —— 用户的原话是「不知道那是干啥的」，而且把它和
                    // 「分叉」记混了（「那分叉分支啊，有时候都搞不清」）。两句都是真话，且都有 pi
                    // 的依据：这张卡由 `branchWithSummary` 在**离开一条分支**时写下
                    // （`session-manager.ts:1600-1625`；TUI 的对应动作是 `/tree` 之后回答
                    // "Summarize branch?"，`interactive-mode.ts:5516-5551`），而**分叉不会产生它**
                    // —— `/fork` 只是把原会话到那一点为止的条目复制进一个新文件
                    // （`agent-session-runtime.ts:262-350`；`docs/sessions.md:26-31` 那张表把
                    // `/tree` / `/fork` / `/clone` 三件事分开写）。
                    //
                    // 放在**折叠态也看得见**的位置：这正是这张卡片自己无法自证的那件事，而 pi 的
                    // TUI 在同一个位置也有一句话（`branch-summary-message.ts:46-56`：
                    // "Branch summary (ctrl+o to expand)"）—— 那句说的是怎么展开，这句说的是它
                    // 是什么。
                    Text(
                        text = "跳转离开一条分支时留下的摘要，不是分叉。",
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                    val branchId = item.branchId
                    if (!branchId.isNullOrBlank()) {
                        Text(
                            text = branchId,
                            style = PiTheme.text.monoSmall,
                            // F12 (`docs/rendering-review.md`): `dim` is 2.89:1 on
                            // this card's `customMessageBg`, under spec §9's 3:1
                            // metadata floor. `muted` is 4.20:1 on a card surface,
                            // and the token stays pi's own.
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // F19 (`docs/rendering-review.md`): now that the host supplies
                // `onClick`, the row itself jumps to the session tree (spec §7.4
                // branch-summary 点击跳转), so the label has to carry the expand —
                // otherwise the summary body would become unreachable. A nested
                // `clickable` consumes the tap before the row's.
                ExpandLabel(
                    expanded = expanded,
                    // F28: the row jumps to the branch (spec §7.4 点击跳转), so the
                    // expand affordance stays a distinct target — but it is the
                    // shared gesture, not a fourth spelling of it.
                    modifier = Modifier.toggleContent(expanded, { expanded = !expanded }, rowKey = item.key),
                )
            }
            if (expanded) {
                // Expanded: markdown. pi's expanded branch is a `Markdown` over
                // the summary (`packages/coding-agent/src/modes/interactive/components/branch-summary-message.ts:41-45`),
                // so headings, lists and fences in a model-written summary read
                // as structure instead of as source.
                // The base colour of that `Markdown` is `customMessageText`, not `text`:
                //   `color: (text) => theme.fg("customMessageText", text)`
                // (`components/branch-summary-message.js:34-36`). This card and its three
                // siblings share one background, so they share one foreground too.
                PiMarkdownText(
                    markdown = item.summary.ifEmpty { "（无摘要）" },
                    modifier = Modifier.fillMaxWidth(),
                    textColor = palette.customMessageText,
                )
            } else {
                // Collapsed: plain text cut to the same preview this block has
                // always shown. pi's collapsed branch is likewise plain
                // (`branch-summary-message.ts:46-56`), and a markdown renderer
                // has no `maxLines` to carry the preview with.
                ProseText(
                    text = item.summary.ifEmpty { "（无摘要）" },
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.customMessageText,
                    maxLines = COLLAPSED_SUMMARY_LINES,
                )
            }
        }
        // F18: pi's `branch_summary` billing row, gated by its own switch.
        if (showBilledCost) {
            PiBilledCostLine(
                label = "Branch summary",
                usage = item.usage,
                modifier = Modifier.fillMaxWidth().padding(top = PiSpacing.tiny),
            )
        }
    }
}

/**
 * The collapsed line count, unchanged from this block's original preview. pi has
 * no numeric equivalent here — a terminal line is not a wrapped phone line — so
 * the existing value is kept rather than invented.
 */
private const val COLLAPSED_SUMMARY_LINES = 2
