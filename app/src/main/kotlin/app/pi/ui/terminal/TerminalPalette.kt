package app.pi.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.pi.ui.theme.PiPalette

/**
 * The surface a terminal is drawn on: what "no colour requested" means for the
 * foreground and the background.
 *
 * ## What follows the theme, and what deliberately does not
 *
 * **The surface follows the pi theme.** [of] derives both values from the
 * resolved [PiPalette], so a theme the user tuned on their desktop changes the
 * terminal too, exactly as it changes every other surface in the app:
 *
 *  - `foreground = palette.text` — pi's own body foreground, which is also what
 *    the guest program's `SGR 39`/`SGR 49` defaults mean;
 *  - `background` = `palette.pageBg`, darkened by a third when that page is a
 *    dark surface and left alone when it is a light one. pi's built-in `dark`
 *    page (`#18181E`) therefore lands on `#101014` and its `light` page
 *    (`#F8F8F8`) stays `#F8F8F8` — the two values the app shipped before this
 *    became a derivation — while a custom theme gets its own page, dimmed by the
 *    same rule. "Slightly deeper than the page" is the whole intent: a
 *    full-screen TUI should read as its own surface (see the pane's KDoc), and
 *    the page is the only ground pi gives us to derive that from. pi itself has
 *    no terminal-surface token: its TUI *is* the terminal.
 *
 * **The 16-colour ANSI table does not follow the theme, and must not.** Programs
 * asking for red still get red: `org.connectbot:termlib` draws the grid, the
 * cursor and the selection, libvterm ships the xterm 16-colour table, and the
 * guest's own `SGR 31`/`SGR 32`/… indices are a wire protocol, not a
 * presentation choice. Remapping `SGR 31` onto an accent colour would make
 * `git diff` stop meaning what it means, which is the one thing a terminal
 * cannot afford. So the app decides only the two colours nobody asked for —
 * these two — and everything else is the guest's own.
 *
 * This is also why the derived surface is *not* exposed as a pi token: it is an
 * app-side derivation from `pageBg`, and naming it as if pi declared it would be
 * the drift `PiPalette`'s KDoc warns against.
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
         * How much of the page's own luminance a dark page gives up for the
         * terminal's surface. A third is the value that reproduces the two
         * surfaces the app shipped by hand: `#18181E → #101014` (pi `dark`) and
         * `#F8F8F8 → #F8F8F8` (pi `light`, no darkening at all).
         */
        private const val DARK_PAGE_DIM = 1f / 3f

        /** The terminal surface for [palette] — see this file's KDoc. */
        fun of(palette: PiPalette): TerminalPalette = TerminalPalette(
            foreground = palette.text,
            background = if (palette.pageBg.isDarkSurface()) {
                lerp(palette.pageBg, Color.Black, DARK_PAGE_DIM)
            } else {
                palette.pageBg
            },
        )
    }
}

/**
 * Whether [this] reads as a dark surface, by the same relative-luminance test
 * pi's HTML exporter uses to pick its light/dark branch
 * (`core/export-html/index.ts:88-105`; see `PiThemeFiles.kt`'s copy of it).
 */
private fun Color.isDarkSurface(): Boolean {
    fun linear(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    val luminance = 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
    return luminance <= 0.5f
}
