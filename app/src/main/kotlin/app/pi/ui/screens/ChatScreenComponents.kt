package app.pi.ui.screens

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.pi.i18n.tr
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.protocol.TranscriptItem
import app.pi.protocol.UserMessage
import app.pi.ui.chat.TailFollow
import app.pi.ui.chat.earlierRowHeightPx
import app.pi.ui.render.LocalPiMarkdownImmediate
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import kotlinx.coroutines.delay

/** `Icon`'s default box, which is what the 「加载更早」 row's glyph measures at. */
private val EARLIER_ROW_ICON = 24.dp


/** The 6 dp between that glyph and its label (`Spacer(Modifier.width(6.dp))`). */
private val EARLIER_ROW_ICON_GAP = 6.dp


/** The row's own vertical padding (`padding(vertical = 8.dp)`), 8 top and 8 bottom. */
private val EARLIER_ROW_PADDING = 8.dp


/**
 * 「加载更早」 drawn as an **overlay** over the transcript list, not as its item 0.
 *
 * It is the same row it always was — same `Icon`, same 6 dp, same `Text(PiTheme.text.meta)`,
 * same centred `Row`, same `fillMaxWidth().clickable{…}.padding(vertical = 8.dp)` order so the
 * ripple covers the same area and the tap target is the same one. What changed is where it
 * lives: `docs/scroll-diagnosis.md` §3.4 (landed as D51) took it out of the `LazyColumn`
 * because while it was item 0 its key stayed at index 0 across a prepend, which defeated the
 * list's own key anchoring and made every 「加载更早」 batch move the content under the reader.
 * The caller owns its position and the band the list reserves for it.
 */
@Composable
internal fun EarlierRowsRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = EARLIER_ROW_PADDING),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = PiTheme.palette.muted,
            modifier = Modifier.size(EARLIER_ROW_ICON),
        )
        Spacer(Modifier.width(EARLIER_ROW_ICON_GAP))
        Text(
            text = text,
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
    }
}


/**
 * The height that row occupies, in dp — computed **before** the list is laid out, because two
 * things depend on it and both would otherwise need a second layout pass: the band the list
 * reserves in `contentPadding.top`, and the overlay's own offset. A measure → pad → measure
 * dependency is what this avoids (`design/ui-refactor/08-hang-diagnosis.md`).
 *
 * `Row` is as tall as its tallest child plus its padding, so the number is
 * `max(icon, the label's line box) + 16` — `earlierRowHeightPx` in `ui/chat/TailFollow.kt`,
 * where the harness pins the arithmetic. The label's line box is measured with the same
 * `TextMeasurer` the `Text` composable uses internally: the same merged `TextStyle` (the
 * composition's `LocalTextStyle` merged with `PiTheme.text.meta`, which is exactly what
 * `Text(text, style = …)` renders), the same `LocalDensity` (font scale included), the same
 * font resolver and the same `softWrap`/`maxLines`/`overflow` defaults, and the width the
 * `Row` leaves it — the window minus the list's own horizontal padding, the icon and the
 * spacer. That is what makes this exact rather than an estimate of "one line of meta text".
 *
 * Keyed on the inputs, so a recomposition per streamed token re-measures nothing.
 */
@Composable
internal fun earlierRowHeight(text: String): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.merge(PiTheme.text.meta)
    val windowWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val heightPx = remember(text, labelStyle, density, windowWidthPx, measurer) {
        val pagePx = with(density) { PiSpacing.pageHorizontal.roundToPx() }
        val iconPx = with(density) { EARLIER_ROW_ICON.roundToPx() }
        val gapPx = with(density) { EARLIER_ROW_ICON_GAP.roundToPx() }
        val paddingPx = with(density) { (EARLIER_ROW_PADDING * 2).roundToPx() }
        val availablePx = (windowWidthPx - pagePx * 2 - iconPx - gapPx).coerceAtLeast(0)
        val linePx = measurer.measure(
            text = text,
            style = labelStyle,
            constraints = Constraints(maxWidth = availablePx),
        ).size.height
        earlierRowHeightPx(iconPx = iconPx, textHeightPx = linePx, verticalPaddingPx = paddingPx)
    }
    return with(density) { heightPx.toDp() }
}

