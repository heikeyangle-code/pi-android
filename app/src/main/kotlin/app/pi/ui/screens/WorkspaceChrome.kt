package app.pi.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import app.pi.ui.theme.stateToneColor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 工作区这一屏自己的 v2 构件。
 *
 * ## 为什么不加进 `ui/components/PiCommon.kt`
 *
 * 这一批的边界是「只改 `ProjectScreen.kt` 与同包新增文件，`PiCommon.kt` 只可新增构件」。
 * 这些构件目前只有工作区一屏用（`Ws` 前缀就是「工作区」），而 `PiCommon.kt` 是别人也在
 * 动的共享文件 —— 放在同包的新文件里，既不与那边冲突，也不把一屏的私有形状塞进公共层。
 *
 * ## 每一个的出处
 *
 * 全部照 `design/ui-refactor/design-demos/workspace-final.html`（它自己又是
 * `direction-b-v2.html` 的构件原样内联），逐条对齐：
 *
 * | 构件 | 稿子里的位置 | 几何 |
 * |---|---|---|
 * | [WsBadge] | `function Badge` (`:474-483`) | 5×5 圆角 1 的点 + 可选 12 等宽符号 + 12 文字，`1px border-muted`、圆角 999、`padding:1px 7px 1px 6px` |
 * | [WsChip] | `function Chip` (`:485-497`) | 高 26、圆角 999、`padding:0 9px`、`1px` 描边（选中 `border-accent`） |
 * | [WsSeg] | `function Seg` (`:499-511`) | 外框 `surf-high` + `border-muted` + 圆角 8、内项高 24 圆角 6，选中 `selected-bg` |
 * | [WsNotice] | `function Notice` (`:615-625`) | 等宽前缀（`·` / `!` / `✗`）+ 13 文本，三档 tone |
 * | [WsErrBlock] | `function ErrBlock` (`:1048-1072`) | 3px 竖条 + `tool-error` 底 + `1px rgba(error,.45)` 边 + 圆角 10 |
 * | `WorkspaceSnackBar`（`ProjectScreen.kt`） | `function Snack` (`:627-643`) | 底部一条：符号 + 文本 + 可选动作，三档底/字 |
 * | [WsSheet] | `function Sheet` (`:514-532`) | scrim `.32` + `surf-high` + 上圆角 16 + 1px 上边；抓手 `32×3`、头 `12px 14px 8px`、标题 15/600 + ✕ `28×28` 圆角 8、副标题 12 muted `mt 4`、正文 `maxHeight 420` / 底部 8、页脚 `10px 14px 14px` |
 * | [WsSectionHeader] | `function Section` (`:424-435`) | `padding:0 14px`、`margin-bottom:7px`、12 标签 + 12 计数 + 右侧 aside |
 * | [WsRow] | `function Row` (`:452-471`) | `padding:10px 12px`、`gap:10`、标题 15/500（等宽时 14）+ 副行 12 + 右值 13 |
 */

// ------------------------------------------------------------------ 段头 ----

/**
 * 段头：左边标签 + 计数，右边一个 slot（design 稿 ④ 的「新建」入口、⑤ 的计数都在那儿）。
 *
 * 与设置页的 `PiSettingsSectionHeader` 同几何（`06 §2`「分组头 padding:0 14px;
 * margin-bottom:7px」+「分组块 marginTop:18px」），差别只有一个：aside 是**可点的
 * slot**而不是一行字 —— 稿子 ④ 段头右侧那颗「+ 新建」是一个控件。
 */
