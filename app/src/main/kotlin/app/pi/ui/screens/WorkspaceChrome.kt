package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSheetChromeMetrics
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import app.pi.ui.theme.stateToneColor

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
 * | [WsBadge] | `function Badge` (`:474-483`) | **无条件**的 5×5 圆角 1 色块 + 可选 12 等宽符号 + 12 文字，`1px border-muted`、圆角 999、`padding:1px 7px 1px 6px` |
 * | [WsChip] | `function Chip` (`:485-497`) + 空目录那颗主按钮（`:1531-1537`） | 两档：胶囊 高 26、圆角 999、`padding:0 9px`、`1px` 描边（选中 `border-accent`）；`solid` 高 32、`padding:0 14px`、**accent 实底无描边**、字色 `var(--page)` |
 * | [WsSectionAction] | ④ 段头的「+ 新建」(`:1496-1499`) | accent 纯文本 + 13 的加号，**无描边无底** |
 * | [WsNotice] | `function Notice` (`:615-625`) | 等宽前缀（`·` / `!` / `✗`）+ 13 文本（本 App 取 12 的 `meta`，见构件注释），三档 tone |
 * | [WsErrBlock] | `function ErrBlock` (`:1048-1072`) | 3px 竖条（`IntrinsicSize.Min` + `fillMaxHeight()`，撑满整块）+ `tool-error` 底 + `1px rgba(error,.45)` 边 + 圆角 10；标题 `t12` |
 * | `WorkspaceSnackBar`（`ProjectScreen.kt`） | `function Snack` (`:627-643`) | 底部一条：`mono t14` 符号 + 14 文本 + 可选动作，三档底/字 |
 * | [WsSheet] | `function Sheet` (`:514-532`) | scrim `.32` + `surf-high` + 上圆角 16（**没有**稿子那条 1px 上沿，理由见 [WsSheet] 的 KDoc）；抓手 `32×3`、头 `12px 14px 8px`、标题 17/24（`PiType.title`）+ ✕ `28×28` 圆角 8、副标题 12 muted `mt 4`、正文 `maxHeight 420` / 底部 8、页脚 `10px 14px 14px` |
 * | [WsSectionHeader] | `function Section` (`:424-435`) | `padding:0 14px`、`margin-bottom:7px`、12 标签（`.02em`）+ 12 计数 + 右侧 aside |
 * | [WsRow] | `function Row` (`:452-471`) | `padding:10px 12px`、`gap:10`、标题 15（字重 **500**，strong 时 600；等宽行 `mono` 14）+ 副行 12 + 右值 13 |
 * | [WsCard] / [WsCardSlice] | `function Card` (`:421-423`) + `Rows` (`:437-449`) | 卡壳：`surf-low` 底、圆角 10、左右 14 页边；列表里由**每一片**自带卡壳（首片圆上角、末片圆下角、行间线在卡内），这样 `LazyColumn` 的懒加载不被 `Rows` 的整列写法牺牲 |
 */

// ------------------------------------------------------------------ 段头 ----

/**
 * 段头：左边标签 + 计数，右边一个 slot（design 稿 ④ 的「新建」入口、⑤ 的计数都在那儿）。
 *
 * 几何按 `06 §2`「分组头 padding:0 14px; margin-bottom:7px」+「分组块 marginTop:18px」。
 * （设置面自己已经**没有**分组头了 —— 那一屏统一成「一张接一张的卡」，这个段头只服务
 * 工作区/会话这些列表屏。）aside 是**可点的 slot** 而不是一行字：稿子 ④ 段头右侧那颗
 * 「+ 新建」是一个控件。
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
            // `.02em` 在 12sp 上是 0.24sp，`letterSpacing` 只吃绝对值 —— 与
            // `SessionsScreen` 的分组头（`SESSIONS_GROUP_TRACKING`）同一个取值：稿子里
            // 这两处都是同一个 `Section` 构件（`workspace-final.html:424-435` 的
            // `letterSpacing:'.02em'`）。`PiTheme.text.meta` 本身不带字距，所以这里不是
            // 覆盖既有档位，而是补上稿子写死的那一档。
            style = PiTheme.text.meta.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = WS_SECTION_TRACKING,
            ),
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

/** 稿子 `Section` 的 `letterSpacing:'.02em'`，在 12sp 上就是 0.24sp。 */
private val WS_SECTION_TRACKING = 0.24.sp

// ------------------------------------------------------------------ 徽标 ----