/**
 * The palette is open exactly while the first token is still being typed: the
 * draft starts with `/` and no whitespace has been typed yet. Returns the query, or
 * null when the palette should be hidden.
 */
internal fun paletteQueryOf(draft: String): String? {
    val trimmed = draft.trimStart()
    if (!trimmed.startsWith("/")) return null
    if (trimmed.any { it.isWhitespace() }) return null
    return trimmed.removePrefix("/")
}


/**
 * The 0-based index of the user-message row [key] among the list's user-message
 * rows, or -1 when the list does not hold it.
 *
 * This is the app-side half of pi's fork list order: `getUserMessagesForForking`
 * walks the session's entries in file order and keeps every user message with
 * text (`core/agent-session.ts:3309-3327`), and a transcript row is projected
 * from one of those same entries, so the two orders agree. It is derived on
 * demand — the callback runs once per long press — rather than kept as a map
 * that a streaming publication would rebuild on every chunk (the append-only
 * cost this screen already went out of its way to avoid, see `userRowIndices`).
 *
 * The caller in the ViewModel trusts the value only together with the row's own
 * text, because one entry can project no user row at all (`Transcript.kt:2353-2368`).
 */
internal fun userMessageOrdinal(items: List<TranscriptItem>, key: String): Int {
    var ordinal = 0
    for (item in items) {
        if (item !is UserMessage) continue
        if (item.key == key) return ordinal
        ordinal++
    }
    return -1
}


/**
 * How long the composer waits before asking for `@` mention candidates.
 *
 * pi debounces this trigger by 20 ms and then spawns `fd` in its own process
 * (`packages/tui/src/components/editor.ts:251`, `:2362-2385`). Here the lookup is a
 * whole guest process (`ui/chat/PiMentionSource.kt`), so the wait is longer on
 * purpose. It is a delay, not a different answer: the debounce only decides *when*
 * the query runs, and a superseded query's result is dropped rather than shown.
 * No measured latency is claimed for that guest process - there is no device here.
 */
internal const val MENTION_DEBOUNCE_MS: Long = 150L

/** 参数补全的防抖：每个按键都去问扩展太吵，停 120 ms 再问；旧应答由 ViewModel 丢弃。 */
internal const val ARG_COMPLETION_DEBOUNCE_MS: Long = 120L


/**
 * F34 / spec §4.5: how many transcript rows are rendered
 * at once, and how many more each "load earlier" step (or a scroll to the top) reveals.
 * The spec's number is 50 for the first paint; the same step is used for the batches,
 * so the window grows in the units the spec describes.
 *
 * This is a *rendering* bound, not a data bound: the reducer keeps every row, which is
 * what makes the earlier batches instant and keeps search, the prompt jumps and the
 * reducer's own replay independent of how much is on screen.
 */
internal const val TRANSCRIPT_WINDOW_STEP = 50


/**
 * How many row keys are remembered as "the window has just brought this row in, and it has
 * never been measured" — the gate for the one synchronous markdown parse a row may pay for
 * (`LocalPiMarkdownImmediate`, P3 of `docs/scroll-diagnosis.md` §1.2).
 *
 * One batch is at most [TRANSCRIPT_WINDOW_STEP] rows (the file read's rows enter the *window*
 * at that step too, however many entries it read), and the window's head can be grown again
 * before the reader has composed the previous batch's rows — so this holds several batches'
 * worth. A key dropped off the end costs that row the old behaviour (one frame at zero
 * height); it never costs correctness, and the set cannot grow with the session.
 */
internal const val FRESH_ROW_KEYS_MAX = 200


internal data class TailObservation(
    val revision: Int,
    val renderedCount: Int,
    val poke: Long,
    val totalItems: Int,
    val firstVisibleIndex: Int,
    val firstVisibleOffsetPx: Int,
    val lastVisibleIndex: Int,
    val lastVisibleOffsetPx: Int,
    val lastVisibleSizePx: Int,
    val viewportEndOffsetPx: Int,
    val afterContentPaddingPx: Int,
    val isScrollInProgress: Boolean,
    val atBottom: Boolean,
)

