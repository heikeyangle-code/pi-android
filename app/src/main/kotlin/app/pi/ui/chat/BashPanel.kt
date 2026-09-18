package app.pi.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.ui.BashRun
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * The `!` / `!!` bash panel.
 *
 * pi renders a running command as a **bordered block** in the chat container
 * (`modes/interactive/components/bash-execution.js:26-43`):
 *
 * ```js
 * 27:  const colorKey = excludeFromContext ? "dim" : "bashMode";
 * 28:  const borderColor = (str) => theme.fg(colorKey, str);
 * 32:  this.addChild(new DynamicBorder(borderColor));        // 上边：整宽
 * 37:  const header = new Text(theme.fg(colorKey, theme.bold(`$ ${command}`)), 1, 0);
 * 43:  this.addChild(new DynamicBorder(borderColor));        // 下边：整宽
 * ```
 *
 * and its output body is `muted` in **both** states (`:106` expanded, `:111` collapsed):
 * `availableLines.map((line) => theme.fg("muted", line))`.
 *
 * The same facts are carried here from the other side of the RPC boundary: the command and the
 * exit status come from the `bash` response, the output from `bash_execution_update` events
 * (`agent-session.ts:3027`) because pi emits no event for the completed result.
 *
 * `maxLines`-free scrolling rather than a transcript row on purpose: this is
 * *pending* state — pi only folds the run into the conversation on the next
 * prompt (`recordBashResult` → `_flushPendingBashMessages`), so drawing it inside
 * the transcript would show context the model has not received yet.
 *
 * ## pi 的、v2 的、我们的
 *
 * - **pi 的**：上下两条整宽边框，颜色 `dim`/`bashMode`（[bashPanelEdges]）；`$ command` 标题同一
 *   token **且加粗**（`:37` `theme.bold`）；输出正文 `muted`；状态行**只在 cancelled / error 时
 *   存在**（[statusText]）。运行中的指示器在 pi 是 loader（`:40`，转圈 + muted 文字）——本 App
 *   保持 **Stop 按钮**：`06 §5` 不允许循环动画，而这条面板是运行中的 `!` 命令唯一能停下来的地方
 *   （`07` D40.6）。
 * - **v2 的**：卡壳几何（圆角 12、`cardBg`、`PiShapes.cardInner`）与 `进入/不进上下文` 这半句 ——
 *   pi 根本不写它：它在建标题前就把 `!!` 剥掉了（`interactive-mode.js:2503-2504`
 *   `const command = isExcluded ? text.slice(2).trim() : text.slice(1).trim();`），只让边框颜色
 *   说明这件事。我们保留这半句，因为本仓库自己的 §9 / `06 §4` 要求「颜色不能是唯一信号」，而
 *   「这次跑进不进上下文」正是这条面板要报告的事实（`07` D43）。
 * - **我们的**：没有别的 —— 没有 loader、没有耗时读数、没有新 token。
 */
@Composable
fun BashPanel(
    run: BashRun,
    onAbort: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    // pi: an excluded run (`!!`) is dimmed, a context-carrying one uses bashMode
    // (`bash-execution.js:27` `const colorKey = excludeFromContext ? "dim" : "bashMode";`).
    // This one token paints the borders **and** the header, exactly as pi's `colorKey` does.
    //
    // pi's *update* path rebuilds the header with `fg("bashMode", …)` and no `colorKey` at all
    // (`bash-execution.js:100`), i.e. an excluded run loses its dim as soon as it produces
    // output. That is an upstream inconsistency, not a rule: this app rebuilds the panel from
    // `run` on every composition, so the constructor's rule (`:27`/`:37`) is the only one it
    // has — and the one pi's own comment states.
    val accent = if (run.excludeFromContext) palette.dim else palette.bashMode
    Surface(
        modifier = modifier.fillMaxWidth().bashPanelEdges(accent),
        color = palette.cardBg,
        shape = PiShapes.cardInner,
            ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$ ${run.command}",
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.mono.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                )
                if (run.running) {
                    IconButton(onClick = onAbort) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "停止命令",
                            tint = palette.error,
                        )
                    }
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭输出",
                            tint = palette.muted,
                        )
                    }
                }
            }
            if (run.output.isNotEmpty()) {
                Text(
                    text = run.output,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    style = PiTheme.text.monoSmall,
                    // `muted`, because pi's panel paints its body with that token in both states
                    // (`bash-execution.js:106` expanded, `:111` collapsed:
                    // `availableLines.map((line) => theme.fg("muted", line))`). It used to be
                    // `toolOutput`, which is the **tool card's** body token — a different
                    // component with a different ground (`toolPendingBg`/…), and the two only
                    // agree in pi's built-in themes, so an imported theme showed it.
                    color = palette.muted,
                )
            }
            val status = statusText(run)
            if (status != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusTone(run, palette),
                )
            }
        }
    }
}

