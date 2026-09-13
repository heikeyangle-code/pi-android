package app.pi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.PiResponses
import app.pi.rpc.TokenUsage
import app.pi.ui.theme.PiMark
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * pi's own token formatter, transcribed from
 * `packages/coding-agent/src/modes/interactive/components/footer.ts:24-30`.
 *
 * It is *not* the same rounding as `ui/blocks/BlockChrome.kt`'s
 * `formatTokens` (that one prints `3k` where pi prints `3.2k` for 3 200). The
 * summarization billing line uses pi's, because its text is pi's verbatim.
 */
fun piFormatTokens(count: Long): String = when {
    count < 1_000L -> count.toString()
    count < 10_000L -> String.format(Locale.US, "%.1fk", count / 1_000.0)
    count < 1_000_000L -> "${Math.round(count / 1_000.0)}k"
    count < 10_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    else -> "${Math.round(count / 1_000_000.0)}M"
}

/**
 * The transcript's status line — v2's `StateLine` (`06 §3` 构件 3, `04 §1.2`).
 *
 * One row, read left to right: the context percentage, an eight-segment progress bar,
 * the tokens in use against the window, the two readings direction A contributed
 * (`输出`, `缓存读`), and the session's cost pinned to the right:
 *
 * ```
 * 上下文 52% [▮▮▮▮▯▯▯▯] 104k / 200k · 输出 8.2k · 缓存读 61.4k · $0.42
 * ```
 *
 * ## The two figures are rounded the way the design writes them
 *
 * `06 §3` 构件 3 and the hero's own comment (`direction-b-v2.html:86`) write the
 * percentage with **no** decimals and the cost with **two** — `52%`, `$0.42` — and
 * that is the literal the frozen board shows. This row prints those two figures, so
 * it prints them that way. It is a *display* rounding and only here: the app still
 * holds pi's own numbers at pi's own precision, and 会话信息
 * (`ui/chat/ChatSheets.kt`'s stats sheet) shows the percentage to one decimal and
 * the cost to three, so nothing is lost — it is one row saying what the design says.
 *
 * ## Where each figure comes from (all of them pi's)
 *
 *  - `上下文 N%` — `stats.contextUsage.percent`, i.e. pi's own `getContextUsage()`,
 *    which is `estimate.tokens / contextWindow * 100`
 *    (`core/agent-session.ts:3446-3450`); `?` when pi reports none, never a
 *    substituted 0 (`footer.ts:108-110`).
 *  - the bar — the same percentage across eight segments of `3×8` with a 1px gap,
 *    filled in `accent` (`06 §2` 状态行). It is drawn only when pi reports a
 *    percentage: an empty bar would claim "0 %".
 *  - `used / window` — `contextUsage.tokens` / `contextUsage.contextWindow`; the
 *    window falls back to the model's own (`footer.ts:109`).
 *  - `输出` / `缓存读` — `stats.tokens.output` / `stats.tokens.cacheRead`, the two
 *    fields A moved in (`04 §1.2`: 两个读数字段与「会话信息」sheet 里的 Token 行同源),
 *    formatted by [piFormatTokens] — pi's rounding (`footer.ts:24-30`), **not**
 *    `ui/blocks/BlockChrome.kt`'s `formatTokens`, which prints `3k` where pi prints
 *    `3.2k`.
 *  - `$…` — `stats.cost` at pi's three decimals (`footer.ts:142-146`), only when
 *    non-zero, and it is the row's right-hand anchor.
 *
 * ## What is no longer on this row, and why nothing is lost
 *
 * pi's footer also prints `↑input ↓output RcacheRead WcacheWrite CH…%`
 * (`footer.ts:106-146`). v2's status line is a *reading*, not a copy of that footer:
 * `04 §1.2` adds the two A readings to B's line (`上下文 52% [分段进度] 104k / 200k
 * $0.42`) and stops there, so the input total, the cache-write total and the
 * cache-hit rate leave this row. None of them is dropped from the app: 会话信息
 * (`ui/chat/ChatSheets.kt`'s stats sheet) prints 输入 / 输出 / 缓存读 / 缓存写 / 合计
 * in full. B7 deleted the `latestUsage` parameter that used to carry them here: with
 * no reading on this row that took a `TokenUsage`, it had become a dead argument
 * whose only effect was to keep the caller importing `TokenUsage`.
 *
 * The percentage keeps pi's own colour thresholds (`>90` error, `>70` warning —
 * `footer.ts:154-156`); every other figure is muted or body text. No ring, no sweep
 * animation, no glow: pi's footer is static text and this row is too.
 *
 * @param stats `get_session_stats`; null before the first read, in which case the
 *   row renders nothing rather than zeros.
 * @param contextWindowFallback the model's window, pi's own fallback when
 *   `getContextUsage()` reports none (`footer.ts:109`).
 * @param autoCompaction pi's `autoCompactEnabled` → the ` (auto)` suffix.
 */
