package app.pi.terminal

import android.view.KeyEvent

/**
 * Keyboard input encoding.
 *
 * Two facts drive everything here.
 *
 *  1. **pi falls back to xterm `modifyOtherKeys`.** Its terminal probe
 *     (`packages/tui/src/terminal.ts:255-320`) offers the Kitty keyboard protocol
 *     and falls back when the terminal answers the sentinel `DA1` query instead —
 *     which is exactly what [TerminalEmulator] deliberately does. pi then
 *     decodes `CSI 27 ; modifier ; codepoint ~` in `parseKey`
 *     (`packages/tui/src/keys.ts:1257`), and that is the form this encoder uses
 *     for every key it can express with a codepoint. The alternative (leaving
 *     modified Enter as a bare `CR`) would make `Shift+Enter` indistinguishable
 *     from `Enter`, and pi binds that difference to "newline instead of submit".
 *  2. **A soft keyboard has no modifier state and no function keys.** The toolbar
 *     is therefore not a convenience but the only way to type `Esc`, `Tab`,
 *     arrows or `Ctrl+…` on a phone; it emits the bytes a terminal would send.
 *
 * Nothing here is interpreted locally — the app is a terminal, not a keybinding
 * layer — so the bytes go to the guest untouched.
 */
object TerminalKeys {

    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val BACKSPACE = "\u007F"

    private const val CODEPOINT_ESCAPE = 27
    private const val CODEPOINT_TAB = 9
    private const val CODEPOINT_ENTER = 13
    private const val CODEPOINT_BACKSPACE = 127
    private const val CODEPOINT_DELETE = 57428
    private const val CODEPOINT_INSERT = 57425
    private const val CODEPOINT_HOME = 57423
    private const val CODEPOINT_END = 57424
    private const val CODEPOINT_PAGE_UP = 57421
    private const val CODEPOINT_PAGE_DOWN = 57422
    private const val CODEPOINT_UP = 57352
    private const val CODEPOINT_DOWN = 57354
    private const val CODEPOINT_LEFT = 57350
    private const val CODEPOINT_RIGHT = 57351
    private const val CODEPOINT_F1 = 57364

    /** `CSI 27 ; modifier ; codepoint ~`, the xterm modifyOtherKeys form. */
    private fun modifyOtherKeys(modifier: Int, codepoint: Int): String =
        "\u001b[27;$modifier;${codepoint}~"

    /** xterm's modifier value: 1 + shift(1) + alt(2) + ctrl(4). */
    fun modifierValue(shift: Boolean, alt: Boolean, ctrl: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    /**
     * A hardware key, as the bytes a terminal would send, or null when the app
     * does not handle that key (so the caller can let a system shortcut through).
     */
    fun encode(event: KeyEvent): String? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        val alt = event.isAltPressed
        val shift = event.isShiftPressed
        val ctrl = event.isCtrlPressed
        val mods = modifierValue(shift, alt, ctrl)

        navigationSequence(event.keyCode)?.let { navigation ->
            val codepoint = navigation.codepoint
            // With no modifier, the legacy form is what every full-screen
            // program (and pi) understands; the modified form is only sent when
            // there is a modifier to report.
            if (mods == 1) return navigation.plain
            if (shift && !alt && !ctrl) {
                shiftedArrow(event.keyCode)?.let { return it }
            }
            return modifyOtherKeys(mods, codepoint)
        }
        return characterKey(event, mods, shift, alt, ctrl)
    }

    /**
     * Printable keys, with modifiers.
     *
     * Android delivers `Ctrl+A` as an `A` key event with `isCtrlPressed`, while a
     * terminal must send `0x01`; `Alt+x` is `ESC x`. A plain letter on a hardware
     * keyboard also arrives here, so it is emitted directly rather than relying on
     * the IME path (a physical keyboard does not compose through it).
     */
    private fun characterKey(event: KeyEvent, mods: Int, shift: Boolean, alt: Boolean, ctrl: Boolean): String? {
        val unicode = event.unicodeChar
        if (unicode == 0) return null
        if (unicode < 0x20) return null
        if (ctrl) {
            // Ctrl+letter is a control byte; the IME path never sees it.
            return controlByte(unicode)?.let { if (alt) ESC + it else it }
        }
        if (alt) return ESC + unicode.toChar()
        // Shift-only and unmodified text: send the composed character. A
        // modifier we cannot express (Meta) is better passed through as text
        // than silently dropped.
        return unicode.toChar().toString()
    }

    /**
     * The control byte for a code point, or null when there is none.
     *
     * `Ctrl+Space` is NUL; `Ctrl+A`..`Ctrl+Z` are 1..26; the punctuation controls
     * follow the ASCII table, which is why `Ctrl+[` is `Esc`, `Ctrl+\` is FS and
     * `Ctrl+-` is US. pi binds `Ctrl+C`/`Ctrl+D` in its shell and `Ctrl+P` for the
     * model cycle, so these bytes are load-bearing.
     */
    fun controlByte(codePoint: Int): String? = when (codePoint) {
        0x20, 0x40 -> "\u0000"
        in 0x41..0x5A -> (codePoint - 0x40).toChar().toString()
        in 0x61..0x7A -> (codePoint - 0x60).toChar().toString()
        0x5B -> "\u001b"
        0x5C -> "\u001c"
        0x5D -> "\u001d"
        0x5E -> "\u001e"
        0x5F -> "\u001f"
        0x2D -> "\u001f" // Ctrl+- is the same physical key as Ctrl+_
        0x3F -> "\u007f"
        else -> null
    }

