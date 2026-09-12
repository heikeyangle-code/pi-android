package app.pi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.PiResponses
import app.pi.rpc.TokenUsage
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.util.Locale

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
 * pi's footer stats, as one row — **F10** (`docs/rendering-review.md`).
 *
 * pi prints these figures in its footer on every render
 * (`packages/coding-agent/src/modes/interactive/components/footer.ts:106-161`):
 *
 * ```
 * if (usageTotals.input)  statsParts.push(`↑${formatTokens(usageTotals.input)}`);
 * if (usageTotals.output) statsParts.push(`↓${formatTokens(usageTotals.output)}`);
 * if (usageTotals.cacheRead)  statsParts.push(`R${formatTokens(usageTotals.cacheRead)}`);
 * if (usageTotals.cacheWrite) statsParts.push(`W${formatTokens(usageTotals.cacheWrite)}`);
 * if ((cacheRead > 0 || cacheWrite > 0) && latestCacheHitRate !== undefined)
 *     statsParts.push(`CH${latestCacheHitRate.toFixed(1)}%`);
 * if (usageTotals.cost) statsParts.push(`$${usageTotals.cost.toFixed(3)}`);
 * const autoIndicator = this.autoCompactEnabled ? " (auto)" : "";
 * const contextPercentDisplay = percent === "?" ? `?/${formatTokens(contextWindow)}${autoIndicator}`
 *                                               : `${percent}%/${formatTokens(contextWindow)}${autoIndicator}`;
 * // percent > 90 → theme.fg("error", …); > 70 → theme.fg("warning", …)
 * ```
 *
 * **Every figure is pi's own**, not a re-derivation: `getSessionStats()` sums the
 * same entries the footer walks (`core/agent-session.ts:3359-3407`) and its
 * `contextUsage` is literally `this.getContextUsage()` (`:3407`) — the call the
 * footer makes at `footer.ts:108`. So the app's polled
 * `PiResponses.SessionStats` carries the identical numbers.
 *
 * **What is deliberately not shown, and why** (the rule is "no data, no row"):
 *
 *  - `pwd (branch) • session` — pi's left-hand context
 *    (`footer.ts:113-127`). The workspace path the app knows is the *guest*
 *    spelling and it has no git query at all; the session name is already the
 *    AppBar title. Showing a path we cannot resolve or a branch we never asked
 *    for would be a guess.
 *  - `• xp` — pi adds it when the runtime's experimental features are on
 *    (`footer.ts:163-165`); the app has no such flag for the engine.
 *  - ` (sub)` — pi appends it when the provider is subscription-backed
 *    (`footer.ts:148-151`); the app cannot ask `modelRuntime.isUsingSubscription`.
 *  - The model id and thinking level pi puts on the right (`footer.ts:167-`)
 *    are already visible in the AppBar's model chip and the composer's thinking
 *    chip, so repeating them here would duplicate state rather than add it.
 *
 * No ring, no sweep animation, no glow: pi's footer is static text and this row
 * is too.
 *
 * @param stats `get_session_stats`; null before the first read, in which case the
 *   row renders nothing rather than zeros.
 * @param latestUsage the reducer's newest usage, used only for `CH` — pi computes
 *   that rate from the **latest** assistant entry's prompt tokens
 *   (`footer.ts:94-100`), not from the session totals.
 * @param contextWindowFallback the model's window, pi's own fallback when
 *   `getContextUsage()` reports none (`footer.ts:109`).
 * @param autoCompaction pi's `autoCompactEnabled` → the ` (auto)` suffix.
 */