@Composable
internal fun WsSectionHeader(
    label: String,
    count: String? = null,
    aside: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.groupGap,
                bottom = PiSettingsMetrics.groupHeaderGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
    ) {
        Text(
            label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (count != null) {
            Text(
                count,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        aside?.invoke()
    }
}

// ------------------------------------------------------------------ 徽标 ----

/**
 * `字 + 符号 + 颜色` 的状态徽标（`ui/theme/PiStateChip.kt` 的 `StateChip` 是它的正文色
 * 版本）。这里的差别是**来源徽标需要混排**：`项目 .pi` 的「.pi」、`包 · pi-skills` 的包名
 * 必须等宽，而前面的汉字不是 —— 稿子把这件事写成「路径型来源用等宽，抽象来源用文本」。
 */
@Composable
internal fun WsBadge(
    text: String,
    tone: StateTone,
    modifier: Modifier = Modifier,
    mono: String? = null,
    glyph: String? = null,
    dot: Boolean = false,
) {
    val palette = PiTheme.palette
    val color = stateToneColor(tone, palette)
    Row(
        modifier = modifier
            .border(PiSettingsMetrics.hairline, palette.borderMuted, CircleShape)
            .padding(start = 6.dp, end = 7.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(PiSettingsMetrics.badgeDot)
                    .background(color, RoundedCornerShape(PiSettingsMetrics.badgeDotRadius)),
            )
        }
        if (glyph != null) {
            Text(glyph, style = PiTheme.text.monoSmall, color = color, maxLines = 1)
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = palette.text,
                maxLines = 1,
            )
        }
        if (mono != null) {
            Text(mono, style = PiTheme.text.monoSmall, color = palette.text, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ 分段 ----

/** 分段按钮的一项。 */
internal data class WsSegment<T>(val value: T, val label: String)

/**
 * v2 的 `Seg`：一个外框里并排几档，选中的那一档换底换字色。
 *
 * 全应用的资源段（技能 / 提示词 / 扩展 / 主题）与「会话列表 / 会话树」共用一个形状；
 * 这里不复用设置页的 `PiSettingsChip`，因为那是**筛选胶囊**（可以多选、可以都不选），
 * 而分段的语义是「有且只有一个当前项」。
 */
@Composable
internal fun <T> WsSeg(
    items: List<WsSegment<T>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WsSegRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                PiSettingsMetrics.hairline,
                palette.borderMuted,
                RoundedCornerShape(WsSegRadius),
            )
            .padding(WsSegPadding),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            val active = item.value == value
            Box(
                modifier = Modifier
                    .height(WsSegItemHeight)
                    .clip(RoundedCornerShape(WsSegItemRadius))
                    .background(
                        if (active) palette.selectedBg else Color.Transparent,
                    )
                    .clickable { onChange(item.value) }
                    .padding(horizontal = WsSegItemPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.label,
                    style = PiTheme.text.meta.copy(
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (active) palette.text else palette.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

private val WsSegRadius = 8.dp
private val WsSegPadding = 2.dp
private val WsSegItemHeight = 24.dp
private val WsSegItemRadius = 6.dp
private val WsSegItemPadding = 10.dp

// ------------------------------------------------------------------ 胶囊 ----

/** 一颗可点的小胶囊（稿子的 `Chip`）：`⋮` 之外的表头动作、空态的「新建文件」用它。 */
@Composable
internal fun WsChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    glyph: String? = null,
    glyphIcon: ImageVector? = null,
) {
    val palette = PiTheme.palette
    Row(
        modifier = modifier
            .height(PiSettingsMetrics.chipHeight)
            .clip(CircleShape)
            .border(
                PiSettingsMetrics.hairline,
                if (active) palette.borderAccent else palette.borderMuted,
                CircleShape,
            )
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PiSettingsMetrics.chipPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        if (glyphIcon != null) {
            Icon(
                glyphIcon,
                contentDescription = null,
                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                tint = palette.muted,
            )
        }
        if (glyph != null) {
            Text(glyph, style = PiTheme.text.monoSmall, color = palette.muted, maxLines = 1)
        }
        Text(
            text,
            style = PiTheme.text.meta,
            color = palette.text,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ 提示 ----

/** 稿子的 `Notice`：一行提示，三档 tone。`tone` 为 null 就是 Info（muted）。 */
@Composable
internal fun WsNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: StateTone = StateTone.Muted,
    glyph: String = "·",
) {
    val color = stateToneColor(tone, PiTheme.palette)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.notePaddingVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Text(glyph, style = PiTheme.text.monoSmall, color = color, maxLines = 1)
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

// ------------------------------------------------------------------ 错误块 ----

/**
 * 稿子的 `ErrBlock`：3px 竖条 + `tool-error` 底 + `✗` + 一句给人看的话 + 可展开的详情
 * + 动作。读目录失败、读文件失败、保存失败三处共用它（稿子就是这么复用的）。
 *
 * @param detail 原文（异常栈、pi 的原因）；给出时多一行「详情」开关。
 */
@Composable
internal fun WsErrBlock(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.notePaddingVertical,
            )
            .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
            .background(palette.toolErrorBg)
            .border(
                PiSettingsMetrics.hairline,
                palette.error.copy(alpha = 0.45f),
                RoundedCornerShape(PiSettingsMetrics.cardRadius),
            ),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(WsErrBarHeight)
                .background(palette.error),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(
                    horizontal = PiSettingsMetrics.cardPadding,
                    vertical = PiSettingsMetrics.rowPaddingVertical,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
            ) {
                Text("✗", style = PiTheme.text.monoSmall, color = palette.error, maxLines = 1)
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.error,
                )
            }
            if (message != null) {
                Text(
                    message,
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (detail != null) {
                Text(
                    if (open) "收起" else "详情",
                    modifier = Modifier
                        .padding(top = PiSettingsMetrics.notePaddingVertical)
                        .clickable { open = !open },
                    style = PiTheme.text.meta,
                    color = palette.error,
                )
                if (open) {
                    Text(
                        detail,
                        modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                        style = PiTheme.text.monoSmall,
                        color = palette.bodyOnTool,
                    )
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.padding(top = PiSettingsMetrics.notePaddingVertical),
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.pageHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/**
 * 竖条的高度。稿子的竖条是 `flex` 撑满整块，Compose 里让它跟内容走：
 * 这个高度是错误块两行正文加内边距的下限，短错误与长错误都不会看见一条比内容矮的条。
 */
private val WsErrBarHeight = 78.dp

/** 错误块里的一个文字动作（`重试` / `回到工作区` / `复制错误`）。 */
@Composable
internal fun WsErrAction(label: String, tone: Color? = null, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = tone ?: PiTheme.palette.accent,
    )
}

// ------------------------------------------------------------------ 行 ----

/**
 * 这一屏的通用行（稿子的 `Row`）。
 *
 * 四个槽从左到右：前置图标、标题（可等宽、可粗）+ 徽标、右值、尾部（⋮ 或读数）。
 * 副行在标题下面。整行可点（稿子的行都是可点的），没有 `onClick` 时不可点。
 *
 * @param mono 标题用等宽（**文件名与路径一律等宽**，稿子的规矩）。
 * @param value 右侧读数（`+N −M`、`12 项`），等宽。
 */
@Composable
internal fun WsRow(
    title: String,
    modifier: Modifier = Modifier,
    lead: (@Composable () -> Unit)? = null,
    mono: Boolean = false,
    strong: Boolean = false,
    titleColor: Color? = null,
    badge: (@Composable () -> Unit)? = null,
    meta: String? = null,
    metaMono: Boolean = false,
    value: (@Composable () -> Unit)? = null,
    trail: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        if (lead != null) {
            Box(Modifier.size(PiSettingsMetrics.cardIconSize), contentAlignment = Alignment.Center) {
                lead()
            }
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = if (mono) {
                        PiTheme.text.mono
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (strong) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                badge?.invoke()
            }
            if (meta != null) {
                Text(
                    meta,
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = if (metaMono) PiTheme.text.monoSmall else PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.invoke()
        trail?.invoke()
    }
}

/** 行之间的 inset hairline（与设置页那条同值、同一根线）。 */
@Composable
internal fun WsHairline(inset: Boolean = true) {
    HorizontalDivider(
        modifier = if (inset) {
            Modifier.padding(start = PiSettingsMetrics.pageHorizontal + PiSettingsMetrics.dividerInset)
        } else {
            Modifier
        },
        thickness = PiSettingsMetrics.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 一行里那个「更多」按钮（稿子的 `MoreBtn`：26×26 圆角 7）。 */
@Composable
internal fun WsMoreButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("⋮", style = PiTheme.text.mono, color = PiTheme.palette.muted, maxLines = 1)
    }
}

/** 一张卡（稿子的 `Card`）：`surf-low` 底、圆角 10、左右 14 的页边。 */
@Composable
internal fun WsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = RoundedCornerShape(PiSettingsMetrics.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column { content() }
    }
}

// ------------------------------------------------------------------ Sheet ----

/**
 * 这一屏自绘的底部 sheet（稿子的 `Sheet`，`workspace-final.html:514-532`）。
 *
 * ## 为什么不是 M3 的 `ModalBottomSheet`
 *
 * 本屏原来三处底部面板都是 `ModalBottomSheet`。它白送三件事（下滑关闭、点 scrim
 * 关闭、返回键关闭），但也自带一副改不掉的壳：它自己的抓手与顶距、28 的圆角、
 * 正文槽的留白，而且**标题行里没有那个 ✕** —— 稿子的 sheet 有。这一屏要的是
 * `.b-sheet` 的那套几何（上圆角 16、`surf-high`、1px 上边、抓手 `32×3`、头
 * `12px 14px 8px`），所以壳改自绘；白送的三件事在这里补回来：
 *
 *  - **下滑关闭**：抓手与标题行那一带由 [draggable] 拖（内容区自己滚动时不会被抢），
 *    内容区拉到顶以后的余量由 [nestedScroll] 交给 sheet —— 松手过 [WsSheetDismiss]
 *    或甩得够快就关，否则弹回；
 *  - **点 scrim 关闭**：scrim 是整屏一层 `rgba(0,0,0,.32)`，自己吃掉点击；
 *  - **返回键关闭**：这一层是 `Dialog` 窗口，`dismissOnBackPress` 把按键交给
 *    `onDismissRequest`（见下面那段注释）—— 与旧的 `ModalBottomSheet` 同一条路径，
 *    所以这一屏**仍然没有**自己的 `BackHandler`。
 *
 * @param subtitle 副标题：12 muted，`margin-top:4`（稿子 `Sheet` 的 `p.sub`）。
 * @param subtitleMono 副标题是路径这类机器输出时用等宽（稿子菜单面板的 `p.sub`
 *   就是一个 `span.mono`）。
 * @param trailing 标题右侧的可选槽位（稿子 `p.right`）；这一屏目前没有面板用它。
 * @param footer 可选页脚：`10px 14px 14px` + 1px 上边（稿子 `p.footer`）。
 * @param maxBodyHeight 内容区上限，稿子默认 420（切换工作区那一处传 520）。
 */
@Composable
internal fun WsSheet(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    footer: String? = null,
    maxBodyHeight: Dp = PiSettingsMetrics.sheetBodyMax,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(
        topStart = PiSettingsMetrics.sheetTopRadius,
        topEnd = PiSettingsMetrics.sheetTopRadius,
    )
    // 关闭回调每次重组都是新的一份 lambda，而下面两个 `remember` 只建一次 —— 用
    // `rememberUpdatedState` 收住它，免得手势一直拿着第一次那一份。
    val close = rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val dismissAt = with(LocalDensity.current) { WsSheetDismiss.toPx() }

    // 入场：稿子 `.b-sheetwrap{animation:bFade .13s ease both}`（整层，scrim 与面板）+
    // `.b-sheet{animation:bRise .16s cubic-bezier(.2,.7,.3,1) both}`（面板自己：淡入 +
    // `translateY(14px)`）。**只做入场** —— 稿子没写退场，关掉就直接消失。
    //
    // 两条动画都只是「当前值」：透明度进 `graphicsLayer`、位移与拖动量相加进 `offset`，
    // 不动布局、不动命中区、不碰 Dialog 的窗口，所以返回键 / 点 scrim / 拖动都不受影响
    // （拖动中途动画还在跑也只是两个偏移相加，没有状态机）；首帧的面板已经在最终布局上
    // （只是透明），系统把动画时长缩成 0 时它一上来就落在终点，不会「面板不出来」。
    val fade = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, tween(WsSheetFadeMillis, easing = WsSheetFadeEasing)) }
        launch { rise.animateTo(1f, tween(WsSheetRiseMillis, easing = WsSheetRiseEasing)) }
    }
    val riseDistance = with(LocalDensity.current) { WsSheetRiseDistance.toPx() }

    /** 手一松：够远了（或甩得够快）就关；只是拉下来一点就弹回原位。 */
    fun release(velocity: Float) {
        if (!dragging) return
        dragging = false
        if (dragOffset > dismissAt || velocity > WsSheetFlingVelocity) {
            close.value()
        } else if (dragOffset > 0f) {
            scope.launch {
                animate(
                    initialValue = dragOffset,
                    targetValue = 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ) { value, _ -> dragOffset = value }
            }
        }
    }

    // 内容区自己滚不动之后的余量：往下拉到顶时余量变成 sheet 的下滑（`ModalBottomSheet`
    // 里这是白送的），往上推时先把已经拉下来的 sheet 收回原位。
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (dragOffset <= 0f || available.y <= 0f) return Offset.Zero
                val taken = minOf(dragOffset, available.y)
                dragOffset -= taken
                return Offset(0f, taken)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y >= 0f) return Offset.Zero
                dragging = true
                dragOffset -= available.y
                return Offset(0f, available.y)
            }
        }
    }

    val drag = rememberDraggableState { delta ->
        dragging = true
        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
    }

    // 这一层是**窗口**，不是屏内的一个 Box。理由有两条，缺一条都会回到 M3 那副壳：
    //
    //  1. 稿子的 `.b-sheetwrap` 是 `position:absolute; inset:0; z-index:8` —— 连手机屏
    //     底部那条 tab bar 一起盖住。而 `Scaffold` 的底栏是**画在 body 之上**的（M3 的
    //     `ScaffoldLayout` 先 place body、再 place bottomBar，这正是 `PiRoot` 里
    //     `ExtensionUiHost` 得把提示条抬到底栏之上那条注释的原因）；画在 body 里的浮层
    //     会被底栏压掉一截，面板最下面那一行与页脚就露不全。
    //  2. 返回键。Compose 的 `Dialog` 是独立窗口，`dismissOnBackPress` 直接把按键交给
    //     `onDismissRequest` —— 与旧的 `ModalBottomSheet` 同一条路径，也和
    //     `ui/blocks/PiImageViewer.kt` 记下的全应用惯例一致（浮层用窗口，屏里不再出现
    //     第二个 `BackHandler`，全应用只有 `PiRoot` 那一处）。
    Dialog(
        onDismissRequest = { close.value() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            // 和旧的 `ModalBottomSheet` 一样铺满整屏（含系统栏那一带），面板才能贴到屏底。
            decorFitsSystemWindows = false,
        ),
    ) {
        ClearSheetPlatformDim()

        BoxWithConstraints(modifier.fillMaxSize()) {
            val panelCap = maxHeight * WsSheetMaxHeight

            // scrim：整屏、0.32 的黑、点它关闭（不给自己加 ripple）。
            Box(
                Modifier
                    .fillMaxSize()
                    // 整层淡入 .13s（`.b-sheetwrap` 的 `bFade`）——缩放在图层上，命中区
                    // 不受影响：scrim 从第一帧起就能点。
                    .graphicsLayer { alpha = fade.value }
                    .background(WsSheetScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { close.value() },
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = panelCap)
                    .offset {
                        IntOffset(
                            0,
                            dragOffset.roundToInt() +
                                ((1f - rise.value) * riseDistance).roundToInt(),
                        )
                    }
                    // 面板自己的淡入（`bRise` 的 `opacity:0`）叠在整层那次淡入上：CSS 里
                    // 两层 `opacity` 是相乘的，这里照乘。
                    .graphicsLayer { alpha = fade.value * rise.value }
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> release(velocity) },
                    )
                    .nestedScroll(nested)
                    // 手指抬起的那一帧收尾：列表那条路径（余量拖动）没有 `onDragStopped`，
                    // 只在 Final pass 上看一眼「还有没有按着」最省事，也不吃事件。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (dragging && event.changes.none { it.pressed }) release(0f)
                            }
                        }
                    }
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .wsSheetTopEdge(palette.borderMuted),
            ) {
                // 系统栏那一条内边距放在面板**里面**：内容抬到导航栏之上，面板底色照样
                // 铺到屏底 —— 旧的 `ModalBottomSheet` 用 `contentWindowInsets` 做的也正是
                // 这件事（内容让开，表面不让）。
                Column(
                    Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                    ),
                ) {
                    // 抓手：32×3、圆角 999、`padding:8px 0 0`、水平居中。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = WsSheetHandleTop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(
                                    width = PiSettingsMetrics.sheetHandleWidth,
                                    height = PiSettingsMetrics.sheetHandleHeight,
                                )
                                .clip(CircleShape)
                                .background(palette.borderMuted),
                        )
                    }

                    // 头：`padding:12px 14px 8px`，一行标题（15/600 + 可选槽位 + ✕），
                    // 下面可选一行副标题（12 muted，`margin-top:4`）。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = PiSettingsMetrics.pageHorizontal,
                                end = PiSettingsMetrics.pageHorizontal,
                                top = PiSettingsMetrics.sheetHeadTop,
                                bottom = PiSettingsMetrics.sheetHeadBottom,
                            ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WsSheetHeadGap),
                        ) {
                            Text(
                                title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            trailing?.invoke()
                            Box(
                                Modifier
                                    // `margin-right:-6`：✕ 往右探出头的右边距 6px。
                                    .offset(x = WsSheetCloseOverhang)
                                    .size(WsSheetCloseSize)
                                    .clip(RoundedCornerShape(WsSheetCloseRadius))
                                    .clickable(onClick = { close.value() }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    modifier = Modifier.size(WsSheetCloseIconSize),
                                    tint = palette.muted,
                                )
                            }
                        }
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                modifier = Modifier.padding(top = WsSheetSubtitleGap),
                                style = if (subtitleMono) {
                                    PiTheme.text.monoSmall
                                } else {
                                    PiTheme.text.meta
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // 内容区：稿子 `.b-scroll` 的 `maxHeight:420` / `padding-bottom:8`。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxBodyHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = WsSheetBodyBottom),
                            content = content,
                        )
                    }

                    if (footer != null) {
                        HorizontalDivider(
                            thickness = PiSettingsMetrics.hairline,
                            color = palette.borderMuted,
                        )
                        Text(
                            footer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = PiSettingsMetrics.pageHorizontal,
                                    end = PiSettingsMetrics.pageHorizontal,
                                    top = PiSettingsMetrics.sheetFooterTop,
                                    bottom = PiSettingsMetrics.sheetFooterBottom,
                                ),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 清掉平台给 dialog 窗口加的那层 dim：[WsSheet] 自己画 0.32 的 scrim，不清就会叠成比稿子
 * 任何一处都深的一层。与 `ui/components/PiDialog.kt` 的 `ClearPlatformDim`、
 * `ui/blocks/PiImageViewer.kt` 的 `ClearViewerPlatformDim` 是同一手法 —— 那个 helper 是
 * 文件私有的，而 `ui/components` 不在本批可改的文件里，所以照那两处的先例在这里再来一份。
 */
@Composable
private fun ClearSheetPlatformDim() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }
}

