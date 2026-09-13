package app.pi.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 状态的三重编码：**字 + 符号 + 颜色**。
 *
 * ## It answers
 *
 * 「这一格现在是什么状态？」— and it must answer it for someone who cannot tell
 * two colours apart, or is reading the phone at arm's length in daylight. `04 §2`
 * makes that a rule rather than a preference (从 C 移入的两条纪律之一), and the
 * app already follows it in one place: `blocks/ToolBlockChrome.kt:57-69` spells
 * a tool's state as a word (`运行中`/`成功`/`失败`) *and* a glyph (`…`/`✓`/`✗`),
 * with the colour third. This file is that rule as a component, so the batches
 * that add queue chips, the engine status line, the approval card and the
 * extension/resource badges do not each re-derive the pill.
 *
 * ## Why the tone is an enum and not a `Color` parameter
 *
 * Because the colour must come from pi's palette and nowhere else
 * (`brand-spec.md` §2 红线). A `Color` parameter would let one call site pass
 * `Color(0xFF9E9E9E)` — which v2 does once, for 「被拒」, and which therefore has
 * to be a *named* case ([StateTone.Rejected]) mapped onto an existing token rather
 * than an inline literal. One table, in one file, is also the only way the next
 * batch can be reviewed by reading nine lines.
 *
 * ## When it is not shown
 *
 * Never for a *quantity* — a count, a percentage or a token total is not a state
 * and belongs in plain `numeric` text (`05 §4.2`). Never as the only carrier of
 * meaning: [StateChip] always renders [label] as well, and `glyph` is optional
 * only because some rows already carry the symbol elsewhere (a switch row has its
 * own control; a session row's 「当前」 is a word, per `06 §4`).
 */

/**
 * The state vocabulary of `06 §4`, mapped onto pi tokens.
 *
 * Every case resolves through [stateToneColor] to a token that is **not** new:
 * `accent`, `success`, `warning`, `error`, `muted` and `dim` are six of pi's own
 * 56 (`brand-spec.md` §2.1), and the two composite cases ([Rejected], [Running])
 * are combinations of them because v2 uses a *different* token for a state that is
 * not a failure.
 */
enum class StateTone {
    /** Selected / enabled / the one current item. pi's `accent`. */
    Accent,

    /** Done. pi's `success`. */
    Success,

    /** In flight, or waiting on the user. pi's `warning`. */
    Warning,

    /** Broken. pi's `error`. */
    Error,

    /**
     * A state that is neither good nor bad: 「被拒」(`06 §4`: 「被拒不是失败，不用
     * error 色」), an idle reading, a filter that matched nothing.
     *
     * v2 paints this `#9E9E9E`, which is **not** one of pi's 56 tokens — the
     * reference doc's own §2 lists it beside the surfaces as an app-side value
     * (`06 §2` 颜色行). Decision **D2** (`07-construction-decisions.md`) resolves it
     * to `bodyOnTool`, the app's derived tool-body grey: it is the nearest existing
     * token (dark `#979797`, i.e. the design's grey to within one lift step), it is
     * already contrast-corrected against all three card grounds, and it keeps the
     * state out of `error` (which would say "failure") and out of `dim` (which would
     * read as disabled). This file is the only place the mapping lives — see
     * [stateToneColor].
     */
    Rejected,

    /** Secondary information: a separator, a hint, an inactive affordance. */
    Muted,

    /**
     * Disabled. pi's `dim` (`#666666`) — one step below [Muted], which is the
     * ordering the token names already encode.
     */
    Dim,
}

/**
 * The colour of [tone], from the palette in scope.
 *
 * A plain function rather than a `@Composable` so a caller that needs the colour
 * but not the pill — the engine status line's glyph, a queue chip's arrow, the
 * rail node's circle — does not have to wrap a `Row` around it. It takes the
 * palette as an argument rather than reading `PiTheme.palette` so it stays a pure
 * function of its inputs, which is what `05 §0` asks of these primitives.
 */
fun stateToneColor(tone: StateTone, palette: PiPalette): Color = when (tone) {
    StateTone.Accent -> palette.accent
    StateTone.Success -> palette.success
    StateTone.Warning -> palette.warning
    StateTone.Error -> palette.error
    // D2: see [StateTone.Rejected] — the neutral reading, not `muted` (which is one
    // lift step darker and drops under the 4.5:1 body floor on a success card).
    StateTone.Rejected -> palette.bodyOnTool
    StateTone.Muted -> palette.muted
    StateTone.Dim -> palette.dim
}

/**
 * The pill: `字 + 符号 + 颜色` in one component.
 *
 * Geometry is `06 §2`'s badge row verbatim — `border-radius:999`,
 * `padding:1px 7px 1px 6px`, `1px solid var(--border-muted)`, an optional
 * `5×5` corner-radius-1 colour block, an optional 12 px mono glyph, and 12 px text
 * in the normal text colour. The colour is therefore **never** the label's colour:
 * only the glyph and the dot carry it, which is what makes the encoding survive a
 * greyscale screenshot.
 *
 * @param label the words. Required — [StateTone] alone is not a state (see the
 *   file KDoc). `06 §4` uses 「运行中 / 成功 / 失败 / 被拒 / 就绪 / 工作中 / 已允许 /
 *   已拒绝」etc.
 * @param tone which state, and therefore which pi token colours the glyph.
 * @param glyph the symbol from `06 §4`'s table (`… ✓ ✗ ⊘ ◌ ≡ ■ ! ·`). Rendered in
 *   the mono face so the pill does not change width as the state advances.
 * @param dot draw the `5×5` colour block before the glyph. v2 uses it where the
 *   pill is a *legend* rather than an inline reading (the 「当前」 session badge,
 *   the device-bridge status card).
 * @param contentDescription overrides what a screen reader announces. The default
 *   is `"<label>"`; pass a fuller sentence where the label alone is ambiguous
 *   (a bare 「成功」 next to three other cards).
 */
@Composable
fun StateChip(
    label: String,
    tone: StateTone,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    dot: Boolean = false,
    contentDescription: String? = null,
) {
    val palette = PiTheme.palette
    val color = stateToneColor(tone, palette)
    Row(
        modifier = modifier
            .border(PiV2Layout.hairline, palette.borderMuted, CircleShape)
            .padding(start = 6.dp, end = 7.dp, top = 1.dp, bottom = 1.dp)
            .semantics { this.contentDescription = contentDescription ?: label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(color, RoundedCornerShape(1.dp)),
            )
            Spacer(Modifier.width(5.dp))
        }
        if (glyph != null) {
            Text(
                text = glyph,
                style = PiTheme.text.monoSmall,
                color = color,
                maxLines = 1,
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.text,
            maxLines = 1,
        )
    }
}

/**
 * The 17 dp rail node of `06 §3` 构件 1 — a circle with the state glyph centred.
 *
 * ## It answers
 *
 * 「这条执行轨道上，这一格过去了没有？」 The rail (`RailRun`) is a 1 dp vertical
 * line with one node per tool call and per diff, and the node is the only thing on
 * that line a reader can land on; the card beside it says *what* ran, the node
 * says *how it ended*.
 *
 * ## Why it is a separate component from [StateChip]
 *
 * Because it carries no word: `06 §2` puts the node at `17×17` with a 1 dp stroke
 * in the state colour at 45 % and a 12 px mono glyph inside. The word lives in the
 * card's footer, a few dp to the right, which is what makes the pair a triple
 * encoding rather than a duplicate one.
 *
 * @param glyph one character from `06 §4`'s table.
 * @param size the node's edge (`06 §2` says 17). Passed in rather than read from a
 *   layout object, because the rail's geometry is `RailRun`'s business and this
 *   component only owns the circle.
 * @param strokeAlpha the node outline's alpha (`06 §2` says 45 %). A parameter
 *   because the same circle is drawn at a different alpha on the approval card's
 *   countdown; the default is the tool-card one.
 * @param label what a screen reader hears. Required: a bare `✓` or `⊘` is not
 *   readable, and this node has no adjacent word *inside* it.
 */
@Composable
fun PiStateNode(
    glyph: String,
    tone: StateTone,
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    strokeAlpha: Float = 0.45f,
) {
    val palette = PiTheme.palette
    val color = stateToneColor(tone, palette)
    Box(
        modifier = modifier
            .size(size)
            .border(PiV2Layout.hairline, color.copy(alpha = strokeAlpha), CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.monoSmall,
            color = color,
            maxLines = 1,
        )
    }
}
