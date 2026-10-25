package app.pi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

// ---------------------------------------------------------------- the chip numbers
//
// `06 §2`「chip：高 26 圆角 999 `padding:0 9px`」. These four are the spec row, in one
// place, so the four chips ChatScreen draws (model / search / queue / key row) cannot
// drift apart the way they had (26 vs 24 high, 999 vs 6 radius, 9 vs 7 padding, each
// file spelling its own).

/** `06 §2` 的 chip 高. */
private val CHAT_CHIP_HEIGHT = 26.dp

/** `06 §2` 的 chip 横向内边距. */
private val CHAT_CHIP_PADDING = 9.dp

/** The glyph-to-label gap inside a chip (`06 §2` 的 `padding:0 9px` 之外唯一的间距). */
private val CHAT_CHIP_GLYPH_GAP = 5.dp

// ------------------------------------------------------- the key row's own geometry
//
// v2 draws the composer's key chips as **keys**, not pills: `height:24;min-width:24;
// padding:0 7px;border-radius:6;background:surf-highest` (`direction-b-v2.html:1430-1431`,
// cited in `KeyChip`'s KDoc in `screens/ChatScreen.kt`). `compact = true` keeps that
// shape; see [ChatChip]'s KDoc for why it is the one deviation from the table above.

/** `direction-b-v2.html:1430` 的 `height:24`. */
private val KEY_CHIP_HEIGHT = 24.dp

/** `direction-b-v2.html:1430` 的 `border-radius:6`. */
private val KEY_CHIP_RADIUS = 6.dp

/** `direction-b-v2.html:1430` 的 `padding:0 7px`. */
private val KEY_CHIP_PADDING = 7.dp

/** The icon-to-label gap of a key chip (`Spacer(Modifier.width(4.dp))` before this batch). */
private val KEY_CHIP_ICON_GAP = 4.dp

/** A labelled key chip's icon: 14 dp, so the chip's text and icon share a baseline rhythm. */
private val KEY_CHIP_ICON_SIZE = 14.dp

/**
 * An **icon-only** key chip's icon: 16 dp, not 14. With the same 7 dp of horizontal padding
 * it makes the chip exactly 30 dp wide — the designer's number (16 + 7 + 7), and the weight
 * that matches the send disc opposite it in the row.
 */
private val KEY_CHIP_ICON_ONLY_SIZE = 16.dp