/** `Sheet` 里没有对应 token 的几个数：`8px 0 0` / `gap:8` / ✕ 的 28 与 -6 / `mt 4` / 底部 8。 */
private val WsSheetHandleTop = 8.dp
private val WsSheetHeadGap = 8.dp
private val WsSheetCloseSize = 28.dp
private val WsSheetCloseRadius = 8.dp
private val WsSheetCloseIconSize = 16.dp
private val WsSheetCloseOverhang = 6.dp
private val WsSheetSubtitleGap = 4.dp
private val WsSheetBodyBottom = 8.dp

/** 稿子 `.b-sheetwrap{animation:bFade .13s ease both}`：整层（scrim + 面板）淡入。 */
private const val WsSheetFadeMillis = 130

/** 稿子 `.b-sheet{animation:bRise .16s cubic-bezier(.2,.7,.3,1) both}`：面板自己上移并淡入。 */
private const val WsSheetRiseMillis = 160

/** `.bRise` 的 `translateY(14px)`。 */
private val WsSheetRiseDistance = 14.dp

/** CSS 的 `ease`＝`cubic-bezier(.25,.1,.25,1)`。 */
private val WsSheetFadeEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** 稿子给 `.bRise` 的那条贝塞尔：`cubic-bezier(.2,.7,.3,1)`。 */
private val WsSheetRiseEasing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)

