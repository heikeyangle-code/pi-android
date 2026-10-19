package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * 设置行构件：全设置面（含「设备能力」页）共用**一套行骨架、三种尾巴**。
 *
 * ## 三种尾巴（用户裁定的统一口径）
 *
 *  1. 布尔行：`标题 15/500 + 一行说明 12 灰 + Switch`，开关贴右；
 *  2. 值行：`标题 + 一行说明 + 值 13 等宽 + chevron`；
 *  3. 动作行：`标题（accent，危险用 error）+ 一行说明 + chevron`。
 *
 * 行内边距 `14 横向 / 10 纵向`（[PiSettingsMetrics]），说明**最多一行**（超出省略），
 * 行与行之间的 1px hairline 由页面给。任何行都不出现「查看 / 打开 / 编辑 / 阅读」
 * 这类文字尾巴 —— 有 chevron 的那一行本来就是可点的。
 *
 * 「设备能力」页此前自己画了一套 `Card` 与 `CapabilityRow`（标题 `titleSmall`、说明
 * 不截断、M3 `Switch`），与设置行两套取值；现在两边都只走这里。
 *
 * 这里不复用 `ui/components/PiCommon.kt` 的 `PiSwitchRow` / `PiValueRow`：
 * 那两个还服务对话页的 sheet 与会话列表（本批范围外），且它们的取值来自旧 spec
 * 的 16dp/12dp 节奏；改它们会牵动别的屏。设置页的行因此自成一套，取值只来自
 * [PiSettingsMetrics]。
 */

