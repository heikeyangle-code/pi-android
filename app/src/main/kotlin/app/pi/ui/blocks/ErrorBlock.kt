package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pi.rpc.ErrorText
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `error-text` (docs/pi-android-ui-spec.md §7.4): a `toolErrorBg` card with a full-height
 * `error` rule down its left edge — `✗ 出错了`, the sentence, then the raw detail.
 *
 * ## It must not look like a tool card, because it is not one
 *
 * The card used to share the tool card's silhouette: a plate with a 1 px ring and a *short*
 * 3 dp accent bar at the top of a header row. On a device that read as the same component —
 * a failed turn looked like one more tool call — while the fact the card carries is different
 * in kind: **a tool card reports a call and has a node on the execution rail; an error is not
 * a call at all.** Three things follow, and only the third is a change this file makes:
 *
 *  1. **It stands on the page axis (`06 §2` 屏水平 14), not the rail's 26 dp inset.** That is
 *     already true by construction: [railStateOf] answers `null` for an `ErrorText`, so no
 *     `ToolRailFrame` is wrapped around this block and [BlockColumn] gives it the page margin
 *     the `LazyColumn` hands every non-rail row.
 *  2. **It draws no rail node**, for the same reason — a node is the *state of a step*.
 *  3. **The 3 dp bar now runs the card's whole height** ([ErrorRule]): at 20 dp it was the
 *     titled cards' stripe, which is a header accent, and an accent that stops a third of the
 *     way down reads as decoration. Edge to edge it is a **rule**, and a red rule flush against
 *     a `toolErrorBg` ground is a shape no tool card has. The ring keeps `error @ 45 %` — one
 *     step harder than the tool card's 35 %, a difference the code already had.
 *
 * ## What the card *shows* is unchanged
 *
 * Deliberately: this is a change of **outline**, not of content. `item.message` is still the
 * provider's own sentence, drawn in full at the prose step in `error` and wrapping wherever it
 * is long; `item.detail`, when pi sent one, is still the machine face in `bodyOnTool` under it.
 * There is no fold, no `详情` and no one-line clamp: an error the reader has to tap to finish
 * reading is a worse error card, and a clamped sentence hides the one line of a 402 body that
 * says *why*. The card is as tall as its text and no taller.
 *
 * The `✗ 出错了` pair is `06 §4`'s triple encoding for this block (符号 + 字 + 色), and it is
 * what the card is scanned for. The ground is the token itself rather than a weakened copy,
 * matching `06 §2`'s 错误块 row — which also restores `bodyOnTool`'s contrast guarantee, since
 * that derived token is computed against the full `toolErrorBg`.
 *
 * The spec's recovery path (「重试 / 换模型 / 查看详情」, §4.9) is not reachable from
 * this block today: the app has no retry action, so F19 deleted the dead
 * `onRetry` parameter and the button it gated. 换模型 is reachable from the AppBar
 * chip / model row; a real 重试 needs an action in `ui/PiSessionViewModel.kt`.
 */
