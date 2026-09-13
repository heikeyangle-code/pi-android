package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.components.EffectiveKind
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * The settings surfaces' v2 geometry and the three components the settings
 * screens share (section header, badge, switch).
 *
 * ## Why the numbers live here instead of `PiSpacing` / `PiShapes`
 *
 * `06-v2-construction-reference.md` §2 是本次施工的唯一取值表，它以
 * `design-demos/direction-b-v2.html` 为准：屏水平 14、行 `padding:10px 12px`、
 * 分组容器圆角 10、徽标圆角 999、开关 38×22。这些值和 `PiSpacing`
 * （`screen = 16`、`PiShapes.card = 16`，来自已被宣布过时的旧 spec）不是同一套数。
 *
 * `ui/theme/PiTheme.kt` 由另一批（B7）负责折进新令牌，所以这一批不碰它，
 * 也不 import 同一批正在写、API 未定稿的 `PiStateChip` / `PiMeter`：设置页需要
 * 的徽标与开关在这里就地实现，取值全部照 §2 抄，等令牌批次落地后可以整体替换。
 */

/** `06 §2` 的取值，按「一处一个数字」收录；命名按语义，不按像素。 */
internal object PiSettingsMetrics {

    /** `06 §2`「屏水平 14px」：设置页所有横向留白。 */
    val pageHorizontal: Dp = 14.dp

    /** `06 §2`「行（Row）：`padding:10px 12px`」。 */
    val rowPaddingVertical: Dp = 10.dp
    val rowPaddingHorizontal: Dp = 12.dp

    /** `06 §2`「副行 12 灰、mt 3」：标题行到副行之间。 */
    val supportingGap: Dp = 3.dp

    /** `06 §2`「标题 15/500」的标题到徽标间距。 */
    val titleGap: Dp = 7.dp

    /** `06 §2`「卡片内 12px」/「设备桥卡 14px」：卡片内边距两档。 */
    val cardPadding: Dp = 12.dp
    val cardPaddingLoose: Dp = 14.dp

    /** 说明卡与页脚这类块之间的纵向呼吸（v2 的 6–10px 一档）。 */
    val notePaddingVertical: Dp = 6.dp

    /** `06 §2`「分组块 marginTop:18px」。 */
    val groupGap: Dp = 18.dp

    /** `06 §2`「分组头 padding:0 14px; margin-bottom:7px」。 */
    val groupHeaderGap: Dp = 7.dp

    /** `06 §2`「列表分隔线左侧 inset 14px」。 */
    val dividerInset: Dp = 14.dp

    /** `06 §2`「分组容器：圆角 10」；卡片同值。 */
    val cardRadius: Dp = 10.dp

    /** `06 §2`「行内 10px gap」。 */
    val rowGap: Dp = 10.dp

    /** `06 §2`「chevron 14」。 */
    val chevronSize: Dp = 14.dp

    /** `06 §2`「「当前生效值」左缘 2px accent 条（top/bottom 各缩进 8）」。 */
    val currentBarWidth: Dp = 2.dp
    val currentBarInset: Dp = 8.dp

    /**
     * `06 §2`「线宽：全篇只有 1px」，用于搜索命中的左缘竖线与卡片描边；
     * 与 `PiSpacing.hairline` / `PiSpacing.hairline` 同值，令牌批次合并后只留一个。
     */
    val hairline: Dp = 1.dp

    /** `06 §2`「徽标：色块 5×5 圆角 1、`padding:1px 7px 1px 6px`」。 */
    val badgeDot: Dp = 5.dp
    val badgeDotRadius: Dp = 1.dp
    val badgeGap: Dp = 5.dp
    val badgePaddingStart: Dp = 6.dp
    val badgePaddingEnd: Dp = 7.dp
    val badgePaddingVertical: Dp = 1.dp

    /** `06 §2`「chip：高 26 圆角 999 `padding:0 9px`」。 */
    val chipHeight: Dp = 26.dp
    val chipPaddingHorizontal: Dp = 9.dp

