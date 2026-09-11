package app.pi.ui.terminal

import androidx.compose.ui.graphics.Color

/**
 * The two colours the app still owns.
 *
 * `org.connectbot:termlib` draws the grid, the cursor and the selection itself,
 * and libvterm ships the xterm 16-colour table, so the only thing left for the
 * app's theme to decide is the surface: what "no colour requested" means for
 * foreground and background. Programs asking for red still get red — a terminal
 * is not a themed surface, and remapping `SGR 31` to an accent colour would make
 * `git diff` stop meaning what it means.
 *
 * The two values are the ones the previous palette used, so the terminal looks
 * the same as before the engine swap.
 */
class TerminalPalette(
    val foreground: Color,
    val background: Color,
) {

    /** An unarmed key-bar chip, and the armed sticky modifier. */
    val chip: Color get() = foreground.copy(alpha = 0.08f)
    val chipArmed: Color get() = foreground.copy(alpha = 0.28f)

    companion object {

        /**
         * The palette that matches the app's dark surface. `pi`'s own `dark`
         * theme uses `#18181E` for the page and `#D4D4D4` for text; the terminal
         * sits a shade darker so a full-screen TUI reads as its own surface.
         */
        fun dark(): TerminalPalette = TerminalPalette(
            foreground = Color(0xFFD4D4D4),
            background = Color(0xFF101014),
        )

        fun light(): TerminalPalette = TerminalPalette(
            foreground = Color(0xFF1F2328),
            background = Color(0xFFF8F8F8),
        )
    }
}
