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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 设置行构件：v2 的六种行型（`02-real-content.md` §4.3）共用一套行骨架。
 *
 * 行骨架照 `06-v2-construction-reference.md` §2「行（Row）」实现：
 * `padding:10px 12px`、标题 15/500、副行 12 灰、尾部值 13 等宽 + tabular、
 * chevron 14、「当前生效值」左缘 2px accent 条。分层只用 1px 线与表面阶梯，
 * 没有任何阴影与 2px 以上的描边。
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
     * 这一行显示的就是**当前生效的值**（v2 数据里的 `cur`）。
     * 判定见 [SettingsGroupScreen]：值被显式写过、且行型是 Value —— 也就是
     * 「用户选的那一个」，不是 pi 的内置默认。
     */
    current: Boolean = false,
    /**
     * 这一行是搜索命中跳转的目标（计划 S8：1px accent 左线 + `surfaceContainerLow` 底，
     * 取代原来的 `secondaryContainer` 底色）。
     */
    highlighted: Boolean = false,
) {
    when (setting.kind) {
        PiRowKind.Switch -> PiSettingsRowShell(highlighted, current, modifier) {
            RowBody(
                title = setting.title,
                supporting = setting.description,
                effective = setting.effective,
                onExplainEffect = onExplainEffect,
            )
            PiSettingsSwitchRowTrailing(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }

        PiRowKind.Action -> PiSettingsRowShell(
            highlighted = highlighted,
            current = current,
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
            // v2 的危险行在尾部写「! 执行」（error 色），普通 Action 行尾部为空。
            // 两个声音（规则 #7 + 裁决 ②-2）：`!` 是符号层 → 等宽；「执行」是我们的词 →
            // 系统字。形状与 `WsBadge` / `PiSettingsEffectiveBadge`（符号等宽 + 词系统字）
            // 相同，整条改 mono 会让一个动词读成标识符。
            if (setting.dangerous) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                ) {
                    Text(
                        text = "!",
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.error,
                    )
                    Text(
                        text = "执行",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.error,
                    )
                }
            }
        }

        // Value / Number / Text / List 都是「尾部值 + chevron」，只有编辑器与尾部
        // 文案不同（06 §2：尾部值 13 等宽 + tabular）。
        PiRowKind.Value, PiRowKind.Number, PiRowKind.Text, PiRowKind.List ->
            PiSettingsRowShell(
                highlighted = highlighted,
                current = current,
                modifier = modifier,
                onClick = onOpen,
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
                    style = PiTheme.text.mono,
                    color = if (current) {
                        PiTheme.palette.accent
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 只读行（运行时事实）没有可打开的东西，v2 也不给它 chevron。
                if (!setting.readOnly) {
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(PiSettingsMetrics.chevronSize),
                        tint = PiTheme.palette.muted,
                    )
                }
            }
    }
}

/**
 * 行骨架：底色（命中/普通）+ 左缘竖线 + `10px 12px` 的行内边距。
 *
 * 左缘两条线的分工是 v2 里的两件事，不能互相替代：
 *  - 命中高亮（搜索跳转）：1px accent + `selectedBg` 填充；
 *  - 当前生效值：2px accent，上下各缩进 8（`06 §2`）。
 *
 * 命中填充为什么是 `selectedBg`：它就是 pi 的「选中」令牌（也就是 v2 的
 * `--selected-bg: #3A3A4A`），`colorScheme()` 里另映射到 `primaryContainer`。
 * 计划 S8 原本写 `surfaceContainerLow` —— 但设置行现在躺在同为
 * `surfaceContainerLow` 的卡片里，那样填等于没填，只剩那 1px 竖线。改成
 * `selectedBg` 后命中行既有可见填充、又不引入任何新颜色。
 *
 * 可读性（同 v2 自己那一对令牌的取值，未做任何调色）：
 *  - 深色 `#3A3A4A`：正文 `#D4D4D4` 7.5:1、副行 `#808080` 2.8:1、accent 5.4:1；
 *  - 浅色 `#D0D0E0`：正文 10.4:1、副行 3.5:1、accent 2.85:1。
 * 深色副行 2.8:1 与浅色 accent 2.85:1 略低于 3:1 的元信息/图形地板，但这不是新
 * 组合：v2 的选中行本来就是 `--selected-bg` + `--muted` / `--accent`，且同一行
 * 另有正文色标题承担信息（`06 §4` 的图形类规则）。
 *
 * `onClick` 为 null 时整行不可点（Switch 行由控件自己吃点击）。
 */
@Composable
private fun PiSettingsRowShell(
    highlighted: Boolean,
    current: Boolean,
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
                // 里「为什么不填 surfaceContainerLow」与对比度实测。
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
        if (current) {
            Box(
                Modifier
                    .padding(
                        top = PiSettingsMetrics.currentBarInset,
                        bottom = PiSettingsMetrics.currentBarInset,
                    )
                    .fillMaxHeight()
                    .width(PiSettingsMetrics.currentBarWidth)
                    .background(PiTheme.palette.accent),
            )
        }
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
 */
@Composable
private fun RowScope.RowBody(
    title: String,
    supporting: String?,
    effective: EffectiveKind,
    onExplainEffect: () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (effective != EffectiveKind.Immediate) {
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
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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

/** Monospace rendering of the raw dotted key, used by search results and L1. */
@Composable
fun PiKeyLabel(key: String, modifier: Modifier = Modifier) {
    Text(
        key,
        modifier = modifier
            .clip(PiShapes.badge)
            .border(
                PiSettingsMetrics.hairline,
                MaterialTheme.colorScheme.outline,
                PiShapes.badge,
            )
            .padding(
                horizontal = PiSettingsMetrics.badgePaddingStart,
                vertical = PiSettingsMetrics.badgePaddingVertical,
            ),
        style = PiTheme.text.monoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One-line key/value pair, for read-only diagnostics rows. */
@Composable
fun PiKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(PiSettingsMetrics.rowGap))
        Text(
            value,
            style = PiTheme.text.mono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