/**
 * pi's completion line — and, on a clean run, **not a line at all**.
 *
 * pi builds its status from exactly three parts (`bash-execution.js:131-158`), each of which is
 * optional, and joins whatever survives:
 *
 * ```js
 * 146:  if (this.status === "cancelled") { statusParts.push(theme.fg("warning", "(cancelled)")); }
 * 149:  else if (this.status === "error") { statusParts.push(theme.fg("error", `(exit ${this.exitCode})`)); }
 * 154:  if (wasTruncated && this.fullOutputPath) {
 * 155:      statusParts.push(theme.fg("warning", `Output truncated. Full output: ${this.fullOutputPath}`));
 * 158:  if (statusParts.length > 0) { this.contentContainer.addChild(new Text(`\n${statusParts.join("\n")}`, 1, 0)); }
 * ```
 *
 * with `status` set to `cancelled` / `error` / `complete` by [setComplete] (`:72-77`): a run that
 * finished with exit code 0 — or with no code at all — is `complete`, and `complete` contributes
 * **no part of its own**. So pi prints nothing for a successful command, and this function
 * returns null for it; a successful *truncated* run keeps only the truncation sentence, which is
 * pi's own `warning` part.
 *
 * The three cases of exit code are pi's own: a cancelled run has none (`BashResult.exitCode` is
 * null, `core/bash-executor.ts`), a truncated one names the file holding the rest, and the rest
 * report the code.
 *
 * `excludeFromContext` is spelled out because it changes whether the output will reach the model
 * on the next prompt. pi does not print it — it strips the `!!` before building the header
 * (`interactive-mode.js:2503-2504`) and lets the border colour carry it — but this repository's
 * own rule is that colour never carries a fact alone (§9, `06 §4`), so the word rides whichever
 * line is drawn. See this file's header for the full argument.
 */
private fun statusText(run: BashRun): String? {
    val scope = if (run.excludeFromContext) "不进上下文" else "进入上下文"
    if (run.running) return "运行中 · $scope"
    val parts = buildList {
        if (run.cancelled) {
            add("已取消")
        } else {
            // pi's `status === "complete"` covers both 0 and "no code": neither is an error.
            run.exitCode?.takeIf { it != 0 }?.let { add("退出码 $it") }
        }
        if (run.truncated) {
            add(run.fullOutputPath?.let { "输出被截断，完整输出：$it" } ?: "输出被截断")
        }
    }
    if (parts.isEmpty()) return null
    return (parts + scope).joinToString(" · ")
}

/**
 * The tone of [statusText]'s line, in pi's own three colours: the running indicator is `muted`
 * (pi's loader label, `:40`), a cancellation is `warning` (`:147`), a non-zero exit is `error`
 * (`:150`), and what is left can only be the truncation sentence — `warning` (`:155`).
 */
private fun statusTone(run: BashRun, palette: PiPalette): Color = when {
    run.running -> palette.muted
    run.cancelled -> palette.warning
    run.exitCode != null && run.exitCode != 0 -> palette.error
    else -> palette.warning
}

/** The card's corner radius, as the edge path needs it in px. `PiShapes.cardInner` is 12. */
private val BASH_PANEL_RADIUS = 12.dp

/**
 * pi's two full-width borders, drawn along the **card's own rounded edges**.
 *
 * pi's `DynamicBorder` is one row of `─` across the whole viewport, coloured `dim` (an excluded
 * run) or `bashMode` (a context-carrying one) — `components/dynamic-border.js`:
 *
 * ```js
 * render(width) { return [this.color("─".repeat(Math.max(1, width)))]; }
 * ```
 *
 * **Why a path and not `Modifier.border`.** v2's geometry for this panel is a 12 dp-radius card
 * (`PiShapes.cardInner`), where pi's is a rectangle. `border()` draws all four sides — pi has
 * two — and a straight full-width line would be cut off at both corners by the `Surface`'s own
 * `clip(shape)`, which is exactly the "整宽" the border exists to state. So the line follows the
 * corner arcs, and keeps its full width: the same trick `WorkspaceChrome`'s `wsSheetTopEdge`
 * uses for an inner sheet's top edge.
 *
 * **Why `onDrawWithContent` and not `drawBehind`.** `Surface(modifier = …)` appends its own
 * `.background(color, shape).clip(shape)` to the caller's chain, so anything this modifier drew
 * *behind* its content would sit under the card's fill and never be seen. `drawContent()` first,
 * then the two paths.
 *
 * The width is the app's single stroke width (`06 §2` 线宽「全篇只有 1px」); in a terminal the
 * border is a whole text row rather than a hairline, and 1 dp is the phone's equivalent of a
 * character cell's own thickness.
 */
private fun Modifier.bashPanelEdges(color: Color): Modifier = drawWithCache {
    val half = 1.dp.toPx() / 2f
    val radius = (BASH_PANEL_RADIUS.toPx() - half)
        .coerceAtLeast(0f)
        .coerceAtMost(size.height / 2f)
    val top = Path().apply {
        moveTo(half, half + radius)
        arcTo(Rect(half, half, half + 2f * radius, half + 2f * radius), 180f, 90f, false)
        lineTo(size.width - half - radius, half)
        arcTo(
            Rect(size.width - half - 2f * radius, half, size.width - half, half + 2f * radius),
            270f,
            90f,
            false,
        )
    }
    val bottom = Path().apply {
        moveTo(half, size.height - half - radius)
        arcTo(
            Rect(half, size.height - half - 2f * radius, half + 2f * radius, size.height - half),
            180f,
            -90f,
            false,
        )
        lineTo(size.width - half - radius, size.height - half)
        arcTo(
            Rect(
                size.width - half - 2f * radius,
                size.height - half - 2f * radius,
                size.width - half,
                size.height - half,
            ),
            90f,
            -90f,
            false,
        )
    }
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawPath(top, color, style = stroke)
        drawPath(bottom, color, style = stroke)
    }
}