@Composable
fun PiStatusLine(
    stats: PiResponses.SessionStats?,
    contextWindowFallback: Long?,
    autoCompaction: Boolean,
    modifier: Modifier = Modifier,
) {
    if (stats == null) return
    val totals = stats.tokens
    val usage = stats.contextUsage
    val contextWindow = usage?.contextWindow ?: contextWindowFallback
    val percent = usage?.percent
    val used = usage?.tokens
    // pi: `?` when the percentage is unknown, never a substituted 0.
    val percentText = percent?.let { String.format(Locale.US, "%.0f", it) } ?: "?"
    val auto = if (autoCompaction) " (auto)" else ""
    val contextColor = when {
        percent != null && percent > 90.0 -> PiTheme.palette.error
        percent != null && percent > 70.0 -> PiTheme.palette.warning
        else -> PiTheme.palette.muted
    }
    // A reading appears only once it has a figure (pi's own rule for these parts,
    // `footer.ts:130-133`): "输出 0" before the first reply would be a claim about a
    // model that has not spoken yet.
    val readings = buildList {
        totals?.output?.takeIf { it > 0L }?.let { add("输出 ${piFormatTokens(it)}") }
        totals?.cacheRead?.takeIf { it > 0L }?.let { add("缓存读 ${piFormatTokens(it)}") }
    }
    val cost = stats.cost?.takeIf { it != 0.0 }?.let { "$${String.format(Locale.US, "%.2f", it)}" }
    val windowText = contextWindow?.takeIf { it > 0L }?.let { piFormatTokens(it) }
    // "No data, no row": with no percentage, no window and no readings there is
    // nothing to read, and an empty status line would still cost 32dp.
    if (percent == null && windowText == null && readings.isEmpty() && cost == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PiSpacing.statusRow)
            .padding(horizontal = PiSpacing.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "上下文 $percentText$auto",
            style = PiTheme.text.meta,
            color = contextColor,
            maxLines = 1,
        )
        if (percent != null) {
            Spacer(Modifier.width(PiSpacing.small))
            ContextSegments(percent)
        }
        if (windowText != null) {
            Spacer(Modifier.width(PiSpacing.small))
            Text(
                // pi reports no token count after a compaction until the next
                // response (`agent-session.ts:3450`), and `?` is the honest spelling
                // it uses for exactly that window (`footer.ts:110`).
                text = "${used?.let { piFormatTokens(it) } ?: "?"} / $windowText",
                // `06 §2` 状态行 + `06 §2` 字号: every reading on this row is a machine
                // reading at the 12 sp step (`mono t12 tab` in v2's `StateLine`), which
                // is what keeps the figures aligned as they tick.
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.muted,
                maxLines = 1,
            )
        }
        if (readings.isNotEmpty()) {
            // The readings absorb the slack so the cost stays pinned to the right
            // (`06 §2` leaves the row's tail to the cost); an unusually long pair
            // elides rather than pushing the cost off the row.
            Text(
                text = " · " + readings.joinToString(" · "),
                modifier = Modifier.weight(1f),
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (cost != null) {
            Spacer(Modifier.width(PiSpacing.small))
            Text(
                text = cost,
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.text,
                maxLines = 1,
            )
        }
    }
}

/** `06 §2` 状态行: the context bar's segment count. */
private const val CONTEXT_SEGMENTS = 8

/** `06 §2` 状态行: each segment is `3×8` with a 1px gap and a 1px corner. */
private val CONTEXT_SEGMENT_WIDTH = 3.dp
private val CONTEXT_SEGMENT_HEIGHT = 8.dp

/**
 * The context bar: [CONTEXT_SEGMENTS] segments, `accent` up to the percentage and
 * `borderMuted` after it.
 *
 * The bar is a *reading* of the percentage printed beside it, never the only copy of
 * it (`06 §4`: 颜色不能是唯一信号), which is why the number and the bar are one
 * glance apart and the number carries the colour thresholds.
 */
@Composable
private fun ContextSegments(percent: Double, modifier: Modifier = Modifier) {
    val palette = PiTheme.palette
    val filled = (CONTEXT_SEGMENTS * percent / 100.0).roundToInt().coerceIn(0, CONTEXT_SEGMENTS)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.hairline),
    ) {
        repeat(CONTEXT_SEGMENTS) { index ->
            Box(
                Modifier
                    .width(CONTEXT_SEGMENT_WIDTH)
                    .height(CONTEXT_SEGMENT_HEIGHT)
                    .clip(RoundedCornerShape(PiSpacing.hairline))
                    .background(if (index < filled) palette.accent else palette.borderMuted),
            )
        }
    }
}

