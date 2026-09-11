package app.pi.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.terminal.TerminalKeys
import app.pi.terminal.TerminalPalette

/**
 * The terminal's key bar (docs/pi-android-ui-spec.md §5.3, §7.5).
 *
 * `esc` `tab` `ctrl` `↑` `↓` `←` `→` `|` `~` `/` `-`, exactly as specified, plus a
 * font-size control because a terminal on a phone is unusable at one size. Every
 * chip sends the bytes a terminal would send — this is not a keybinding layer, so
 * `esc` is `0x1b` and nothing is intercepted.
 *
 * `ctrl` is different from the rest: it arms a modifier for the *next* input rather
 * than sending anything, because a soft keyboard has no way to hold a modifier
 * down. The next composed character goes through
 * [TerminalKeys.applyControl] and becomes a control byte.
 */
@Composable
fun TerminalKeyBar(
    palette: TerminalPalette,
    ctrlArmed: Boolean,
    onCtrlToggle: () -> Unit,
    onKey: (String) -> Unit,
    onFontSize: (Float) -> Unit,
    fontSize: Float,
    statusText: String?,
    modifier: Modifier = Modifier,
    /**
     * `app.terminal.keyBar`, already resolved to chips by
     * `TerminalPreferences.keyBar`. Defaults to the documented bar so tests and
     * previews keep the specified keys.
     */
    keys: List<TerminalKeys.ToolbarKey> = TerminalKeys.toolbarKeys,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.96f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { key ->
            if (key.bytes == null) {
                KeyChip(
                    label = key.label,
                    palette = palette,
                    active = ctrlArmed,
                    onClick = onCtrlToggle,
                )
            } else {
                KeyChip(
                    label = key.label,
                    palette = palette,
                    active = false,
                    onClick = { onKey(key.bytes) },
                )
            }
        }
        FontSizeChip(palette = palette, fontSize = fontSize, onFontSize = onFontSize)
        statusText?.let {
            Text(
                text = it,
                color = palette.foreground.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** One key chip. [active] marks the armed `ctrl` modifier. */
@Composable
private fun KeyChip(
    label: String,
    palette: TerminalPalette,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 36.dp, height = 32.dp)
            .background(
                if (active) palette.cursor.copy(alpha = 0.35f) else palette.foreground.copy(alpha = 0.08f),
                RoundedCornerShape(6.dp),
            )
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = palette.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

/** Font size, on the bar rather than in a settings screen nobody would find. */
@Composable
private fun FontSizeChip(
    palette: TerminalPalette,
    fontSize: Float,
    onFontSize: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        KeyChip(
            label = "A${fontSize.toInt()}",
            palette = palette,
            active = false,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(10f, 11f, 12f, 13f, 14f, 16f, 18f).forEach { size ->
                DropdownMenuItem(
                    text = { Text("$size".removeSuffix(".0") + " sp") },
                    onClick = {
                        expanded = false
                        onFontSize(size)
                    },
                )
            }
        }
    }
}