/** `.b-sheet{max-height:80%}`，面板再高也不超过屏幕的八成。 */
private const val WsSheetMaxHeight = 0.8f

/** 拉过这个距离松手就关（不到就弹回）。 */
private val WsSheetDismiss = 64.dp

/** 或者甩得比这个快（px/s）也关。 */
private const val WsSheetFlingVelocity = 900f

/** `.b-scrim{background:rgba(0,0,0,.32)}`：与 M3 的 scrim 同值，也是稿子那句。 */
private val WsSheetScrim = Color.Black.copy(alpha = 0.32f)

/**
 * `.b-sheet` 的 `border-top:1px solid var(--border-muted)`：**只画上沿**，并且贴着
 * 两个上圆角走 —— `Modifier.border()` 是四面一起画的，这里要的是一条。
 *
 * 中心线往里让半个线宽：`.clip()` 会把描边的外半边切掉，不让的话 1px 只剩 0.5px。
 */
private fun Modifier.wsSheetTopEdge(color: Color): Modifier = drawWithCache {
    val half = PiSettingsMetrics.hairline.toPx() / 2f
    val radius = (PiSettingsMetrics.sheetTopRadius.toPx() - half)
        .coerceAtLeast(0f)
        .coerceAtMost(size.height / 2f)
    val top = Path().apply {
        moveTo(half, half + radius)
        arcTo(Rect(half, half, half + 2f * radius, half + 2f * radius), 180f, 90f, false)
        lineTo(size.width - half - radius, half)
        arcTo(
            Rect(
                size.width - half - 2f * radius,
                half,
                size.width - half,
                half + 2f * radius,
            ),
            270f,
            90f,
            false,
        )
    }
    val stroke = Stroke(width = PiSettingsMetrics.hairline.toPx())
    onDrawWithContent {
        drawContent()
        drawPath(top, color, style = stroke)
    }
}
