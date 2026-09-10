package app.pi.terminal

import androidx.compose.ui.graphics.Color

/**
 * A terminal palette: the default colours the screen is painted in, plus the 16
 * ANSI slots when the app's own pi theme should tint them.
 *
 * A terminal is *not* a themed surface, which is why only the defaults come from
 * the app's palette. Programs ask for "red" and expect red; if the theme remapped
 * `SGR 31` to the accent colour, `git diff` would stop meaning what it means.
 * What the theme legitimately controls is the surface: the background the view is
 * drawn on, and the text colour a program gets when it asks for neither.
 *
 * One deliberate liberty: when a program emits a *system* colour index, the
 * palette slot is used if the app supplied one. That is how a terminal profile
 * is supposed to behave, and it is the single knob that lets the Workbench feel
 * like the rest of the app without lying to the programs running in it.
 */
class TerminalPalette(
    val foreground: Color,
    val background: Color,
    /** The bar drawn over the character the cursor sits on. */
    val cursor: Color,
    /** Highlight for a text selection. */
    val selection: Color,
    /** 16 system colours; a null slot falls back to the xterm table. */
    val ansi: Array<Color?> = arrayOfNulls(16),
) {

    /** Resolve a cell colour, treating `DEFAULT` as the palette's own. */
    internal fun resolveForeground(packed: Int): Color =
        if (packed == AnsiColors.DEFAULT) foreground else opaque(packed)

    internal fun resolveBackground(packed: Int): Color =
        if (packed == AnsiColors.DEFAULT) background else opaque(packed)

    /** A system colour by index, with `bold` brightening it the way ACLs do. */
    internal fun systemColor(index: Int, bold: Boolean): Color {
        val slot = index.coerceIn(0, 15)
        val resolved = ansi[slot]
        if (resolved != null) return resolved
        val bright = bold && slot < 8
        val tableIndex = if (bright) slot + 8 else slot
        return opaque(AnsiColors.BASIC_16[tableIndex])
    }

    private fun opaque(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

    companion object {
        /**
         * The palette that matches the app's dark surface. `pi`'s own `dark`
         * theme uses `#18181E` for the page and `#D4D4D4` for text; the terminal
         * sits a shade darker so a full-screen TUI reads as its own surface.
         */
        fun dark(): TerminalPalette = TerminalPalette(
            foreground = Color(0xFFD4D4D4),
            background = Color(0xFF101014),
            cursor = Color(0xFF8ABEB7),
            selection = Color(0xFF3A3A4A),
        )

        fun light(): TerminalPalette = TerminalPalette(
            foreground = Color(0xFF1F2328),
            background = Color(0xFFF8F8F8),
            cursor = Color(0xFF5A8080),
            selection = Color(0xFFD0D0E0),
        )
    }
}
