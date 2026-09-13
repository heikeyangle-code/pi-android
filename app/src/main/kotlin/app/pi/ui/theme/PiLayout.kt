package app.pi.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The geometry the frozen v2 board fixes and the base app never had.
 *
 * ## Why this file exists at all
 *
 * `PiSpacing` (`theme/PiTheme.kt:42-128`) was written against
 * `docs/pi-android-ui-spec.md`, which this project has since declared out of date
 * (`00-screen-inventory.md:4`): its page margin is `screen = 16.dp`, its card
 * padding is `card = 12.dp`, and it carries 13 tokens with zero call sites
 * (`00-screen-inventory.md` §5.3). The frozen v2 board
 * (`design-demos/direction-b-v2.html`) uses 14 / 12 / 10 / 7 and an 8 dp block
 * rhythm, and the construction reference (`06-v2-construction-reference.md` §2)
 * gives those numbers verbatim. Squeezing them back into `PiSpacing` under the old
 * names would have made `screen` mean two different numbers depending on who reads
 * it.
 *
 * ## Why the values are here and not in `PiSpacing` (yet)
 *
 * Another change owns `theme/PiTheme.kt` right now — the bundled monospace face,
 * which moves the three `PiTextStyles` roles and therefore touches that file as a
 * whole. Editing the same file from two directions is how a token quietly goes
 * missing, so these constants live here for the construction batches and fold into
 * `PiSpacing` in one later move, once `PiTheme.kt` is stable. `PiSpacing` is
 * **not** touched by this batch, and every value below has exactly one home.
 *
 * ## The rule these numbers follow
 *
 * `06 §2` 「线宽：全篇只有 1px」. Every stroke in v2 is 1dp: no 2dp divider, no
 * 2dp card border, no 3dp focus ring. [hairline] is that rule's name here.
 *
 * ## What is deliberately absent
 *
 * `06 §5` says the workspace has no ≥600dp two-pane layout (the terminal was
 * retired), so there is **no breakpoint width constant** — a constant nothing
 * consults is how `PiSpacing` accumulated its 13 zero-reference tokens in the
 * first place. Add one here when a screen actually needs it.
 */
internal object PiV2Layout {

    // ------------------------------------------------------------ 页面与容器

    /** `06 §2`「屏水平 14px」: the page margin every v2 screen uses. */
    val pageHorizontal = 14.dp

    /** `06 §2`「卡片内 12px」: the interior padding of a default card. */
    val cardPadding = 12.dp

    /**
     * `06 §2`「当前目录卡 / 设备桥卡 14px」: the two cards whose content is a
     * heading plus a full-width row rather than a stack of lines, so their padding
     * is one step looser than [cardPadding].
     */
    val cardPaddingLoose = 14.dp

    /** `06 §2`「分组头 padding:0 14px; margin-bottom:7px」: below a section label. */
    val groupHeaderGap = 7.dp

    /** `06 §2`「分组块 marginTop:18px」: above a section label. */
    val groupGap = 18.dp

    /**
     * `06 §2`「滚动区底部留 14–18px」. 18 is the top of that range and equals
     * `PiSpacing.unit`, which is where the transcript's own rhythm comes from, so a
     * list already using `unit` does not need this constant.
     */
    val scrollBottomPadding = 18.dp

    // ---------------------------------------------------------------- 条与线

    /**
     * `06 §2`「线宽：全篇只有 1px」. Named here because the batches that add the
     * transcript's rail and the tick meter need one spelling of it; it is the same
     * number as `PiSpacing.hairline` (`theme/PiTheme.kt:86`) and the two are meant
     * to collapse into one when `PiTheme.kt` is next edited for real.
     */
    val hairline: Dp = 1.dp

    /** `06 §2`: the transcript's block rhythm (`块间距 8`). */
    val blockGap = 8.dp

    // -------------------------------------------------------------- 顶栏底栏

    /** `06 §2`「顶栏：高 48」. */
    val topBarHeight = 48.dp

    /** `06 §2`「底栏：高 56」. */
    val bottomBarHeight = 56.dp
}