@Composable
fun PiStatusLine(
    stats: PiResponses.SessionStats?,
    latestUsage: TokenUsage?,
    contextWindowFallback: Long?,
    autoCompaction: Boolean,
    modifier: Modifier = Modifier,
) {
    if (stats == null) return
    val totals = stats.tokens
    val parts = mutableListOf<String>()
    // pi prints a part only when its own figure is non-zero (`footer.ts:130-133`).
    totals?.input?.takeIf { it > 0L }?.let { parts += "↑${piFormatTokens(it)}" }
    totals?.output?.takeIf { it > 0L }?.let { parts += "↓${piFormatTokens(it)}" }
    totals?.cacheRead?.takeIf { it > 0L }?.let { parts += "R${piFormatTokens(it)}" }
    totals?.cacheWrite?.takeIf { it > 0L }?.let { parts += "W${piFormatTokens(it)}" }
    // pi's cache-hit item: only once a cache has been touched, and only from the
    // latest assistant usage (`footer.ts:93-100`, `:134-136`).
    val promptTokens = latestUsage?.let {
        (it.input ?: 0L) + (it.cacheRead ?: 0L) + (it.cacheWrite ?: 0L)
    } ?: 0L
    val cacheHitRate = latestUsage?.cacheRead?.takeIf { promptTokens > 0L }?.let {
        it.toDouble() / promptTokens * 100.0
    }
    if ((totals?.cacheRead ?: 0L) > 0L || (totals?.cacheWrite ?: 0L) > 0L) {
        cacheHitRate?.let { parts += "CH${String.format(Locale.US, "%.1f", it)}%" }
    }
    // `$0.123` — pi's three decimals (`footer.ts:142-146`), and only when non-zero.
    stats.cost?.takeIf { it != 0.0 }?.let { parts += "$${String.format(Locale.US, "%.3f", it)}" }
    val statsText = parts.joinToString(" ")

    val contextWindow = stats.contextUsage?.contextWindow ?: contextWindowFallback
    val percent = stats.contextUsage?.percent
    // pi: `?/window` when the percentage is unknown, never a substituted 0.
    val percentText = percent?.let { String.format(Locale.US, "%.1f", it) } ?: "?"
    val contextText = if (contextWindow != null && contextWindow > 0L) {
        "$percentText/${piFormatTokens(contextWindow)}"
    } else {
        percentText
    }
    val auto = if (autoCompaction) " (auto)" else ""
    val contextColor = when {
        percent != null && percent > 90.0 -> PiTheme.palette.error
        percent != null && percent > 70.0 -> PiTheme.palette.warning
        else -> PiTheme.palette.muted
    }
    if (statsText.isEmpty() && percent == null && contextWindow == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PiSpacing.statusRow)
            .padding(horizontal = PiSpacing.screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The stats are the long part, so the context percentage — the figure a
        // user checks before a long task — is pinned to the end instead of being
        // the thing an ellipsis eats.
        Text(
            text = statsText,
            modifier = Modifier.weight(1f, fill = false),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (statsText.isNotEmpty()) Spacer(Modifier.width(PiSpacing.inline))
        Text(
            text = contextText + auto,
            style = PiTheme.text.meta,
            color = contextColor,
            maxLines = 1,
        )
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
 */
@Composable
fun PiEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PiSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
        Spacer(Modifier.height(PiSpacing.unit))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(PiSpacing.unit))
            action()
        }
    }
}

/** Small uppercase-ish group label. Used by the settings stack and pickers. */
@Composable
fun PiSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(
            start = PiSpacing.screen,
            end = PiSpacing.screen,
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
            .padding(horizontal = PiSpacing.screen, vertical = 10.dp),
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
            .padding(horizontal = PiSpacing.screen, vertical = 12.dp),
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

/** Marks how a setting takes effect — pi has four different timings. */
@Composable
fun PiEffectiveBadge(kind: EffectiveKind, modifier: Modifier = Modifier) {
    val (label, color) = when (kind) {
        EffectiveKind.Immediate -> return
        EffectiveKind.Reload -> "需重载" to MaterialTheme.colorScheme.tertiary
        EffectiveKind.NewSession -> "新会话" to MaterialTheme.colorScheme.onSurfaceVariant
        EffectiveKind.RestartEngine -> "需重启引擎" to MaterialTheme.colorScheme.error
        EffectiveKind.RestartApp -> "需重启" to MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier,
        shape = PiShapes.badge,
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
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
 */
enum class EffectiveKind { Immediate, Reload, RestartEngine, NewSession, RestartApp }
