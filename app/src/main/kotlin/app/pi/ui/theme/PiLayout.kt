package app.pi.ui.theme

import androidx.compose.ui.unit.Dp

/**
 * **Retired forwarding names — do not add members and do not use in new code.**
 *
 * This object used to *own* the v2 geometry (page margin, card padding, group
 * gaps, the block rhythm, the two bar heights). B7 folded every one of those
 * values into `PiSpacing` (`theme/PiTheme.kt`), which is where the single table
 * now lives, and left this object behind for exactly one reason: the numbers are
 * also read from the **other half** of the file division
 * (`10-final-audit-plan.md` §1), which this batch may not edit —
 * `ui/screens/ProjectScreen.kt:147,331`, `ui/screens/BootScreen.kt:163,227`,
 * `ui/extension/ExtensionDialogs.kt:133,194,202,502` and
 * `ui/extension/ExtensionChrome.kt:66,112,120` all name `PiV2Layout`.
 *
 * Every member below is a *forward* to its `PiSpacing` home: no number is written
 * twice, so there is one table with two spellings rather than two tables. Delete
 * this file in the same commit that moves those twelve call sites onto
 * `PiSpacing`; nothing in `ui/blocks`, `ui/render`, `ui/chat`, `ui/theme`,
 * `ui/components/PiCommon.kt` or `ui/screens/ChatScreen.kt` refers to it any more.
 *
 * `hairline` is forwarded here for the same reason as everything else: five
 * other-half call sites still spell it `PiV2Layout.hairline`
 * (`ui/screens/BootScreen.kt:163,227`, `ui/extension/ExtensionDialogs.kt:172,180,468`,
 * `ui/extension/ExtensionChrome.kt:120`). It was always the same number as
 * `PiSpacing.hairline`, and it forwards to it — no second value.
 */
@Deprecated("Folded into PiSpacing — see this file's KDoc for the twelve call sites still to move.")
internal object PiV2Layout {

    /** `06 §2`「屏水平 14px」→ [PiSpacing.pageHorizontal]. */
    val pageHorizontal = PiSpacing.pageHorizontal

    /** `06 §2`「卡片内 12px」→ [PiSpacing.cardPadding]. */
    val cardPadding = PiSpacing.cardPadding

    /** `06 §2`「当前目录卡 / 设备桥卡 14px」→ [PiSpacing.cardPaddingLoose]. */
    val cardPaddingLoose = PiSpacing.cardPaddingLoose

    /** `06 §2`「分组头 margin-bottom:7px」→ [PiSpacing.groupHeaderGap]. */
    val groupHeaderGap = PiSpacing.groupHeaderGap

    /** `06 §2`「分组块 marginTop:18px」→ [PiSpacing.groupGap]. */
    val groupGap = PiSpacing.groupGap

    /** `06 §2`「滚动区底部留 14–18px」→ [PiSpacing.scrollBottom]. */
    val scrollBottomPadding = PiSpacing.scrollBottom

    /** `06 §2`「线宽：全篇只有 1px」→ [PiSpacing.hairline]. */
    val hairline: Dp = PiSpacing.hairline

    /** `06 §2`「块间距 8」→ [PiSpacing.blockGap]. */
    val blockGap = PiSpacing.blockGap

    /** `06 §2`「顶栏：高 48」→ [PiSpacing.topBarHeight]. */
    val topBarHeight = PiSpacing.topBarHeight

    /** `06 §2`「底栏：高 56」→ [PiSpacing.bottomBarHeight]. */
    val bottomBarHeight = PiSpacing.bottomBarHeight
}
