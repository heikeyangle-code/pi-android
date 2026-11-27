package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.components.EffectiveKind
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * Unified settings rows:
 *  1. Switch row: whole-row toggleable + `PiSettingsSwitch`;
 *  2. Value row: title + description + right-aligned mono value + chevron;
 *  3. Action / Entry row: optional leading icon tile + title + description + trailing badge + chevron.
 */
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
    highlighted: Boolean = false,
) {
    when (setting.kind) {
        PiRowKind.Switch -> {
            val haptic = LocalHapticFeedback.current
            PiSettingsRowShell(
                highlighted = highlighted,
                modifier = modifier
                    .alpha(if (enabled) 1f else 0.45f)
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { next ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggle(next)
                        },
                    ),
            ) {
                RowBody(
                    title = setting.title,
                    supporting = setting.description,
                    effective = setting.effective,
                    onExplainEffect = onExplainEffect,
                )
                PiSettingsSwitch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                )
            }
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
                titleColor = if (setting.dangerous) {
                    PiTheme.palette.error
                } else {
                    PiTheme.palette.accent
                },
            )
            PiSettingsChevron()
        }

        PiRowKind.Value, PiRowKind.Number, PiRowKind.Text, PiRowKind.List ->
            PiSettingsRowShell(
                highlighted = highlighted,
                modifier = modifier,
                onClick = if (setting.readOnly) null else onOpen,
                enabled = enabled,
            ) {
                RowBody(
                    title = setting.title,
                    supporting = setting.description,
                    effective = setting.effective,
                    onExplainEffect = onExplainEffect,
                )
                Box(
                    modifier = Modifier.weight(0.4f),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        valueText,
                        style = PiTheme.text.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!setting.readOnly) {
                    PiSettingsChevron()
                }
            }
    }
}

/** Whole-row tappable boolean toggle row. */
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
    val haptic = LocalHapticFeedback.current
    PiSettingsRowShell(
        highlighted = false,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { next ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(next)
                },
            ),
    ) {
        RowBody(
            title = title,
            supporting = supporting,
            supportingTone = supportingTone,
        )
        PiSettingsSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

/** Navigation / entry row with optional restrained monochrome hairline icon well and trailing badge. */
@Composable
internal fun PiSettingsEntryRow(
    title: String,
    supporting: String?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingTint: Color? = null,
    monoTitle: Boolean = false,
    expanded: Boolean? = null,
    trailingBadge: (@Composable () -> Unit)? = null,
) {
    PiSettingsRowShell(highlighted = false, modifier = modifier, onClick = onClick) {
        if (leadingIcon != null) {
            val palette = PiTheme.palette
            val semantic = leadingTint == palette.error || leadingTint == palette.warning
            val wellShape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(wellShape)
                    .background(
                        if (semantic && leadingTint != null) leadingTint.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .border(
                        PiSpacing.hairlineThin,
                        if (semantic && leadingTint != null) leadingTint.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                        wellShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = if (semantic && leadingTint != null) leadingTint else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        RowBody(
            title = title,
            supporting = supporting,
            mono = monoTitle,
        )
        trailingBadge?.invoke()
        if (onClick != null) PiSettingsChevron(expanded = expanded)
    }
}

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
            .background(if (highlighted) PiTheme.palette.selectedBg else Color.Transparent)
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
            val accent = PiTheme.palette.accent
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(color = accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                    },
            )
        }
    }
}

@Composable
private fun RowScope.RowBody(
    title: String,
    supporting: String?,
    effective: EffectiveKind? = null,
    onExplainEffect: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    supportingTone: Color? = null,
    mono: Boolean = false,
    supportingMono: Boolean = false,
) {
    Column(Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f, fill = false),
                style = if (mono) PiTheme.text.mono else PiType.rowTitle,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PiSettingsChevron(expanded: Boolean? = null) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier
            .size(PiSettingsMetrics.chevronSize)
            .then(if (expanded == true) Modifier.rotate(90f) else Modifier),
        tint = PiTheme.palette.muted,
    )
}

/** Tonal info note card. Pass `horizontalInset = 0.dp` when placed inside an already padded sheet. */
@Composable
fun PiInfoNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    horizontalInset: Dp = PiSettingsMetrics.pageHorizontal,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset, vertical = PiSettingsMetrics.notePaddingVertical),
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
            style = PiTheme.text.meta,
            color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
