package app.pi.ui.blocks

import app.pi.rpc.TranscriptItem

/**
 * 转写行的底部留白（块间距）—— **唯一**一份真值。
 *
 * ## 它为什么存在（根因，不是"修好了断线"）
 *
 * 轨道的竖线靠"往自己边界外多画一段"去接下一张卡（`ToolRail.kt` 的
 * `bottom = if (lastOfRun) RAIL_LINE_INSET else -RAIL_BRIDGE`），壳的底与左右描边用的是
 * 同一个手法。这个**跨越量曾经是编译期常量** `RAIL_BRIDGE = PiSpacing.blockGap`（8），
 * 而**实际间距是运行期设置**（`app.appearance.messageDensity`：4 / 8 / 16）——
 * 两者只在默认档相等，于是「宽松」档下每张卡之间差 8 dp，**线就断在那儿**；
 * 壳若照抄，断的会是从"线"变成"一整块板子上的缝"。
 *
 * ## 修法：让留白"跟随排版自然来"
 *
 * 用户裁定不要"把间距作为参数传下去"（那仍是两处各存一份、迟早不同步），改成：
 *
 *  1. **留白是每一行自己的属性**（这个函数），不再由列表统一 `Arrangement.spacedBy` 给；
 *  2. `BlockColumn` 把它作为**自己的底部 padding** 落地（唯一一处给间距，避开 F11 的
 *     18 + 9 + 9 = 36 dp 双倍坑）；
 *  3. 轨道与壳从 `LocalRowGap` **读回同一个值**做跨越，读自己、不需要同步。
 *
 * 于是任何密度档、以及以后"每类行不同留白"的加强，轨道与壳都自动跟随：三条读者路径
 * 都只有这一个来源。
 *
 * ## 三档的值
 *
 * `06 §2` 的「块间距 8」是**记账的偏离**（`差异表` §2 第 7 行）：默认档取 `PiSpacing.small`
 * 的 4，紧凑与宽松各是它的一半与两倍（2 / 8）—— 三档都保持"翻倍"的关系，密度设置照旧
 * 只挪**节奏**、不挪页边（`06 §2`：屏水平 14 在任何密度档都不变）。
 *
 * 这里的三个数是**字面量**而不是 `PiSpacing` 的引用：`PiSpacing` 是 Compose 的 `Dp` 类型，
 * 而这个文件必须能被裸 JVM 的 harness 编译（`tools/run-app-pure-checks.sh` 的
 * `tool-state` 一项就钉着这三档与实际底部留白的一致性）。数值与令牌的对应写在常量名上：
 * `DEFAULT_ROW_GAP_DP` = `PiSpacing.small`，`COZY_ROW_GAP_DP` = `PiSpacing.blockGap`。
 */

/** 「紧凑」档：默认档的一半。 */
internal const val COMPACT_ROW_GAP_DP: Int = 2

/** 默认档 —— `PiSpacing.small`；`06 §2` 写 8，这是一处记账的偏离（`差异表` §2 第 7 行）。 */
internal const val DEFAULT_ROW_GAP_DP: Int = 4

/** 「宽松」档：默认档的两倍（= `PiSpacing.blockGap`，也就是 `06 §2` 的 8）。 */
internal const val COZY_ROW_GAP_DP: Int = 8

/**
 * 这一行自己的底部留白，按 dp。**今天每类行同值**——留白仍是一份全局节奏，只是这份节奏
 * 的落点从"列表的 Arrangement"搬到了"行自己"；将来要做"每类行不同留白"（用户说的
 * 「万一以后排版功能再加强」），就是在这里按 [item] 分叉，`BlockColumn` 与 `ToolRailFrame`
 * 不用动一行。
 */
internal fun TranscriptItem.rowGapDp(density: String?): Int = blockGapDp(density)

/**
 * 密度设置 → 块间距（dp），给**没有 `TranscriptItem` 可问**的那一处用：
 * 列表顶端那条「加载更早」的哨兵行（它是覆盖在列表上的 Chrome，不是转写行），
 * 它下方要留出的正是同一份节奏 —— 同一个函数，不是第二份真值。
 */
internal fun blockGapDp(density: String?): Int = when (density) {
    "compact" -> COMPACT_ROW_GAP_DP
    "cozy" -> COZY_ROW_GAP_DP
    else -> DEFAULT_ROW_GAP_DP
}
