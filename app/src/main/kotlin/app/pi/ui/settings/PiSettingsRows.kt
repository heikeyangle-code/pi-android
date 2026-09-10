package app.pi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.components.EffectiveKind
import app.pi.ui.components.PiEffectiveBadge
import app.pi.ui.components.PiSwitchRow
import app.pi.ui.components.PiValueRow
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * Settings-row components, built on the two rows that already exist in
 * `PiCommon.kt` so the stack stays visually consistent with the rest of the app.
 *
 * The only thing those two rows cannot express is the effective badge, because
 * they have no trailing slot. Rather than copy their layout, each row is wrapped
 * in [WithEffectBadge], which parks the badge at the right edge. Number, text,
 * list and action rows reuse [PiValueRow] as their body and only differ in what
 * the trailing value says and what tapping opens.
 */

/** The six kinds of spec §6.2, dispatched from the registry metadata. */
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
) {
    when (setting.kind) {
        PiRowKind.Switch -> WithEffectBadge(setting.effective, onExplainEffect, modifier) {
            PiSwitchRow(
                title = setting.title,
                supporting = setting.description,
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }

        PiRowKind.Action -> PiActionSettingRow(
            title = setting.title,
            supporting = setting.description,
            effective = setting.effective,
            dangerous = setting.dangerous,
            enabled = enabled,
            onExplainEffect = onExplainEffect,
            onClick = onOpen,
            modifier = modifier,
        )

        // Value, Number, Text and List all render as a value row plus a chevron;
        // only the trailing text and the editor behind them differ.
        PiRowKind.Value, PiRowKind.Number, PiRowKind.Text, PiRowKind.List ->
            WithEffectBadge(setting.effective, onExplainEffect, modifier) {
                Box(
                    Modifier.clickable(enabled = enabled, onClick = onOpen),
                ) {
                    PiValueRow(
                        title = setting.title,
                        supporting = setting.description,
                        value = valueText,
                    )
                }
            }
    }
}

@Composable
fun PiActionSettingRow(
    title: String,
    supporting: String?,
    effective: EffectiveKind,
    dangerous: Boolean,
    enabled: Boolean,
    onExplainEffect: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WithEffectBadge(effective, onExplainEffect, modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = PiSpacing.screen, vertical = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dangerous) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Explains one of pi's four take-effect timings (spec §6.5). The badge is shown
 * for everything that is not immediate, and tapping it must say what "reload"
 * means for this particular row, so the badge is a real touch target.
 */
@Composable
private fun WithEffectBadge(
    kind: EffectiveKind,
    onExplainEffect: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { body() }
        if (kind != EffectiveKind.Immediate) {
            PiEffectiveBadge(
                kind = kind,
                modifier = Modifier.clickable(onClick = onExplainEffect),
            )
            Spacer(Modifier.width(PiSpacing.screen))
        }
    }
}

/** Grey explanatory card, used for glob syntax and other caveats (spec §6.2). */
@Composable
fun PiInfoNote(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.screen, vertical = 6.dp),
        shape = PiShapes.cardInner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Monospace rendering of the raw dotted key, used by search results and L1. */
@Composable
fun PiKeyLabel(key: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = PiShapes.badge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            key,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            .padding(horizontal = PiSpacing.screen, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