/** The six kinds of `02-real-content.md` §4.3, dispatched from the registry metadata. */
@Composable
fun PiSettingRow(
    setting: PiSetting,
    valueText: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onExplainEffect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * 这一行是命中跳转的目标（1px accent 左线 + `selectedBg` 底）。
     *
     * 「当前生效值」的 2px accent 条已删：统一后的设置行只有三种尾巴，行内不再
     * 挂第二种标记；主题选择列表里的「当前」徽标仍然保留（那是一个选项列表）。
     */
    highlighted: Boolean = false,
) {
    when (setting.kind) {
        PiRowKind.Switch -> PiSettingsRowShell(highlighted = highlighted, modifier = modifier) {
            RowBody(
                title = setting.title,
                supporting = setting.description,
                effective = setting.effective,
                onExplainEffect = onExplainEffect,
            )
            PiSettingsSwitch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }

        PiRowKind.Action -> PiSettingsRowShell(
            highlighted = highlighted,
            modifier = modifier,
            onClick = onOpen,
            enabled = enabled,
        ) {
            RowBody(
                title = setting.title,
                supporting = setting.description,
                effective = setting.effective,
                onExplainEffect = onExplainEffect,
                // v2：Action 行的标题本身就是动作，用 accent；危险行动作词用 error。
                titleColor = if (setting.dangerous) {
                    PiTheme.palette.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            // 动作行的尾巴就是 chevron（原来的「! 执行」文字尾巴已按统一口径删掉）：
            // 危险由 error 色的标题承担，不再往行尾再挂一个动词。
            PiSettingsChevron()
        }

        // Value / Number / Text / List 都是「尾部值 + chevron」，只有编辑器不同
        // （06 §2：尾部值 13 等宽 + tabular）。
        PiRowKind.Value, PiRowKind.Number, PiRowKind.Text, PiRowKind.List ->
            PiSettingsRowShell(
                highlighted = highlighted,
                modifier = modifier,
                // 只读行（运行时事实）没有可打开的东西，所以**点击与 chevron 用同一个条件**：
                // 以前只藏了 chevron，行却还能点开一个空的、禁用的编辑器 —— 行上写着当时的
                // 引擎版本（`0.85.1`），sheet 里是空白（`docs/settings-audit-impl.md` §B4）。
                onClick = if (setting.readOnly) null else onOpen,
                enabled = enabled,
            ) {
                RowBody(
                    title = setting.title,
                    supporting = setting.description,
                    effective = setting.effective,
                    onExplainEffect = onExplainEffect,
                )
                Text(
                    valueText,
                    // 值再长也只能吃 40%：不封顶时它是 Row 里第一个被测量的子项，
                    // 长值会把标题挤成 0 宽（同 `display` 里那条多行摘要的另一半保险）。
                    modifier = Modifier.weight(0.4f, fill = false),
                    style = PiTheme.text.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 只读行（运行时事实）没有可打开的东西，v2 也不给它 chevron。
                if (!setting.readOnly) {
                    PiSettingsChevron()
                }
            }
    }
}

/**
 * 布尔行：`标题 15/500 + 一行说明 12 灰 + Switch`（开关贴右）。
 *
 * 「设备能力」页的三行能力开关走这一条；`description` 与「未开启」这类提示合成
 * **一行**（[supportingTone] 非空时整句换警示色），不再有第二行与行内按钮。
 */
@Composable
internal fun PiSettingsToggleRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingTone: Color? = null,
) {
    PiSettingsRowShell(highlighted = false, modifier = modifier) {
        RowBody(
            title = title,
            supporting = supporting,
            supportingTone = supportingTone,
        )
        PiSettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * 值行：`标题 + 一行说明（这里放值）+ chevron`。
 *
 * 值放在**第二行**（整行宽、13 等宽、一行省略）而不是贴右：模型 id 与 pattern 列表可以很长，
 * 贴右会把最要紧的那几个字截掉。原来这条尾巴是一个「修改」词（模型与供应商那三行），
 * 已按统一口径换成 chevron。
 */
@Composable
internal fun PiSettingsValueRow(
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    PiSettingsRowShell(highlighted = false, modifier = modifier, onClick = onClick) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = PiTheme.palette.muted,
                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            )
        }
        RowBody(
            title = title,
            supporting = value,
            supportingMono = true,
        )
        if (onClick != null) PiSettingsChevron()
    }
}

/**
 * 入口 / 动作行：`标题 + 一行说明 + chevron`。
 *
 * 设置首页的入口行（模型与供应商、设备能力、扩展包、Pi 文件、许可、诊断）、
 * 许可页的每一条通告、以及文末的分组入口都走这一条 —— 以前它们各自抄了一遍
 * `Row(padding = 10/12) + Icon + Column(title, supporting) + Text(查看/打开) + chevron`，
 * 于是同一个形状在四个文件里有四种字号与三种文字尾巴。
 *
 * 动作行的标题色（accent / 危险 error）由 `PiSettingRow` 自己按注册表的 `dangerous`
 * 决定，所以这里**不留一个 `tone` 形参**：它没有任何调用方会传。
 *
 * @param leadingIcon 前置图标（16 灰）；分组入口行没有图标，传 null。
 * @param monoTitle 标题用等宽（许可通告的组件名，要与 `dpkg -l` 对得上）。
 * @param expanded 只有折叠行传值：chevron 展开时转 90°。
 */
@Composable
internal fun PiSettingsEntryRow(
    title: String,
    supporting: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    monoTitle: Boolean = false,
    expanded: Boolean? = null,
) {
    PiSettingsRowShell(highlighted = false, modifier = modifier, onClick = onClick) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = PiTheme.palette.muted,
                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            )
        }
        RowBody(
            title = title,
            supporting = supporting,
            mono = monoTitle,
        )
        if (onClick != null) PiSettingsChevron(expanded = expanded)
    }
}

/**
 * 行骨架：底色（命中/普通）+ 左缘竖线 + `14px / 10px` 的行内边距。
 *
 * 命中高亮（`highlighted`）是唯一还留在行上的标记：1px accent + `selectedBg` 填充。
 * 它是**搜索跳转**的落点，不是「当前值」—— 后者那种 2px accent 条已经删掉
 * （统一后的行只有三种尾巴，行内不再挂第二种标记）。
 *
 * 命中填充为什么是 `selectedBg`：它就是 pi 的「选中」令牌（也就是 v2 的
 * `--selected-bg: #3A3A4A`），`colorScheme()` 里另映射到 `primaryContainer`。
 * 设置行躺在同为 `surfaceContainerLow` 的卡片里，填 `surfaceContainerLow` 等于没填，
 * 只剩那 1px 竖线。改成 `selectedBg` 后命中行既有可见填充、又不引入任何新颜色。
 *
 * `onClick` 为 null 时整行不可点（Switch 行由控件自己吃点击）。
 */