/**
 * 极简空对话欢迎画布（纯前端 UI 层，零修改官方 Pi SDK）：
 *  - 视觉焦点：`80dp` 双层纸面留白框承载自定义 Pi 形象徽标，在深/浅主题下立体克制；
 *  - 自然问候：无边框、无多余胶囊（工作区已在顶栏、模型已在底栏），按本地时段轻声问候；
 *  - 零跳动过渡：下方预留固定高度静谧状态槽，仅在引擎连接/会话加载期淡入 `PiSpinner + 文案`，
 *    就绪后平滑淡出，图标与问候语纹丝不动。
 */
@Composable
internal fun ChatEmptyWelcome(
    statusNote: String? = null,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val emblemOuterShape = RoundedCornerShape(24.dp)
    val emblemInnerShape = RoundedCornerShape(21.dp)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else palette.cardBg
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.80f)
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> tr("早上好，想从哪开始？", "Good morning. Where shall we start?")
        in 12..13 -> tr("中午好，想聊点什么？", "Good afternoon. What's on your mind?")
        in 14..17 -> tr("下午好，想聊点什么？", "Good afternoon. What's on your mind?")
        in 18..22 -> tr("晚上好，想聊点什么？", "Good evening. What's on your mind?")
        else -> tr("夜深了，想聊点什么？", "Up late. What's on your mind?")
    }

    Column(
        modifier = modifier
            .padding(horizontal = 32.dp)
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.Surface(
            shape = emblemOuterShape,
            color = cardBg,
            shadowElevation = if (isDark) 0.dp else 6.dp,
            border = androidx.compose.foundation.BorderStroke(PiSpacing.hairlineThin, hairline),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = app.pi.R.drawable.pi_welcome_emblem),
                contentDescription = tr("Pi 形象图标", "Pi emblem"),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .padding(3.dp)
                    .size(80.dp)
                    .clip(emblemInnerShape),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = greeting,
            style = app.pi.ui.components.PiType.title.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        var retainedStatusNote by remember { mutableStateOf(statusNote.orEmpty()) }
        if (!statusNote.isNullOrBlank()) {
            retainedStatusNote = statusNote
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.heightIn(min = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !statusNote.isNullOrBlank(),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    app.pi.ui.components.PiSpinner(
                        size = 12.dp,
                        strokeWidth = 1.5.dp,
                        color = palette.muted,
                    )
                    Text(
                        text = retainedStatusNote,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScrollArrowButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    unreadCount: Int = 0,
    dimmed: Boolean = false,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "scrollBtnScale",
    )
    val hasUnread = unreadCount > 0
    val targetAlpha = when {
        pressed || hasUnread -> 1f
        dimmed -> 0.36f
        else -> 0.92f
    }
    val smoothAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = androidx.compose.animation.core.tween(240),
        label = "scrollBtnAlpha",
    )
    val shape = RoundedCornerShape(999.dp)
    val containerBg = if (hasUnread) palette.accent else palette.cardBg.copy(alpha = 0.94f)
    val borderColor = if (hasUnread) palette.accent else palette.borderMuted.copy(alpha = 0.72f)
    val fg = if (hasUnread) palette.contrastOn(palette.accent) else palette.text.copy(alpha = 0.85f)
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = smoothAlpha
            }
            .clip(shape)
            .background(containerBg)
            .border(
                width = PiSpacing.hairlineThin,
                color = borderColor,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = palette.accent),
                onClickLabel = label,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .padding(
                horizontal = if (hasUnread) 11.dp else 8.dp,
                vertical = 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = fg,
        )
        if (hasUnread) {
            Text(
                text = if (unreadCount > 99) "99+" else "$unreadCount",
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = fg,
                maxLines = 1,
            )
        }
    }
}

internal val TailFollowSaver: Saver<TailFollow, Any> = listSaver(
    save = { it.savedState() },
    restore = { saved -> TailFollow.fromSavedState(saved) },
)
