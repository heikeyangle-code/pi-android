package app.pi.ui.theme

/**
 * The `system` theme's input, and the one place that turns it into a [PiPalette].
 *
 * pi's `system` theme is not a document on disk: it is *generated* from what the
 * terminal reports about itself (`SYSTEM_THEME_NAME`, `system-theme.ts:32`;
 * `generateSystemThemeColors`, `:400`). This app's surfaces are Compose and it has no
 * terminal to ask, so [PiThemeLoader] still has to hand the derivation a concrete
 * palette. [PiSystemPaletteSource] is that palette and [resolveSystemTheme] is the
 * loader's only way to read it:
 *
 *  - **Today** it holds [defaultAnsiPalette] — pi's own default ANSI 16 on pi's own
 *    default background, the very pair pi falls back to when it has to resolve an
 *    index itself (`BASIC_COLORS`, `packages/tui/src/colors.ts:155-171`). Nothing in
 *    it is invented for Android.
 *  - **Later** the 「配色方案」 page assigns the palette the user picked and asks the
 *    view model for a theme refresh (`PiSessionViewModel.refreshTheme`); it does not
 *    touch the loader, and the loader does not grow a second way to choose colours.
 *
 * **The polarity comes from the palette, not from the device.** pi decides dark vs
 * light from the terminal's own colours ([terminalAppearance], `system-theme.ts:271-290`),
 * and the derivation's lightness curves depend on that answer. With the default palette
 * the background is `#1E1E1E`, so `system` is a **dark** theme even on a light phone:
 * the theme belongs to the terminal, not to the system setting. [PiThemeLoader] lets
 * `dark` follow this answer instead of `systemDark` for exactly that reason — a light
 * system bar over this palette is the white-strip defect `MainActivity.kt` documents.
 *
 * No colour maths lives here. The 56 tokens and the three canvas surfaces come from
 * [deriveSystemPalette], and the polarity from [deriveSystemColors]'s `appearance`, so
 * a later change to the port cannot be silently outrun by a second copy of a rule.
 */

/**
 * The terminal palette the `system` theme is derived from.
 *
 * One mutable value, so a future 「配色方案」 page has exactly one thing to write and the
 * loader exactly one thing to read. [palette] is `@Volatile` because that hand-off
 * crosses threads: [PiThemeLoader.load] runs on `Dispatchers.IO` and a settings screen
 * writes on the main thread, so a plain field could hand a reader a half-written
 * reference. A `PiAnsiPalette` is immutable once built, so publishing the *reference*
 * is enough.
 *
 * A palette whose `ansi` is not 16 entries long is allowed: that is pi's "background
 * only" tier (`system-theme.ts:412` treats any other length as "no palette"), and the
 * derivation still paints all 56 tokens.
 */
object PiSystemPaletteSource {
    /** The colours the next `system` resolution derives from. */
    @Volatile
    var palette: PiAnsiPalette = defaultAnsiPalette()
}

/** A resolved `system` theme: the app palette, plus the polarity it was derived for. */
internal data class PiDerivedSystemTheme(
    val palette: PiPalette,
    val dark: Boolean,
)

/**
 * The last answer, keyed by the input it was derived for.
 *
 * [PiThemeLoader.load] runs again on every system light/dark change and on every
 * edit of the `theme` setting, while a derivation solves 56 tokens by bisection; the
 * memo turns a repeated load into a value comparison. It is keyed by the **input
 * palette's contents** — `PiAnsiPalette.equals` compares `ansi` element by element —
 * so injecting a new palette can never be served a stale palette.
 */
private class PiSystemMemo(val input: PiAnsiPalette, val theme: PiDerivedSystemTheme)

private object PiSystemMemoSlot {
    @Volatile
    var last: PiSystemMemo? = null
}

/**
 * Derive the `system` theme from [input], or hand back the last answer when [input]
 * is the palette it was derived for.
 *
 * [input] defaults to [PiSystemPaletteSource.palette] so that the loader's call site
 * reads as "the current system theme", not as "whatever this function was given".
 *
 * The two calls below look redundant but are not: [deriveSystemColors] is the only
 * public surface that exposes the **appearance** the derivation picked (the value the
 * lightness curves were solved against), while [deriveSystemPalette] is the only one
 * that maps the 56 tokens onto [PiPalette] together with the exporter's three canvas
 * surfaces. Reading `dark` from the former and the colours from the latter keeps the
 * two halves provably of one derivation; the memo makes them cost one derivation per
 * input rather than one per load.
 */
internal fun resolveSystemTheme(
    input: PiAnsiPalette = PiSystemPaletteSource.palette,
): PiDerivedSystemTheme {
    PiSystemMemoSlot.last?.let { memo ->
        if (memo.input == input) return memo.theme
    }
    val colors = deriveSystemColors(input)
    val theme = PiDerivedSystemTheme(
        palette = deriveSystemPalette(input),
        dark = colors.appearance == PiSystemAppearance.DARK,
    )
    PiSystemMemoSlot.last = PiSystemMemo(input, theme)
    return theme
}