/**
 * The summarization billing line pi prints for a compaction or a branch summary.
 *
 * **pi's text, verbatim** (`modes/interactive/interactive-mode.ts:3802-3812`):
 *
 * ```
 * const tokens = usage.input + usage.output + usage.cacheRead + usage.cacheWrite;
 * const cost = usage.cost.total >= 0.01 ? ` (~$${usage.cost.total.toFixed(2)})` : "";
 * const label = notice.kind === "compaction" ? "Compaction" : "Branch summary";
 * new Text(theme.fg("warning", `${label}: ${formatTokens(tokens)} tokens billed${cost}`), 1, 0)
 * ```
 *
 * so the English labels and the `(~$0.03)` threshold are pi's, not an app
 * translation — a translated label would stop matching the line the desktop TUI
 * shows for the same session.
 *
 * Shown only when pi's `showCacheMissNotices` is on: pi guards the call with
 * `if (!this.settingsManager.getShowCacheMissNotices()) return;` (`:3803`), and
 * that setting defaults to `false` (`core/settings-manager.ts:120`, `:966`).
 */
@Composable
fun PiBilledCostLine(
    label: String,
    usage: TokenUsage?,
    modifier: Modifier = Modifier,
) {
    if (usage == null) return
    val tokens = (usage.input ?: 0L) + (usage.output ?: 0L) +
        (usage.cacheRead ?: 0L) + (usage.cacheWrite ?: 0L)
    val cost = usage.cost?.takeIf { it >= 0.01 }?.let {
        " (~$${String.format(Locale.US, "%.2f", it)})"
    }.orEmpty()
    Text(
        text = "$label: ${piFormatTokens(tokens)} tokens billed$cost",
        modifier = modifier,
        style = PiTheme.text.meta,
        // pi paints it with `theme.fg("warning", …)`.
        color = PiTheme.palette.warning,
    )
}

/**
 * Empty states never say "no data". They say what the screen is for and offer
 * the one action that fills it (docs/pi-android-ui-spec.md §7.3).
 *
 * The mark is v2's: either the π glyph (`06 §2` 空态「π 字形 34」，`brand-spec.md` §1
 * allows it as an empty-state identifier) or the screen's own icon inside the circle
 * this component has always drawn. v2's prototype keeps both spellings and chooses
 * per screen — the chat's two engine states carry the mark, because they are the
 * app's own empty surface, while a search that matched nothing keeps its icon.
 *
 * [markPi] defaults to `true` so the **chat** empty states get the mark without
 * touching `screens/ChatScreen.kt` (which this batch does not own). The call sites
 * that draw a *finding* rather than the app's own surface — the session list's two
 * empty states, the settings search and the session tree — want `markPi = false`
 * and are named in this batch's notes: they live in files outside its boundary.
 *
 * Geometry follows `06 §2` where the container allows it: the horizontal inset is
 * the board's 34, the title is the 17/600 title role, and the body is 14 with the
 * board's 1.6 leading, left-aligned under the centred title. The board's `86px`
 * *vertical* inset is not repeated: it is how the prototype centres this block in a
 * fixed-height frame, and in the app the host hands the state a `weight(1f)` box
 * that this column already centres in (`Arrangement.Center`).
 *
 * @param icon the screen's own icon; ignored while [markPi] is on.
 */