/**
 * The chat page's one chip.
 *
 * ## The spec this implements
 *
 * `06 §2`: chip「高 26 圆角 999 `padding:0 9px`」— one `1px` `borderMuted` ring on the page
 * ground, and the label in the machine face at v2's `mono t12`
 * ([app.pi.ui.theme.PiTextStyles.monoSmall], 12/18). Everything a chip on this page does
 * (the model id, the search arrows, the two queue counts) is machine language, so there is
 * one label role rather than a per-chip choice.
 *
 * ## The two states
 *
 * [active] is the state the settings page's chip calls active — the accent ring
 * (`borderAccent`) over a `selectedBg` ground, label in the normal text colour. [accent] is
 * "this chip *is* an affordance": the label and glyph in `accent`, still on the page
 * ground. Both are off by default, which is where all four of this screen's chips live;
 * they exist so a selected chip and an accented one cannot grow a fifth spelling later.
 *
 * [muted] is the disabled reading (the search arrows with no match, a 引导 chip with
 * nothing to steer): muted label, no tap. It is a separate flag rather than
 * `onClick == null` because the queue's count chips are muted-safe but have no action
 * either — "no action" and "disabled" are not the same fact.
 *
 * ## Why a copy of `PiSettingsChip`, and not a call to it
 *
 * `PiSettingsChip` / `PiSettingsMetrics` are `internal` to `app.pi.ui.settings`, and this
 * batch is not allowed to widen that. The two therefore have to stay in step by hand: the
 * numbers above are the same `06 §2` row, and if that row moves, both files move.
 *
 * ## `compact`: the key row is a different component on purpose
 *
 * The composer's key row (`/`, the paperclip, 引导 / 后续) is not a row of pills: v2 draws
 * each one as a **key**, `24×24` with `border-radius:6`, `padding:0 7px` and the
 * `surf-highest` ground (`direction-b-v2.html:1430-1431`). [compact] keeps that geometry
 * and that filled ground and shares everything else — the label role, the glyph slot, the
 * clip-before-click order, the click label, the 6 dp trailing gap the caller passes in.
 * Turning the key row into 26 dp pills instead would be a `06 §2` 输入区 change, so it is
 * left as an explicit flag rather than folded into the default: it is visible in one
 * place and one edit away.
 *
 * ## Accessibility
 *
 * [onClickLabel] is a **separate** parameter because the paperclip draws no text (the
 * user's ruling: 「附件本身图标就行，不要有汉字」) and an icon-only control whose click label
 * were its empty label would announce nothing at all.
 *
 * @param text the label. Empty is legal and means icon-only: no `Text` is drawn and the
 *   icon takes the 16 dp size ([KEY_CHIP_ICON_ONLY_SIZE]) — a `Text("")` would still claim the
 *   gap and quietly keep the chip wider than it looks.
 * @param glyph a text glyph before [text] (the search arrows `↑`/`↓`, the queue's `⇢`/`⇣`).
 *   Ignored when [icon] is given.
 * @param icon a vector glyph before [text]; the paperclip is the one chip whose glyph
 *   carries meaning. Drawn in the label colour, so a disabled chip greys both halves.
 * @param glyphTone overrides [glyph]'s colour. The queue's counts are the one caller: `⇢`
 *   is `warning` and `⇣` is `muted` (`06 §4` 的第三通道).
 */
@Composable
fun ChatChip(
    text: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    icon: ImageVector? = null,
    glyphTone: Color? = null,
    active: Boolean = false,
    accent: Boolean = false,
    muted: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String = text,
    compact: Boolean = false,
) {
    val palette = PiTheme.palette
    val shape = if (compact) RoundedCornerShape(KEY_CHIP_RADIUS) else PiShapes.badge
    val labelColor = when {
        muted -> palette.muted
        accent -> palette.accent
        else -> palette.text
    }
    val glyphColor = glyphTone ?: if (accent) palette.accent else palette.muted
    val background = when {
        active -> palette.selectedBg
        // The key row's keys are filled, not outlined: `surf-highest` when they can be
        // touched and one step down when they cannot (`direction-b-v2.html:1430`).
        compact && muted -> MaterialTheme.colorScheme.surfaceContainerHigh
        compact -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    val ring = when {
        active -> palette.borderAccent
        // No ring on a key chip: its ground is the shape.
        compact -> null
        else -> palette.borderMuted
    }
    Row(
        modifier = modifier
            .then(
                if (compact) {
                    Modifier.heightIn(min = KEY_CHIP_HEIGHT)
                } else {
                    Modifier.height(CHAT_CHIP_HEIGHT)
                },
            )
            .then(
                if (compact) {
                    Modifier.defaultMinSize(minWidth = KEY_CHIP_HEIGHT)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(background)
            .then(if (ring != null) Modifier.border(PiSpacing.hairline, ring, shape) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (compact) KEY_CHIP_PADDING else CHAT_CHIP_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            if (compact) KEY_CHIP_ICON_GAP else CHAT_CHIP_GLYPH_GAP,
        ),
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(
                    if (text.isEmpty()) KEY_CHIP_ICON_ONLY_SIZE else KEY_CHIP_ICON_SIZE,
                ),
                tint = labelColor,
            )

            glyph != null -> Text(
                text = glyph,
                style = PiTheme.text.monoSmall,
                color = glyphColor,
                maxLines = 1,
            )
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = PiTheme.text.monoSmall,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