@Composable
private fun PiSettingsRowShell(
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                // 命中填充 = pi 的 selectedBg（v2 的 --selected-bg）；见上方 KDoc
                // 里「为什么不填 surfaceContainerLow」。
                if (highlighted) PiTheme.palette.selectedBg else Color.Transparent,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    end = PiSettingsMetrics.rowPaddingHorizontal,
                    top = PiSettingsMetrics.rowPaddingVertical,
                    bottom = PiSettingsMetrics.rowPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
            content = content,
        )
        if (highlighted) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(PiSettingsMetrics.hairline)
                    .background(PiTheme.palette.accent),
            )
        }
    }
}

/**
 * 标题 + 生效徽标 + 副行。徽标紧跟标题（v2 的 Row 把 `badge` 放在标题行里，
 * 不是钉在最右侧），点它弹生效说明。
 *
 * 副行只有**一行**：说明文案本来就有几百条，行高不能被其中几条长的撑开
 * （超出省略）。
 */
@Composable
private fun RowScope.RowBody(
    title: String,
    supporting: String?,
    effective: EffectiveKind? = null,
    onExplainEffect: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    supportingTone: Color? = null,
    mono: Boolean = false,
    /** 副行是**机器值**（模型 id、pattern 列表）：等宽脸。 */
    supportingMono: Boolean = false,
) {
    Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
        ) {
            Text(
                title,
                style = if (mono) {
                    PiTheme.text.mono
                } else {
                    MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                },
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 生效徽标（需重载 / 新会话 / 需重启…）保留：它说的是「这一行什么时候生效」，
            // 是设置面唯一必须留的徽标。Immediate 不出徽标。
            if (effective != null && effective != EffectiveKind.Immediate && onExplainEffect != null) {
                PiSettingsEffectiveBadge(
                    kind = effective,
                    modifier = Modifier.clickable(onClick = onExplainEffect),
                )
            }
        }
        if (supporting != null) {
            Text(
                supporting,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = if (supportingMono) PiTheme.text.mono else PiTheme.text.meta,
                color = supportingTone ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 行尾的 chevron（14，muted）。折叠行展开时转 90°。 */
@Composable
private fun PiSettingsChevron(expanded: Boolean? = null) {
    Icon(
        Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier
            .size(PiSettingsMetrics.chevronSize)
            .then(if (expanded == true) Modifier.rotate(90f) else Modifier),
        tint = PiTheme.palette.muted,
    )
}

/** 灰底说明卡：glob 语法、主题告警与只读提示共用（`06 §2` 卡片：圆角 10、内 12/14）。 */
@Composable
fun PiInfoNote(
    text: String,
    modifier: Modifier = Modifier,
    /**
     * 需要时只换字色（pi 的 `warning` / `error` 令牌），底色不动 ——
     * `06 §3`「提示条 Notice」的三档 tone 就是「同一张底 + 不同字色」。
     */
    tone: Color? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSettingsMetrics.notePaddingVertical),
        shape = PiSettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text,
            modifier = Modifier.padding(
                start = PiSettingsMetrics.cardPaddingLoose,
                end = PiSettingsMetrics.cardPaddingLoose,
                top = PiSettingsMetrics.rowPaddingVertical,
                bottom = PiSettingsMetrics.rowPaddingVertical,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Monospace rendering of a row's identity: its raw dotted key, used by search results and L1.
 *
 * The caller passes `PiSetting.keyLabel ?: PiSetting.key` — a row that is a pi **file** rather
 * than a pi key (the two system-prompt rows) shows the file it writes instead.
 */
@Composable
/** One-line key/value pair, for read-only diagnostics rows. */
@Composable
