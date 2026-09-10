package app.pi.terminal

/**
 * The terminal colour tables, plus the small amount of bookkeeping needed to
 * tell "unset" apart from "explicitly the default colour".
 *
 * Where these numbers come from: the xterm 256-colour table is not a guess, it
 * is a specification — the first 16 entries are the classic VGA-ish ANSI
 * palette, entries 16..231 are a 6x6x6 RGB cube whose channel values are
 * `0,95,135,175,215,255`, and entries 232..255 are 24 greys from `8` to `238`
 * in steps of 10. `app.pi.rpc.Ansi` (the captured-output SGR parser) uses the
 * same table; it is duplicated here rather than shared because that module is
 * the *wire protocol* core and this one is the *screen* — `Ansi.Span` describes
 * a run of text in a log line, `Cell` describes one character on a grid with a
 * position, and coupling the two would mean changing the protocol module's
 * public surface for a rendering detail.
 *
 * The palette is not the app's theme: a terminal is expected to paint whatever
 * the program asked for. The theme only supplies the *default* foreground and
 * background (and thus the surface the view is drawn on), which is what a real
 * terminal does through its profile settings.
 */
internal object AnsiColors {

    /**
     * "No opinion": the program has not chosen a colour, so the terminal's own
     * default applies. `-1` rather than `0` because `0x000000` is a legitimate
     * colour (pure black) and a sentinel that collides with it would render
     * explicit black as the theme's default.
     */
    const val DEFAULT: Int = -1

    /** Packed `0xRRGGBB`. */
    fun pack(r: Int, g: Int, b: Int): Int =
        ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun red(rgb: Int): Int = (rgb shr 16) and 0xFF

    fun green(rgb: Int): Int = (rgb shr 8) and 0xFF

    fun blue(rgb: Int): Int = rgb and 0xFF

    /**
     * `SGR 30..37`, `40..47`, `90..97`, `100..107` — the same 16 slots that open
     * the 256-colour table, encoded as *negative* values (`slot n` is `-(n+1)`).
     *
     * They are kept as indices rather than flattened to RGB because a terminal
     * brightens slots 0..7 when bold is set, and the view's palette may override
     * a slot. Flattening here would lose both. [DEFAULT] stays the only -1.
     */
    val SYSTEM: IntArray = IntArray(16) { -(it + 1) }

    /** True when [packed] names one of the 16 system slots. */
    fun isSystem(packed: Int): Boolean = packed < 0 && packed != DEFAULT

    /** The system slot for [packed], or -1. */
    fun systemIndex(packed: Int): Int = if (isSystem(packed)) -(packed + 1) else -1

    /**
     * A palette index for a 256-colour SGR, or the 16-colour fallback for
     * anything out of range. Out-of-range indices are clamped rather than
     * dropped: a garbled colour is a much smaller failure than a blank screen.
     */
    fun indexed(index: Int): Int {
        val i = index.coerceIn(0, 255)
        if (i < 16) return BASIC_16[i]
        if (i < 232) {
            val n = i - 16
            // The cube's channel values are 0, 95, 135, 175, 215, 255 — not a
            // linear ramp. `55 + v*40` reproduces them exactly (v=0 is special).
            fun channel(v: Int) = if (v == 0) 0 else 55 + v * 40
            return pack(channel(n / 36), channel((n % 36) / 6), channel(n % 6))
        }
        val level = 8 + (i - 232) * 10
        return pack(level, level, level)
    }

    /**
     * The 16 system colours. These are the values `xterm` itself uses for
     * `xterm-256color`, which is the `TERM` every guest process is given, so a
     * program that asks for `SGR 31` (red) gets the red it expects.
     */
    val BASIC_16: IntArray = intArrayOf(
        0x000000, // 0 black
        0xCD0000, // 1 red
        0x00CD00, // 2 green
        0xCDCD00, // 3 yellow
        0x0000EE, // 4 blue
        0xCD00CD, // 5 magenta
        0x00CDCD, // 6 cyan
        0xE5E5E5, // 7 white
        0x7F7F7F, // 8 bright black (grey)
        0xFF0000, // 9 bright red
        0x00FF00, // 10 bright green
        0xFFFF00, // 11 bright yellow
        0x5C5CFF, // 12 bright blue
        0xFF00FF, // 13 bright magenta
        0x00FFFF, // 14 bright cyan
        0xFFFFFF, // 15 bright white
    )
}