    /**
     * The toolbar's armed `Ctrl` modifier, applied to the next input.
     *
     * Only the first character is transformed: a control byte followed by more
     * text is not something a terminal has a meaning for.
     */
    fun applyControl(text: String): String {
        if (text.isEmpty()) return text
        val control = controlByte(text[0].code) ?: return text
        return control + text.substring(1)
    }

    /** `Shift+Tab` is `CSI Z` in every terminal; pi binds it to reverse focus. */
    fun shiftTab(): String = "\u001b[Z"

    /** Paste, wrapped the way a bracketed-paste-aware program expects. */
    fun paste(text: String, bracketed: Boolean): String =
        if (bracketed) "$ESC[200~$text$ESC[201~" else text

    private class Navigation(val plain: String, val codepoint: Int)

    private fun navigationSequence(keyCode: Int): Navigation? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Navigation("\u001b[A", CODEPOINT_UP)
        KeyEvent.KEYCODE_DPAD_DOWN -> Navigation("\u001b[B", CODEPOINT_DOWN)
        KeyEvent.KEYCODE_DPAD_RIGHT -> Navigation("\u001b[C", CODEPOINT_RIGHT)
        KeyEvent.KEYCODE_DPAD_LEFT -> Navigation("\u001b[D", CODEPOINT_LEFT)
        KeyEvent.KEYCODE_MOVE_HOME -> Navigation("\u001b[H", CODEPOINT_HOME)
        KeyEvent.KEYCODE_MOVE_END -> Navigation("\u001b[F", CODEPOINT_END)
        KeyEvent.KEYCODE_INSERT -> Navigation("\u001b[2~", CODEPOINT_INSERT)
        KeyEvent.KEYCODE_FORWARD_DEL -> Navigation("\u001b[3~", CODEPOINT_DELETE)
        KeyEvent.KEYCODE_PAGE_UP -> Navigation("\u001b[5~", CODEPOINT_PAGE_UP)
        KeyEvent.KEYCODE_PAGE_DOWN -> Navigation("\u001b[6~", CODEPOINT_PAGE_DOWN)
        KeyEvent.KEYCODE_ESCAPE -> Navigation(ESC, CODEPOINT_ESCAPE)
        KeyEvent.KEYCODE_TAB -> Navigation(TAB, CODEPOINT_TAB)
        KeyEvent.KEYCODE_DEL -> Navigation(BACKSPACE, CODEPOINT_BACKSPACE)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Navigation(ENTER, CODEPOINT_ENTER)
        KeyEvent.KEYCODE_F1 -> Navigation("\u001bOP", CODEPOINT_F1)
        KeyEvent.KEYCODE_F2 -> Navigation("\u001bOQ", CODEPOINT_F1 + 1)
        KeyEvent.KEYCODE_F3 -> Navigation("\u001bOR", CODEPOINT_F1 + 2)
        KeyEvent.KEYCODE_F4 -> Navigation("\u001bOS", CODEPOINT_F1 + 3)
        KeyEvent.KEYCODE_F5 -> Navigation("\u001b[15~", CODEPOINT_F1 + 4)
        KeyEvent.KEYCODE_F6 -> Navigation("\u001b[17~", CODEPOINT_F1 + 5)
        KeyEvent.KEYCODE_F7 -> Navigation("\u001b[18~", CODEPOINT_F1 + 6)
        KeyEvent.KEYCODE_F8 -> Navigation("\u001b[19~", CODEPOINT_F1 + 7)
        KeyEvent.KEYCODE_F9 -> Navigation("\u001b[20~", CODEPOINT_F1 + 8)
        KeyEvent.KEYCODE_F10 -> Navigation("\u001b[21~", CODEPOINT_F1 + 9)
        KeyEvent.KEYCODE_F11 -> Navigation("\u001b[23~", CODEPOINT_F1 + 10)
        KeyEvent.KEYCODE_F12 -> Navigation("\u001b[24~", CODEPOINT_F1 + 11)
        else -> null
    }

    /** `Shift`+arrow has its own legacy letter, still decoded by pi. */
    private fun shiftedArrow(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "\u001b[a"
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[b"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[c"
        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[d"
        else -> null
    }

    /** One chip in the key bar. `bytes == null` marks the sticky `ctrl` modifier. */
    class ToolbarKey(val label: String, val bytes: String?)

    /**
     * The key set docs/pi-android-ui-spec.md §5.3 asks for:
     * `esc` `tab` `ctrl` `↑` `↓` `←` `→` `|` `~` `/` `-`.
     */
    val toolbarKeys: List<ToolbarKey> = listOf(
        ToolbarKey("esc", ESC),
        ToolbarKey("tab", TAB),
        ToolbarKey("ctrl", null),
        ToolbarKey("↑", "\u001b[A"),
        ToolbarKey("↓", "\u001b[B"),
        ToolbarKey("←", "\u001b[D"),
        ToolbarKey("→", "\u001b[C"),
        ToolbarKey("|", "|"),
        ToolbarKey("~", "~"),
        ToolbarKey("/", "/"),
        ToolbarKey("-", "-"),
    )
}