@Composable
fun PiEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    markPi: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = EMPTY_STATE_INSET),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (markPi) {
            PiMark(size = 34.dp, tint = PiTheme.palette.muted)
        } else {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(20.dp).size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(EMPTY_STATE_TITLE_GAP))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            body,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = EMPTY_STATE_BODY_LEADING),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
        if (action != null) {
            Spacer(Modifier.height(PiSpacing.unit))
            action()
        }
    }
}

/** `06 §2` 空态「padding:86px 34px」: the horizontal half, the only one Compose needs. */
private val EMPTY_STATE_INSET = 34.dp

/** `06 §2` 空态: the mark-to-title gap is v2's `marginTop:12`. */
private val EMPTY_STATE_TITLE_GAP = 12.dp

/** `06 §2` 空态「正文 14/1.6」: 14 sp × 1.6, stated as leading rather than as a ratio. */
private val EMPTY_STATE_BODY_LEADING = 22.sp

/** Small uppercase-ish group label. Used by the settings stack and pickers. */
@Composable
fun PiSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(
            start = PiSpacing.pageHorizontal,
            end = PiSpacing.pageHorizontal,
            top = PiSpacing.unit,
            bottom = 6.dp,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One settings row. pi's semantics are carried by [supporting]; the trailing
 * slot shows the *current value* so a group screen is readable without opening
 * anything (docs/pi-android-ui-spec.md §6.2 — six row types, this is the
 * Switch/Value pair that covers most of them).
 */
@Composable
fun PiSwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A settings row whose value is chosen elsewhere. [value] is the summary.
 *
 * [onClick] is applied to the whole row rather than only the trailing text: a
 * row that advertises a value but ignores taps is worse than one that offers no
 * affordance at all.
 */
@Composable
fun PiValueRow(
    title: String,
    supporting: String?,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * When a written value starts to apply.
 *
 * `Reload` and `RestartEngine` are **different** things on purpose:
 *
 *  - [Reload] — the app (or pi's own resource loader) re-reads the value/file
 *    while the engine keeps running: the terminal key bar and the shortcuts are
 *    applied by this app the moment they are written, and pi re-reads a theme
 *    selection when its own UI asks for it.
 *  - [RestartEngine] — the value is read **only when `pi --mode rpc` starts**:
 *    pi's process configuration (`--offline`, `--system-prompt`,
 *    `PI_CACHE_RETENTION`) and the resources its loader caches at startup.
 *    Nothing short of a new process applies it, so the badge must not say
 *    "重载" — a word that promises the change is one tap away.
 *  - [RestartApp] — read while *this* app starts (the foreground-service switch).
 *  - [Immediate] — nothing to wait for.
 *
 * pi itself has only two of these timings in its TUI (a settings write is either
 * live or needs `/reload`); the split exists because the app must not promise
 * pi's `/reload` over RPC, where it does not exist.
 *
 * **The badge composable that used to render this enum is gone** (B7, the
 * 死代码 收尾项): `PiEffectiveBadge` had zero call sites — `PiSettingsRegistry`
 * carries [EffectiveKind] per setting and the settings package draws the 生效徽标
 * itself, in v2's badge shape — so it was deleted rather than left as a second,
 * unreachable spelling of the same pill. The enum stays: it is the registry's
 * type and the settings package references it, and this batch does not own that
 * package.
 */
enum class EffectiveKind { Immediate, Reload, RestartEngine, NewSession, RestartApp }