    /** `06 §2`「开关：`38×22` 圆角 999 `padding:2`，旋钮 16」。 */
    val switchWidth: Dp = 38.dp
    val switchHeight: Dp = 22.dp
    val switchPadding: Dp = 2.dp
    val switchKnob: Dp = 16.dp

    /** `06 §2`「搜索框 40 高、圆角 9」；图标 16、图标到文字 9。 */
    val searchFieldHeight: Dp = 40.dp
    val searchFieldRadius: Dp = 9.dp
    val searchIconSize: Dp = 16.dp

    /** 卡片内的前置图标（v2 的图标只用 16/17/18/20 几档；卡头取 18）。 */
    val cardIconSize: Dp = 18.dp
    val searchIconGap: Dp = 9.dp

    /** `06 §2`「sheet：顶部圆角 16、抓手 `32×3`」。 */
    val sheetTopRadius: Dp = 16.dp
    val sheetHandleWidth: Dp = 32.dp
    val sheetHandleHeight: Dp = 3.dp

    /** `06 §2`「sheet：头 `padding:12px 14px 8px`、正文最大 420、页脚 `10px 14px 14px`」。 */
    val sheetHeadTop: Dp = 12.dp
    val sheetHeadBottom: Dp = 8.dp
    val sheetBodyMax: Dp = 420.dp
    val sheetFooterTop: Dp = 10.dp
    val sheetFooterBottom: Dp = 14.dp

    /**
     * 清单编辑器与主题编辑器内嵌的滚动区上限。§2 只给了 sheet 正文的 420；
     * 这两个列表下面还有输入框与按钮，所以各自收紧一档，值来自 v2 HTML
     * （`EditorSheetBody('list')` / `('theme')` 的可视高度）。
     */
    val sheetListMax: Dp = 300.dp
    val sheetThemeMax: Dp = 200.dp

    /** `06 §2`「页脚说明 16px 14px 0、13/1.6」。 */
    val footerTop: Dp = 16.dp
}

/** `06 §2`「分组容器 / 卡片：圆角 10，无描边无阴影」。 */
internal val PiSettingsCardShape = RoundedCornerShape(PiSettingsMetrics.cardRadius)

/** `06 §2`「搜索框 / 输入框：圆角 9」。 */
internal val PiSettingsFieldShape = RoundedCornerShape(PiSettingsMetrics.searchFieldRadius)

/** `06 §2`「sheet：顶部圆角 16」（只有上两个角，下沿贴屏底）。 */
internal val PiSettingsSheetShape = RoundedCornerShape(
    topStart = PiSettingsMetrics.sheetTopRadius,
    topEnd = PiSettingsMetrics.sheetTopRadius,
)

/**
 * `06 §2`「分组头 `padding:0 14px; margin-bottom:7px`」，标签 12/500 正文色，
 * 右侧可选计数与说明（v2 的 `Section` 组件）。
 *
 * 不复用 `ui/components/PiCommon.kt` 的 `PiSectionHeader`：那个还服务对话页的
 * sheet 与会话列表，改它会牵动本批范围外的屏，而 v2 对设置页给的是另一套取值。
 */
