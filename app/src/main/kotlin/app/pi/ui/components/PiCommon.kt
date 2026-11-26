package app.pi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.protocol.TokenUsage
import app.pi.ui.theme.PiPalette
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
 * pi's context reading as a **ring**, the composer's and the detail sheet's form of
 * the status row's eight-segment bar.
 *
 * ## What it is
 *
 * An arc of [diameter] with a [stroke]-wide pen and **no number inside**: the fill
 * runs from twelve o'clock clockwise for `percent × 3.6°`, so a glance reads the
 * proportion without reading a figure. The transcript's own reading is not lost —
 * the detail sheet prints the same percentage in words, and the ring's own
 * `contentDescription` is set by the caller through its click label.
 *
 * ## The two colours are pi's thresholds, not this component's taste
 *
 * The track is `muted`; the fill is `accent` up to 70 %, `warning` above it and
 * `error` above 90 % — the same three bands pi colours its footer percentage with
 * (`components/footer.ts:154-156`), which the app's status row also uses. Colour is
 * never the only channel here either: the percentage is spelled out in the detail
 * sheet one tap away, and the ring's own click label says what it opens.
 *
 * ## `percent == null` is a real state, not zero
 *
 * pi reports no context usage between a compaction and the next reply. The ring then
 * draws the **empty track** and a centred `?` — pi's own spelling for that window
 * (`footer.ts:110`, and the app's status row used it) — rather than an empty arc that
 * would read as "0 %".
 *
 * @param percent 0–100, straight from pi's `getContextUsage().percent`; null when pi
 *   has not reported one.
 * @param diameter the ring's outer size — **the drawn arc**, not the touch target.
 *   The caller owns the touch box: whatever `modifier` it passes is the outer box this
 *   composable fills, and the arc is centred in it. That split is D27's (24 dp visual,
 *   32 dp touch, 30 dp slot in the composer's key row), and it was a real defect before:
 *   the box used to be `modifier.size(diameter)`, so the `size` handed in by the caller
 *   was immediately overridden by the visual size — the touch area and the drawn ring
 *   disagreed about which of them was the button.
 * @param stroke the pen width. `06 §2`'s bar has no analogue; 2 is the weight that
 *   keeps a 24 dp ring legible as a ring rather than a dot.
 * @param placeholderStyle the style of the `?`. It is a parameter because the ring
 *   is drawn at two very different sizes (24 dp in the composer, 56 dp in the sheet)
 *   and one glyph size cannot serve both.
 */
@Composable
fun PiContextRing(
    percent: Double?,
    diameter: Dp,
    modifier: Modifier = Modifier,
    stroke: Dp = 2.dp,
    // Required rather than defaulted: the two call sites draw the ring at very
    // different sizes (24 dp in the composer's key row, 52 dp in the sheet), and a
    // default would have to read `PiTheme.text` inside a parameter list — a
    // composable call in a default argument works, but this project has no other
    // instance of it and the two sizes want two different styles anyway.
    placeholderStyle: TextStyle,
    /** 中性模式（输入框仪表用）：<=70% 用克制中性墨色，仅 >70% / >90% 亮预警/红警，避免低占用时到处染主题色。 */
    neutral: Boolean = false,
) {
    val palette = PiTheme.palette
    // The caller's box is the touch target; the arc is drawn *inside* it, centred.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val pen = stroke.toPx().coerceAtMost(size.minDimension / 2f)
            val inset = pen / 2f
            val box = Size(size.width - pen, size.height - pen)
            val arc = Offset(inset, inset)
            drawArc(
                color = palette.borderMuted.copy(alpha = 0.72f),
                startAngle = RING_START_ANGLE,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arc,
                size = box,
                style = Stroke(width = pen),
            )
            if (percent != null) {
                val fillColor = if (neutral && percent <= 70.0) {
                    palette.text.copy(alpha = 0.72f)
                } else {
                    contextProgressColor(percent, palette)
                }
                drawArc(
                    color = fillColor,
                    startAngle = RING_START_ANGLE,
                    sweepAngle = (percent * 3.6).toFloat().coerceIn(0f, 360f),
                    useCenter = false,
                    topLeft = arc,
                    size = box,
                    style = Stroke(width = pen, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
        if (percent == null) {
            // Centred on the **ring**, not on the touch box around it: the two are
            // concentric here, and pinning the glyph to the arc's own diameter is what
            // keeps it that way if a caller ever hands in an off-centre box.
            Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                Text(
                    text = "?",
                    style = placeholderStyle,
                    color = palette.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Twelve o'clock: the angle Compose measures arcs from is 3 o'clock. */
private const val RING_START_ANGLE = -90f

/**
 * The colour pi gives a context percentage: `accent`, `warning` past 70, `error` past
 * 90 (`components/footer.ts:154-156`). Shared by the ring's fill and the detail
 * sheet's percentage, so the two cannot disagree.
 *
 * This is now the **only** place the two thresholds are spelled: the transcript's
 * status row (the other reader) was deleted, so the ring's fill and the detail
 * sheet's percentage cannot drift apart — there is nothing left to change twice.
 * The at-rest colour here is `accent` because this function paints a **fill**, not a
 * label.
 *
 * A null [percent] is not a colour: the caller draws no fill at all and says `?`.
 */
fun contextProgressColor(percent: Double, palette: PiPalette): Color = when {
    percent > 90.0 -> palette.error
    percent > 70.0 -> palette.warning
    else -> palette.accent
}

/**
 * The app's own menu, in v2's container rather than Material's.
 *
 * ## Why not `DropdownMenu`
 *
 * M3's default menu draws a 2 dp shadow, a 4 dp corner and its own surface colour.
 * `04 §2.3` and all of v2 say the opposite: hierarchy is 1 px lines and the surface
 * ladder, **no shadows**, and the corners in play are 10 and 14. A menu is a surface
 * like any other, so it takes v2's: `surf-high`, a 1 px `borderMuted` ring, radius
 * 10, zero elevation — and, for the same reason, **no ripple**: the pressed row
 * simply becomes `surf-highest`, which is a colour swap and not an animation.
 *
 * ## It has to be a `Popup`
 *
 * The composer's own box is a 14 dp-radius container that clips its content, so a
 * menu laid over it as a sibling `Box` would be cut off at the corner. `Popup` is a
 * separate window (and it dismisses on an outside tap for free).
 *
 * ## Placement
 *
 * [PiMenuPlacement.Above] is the composer's: the menu's left edge lines up with the
 * anchor's and its **bottom** sits 8 dp above the anchor's top, because the anchor is
 * a chip in a row that lives at the bottom of the screen. [PiMenuPlacement.Below] is
 * for an anchor near the top of the screen (the AppBar's ⋮), where "above" would be
 * off-screen. Neither flips itself: both callers know where they are, and a menu that
 * silently moved would be harder to reason about than one that is placed.
 *
 * ## The scroll bound
 *
 * [maxHeight] exists for the AppBar's menu, which has seventeen rows and would be
 * ~680 dp tall — taller than the phone. v2's spec for this container has no bound
 * because the menu it was drawn for has four rows (169 dp); the bound is this app's
 * adaptation for the long one, and the composer's menu never reaches it.
 *
 * @param items the rows, in order. A row with [PiMenuItem.dividerBefore] gets a
 *   1 px `borderMuted` rule at 55 % above it.
 */
@Composable
fun PiMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<PiMenuItem>,
    placement: PiMenuPlacement,
    modifier: Modifier = Modifier,
    maxHeight: Dp = PI_MENU_MAX_HEIGHT,
    alignEnd: Boolean = false,
) {
    if (!expanded || items.isEmpty()) return
    val density = LocalDensity.current
    val dividerCount = items.count { it.dividerBefore }
    val height = (PI_MENU_ROW_HEIGHT * items.size +
        PI_MENU_VERTICAL_PADDING * 2 +
        PI_MENU_DIVIDER_HEIGHT * dividerCount).coerceAtMost(maxHeight)
    val offsetY = when (placement) {
        PiMenuPlacement.Above -> -(height + PI_MENU_ANCHOR_GAP)
        PiMenuPlacement.Below -> PI_MENU_ANCHOR_GAP
    }
    val hasAnySymbol = items.any { it.symbol != null }
    Popup(
        alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = with(density) { IntOffset(0, offsetY.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val enterAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            enterAlpha.animateTo(1f, tween(110))
        }
        Column(
            modifier = modifier
                .graphicsLayer { alpha = enterAlpha.value }
                .width(if (hasAnySymbol) PI_MENU_WIDTH else 208.dp)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(PI_MENU_RADIUS))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = PiSpacing.hairlineThin,
                    color = PiTheme.palette.borderMuted.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(PI_MENU_RADIUS),
                )
                .verticalScroll(rememberScrollState())
                .padding(vertical = PI_MENU_VERTICAL_PADDING),
        ) {
            items.forEach { item ->
                if (item.dividerBefore) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = PiTheme.palette.borderMuted.copy(alpha = 0.55f),
                    )
                }
                PiMenuRow(item = item, showSymbolSlot = hasAnySymbol, onDismiss = onDismiss)
            }
        }
    }
}

/** One row of a [PiMenu]: `[symbol 24] + 10 + label + slack + right-aligned note`. */
@Composable
private fun PiMenuRow(item: PiMenuItem, showSymbolSlot: Boolean, onDismiss: () -> Unit) {
    val palette = PiTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spoken = item.a11yLabel ?: item.note?.let { tr("${item.label}，$it", "${item.label}, $it") } ?: item.label
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.enabled) 1f else 0.45f)
            .background(
                if (pressed && item.enabled) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                enabled = item.enabled,
                interactionSource = interaction,
                indication = null,
                onClickLabel = spoken,
            ) {
                onDismiss()
                item.onSelect()
            }
            .height(PI_MENU_ROW_HEIGHT)
            .padding(horizontal = PI_MENU_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSymbolSlot) {
            Box(Modifier.width(PI_MENU_SYMBOL_WIDTH), contentAlignment = Alignment.Center) {
                if (item.symbol != null) {
                    Text(
                        text = item.symbol,
                        style = PiTheme.text.monoSmall.copy(fontSize = 13.sp),
                        color = item.symbolColor ?: palette.text,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(PI_MENU_SYMBOL_GAP))
        }
        Text(
            text = item.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.note != null) {
            Text(
                text = item.note,
                style = PiTheme.text.meta,
                color = palette.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * One row of a [PiMenu].
 *
 * [onSelect] is **last** so a caller can write `PiMenuItem("新建会话") { … }` — a
 * trailing lambda binds to the final parameter, and putting it anywhere else means
 * every one-line row has to name an argument it does not otherwise need. The rows
 * that carry a symbol or a note use named arguments and are unaffected by the order.
 */
data class PiMenuItem(
    val label: String,
    /** The trigger character or glyph, centred in its own 24 dp column. */
    val symbol: String? = null,
    /** The right-aligned explanation of *which* variant this is (`进上下文`). */
    val note: String? = null,
    /** The symbol's colour; the label is always the text colour. */
    val symbolColor: Color? = null,
    /** Overrides what a screen reader hears; defaults to `label，note`. */
    val a11yLabel: String? = null,
    /** Draw the 1 px rule above this row. */
    val dividerBefore: Boolean = false,
    val enabled: Boolean = true,
    val onSelect: () -> Unit,
)

/** Where a [PiMenu] opens relative to its anchor. */
enum class PiMenuPlacement { Above, Below }

/** 现代菜单容器：圆角 14，清晰留白。 */
private val PI_MENU_WIDTH = 244.dp
private val PI_MENU_RADIUS = 14.dp
private val PI_MENU_VERTICAL_PADDING = 6.dp
private val PI_MENU_ROW_HEIGHT = 42.dp
private val PI_MENU_ROW_PADDING = 14.dp
private val PI_MENU_SYMBOL_WIDTH = 24.dp
private val PI_MENU_SYMBOL_GAP = 10.dp
private val PI_MENU_DIVIDER_HEIGHT = 1.dp

/** The gap between the anchor and the menu's near edge. */
private val PI_MENU_ANCHOR_GAP = 8.dp

/** See [PiMenu]'s KDoc: the long AppBar menu scrolls inside this. */
private val PI_MENU_MAX_HEIGHT = 320.dp

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
 * Shown only when pi's `notices.compactionCosts` is on: pi guards the call with
 * `if (!this.settingsManager.getNotices().compactionCosts) return;` (`interactive-mode.js:3529`),
 * defaulting to `false` (and automatically migrating legacy `showCacheMissNotices`).
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
        // pi's own English billing line, and the only place the app prints a token count
        // *and* a cost together: v2 draws it `mono t12` (`direction-b-v2.html:2597`,
        // 「Compaction: 42k tokens billed (~$0.03)」), `06 §3` 构件 8 calls it the
        // 「可选英文计费行」, and `05 §4.2` keeps token and 费用 on the machine face
        // （「耗时/退出码/行数/token/费用/秒数 六类改成 `numeric`」）. `meta` was the UI
        // face, so the two readings this line exists for were the one part of it not
        // set as machine language.
        style = PiTheme.text.monoSmall,
        // pi paints it with `theme.fg("warning", …)`.
        color = PiTheme.palette.warning,
    )
}

/** Standard centered Studio empty state with icon tile, title, copy, and optional action. */
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
            .padding(horizontal = EMPTY_STATE_INSET),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
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
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(PiSpacing.unit))
            action()
        }
    }
}

