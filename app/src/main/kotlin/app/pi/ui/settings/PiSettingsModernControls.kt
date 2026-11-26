package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.components.EffectiveKind
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiFontFeatures
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * Quiet section header shared across dedicated modern settings screens.
 */
@Composable
internal fun PiSettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
                end = PiSettingsMetrics.pageHorizontal,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = PiTheme.palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * 1-tap inline tactile segmented selector for 2–4 concise choices (same visual grammar as `AppearanceScreen`).
 */
@Composable
internal fun PiInlineSegmentRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    subtitle: String? = null,
    effective: EffectiveKind = EffectiveKind.Immediate,
    onExplainEffect: (() -> Unit)? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val isDark = PiTheme.isDark
    val accent = PiTheme.palette.accent
    val trackBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    val activeBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
    val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f)
    val activeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.75f else 0.90f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
            .then(
                if (highlighted) {
                    Modifier.drawBehind {
                        drawRect(accent.copy(alpha = 0.08f))
                        drawLine(
                            color = accent,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 11.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.supportingGap)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
                ) {
                    Text(
                        text = title,
                        style = PiType.rowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (effective != EffectiveKind.Immediate && onExplainEffect != null) {
                        PiEffectivePill(kind = effective, onClick = onExplainEffect)
                    }
                }
                if (subtitle != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = subtitle,
                        style = PiTheme.text.monoSmall.copy(fontFeatureSettings = PiFontFeatures.TABULAR),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = trackBg,
            border = BorderStroke(PiSpacing.hairlineThin, trackBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                options.forEach { (wire, label) ->
                    val active = wire == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) activeBg else Color.Transparent)
                            .border(
                                width = if (active) PiSpacing.hairlineThin else 0.dp,
                                color = if (active) activeBorder else Color.Transparent,
                                shape = RoundedCornerShape(7.dp),
                            )
                            .clickable(enabled = enabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(wire)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = PiTheme.text.meta.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                fontFeatureSettings = PiFontFeatures.TABULAR,
                            ),
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1-tap horizontal preset strip for multi-choice or numeric settings, with an optional
 * tappable value pill on the right to open the exact editor sheet for arbitrary custom values.
 */
@Composable
internal fun <T> PiInlinePresetRow(
    title: String,
    valueText: String,
    presets: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    onCustomClick: (() -> Unit)? = null,
    effective: EffectiveKind = EffectiveKind.Immediate,
    onExplainEffect: (() -> Unit)? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val pillBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
            .then(
                if (highlighted) {
                    Modifier.drawBehind {
                        drawRect(palette.accent.copy(alpha = 0.08f))
                        drawLine(
                            color = palette.accent,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 11.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.supportingGap)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
                ) {
                    Text(
                        text = title,
                        style = PiType.rowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (effective != EffectiveKind.Immediate && onExplainEffect != null) {
                        PiEffectivePill(kind = effective, onClick = onExplainEffect)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { onCustomClick?.invoke() },
                    enabled = enabled && onCustomClick != null,
                    shape = RoundedCornerShape(8.dp),
                    color = pillBg,
                    border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = if (onCustomClick != null) "$valueText ✎" else valueText,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = PiTheme.text.monoSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = PiFontFeatures.TABULAR,
                        ),
                        color = if (onCustomClick != null) palette.accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { (value, label) ->
                val active = selected == value
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(value)
                    },
                    enabled = enabled,
                    shape = RoundedCornerShape(9.dp),
                    color = if (active) palette.accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        if (active) 1.dp else PiSpacing.hairlineThin,
                        if (active) palette.accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        style = PiTheme.text.meta.copy(
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            fontFeatureSettings = PiFontFeatures.TABULAR,
                        ),
                        color = if (active) palette.accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            if (onCustomClick != null) {
                Surface(
                    onClick = onCustomClick,
                    enabled = enabled,
                    shape = RoundedCornerShape(9.dp),
                    color = Color.Transparent,
                    border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = tr("自定义", "Custom"),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PiEffectivePill(
    kind: EffectiveKind,
    onClick: () -> Unit,
) {
    val label = when (kind) {
        EffectiveKind.Reload -> tr("需重载", "Reload")
        EffectiveKind.NewSession -> tr("新会话", "New session")
        EffectiveKind.RestartEngine -> tr("重启引擎", "Restart")
        EffectiveKind.AutoRestartEngine -> tr("自动重启", "Auto-restart")
        EffectiveKind.RestartApp -> tr("重启 App", "Relaunch")
        EffectiveKind.Immediate -> return
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = PiTheme.palette.warning.copy(alpha = 0.12f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = PiTheme.palette.warning,
            maxLines = 1,
        )
    }
}
