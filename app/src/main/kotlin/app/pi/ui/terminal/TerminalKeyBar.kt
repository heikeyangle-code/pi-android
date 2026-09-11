package app.pi.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.VTermKey

/**
 * The terminal's key bar: the keys a soft keyboard cannot produce.
 *
 * ## Where the keys come from
 *
 * The layout and the key set are Termux's, taken from `ExtraKeysView`'s default
 * (`TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS` in termux-app):
 *
 * ```
 * [['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'],
 *  ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]
 * ```
 *
 * That is the layout a phone-terminal user already knows, including the
 * long-press `popup` on `-` that yields `|`.
 *
 * ## Why the ExtraKeysView *library* is not a dependency
 *
 * It is a `LinearLayout`-style `View` bound to Termux's own `TerminalView` and
 * `TermuxConstants`, and its output path is `TerminalView.handleKeyCode`, which
 * does not exist in this app. What is worth taking from it is the *key table*,
 * and that is taken. Dispatch goes through the engine this app actually uses:
 * `TerminalEmulator.dispatchKey(modifiers, VTermKey…)` and
 * `TerminalEmulator.dispatchCharacter(modifiers, codepoint)`, which ask libvterm
 * to encode the sequence — including the modes a program can switch on
 * (`DECCKM` for the arrow keys, `DECKPAM` for the keypad) and the
 * `modifyOtherKeys`/Kitty forms pi negotiates for itself. Hard-coding `ESC [ A`
 * for Up, as the previous bar did, is wrong the moment a program asks for
 * application-cursor mode.
 *
 * The modifier bitmask is libvterm's: 1 = Shift, 2 = Alt, 4 = Ctrl.
 */
data class TerminalBarKey(
    /** Stable name, also what `app.terminal.keyBar` stores. */
    val id: String,
    val label: String,
    /** A libvterm key code ([VTermKey]); [VTermKey.NONE] for the other kinds. */
    val vtermKey: Int = VTermKey.NONE,
    /** The code point this chip sends, when it is a literal character. */
    val character: Char? = null,
    /** Termux's long-press alternative, e.g. `|` on the `-` chip. */
    val popup: Char? = null,
    /** Set on the sticky modifier chips; nothing is sent when one is tapped. */
    val sticky: StickyModifier? = null,
) {

    /** The same chip, sending [value] instead of its face value (the popup path). */
    fun withCharacter(value: Char): TerminalBarKey = copy(character = value)

    companion object {

        val ESC = TerminalBarKey("esc", "ESC", vtermKey = VTermKey.ESCAPE)
        val TAB = TerminalBarKey("tab", "TAB", vtermKey = VTermKey.TAB)
        val CTRL = TerminalBarKey("ctrl", "CTRL", sticky = StickyModifier.Ctrl)
        val ALT = TerminalBarKey("alt", "ALT", sticky = StickyModifier.Alt)

        val UP = TerminalBarKey("up", "\u2191", vtermKey = VTermKey.UP)
        val DOWN = TerminalBarKey("down", "\u2193", vtermKey = VTermKey.DOWN)
        val LEFT = TerminalBarKey("left", "\u2190", vtermKey = VTermKey.LEFT)
        val RIGHT = TerminalBarKey("right", "\u2192", vtermKey = VTermKey.RIGHT)

        val HOME = TerminalBarKey("home", "HOME", vtermKey = VTermKey.HOME)
        val END = TerminalBarKey("end", "END", vtermKey = VTermKey.END)
        val PGUP = TerminalBarKey("pgup", "PGUP", vtermKey = VTermKey.PAGEUP)
        val PGDN = TerminalBarKey("pgdn", "PGDN", vtermKey = VTermKey.PAGEDOWN)

        val SLASH = TerminalBarKey("slash", "/", character = '/')
        val DASH = TerminalBarKey("dash", "-", character = '-', popup = '|')
        val PIPE = TerminalBarKey("pipe", "|", character = '|')
        val TILDE = TerminalBarKey("tilde", "~", character = '~')

        /** Termux's default two rows, in its order. */
        val defaultRows: List<List<TerminalBarKey>> = listOf(
            listOf(ESC, SLASH, DASH, HOME, UP, END, PGUP),
            listOf(TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN),
        )

        /**
         * Every chip a stored `app.terminal.keyBar` list may name.
         *
         * It is a superset of [defaultRows] on purpose: `pipe` and `tilde` are
         * not in Termux's default bar, but they are in the preset list the
         * Settings screen offers (`PiSettingsRegistry.kt`, `termKeyBarPresets`),
         * so a user who configured the old bar keeps exactly those chips instead
         * of silently losing two.
         */
        val catalog: List<TerminalBarKey> = listOf(
            ESC, TAB, CTRL, ALT, UP, DOWN, LEFT, RIGHT,
            HOME, END, PGUP, PGDN, SLASH, DASH, PIPE, TILDE,
        )

        private val indexById: Map<String, TerminalBarKey> = catalog.associateBy { it.id }

        fun byId(id: String): TerminalBarKey? = indexById[id.lowercase()]
    }
}