private val EMPTY_STATE_INSET = 34.dp
private val EMPTY_STATE_BODY_LEADING = 22.sp

@Composable
fun PiSwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = { next ->
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onCheckedChange(next)
                },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        PiSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
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
 *  - [RestartEngine] — the value is read **only when the SDK Host engine starts**:
 *    process configuration (`PI_OFFLINE`, `PI_CACHE_RETENTION`, the four
 *    resource-discovery suppressions) and resources cached at boot.
 *    Nothing short of a new process applies it, so the badge must not say
 *    "重载" — a word that promises the change is one tap away.
 *  - [NewSession] — the value is read when a session's resource loader is rebuilt
 *    (`core/agent-session-services.js` builds one per session and `reload()`s it,
 *    `core/agent-session-runtime.js` rebuilds the services on a session switch), so
 *    the running turn keeps what it has and the next session is different. The two
 *    prompt rows are this case: they are pi's files, not process arguments.
 *  - [AutoRestartEngine] — the same timing as [RestartEngine] (a new process is
 *    still what makes the value true) **but the app performs it itself** as part
 *    of the write: the row is `app.runtime.proroot`, whose toggle runs the probe
 *    and then restarts the engine onto the chosen runtime
 *    (`RuntimeSwitchAction`). The badge says 「自动重启引擎」 rather than
 *    「需重启引擎」 so it does not ask the user for a step that already happened —
 *    and the row is never left claiming a value the running engine does not have
 *    (a refused restart is rolled back). Use this kind only where the write path
 *    really does the restart; a row without that wiring must stay
 *    [RestartEngine], or its badge tells the user the change is applied while
 *    nothing has happened.
 *  - [RestartApp] — read while *this* app starts (the foreground-service switch).
 *  - [Immediate] — nothing to wait for.
 *
 * pi itself has only two of these timings in its TUI (a settings write is either
 * live or needs `/reload`); the finer split reflects what needs an engine restart
 * vs. a new session vs. immediate reload on mobile.
 *
 * **The badge composable that used to render this enum is gone** (B7, the
 * 死代码 收尾项): `PiEffectiveBadge` had zero call sites — `PiSettingsRegistry`
 * carries [EffectiveKind] per setting and the settings package draws the 生效徽标
 * itself, in v2's badge shape — so it was deleted rather than left as a second,
 * unreachable spelling of the same pill. The enum stays: it is the registry's
 * type and the settings package references it, and this batch does not own that
 * package.
 *
 * Every value here has a reader in `ui/settings`: the badge label
 * (`PiSettingsStyle.PiSettingsEffectiveBadge`), the explanation dialog
 * (`PiSettingsEditors.PiEffectiveDialog`, which is a `when` — exhaustive on
 * purpose, so a new kind cannot be added without deciding what it means and
 * whether it offers an action), and the badge's action
 * (`SettingsGroupScreen`, where only [RestartEngine] routes to the manual
 * restart).
 */
enum class EffectiveKind { Immediate, Reload, RestartEngine, AutoRestartEngine, NewSession, RestartApp }
