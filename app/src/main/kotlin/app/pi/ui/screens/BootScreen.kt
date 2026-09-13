package app.pi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.runtime.RuntimeProvisioner
import app.pi.ui.Boot
import app.pi.ui.theme.PiMark
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * First-launch and boot-failure surface.
 *
 * This screen exists instead of a spinner because the first launch genuinely
 * takes tens of seconds (unpacking a Linux userland) and because one step can
 * honestly fail: Android 10+ refuses to execute files in the app's own data
 * directory, and this app's entire runtime lives there. When that happens the
 * user needs to know *which* step failed and what it means, not a generic error.
 *
 * ## Geometry (v2 `direction-b-v2.html` `.b-boot`)
 *
 * One centred column of 30 dp inline padding: a `52` circle badge, the state's
 * title at `17/600`, a body line, and the one action. The three states are
 * `phone59` (准备启动本地引擎), `phone60` (正在安装运行时, with the segmented bar)
 * and `phone61` (引擎没有在运行, with the v2 error block). The copy is the product's
 * own (`02-real-content.md` §7.3) and is not touched by this batch.
 *
 * The badge is the π mark (`brand-spec.md` §1: App 图标 / 顶部标识 / 空态标识) at 26
 * inside its 52 circle, tinted `muted`; the failure state replaces it with `✗` in
 * `error` — the same 三重编码 the rest of the app uses (symbol, then word, then
 * colour), which is what makes the dead-engine state readable without colour.
 *
 * `StepBar` keeps the hand-drawn `Box` pair the file always had (see its KDoc);
 * only its shape changed, from a continuous fill to v2's segmented bar.
 */
@Composable
fun BootScreen(
    boot: Boot,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BootPagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BootBadge(boot)

        when (boot) {
            Boot.Idle -> {
                Spacer(Modifier.height(BootHeadingGap))
                BootHeading("准备启动本地引擎")
                Spacer(Modifier.height(BootBodyGap))
                BootParagraph("首次启动要准备 Linux 运行环境，需要几分钟。不需要 root。")
                Spacer(Modifier.height(BootActionGap))
                BootButton("开始", onRetry)
            }

            is Boot.Working -> {
                Spacer(Modifier.height(BootHeadingGap))
                BootHeading("正在安装运行时")
                Spacer(Modifier.height(BootBodyGap))
                // The step name is the only line on this screen that is *doing*
                // something, so it carries the text colour while everything around
                // it is muted.
                Text(
                    text = boot.step.label,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(BootStepGap))
                Text(
                    text = "步骤 ${boot.step.index + 1} / ${boot.step.total}",
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
                Spacer(Modifier.height(BootBarGapAbove))
                StepBar(boot.step)
                Spacer(Modifier.height(BootWorkNoteGap))
                BootParagraph("只在首次启动时做一次，之后冷启动是秒级。")
            }

            is Boot.Failed -> {
                // "引擎没能启动" described the only case this screen had when it was
                // written. It is also where a *mid-session* engine death now lands
                // (the ViewModel publishes `Boot.Failed` when pi exits on its own, so
                // the composer cannot write into a closed pipe), and for that case the
                // old title was false: the engine had started, and stopped. The
                // neutral wording is true for both; the message below says which.
                Spacer(Modifier.height(BootHeadingGap))
                BootHeading("引擎没有在运行")
                Spacer(Modifier.height(BootCardGap))
                BootErrorCard(message = boot.message, detail = boot.detail)
                Spacer(Modifier.height(BootCardActionGap))
                BootButton("重试", onRetry)
                Spacer(Modifier.height(BootNoteGap))
                BootParagraph("如果反复失败，把这个提示连同它下面的文字发给我。")
            }

            Boot.Ready -> Unit
        }
    }
}

// ---------------------------------------------------------------- the three states

/**
 * The 52 dp circle above every state.
 *
 * `phone59`/`phone60` carry the π mark at `muted` inside it (the board's badge
 * has `color:var(--muted)`, i.e. the mark is not the accent — the accent is
 * reserved for the one live thing on the screen); `phone61` swaps the mark for
 * `✗` in `error`, which is the failure's symbol in `06 §4`'s table.
 *
 * 17 sp is the board's `mono t17` for that glyph and is the only inline size in
 * this file: `PiTheme.text.mono` is the app's 13 sp machine role, and the badge
 * glyph is the one place v2 draws it at 17.
 */
@Composable
private fun BootBadge(boot: Boot) {
    val palette = PiTheme.palette
    Box(
        modifier = Modifier
            .size(BootBadgeSize)
            .border(PiSpacing.hairline, palette.borderMuted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (boot is Boot.Failed) {
            Text(
                text = "✗",
                style = PiTheme.text.mono.copy(fontSize = 17.sp, lineHeight = 22.sp),
                color = palette.error,
            )
        } else {
            PiMark(size = BootBadgeMarkSize, tint = palette.muted)
        }
    }
}

/** `17/600` — `MaterialTheme.typography.titleMedium` is exactly that. */
@Composable
private fun BootHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = PiTheme.palette.text,
    )
}