@Composable
internal fun PiSettingsSectionHeader(
    label: String,
    count: String? = null,
    aside: String? = null,
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
        if (aside != null) {
            Text(
                aside,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * `06 §2`「组内 inset hairline（`borderMuted` 55%），无描边无阴影」的 1px 线。
 *
 * `outlineVariant` 就是 `borderMuted.copy(alpha = 0.55f)`（`PiPalette.colorScheme()`），
 * 所以这里直接用它，不再自造一个透明度。
 */
@Composable
internal fun PiSettingsHairline(inset: Dp = PiSettingsMetrics.dividerInset) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = PiSettingsMetrics.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * `06 §2`「分组容器」：圆角 10、`surfaceContainerLow` 底、无描边，左右各留
 * 14px 页边（v2 的 `Card` 是 `margin:0 14px`）。卡内的行自带 `10px 12px`
 * 内边距，所以文本离屏边 26px。
 */
@Composable
internal fun PiSettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = PiSettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column { content() }
    }
}

/**
 * v2 的 `Chip`（`design-demos/direction-b-v2.html:605`）：高 26、圆角 999、
 * `padding:0 9px`、`gap:5`；描边「选中 `borderAccent` / 否则 `borderMuted`」，底
 * 「选中 `surf-high` / 否则透明」，字 12（选中正文色、否则 muted），可选前置符号用
 * 等宽、muted。
 *
 * 搜索快捷词与**会话列表的两个筛选**共用它：`phone19` 的「↕ 按时间 / ○ 全部」就是
 * `active` 那两档，所以它必须能画选中态，不能只是一个描边胶囊。
 *
 * @param active 这一档是不是当前选中的那一档（不是「开/关」）。
 * @param glyph 前置符号（`↕` / `✓` / `○`），可选。
 */
@Composable
internal fun PiSettingsChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    glyph: String? = null,
) {
    Row(
        modifier = modifier
            .height(PiSettingsMetrics.chipHeight)
            .clip(PiShapes.badge)
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .border(
                PiSettingsMetrics.hairline,
                if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                PiShapes.badge,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = PiSettingsMetrics.chipPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        if (glyph != null) {
            Text(
                glyph,
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.muted,
            )
        }
        Text(
            text,
            style = PiTheme.text.monoSmall,
            color = if (active) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
        )
    }
}

/**
 * `06 §2`「徽标：圆角 999、`padding:1px 7px 1px 6px`、色块 5×5 圆角 1、
 * 可选符号 12 等宽、文字 12 正文色」，外面还有 1px `borderMuted` 描边。
 *
 * 颜色只由调用方从 pi/M3 令牌传入（本批不改任何取色）。
 */
@Composable
internal fun PiSettingsBadge(
    label: String,
    tone: Color,
    modifier: Modifier = Modifier,
    glyph: String? = null,
) {
    Row(
        modifier = modifier
            .clip(PiShapes.badge)
            .border(PiSettingsMetrics.hairline, MaterialTheme.colorScheme.outline, PiShapes.badge)
            .padding(
                start = PiSettingsMetrics.badgePaddingStart,
                end = PiSettingsMetrics.badgePaddingEnd,
                top = PiSettingsMetrics.badgePaddingVertical,
                bottom = PiSettingsMetrics.badgePaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        Box(
            Modifier
                .size(PiSettingsMetrics.badgeDot)
                .clip(RoundedCornerShape(PiSettingsMetrics.badgeDotRadius))
                .background(tone),
        )
        if (glyph != null) {
            Text(
                glyph,
                style = PiTheme.text.monoSmall,
                color = tone,
            )
        }
        Text(
            label,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 四种生效徽标（`02-real-content.md` §4.3）：`需重载` / `新会话` / `需重启引擎` / `需重启`。
 *
 * 文字与色相沿用 `ui/components/PiCommon.kt` 的既有映射（tertiary / onSurfaceVariant /
 * error，都是 M3 槽位 = pi 派生），本批只把**形状**换成 §2 的圆角 999 + 1px 描边 +
 * 色块，不新增也不改任何颜色。`Immediate` 不出徽标。
 */
@Composable
internal fun PiSettingsEffectiveBadge(
    kind: EffectiveKind,
    modifier: Modifier = Modifier,
) {
    val (label, tone) = when (kind) {
        EffectiveKind.Immediate -> return
        EffectiveKind.Reload -> "需重载" to MaterialTheme.colorScheme.tertiary
        EffectiveKind.NewSession -> "新会话" to MaterialTheme.colorScheme.onSurfaceVariant
        EffectiveKind.RestartEngine -> "需重启引擎" to MaterialTheme.colorScheme.error
        EffectiveKind.RestartApp -> "需重启" to MaterialTheme.colorScheme.error
    }
    PiSettingsBadge(label = label, tone = tone, modifier = modifier)
}

/**
 * `06 §2`「开关：`38×22` 圆角 999 `padding:2`，旋钮 16；开 = accent，关 = surf-highest」。
 *
 * 自己画而不是用 M3 的 `Switch`：M3 的开关把轨道尺寸写死在 `SwitchImpl` 里，
 * 传 `Modifier.size(38.dp, 22.dp)` 不会生效，实测仍是 52×32，对不上 §2 的表。
 * `Role.Switch` 与 `toggleable` 保住无障碍语义，颜色仍全部来自令牌。
 */
@Composable
internal fun PiSettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = PiTheme.palette.accent
    val track = if (checked) accent else MaterialTheme.colorScheme.surfaceContainerHighest
    val border = if (checked) accent else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(
                width = PiSettingsMetrics.switchWidth,
                height = PiSettingsMetrics.switchHeight,
            )
            .clip(PiShapes.badge)
            .background(track)
            .border(PiSettingsMetrics.hairline, border, PiShapes.badge)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(PiSettingsMetrics.switchPadding),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(PiSettingsMetrics.switchKnob)
                .clip(CircleShape)
                // 开 = page（旋钮压在 accent 上），关 = muted（§2 的 `--page` / `--muted`）。
                .background(
                    if (checked) MaterialTheme.colorScheme.surface else PiTheme.palette.muted,
                ),
        )
    }
}

/**
 * 开关行右侧的三重编码（`06 §4`）：符号 + 字 + 控件，颜色只是第三层。
 * 复用 M3 `Switch` 的旧调用点不受影响；这里给设置行用。
 */
@Composable
internal fun PiSettingsSwitchRowTrailing(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
        ) {
            Text(
                if (checked) "✓" else "○",
                style = PiTheme.text.monoSmall,
                color = if (checked) PiTheme.palette.success else PiTheme.palette.muted,
            )
            Text(
                if (checked) "开" else "关",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        PiSettingsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * v2 的对话框（`06 §2`：最大宽 330、圆角 14、底色 `surf-high`、1px `borderMuted`
 * 描边、`padding:18px 16px 12px`、scrim `rgba(0,0,0,.32)`、无阴影）。
 *
 * 外壳本身在 `ui/components/PiDialog.kt` —— 那是扩展对话框与设置对话框共用的构件
 * （两边原先一个用 `AlertDialog` + `surf-low`、一个自绘 `Dialog` + `surf-high`）。
 * 这里只是设置页的薄封装：v2 的标题（15/600）、可选副行（12 muted）、正文
 * （14 正文色）与行尾按钮（`padding:7px 12px`）。
 *
 * 按钮是文案 + 回调而不是 composable 槽：v2 的对话框按钮是
 * `padding:7px 12px; border-radius:8` 的纯文字，M3 的 `TextButton` 是 40dp 高、
 * 带水波纹的另一套取值，槽会把它漏回来。
 *
 * @param sub v2 标题下那行 12 灰的副行（`phone45` 用它写生效徽标词）。
 * @param body 正文一段；`phone45` 的正文以「「设置名」」开头，调用方自己拼。
 * @param content 富正文（多段、可滚动、选项列表），与 [body] 二选一。
 * @param confirmationTone 主按钮的非 accent 色（v2 的删除确认用 `error`）。
 * @param confirmationEnabled 关闭态用 `muted`（`phone25` 画的就是被拒的删除）。
 */
@Composable
internal fun PiSettingsDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    body: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    confirmationLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmationEnabled: Boolean = true,
    confirmationTone: Color? = null,
    dismissalLabel: String? = null,
    onDismissButton: (() -> Unit)? = null,
) {
    PiDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        PiDialogTitle(title)
        if (sub != null) {
            Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
            Text(
                sub,
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
        if (body != null) {
            PiDialogBody(body)
        }
        if (content != null) {
            Spacer(Modifier.height(PiSettingsMetrics.rowGap))
            content()
        }
        if (confirmationLabel != null || dismissalLabel != null) {
            PiDialogActions {
                if (dismissalLabel != null) {
                    PiDialogAction(
                        label = dismissalLabel,
                        onClick = onDismissButton ?: onDismissRequest,
                        primary = false,
                    )
                }
                if (confirmationLabel != null) {
                    PiDialogAction(
                        label = confirmationLabel,
                        onClick = onConfirm ?: onDismissRequest,
                        primary = true,
                        enabled = confirmationEnabled,
                        tone = confirmationTone,
                    )
                }
            }
        }
    }
}