/** The two sticky modifiers the bar can arm. */
enum class StickyModifier { Ctrl, Alt }

/**
 * The bar itself. It is deliberately dumb about the guest: a tap is reported to
 * [onKey] and the pane decides what the engine is told, so that a chip press and
 * a hardware key go through one code path and share one modifier state.
 */
@Composable
fun TerminalKeyBar(
    palette: TerminalPalette,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onKey: (TerminalBarKey) -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    /**
     * The rows to draw. `app.terminal.keyBar` resolves to these through
     * `TerminalPreferences.keyBar`; the default is Termux's layout.
     */
    rows: List<List<TerminalBarKey>> = TerminalBarKey.defaultRows,
    /**
     * Paste, as one extra chip at the end of the last row.
     *
     * `ExtraKeysView` has no paste key because Termux reaches its clipboard
     * through the long-press menu of its own text selection; this app has no
     * such menu, and a soft keyboard cannot produce `Ctrl+V`, so pasting needs a
     * chip of its own. It is deliberately appended next to the keys rather than
     * hidden in the tab strip: it is terminal *input*, and the user is already
     * looking here.
     */
    onPaste: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.background.copy(alpha = 0.96f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { key ->
                    KeyChip(
                        key = key,
                        palette = palette,
                        armed = when (key.sticky) {
                            StickyModifier.Ctrl -> ctrlArmed
                            StickyModifier.Alt -> altArmed
                            null -> false
                        },
                        onKey = onKey,
                    )
                }
                if (index == rows.lastIndex && onPaste != null) {
                    ActionChip(label = "PASTE", palette = palette, onClick = onPaste)
                }
            }
        }
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

/** One key chip. [armed] marks a sticky modifier that will apply to the next key. */
@Composable
private fun KeyChip(
    key: TerminalBarKey,
    palette: TerminalPalette,
    armed: Boolean,
    onKey: (TerminalBarKey) -> Unit,
) {
    var popupOpen by remember { mutableStateOf(false) }

    Box {
        Box(
            Modifier
                .size(width = 40.dp, height = 34.dp)
                .background(
                    if (armed) palette.chipArmed else palette.chip,
                    RoundedCornerShape(6.dp),
                )
                // A plain tap sends the key; a long press opens Termux's popup,
                // which is how one chip carries two characters.
                .pointerInput(key, onKey) {
                    detectTapGestures(
                        onTap = { onKey(key) },
                        onLongPress = { if (key.popup != null) popupOpen = true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = key.label,
                color = palette.foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }

        val popup = key.popup
        if (popup != null) {
            DropdownMenu(expanded = popupOpen, onDismissRequest = { popupOpen = false }) {
                DropdownMenuItem(
                    text = { Text(popup.toString(), fontFamily = FontFamily.Monospace) },
                    onClick = {
                        popupOpen = false
                        onKey(key.withCharacter(popup))
                    },
                )
            }
        }
    }
}

/** A labelled action that is not a terminal key (currently only `PASTE`). */
@Composable
private fun ActionChip(
    label: String,
    palette: TerminalPalette,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 62.dp, height = 34.dp)
            .background(palette.chip, RoundedCornerShape(6.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = palette.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}