/**
 * `字 + 符号 + 颜色` 的状态徽标（`ui/theme/PiStateChip.kt` 的 `StateChip` 是它的正文色
 * 版本）。这里的差别是**来源徽标需要混排**：`项目 .pi` 的「.pi」、`包 · pi-skills` 的包名
 * 必须等宽，而前面的汉字不是 —— 稿子把这件事写成「路径型来源用等宽，抽象来源用文本」。
 *
 * @param dot 那颗 5×5 色块。**默认开，因为稿子无条件先画它**：`function Badge`
 *   （`workspace-final.html:474-483`）第一件事就是 `<span style={{width:5,height:5,
 *   background:c}}/>`，`06 §2` 的「徽标」行也是「色块 5×5 圆角 1」在前。这一屏原来只有 ⑤
 *   的来源徽标传了 `true`，于是 ① 的「切换」、③ 的「本次会话」、工作区面板的「当前」
 *   「未接」都成了没有色块的空描边胶囊 —— 三重编码里少了一层。
 */
@Composable
internal fun WsBadge(
    text: String,
    tone: StateTone,
    modifier: Modifier = Modifier,
    mono: String? = null,
    glyph: String? = null,
    dot: Boolean = true,
) {
    val palette = PiTheme.palette
    val color = stateToneColor(tone, palette)
    val badgeShape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .clip(badgeShape)
            .background(color.copy(alpha = 0.12f))
            .border(PiSpacing.hairlineThin, color.copy(alpha = 0.24f), badgeShape)
            .padding(start = 6.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
        if (glyph != null) {
            Text(glyph, style = PiTheme.text.monoSmall, color = color, maxLines = 1)
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = color,
                maxLines = 1,
            )
        }
        if (mono != null) {
            Text(mono, style = PiTheme.text.monoSmall, color = color, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ 胶囊 ----

/**
 * 紧凑动作按钮：统一圆角 8dp 轻盈层次，告别高饱和大药丸。
 */
@Composable
internal fun WsChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    glyph: String? = null,
    glyphIcon: ImageVector? = null,
) {
    val palette = PiTheme.palette
    val chipShape = RoundedCornerShape(8.dp)
    val contentColor = if (solid) palette.accent else palette.text.copy(alpha = 0.88f)
    val bgColor = if (solid) palette.accent.copy(alpha = 0.15f) else palette.cardBg
    val borderColor = if (solid) palette.accent.copy(alpha = 0.42f) else palette.borderMuted.copy(alpha = 0.65f)
    Row(
        modifier = modifier
            .height(if (solid) 30.dp else 28.dp)
            .clip(chipShape)
            .background(bgColor)
            .border(if (solid) 1.dp else PiSpacing.hairlineThin, borderColor, chipShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (solid) 11.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (glyphIcon != null) {
            Icon(
                glyphIcon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (solid) palette.accent else palette.muted,
            )
        }
        if (glyph != null) {
            Text(
                glyph,
                style = PiTheme.text.monoSmall,
                color = if (solid) palette.accent else palette.muted,
                maxLines = 1,
            )
        }
        Text(
            text,
            style = PiTheme.text.meta.copy(
                fontWeight = if (solid) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 稿子空目录那颗实底按钮：`height:32`。 */
private val WS_SOLID_BUTTON_HEIGHT = 32.dp

/** 它的 `padding:0 14px`。 */
private val WS_SOLID_BUTTON_PADDING = 14.dp

/** 它的图标 15、`gap:6`。 */
private val WS_SOLID_BUTTON_ICON = 15.dp
private val WS_SOLID_BUTTON_GAP = 6.dp

/**
 * 段头右侧那颗「+ 新建」（稿子 `:1496-1499`）：**accent 纯文本 + 13 的加号，无描边无底**。
 *
 * 它不是 [WsChip]：稿子在这里画的是一个 `press` 的 inline-flex 文本动作，不是胶囊。这一屏
 * 原来把它画成了描边胶囊，于是整屏唯一的主按钮语言（实底 accent）被稀释成了第二颗胶囊。
 */
@Composable
internal fun WsSectionAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WS_SECTION_ACTION_RADIUS))
            .clickable(onClick = onClick)
            .padding(horizontal = WS_SECTION_ACTION_PADDING, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(WS_SECTION_ACTION_ICON),
            tint = PiTheme.palette.accent,
        )
        Text(
            label,
            style = PiTheme.text.meta,
            color = PiTheme.palette.accent,
            maxLines = 1,
        )
    }
}

/** 稿子给段头动作 `gap:4`、图标 13；按压面只有一点圆角，让点按落点不至于是一条线。 */
private val WS_SECTION_ACTION_ICON = 13.dp
private val WS_SECTION_ACTION_RADIUS = 8.dp
private val WS_SECTION_ACTION_PADDING = 4.dp

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
            // 稿子的 `Notice` 正文是 `t13`（`workspace-final.html:615-625`）。本 App 的系统字
            // 角色里没有 13sp，取 12 的 `meta` —— 这也正是**同一个构件在对话页的取值**：
            // `ui/blocks/NoticeBlock.kt:76` 把稿子同样写 `t13` 的提示正文画成 `meta`。
            // 14 的正文比稿子大 1px，是这一屏「小字号走形」里的一员。
            style = PiTheme.text.meta,
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
            .clip(RoundedCornerShape(WsShape.medium))
            .background(palette.toolErrorBg)
            .border(
                PiSettingsMetrics.hairline,
                palette.error.copy(alpha = 0.45f),
                RoundedCornerShape(WsShape.medium),
            )
            // 竖条要**撑满整块**（稿子 `:1052-1054` 的 `display:flex` + `width:3` 让那条
            // flex 子项拉伸到内容高度）。原来给它一个固定的 78dp，展开「详情」之后错误块
            // ≈140dp，竖条就在中间断了 —— 于是这里有 `IntrinsicSize.Min`：这一行的最小高度
            // 由右边那一列决定，竖条再 `fillMaxHeight()` 跟上，多长的错误都不会断。
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
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
                    // 稿子的标题是 `t12`（`workspace-final.html:1058`）：`✗` + 一句短标题，
                    // 两半同色同档。14 的标签（`labelLarge` 那一档）比稿子大一档、还带了
                    // 500 的字重，与旁边 12 的符号不齐。
                    style = PiTheme.text.meta,
                    color = palette.error,
                )
            }
            if (message != null) {
                Text(
                    message,
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.prose,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (detail != null) {
                Text(
                    if (open) tr("收起", "Hide") else tr("详情", "Details"),
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
 * 竖条的高度不再由常量钉住：`WsErrBlock` 用 `IntrinsicSize.Min` + `fillMaxHeight()` 让它跟
 * 内容走（稿子的竖条是 flex 拉伸，`workspace-final.html:1052-1054`）。原来的 78dp 是
 * 「两行正文加内边距」的下限，但它挡不住展开「详情」之后的块高，竖条会断。
 */

/** 错误块里的一个文字动作（`重试` / `回到工作区` / `复制错误`）。 */
@Composable
internal fun WsErrAction(label: String, tone: Color? = null, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(WsShape.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        // 稿子的动作是 `t13 c-accent`（`workspace-final.html:1063-1068`）。同 `WsNotice`
        // 正文：13 在本 App 的系统字角色里没有，取 12 的 `meta`（对话页的同类提示同一取值），
        // 14 的正文是它原来偏大的那一档。
        style = PiTheme.text.meta,
        color = tone ?: PiTheme.palette.accent,
    )
}

/**
 * 工作区这一屏只用三档圆角：小 8（图标底、按压高亮、小按钮）、中 12（卡片、输入框、
 * 菜单、提示条、点击区）、胶囊（芯片与主按钮）。新写的东西从这里取，不再各写各的数字。
 */
internal object WsShape {
    val small = 9.dp
    val medium = 14.dp
}

// ------------------------------------------------------------------ 行 ----

/**
 * 这一屏的通用行（稿子的 `Row`）。
 *
 * 四个槽从左到右：前置图标、标题（可等宽、可粗）+ 徽标、右值、尾部（⋮ 或读数）。
 * 副行在标题下面。整行可点（稿子的行都是可点的），没有 `onClick` 时不可点。
 *
 * @param mono 标题用等宽。**等宽只留给路径**（以及命令这类代码）：文件名、工作区名、
 *   资源名都是无衬线的 15/Medium。
 * @param value 右侧读数（`+N −M`、`12 项`），等宽。
 */
@Composable
internal fun WsRow(
    title: String,
    modifier: Modifier = Modifier,
    lead: (@Composable () -> Unit)? = null,
    /**
     * 前置图标的**底**尺寸，默认 32dp。
     *
     * 图标列先对齐、再谈图标本身长什么样：一行里图标大小不一，比图标画得对不对更显乱。
     * 底是 `surfaceContainerHigh` 的圆角方块 —— 在卡上只比卡底亮一点，是「对齐用的格子」，
     * 不是装饰。
     */
    leadSize: Dp = PiSpacing.glyphBox,
    /** 前置图标底的颜色；默认是中性的 `surfaceContainerHigh`，目录用强调色的淡底。 */
    leadBackground: Color? = null,
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
                start = PiSettingsMetrics.rowPaddingHorizontal,
                end = if (trail != null) 8.dp else PiSettingsMetrics.rowPaddingHorizontal,
                top = PiSettingsMetrics.rowPaddingVertical,
                bottom = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        if (lead != null) {
            Box(
                modifier = Modifier
                    .size(leadSize)
                    .clip(RoundedCornerShape(WsShape.small))
                    .background(leadBackground ?: MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
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
                    // 稿子的标题是 `t15 w5`，等宽行是 `mono t14`
                    // （`workspace-final.html:461`：`className={"t15 ell "+(p.mono?'mono t14':'')}`
                    // + `fontWeight:p.strong?600:500`）。行标题那一档现在是 `PiType.rowTitle`
                    // （15/23 Medium，随字号档平移），两档字重仍由下面的 `fontWeight` 显式给：
                    // 非 strong 是 **500**，strong 是 600。等宽标题不另开字号 —— 同一个角色换
                    // 字族；`PiTheme.text.mono` 是 13 的机器正文，不能当行标题。
                    style = if (mono) {
                        PiType.rowTitle.copy(fontFamily = PiTheme.text.mono.fontFamily)
                    } else {
                        PiType.rowTitle
                    },
                    color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium,
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

/**
 * 有图标列那一类行的行间线起点：行内边距 + 32dp 图标底 + 行间距 = **56dp**。
 *
 * 线要落在**文字列**上；不带图标的行用 [PiSettingsMetrics.rowPaddingHorizontal]。
 */
internal val WsIconHairlineStart: Dp =
    PiSettingsMetrics.rowPaddingHorizontal + PiSpacing.glyphBox + PiSettingsMetrics.rowGap

/**
 * 行之间的 inset hairline（与设置页那条同值、同一根线）：**0.5dp、`outlineVariant`、
 * 左端缩到文字列**（不带图标的行是行内边距 14；带图标的行由调用方传
 * `rowPaddingHorizontal + 图标 + rowGap`）。
 */
@Composable
internal fun WsHairline(
    inset: Boolean = true,
    startInset: Dp = PiSettingsMetrics.rowPaddingHorizontal,
) {
    HorizontalDivider(
        modifier = if (inset) {
            Modifier.padding(start = startInset)
        } else {
            Modifier
        },
        thickness = PiSpacing.hairlineThin,
        // **55% 的 `borderMuted`，这是与稿子的一处已定偏离。** 稿子的行间线是
        // `.div{background:var(--border-muted)}` —— 不透明（`workspace-final.html:177`），
        // 而稿子另一处的 `.hair` 才是 `opacity:.55`（`:178`）；本项目自己的
        // `06 §2`「分组容器：组内 inset hairline（`borderMuted` 55%）」写的是 55%。
        // 两侧矛盾，以 **`06 §2` 为准**（它是冻结规范，稿子是原型），所以这里保持
        // `outlineVariant`（= `borderMuted` 的 55%）。要改回去的话，先改 `06 §2`。
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 文件/目录行尾「更多」操作按钮：使用标准 Material 横排三点图标，保证暗/浅色主题下清晰可见且触控区充足。 */
@Composable
internal fun WsMoreButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(WsShape.small))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MoreHoriz,
            contentDescription = tr("更多操作", "More actions"),
            modifier = Modifier.size(18.dp),
            tint = PiTheme.palette.muted,
        )
    }
}

/**
 * 稿子专门为**二进制**加的第三个文件态图标（`workspace-final.html:311`：文件轮廓 + 右下角
 * 一块实心矩形）。目录树、查看器与导入提示都用它，替掉原来那个字符 `▤`。
 */
internal val WsBinaryFileGlyph: ImageVector = ImageVector.Builder(
    name = "WsBinaryFile",
    defaultWidth = 18.dp,
    defaultHeight = 18.dp,
    viewportWidth = 18f,
    viewportHeight = 18f,
).addPath(
    pathData = PathParser().parsePathString(
        "M4.6 3.4h5.2l3.6 3.6v7.6H4.6z" + "M9.6 3.6v3.6h3.6",
    ).toNodes(),
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 1.5f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).addPath(
    pathData = PathParser().parsePathString(
        "M6 10.2h4.8a0.6 0.6 0 0 1 0.6 0.6v1.8a0.6 0.6 0 0 1 -0.6 0.6H6a0.6 0.6 0 0 1 -0.6 -0.6v-1.8a0.6 0.6 0 0 1 0.6 -0.6z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

/** 一张卡（稿子的 `Card`）：`surf-low` 底、圆角 10、左右 14 的页边。 */
@Composable
internal val LocalInsideWsSheet = compositionLocalOf { false }

internal fun WsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val bg = if (LocalInsideWsSheet.current) {
        PiSheetChromeMetrics.sheetCardBg
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = RoundedCornerShape(WsShape.medium),
        color = bg,
    ) {
        Column { content() }
    }
}

/**
 * 一张卡在 `LazyColumn` 里的**一片**。
 *
 * 稿子的 `Rows` 是「一个 `Card` 里塞整列 + 行间 `.div`」（`workspace-final.html:437-449`），
 * 那是原型写法：④ 的目录可能有几千个文件，把整列放进一个 item 就等于放弃懒加载。
 * 所以卡壳由**每一片自己带**：左右 14 的页边、`surf-low` 底、圆角 10 —— 只有首片圆上角、
 * 末片圆下角，中间不圆；行间那条 hairline 由非首片画在卡片**内部**（与稿子
 * `i>0 ? <div className="div"/>` 同一个位置）。屏幕上一列相邻的片因此看起来仍是一张卡。
 *
 * 行构件自己**不再**画末尾那条线（原来是 `ChangedFileRow`/`WorkspaceEntryRow` 各自收尾），
 * 于是④ 最后一行下面不会多出一条悬空的 hairline。
 *
 * @param index 这一片在列表里的序号；0 的那一片圆上角、不画上方的线。
 * @param lastIndex 最后一片的序号；它圆下角。
 */
@Composable
internal fun WsCardSlice(
    index: Int,
    lastIndex: Int,
    modifier: Modifier = Modifier,
    /** 行间线的左缩进：文件行缩到文字列（32dp 图标之后），其余行用默认值。 */
    hairlineStart: Dp = PiSettingsMetrics.rowPaddingHorizontal,
    content: @Composable () -> Unit,
) {
    val radius = WsShape.medium
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = RoundedCornerShape(
            topStart = if (index == 0) radius else 0.dp,
            topEnd = if (index == 0) radius else 0.dp,
            bottomStart = if (index == lastIndex) radius else 0.dp,
            bottomEnd = if (index == lastIndex) radius else 0.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            if (index > 0) WsHairline(startInset = hairlineStart)
            content()
        }
    }
}

// ------------------------------------------------------------------ Sheet ----

/**
 * Unified Studio bottom sheet wrapper for workspace flows — delegates directly to [PiSheet]
 * with a consistent header (title, optional mono/meta subtitle, optional trailing CTA,
 * tonal close circle), scrollable body with automatic [LocalInsideWsSheet] card polarity,
 * optional footer note, and `imePadding()` + `navigationBarsPadding()`.
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
    PiSheet(
        onDismiss = onClose,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PiSheetChromeMetrics.headHorizontal,
                        end = PiSheetChromeMetrics.headHorizontal,
                        top = 2.dp,
                        bottom = PiSheetChromeMetrics.headBottom,
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WsSheetHeadGap),
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = PiType.sheetTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    trailing?.invoke()
                    Box(
                        modifier = Modifier
                            .size(WsSheetCloseSize)
                            .clip(CircleShape)
                            .background(PiSheetChromeMetrics.sheetControlBg)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = tr("关闭", "Close"),
                            modifier = Modifier.size(WsSheetCloseIconSize),
                            tint = palette.muted,
                        )
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.padding(top = WsSheetSubtitleGap),
                        style = if (subtitleMono) PiTheme.text.monoSmall else PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Scrollable body
            CompositionLocalProvider(LocalInsideWsSheet provides true) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxBodyHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = WsSheetBodyBottom),
                        content = content,
                    )
                }
            }

            if (footer != null) {
                HorizontalDivider(
                    thickness = PiSettingsMetrics.hairline,
                    color = palette.borderMuted,
                )
                Text(
                    text = footer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = PiSheetChromeMetrics.footerHorizontal,
                            end = PiSheetChromeMetrics.footerHorizontal,
                            top = PiSheetChromeMetrics.footerTop,
                            bottom = PiSheetChromeMetrics.footerBottom,
                        ),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
        }
    }
}

private val WsSheetHeadGap = 8.dp
private val WsSheetCloseSize = 34.dp
private val WsSheetCloseIconSize = 16.dp
private val WsSheetSubtitleGap = 4.dp
private val WsSheetBodyBottom = 10.dp