/**
 * `14` body copy, centred.
 *
 * `.b-boot` is `text-align:center`, so a line that wraps has to be centred as a
 * paragraph, which needs the full width plus [TextAlign.Center] — a centred
 * *composable* whose text is start-aligned would leave the second line hanging
 * left.
 */
@Composable
private fun BootParagraph(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = PiTheme.palette.muted,
        textAlign = TextAlign.Center,
    )
}

/**
 * The v2 error block (`06 §2` 错误块): radius 10, `tool-error` fill, a 1 px
 * `error` at 45 %, a 3 px `error` stripe down the left edge, `padding:10px 12px`.
 *
 * `phone61` shows the message at `14` in the text colour and the detail as a
 * monospace `12` in `bodyOnTool` — that token exists for exactly this (text on a
 * tool surface, contrast-corrected against all three tool fills), which is why
 * the detail is not painted `error` as well: the block is already red, and a red
 * machine line at 12 px is the least readable thing on the screen.
 *
 * The stripe sits outside the padding, flush with the card's left edge, the same
 * way `PiPackagesScreen`'s invalid-trust card draws it.
 */
@Composable
private fun BootErrorCard(message: String, detail: String?) {
    val palette = PiTheme.palette
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(BootCardRadius),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(PiSpacing.hairline, palette.error.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(PiSpacing.stripe)
                    .fillMaxHeight()
                    .background(palette.error),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "✗",
                    style = PiTheme.text.mono,
                    color = palette.error,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.text,
                    )
                    if (!detail.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = detail,
                            style = PiTheme.text.monoSmall,
                            color = palette.bodyOnTool,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one action of a state: v2's `Btn primary` — 34 high, `padding:0 12px`,
 * radius 8, accent fill, the page colour as its label colour.
 *
 * Built from a `Box` rather than `Button` because M3's `Button` enforces a 48 dp
 * minimum interactive size and a 40 dp minimum height, which would make this
 * button visibly taller than the board's. The label is `14/600` rather than the
 * board's `13`: the app has no sans 13 role (`B7` owns the type scale), and 14 is
 * the role every other button label in this app already uses.
 */
@Composable
private fun BootButton(label: String, onClick: () -> Unit) {
    val palette = PiTheme.palette
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(BootButtonRadius))
            .background(palette.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .height(BootButtonHeight)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = palette.pageBg,
        )
    }
}

/**
 * Hand-drawn progress bar rather than `LinearProgressIndicator`.
 *
 * Deliberate: the indicator's `progress` parameter changed shape between Compose
 * releases, and this app is built somewhere that cannot compile-check locally on
 * every machine. A `Row` of `Box`es has no such surface area.
 *
 * v2 (`phone60`) draws one `4`-high segment per step with a `2` gap and fills a
 * segment only once its step is reached, so the bar counts steps rather than
 * interpolating within one. `RuntimeProvisioner.Step.fraction` is
 * `index / total`, i.e. the same information one step behind, and the board's
 * "步骤 3 / 7" fills three segments — which is why the current step counts as
 * reached.
 */
@Composable
private fun StepBar(step: RuntimeProvisioner.Step) {
    val palette = PiTheme.palette
    val total = step.total.coerceAtLeast(1)
    val reached = (step.index + 1).coerceIn(0, total)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BootBarSegmentGap),
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(BootBarHeight)
                    .background(if (index < reached) palette.accent else palette.borderMuted),
            )
        }
    }
}

// -------------------------------------------------------------- the board's numbers

/** `.b-boot{padding:0 30px}`: the boot surface's own inline margin (not the page 14). */
private val BootPagePadding = 30.dp

/** `.b-boot-badge{width:52px;height:52px}`. */
private val BootBadgeSize = 52.dp

/** `PiMark s={26}` inside the badge. */
private val BootBadgeMarkSize = 26.dp

/** `.b-boot-bar i{height:4px}` / `.b-boot-bar{gap:2px}`. */
private val BootBarHeight = 4.dp
private val BootBarSegmentGap = 2.dp

/** The failure card's `border-radius:10px`. */
private val BootCardRadius = 10.dp

/** `Btn{height:34px;borderRadius:8px}`. */
private val BootButtonHeight = 34.dp
private val BootButtonRadius = 8.dp

/**
 * The board's per-state rhythm, read off `phone59`–`phone61`:
 * `mt18` badge→title, `mt10` title→body, `mt4` step name→counter, `mt18`
 * counter→bar, `mt16` title→error card, and the action's own top gap — 20 after the
 * idle body, 16 after the failure card.
 */
private val BootHeadingGap = 18.dp
private val BootBodyGap = 10.dp
private val BootStepGap = 4.dp
private val BootBarGapAbove = 18.dp
private val BootCardGap = 16.dp
private val BootActionGap = 20.dp
private val BootCardActionGap = 16.dp
private val BootWorkNoteGap = 14.dp
private val BootNoteGap = 18.dp