@Composable
fun ErrorBlock(
    item: ErrorText,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val detail = item.detail

    BlockColumn(modifier) {
        BlockCard(
            color = palette.toolErrorBg,
            borderColor = palette.error.copy(alpha = 0.45f),
            // The rule is flush with the card's left edge, so the card itself holds no
            // interior padding: every row insets itself with `ErrorRowStart` / [ERROR_ROW_END],
            // which is what the design's `.ehead{padding:0 10px 0 12px}` and
            // `.msg{padding:0 12px 8px}` describe — 12 from the **card's** edge, of which the
            // rule already owns the first 3.
            padding = PaddingValues(0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top,
            ) {
                ErrorRule(palette.error)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // The card's floor, on the column rather than on the last line: the
                        // rule fills this column's whole height (`IntrinsicSize.Min` below), and
                        // the design's `.rule{top:0;bottom:0}` runs to the card's bottom edge,
                        // not to the baseline of the last row.
                        .padding(bottom = ERROR_BODY_BOTTOM),
                ) {
                    ErrorHeader()
                    // The header above already says 出错了, so an empty message is left
                    // out rather than repeated: pi prints the sentence it was given, and
                    // nothing when there is none.
                    //
                    // `error`, because every error **sentence** pi prints carries that token and
                    // none of them uses the body colour: the assistant-side error is
                    // `theme.fg("error", `Error: ${errorMsg}`)`
                    // (`components/assistant-message.js:153`), and the same token paints pi's
                    // three chat-level error texts (`interactive-mode.js:2232`, `:2795`, `:3522`)
                    // as well as the tool renderers' own error branches
                    // (`renderers/edit.js:67`, `write.js:122`). This sentence used to be the one
                    // error text in the app painted `text`.
                    if (item.message.isNotBlank()) {
                        ProseText(
                            text = item.message,
                            color = palette.error,
                            modifier = Modifier.padding(start = ErrorRowStart, end = ERROR_ROW_END),
                        )
                    }
                    if (!detail.isNullOrBlank()) {
                        // F13 (`docs/rendering-review.md`): raw error detail is body
                        // text on a tool-error surface, so it takes the variant that
                        // clears §9's 4.5:1 floor.
                        MonoText(
                            text = detail,
                            color = palette.bodyOnTool,
                            modifier = Modifier.padding(
                                start = ErrorRowStart,
                                end = ERROR_ROW_END,
                                top = PiSpacing.gutter,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The card's left rule: **3 dp wide and as tall as the card**, which is the whole point (the
 * bar it replaces was `PiSpacing.accentStripe`, 20 dp, a header accent).
 *
 * `height(IntrinsicSize.Min)` on the parent row is what makes `fillMaxHeight` mean "the card's
 * height" rather than "the incoming constraint's": the row measures its own content first and
 * this box then fills that. Same pair as `ChatSheets`' usage separator and
 * `PiPackagesScreen`'s own error surface.
 */
@Composable
private fun ErrorRule(color: Color) {
    Box(
        modifier = Modifier
            .width(PiSpacing.stripe)
            .fillMaxHeight()
            .background(color),
    )
}

/**
 * Where the header and the body lines start, measured from **inside** the rule: the design's
 * `padding-left:12px` is from the card's edge, and the rule already owns the first 3 dp.
 */
private val ErrorRowStart = PiSpacing.cardPadding - PiSpacing.stripe

/** The design's `.ehead{padding:0 10px 0 12px}` right inset. */
private val ERROR_ROW_END = 10.dp

/**
 * The card's bottom inset (`.msg{padding:0 12px 8px}`). A literal rather than a token step: it
 * is the card's floor, not a gap between two of its own parts.
 */
private val ERROR_BODY_BOTTOM = 8.dp

/**
 * The header row's height. The design's `.ehead{height:24px}`, and the same 24 the folded tool
 * row uses — the two cards are scanned the same way, so they hold the same line box. A
 * **minimum**, not a fixed height: `app.appearance.fontScaleDelta` and the system font scale can
 * make a 12 sp line box taller than 18 dp, and a hard height would clip it rather than grow.
 */
private val ERROR_HEADER_MIN_HEIGHT = 24.dp

/**
 * `✗ 出错了` and nothing else. There is no `详情` beside it: the card has nothing folded away
 * for one to reveal (see the block's KDoc) — the sentence and the raw detail are both in the
 * body below, in full.
 */
@Composable
private fun ErrorHeader() {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ERROR_HEADER_MIN_HEIGHT)
            .padding(start = ErrorRowStart, end = ERROR_ROW_END),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "✗",
            style = PiTheme.text.mono,
            color = palette.error,
            maxLines = 1,
        )
        Spacer(Modifier.width(PiSpacing.gutter))
        Text(
            text = "出错了",
            style = PiTheme.text.meta,
            color = palette.error,
            maxLines = 1,
        )
    }
}
