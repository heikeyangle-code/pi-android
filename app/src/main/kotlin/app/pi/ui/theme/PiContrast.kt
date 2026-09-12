package app.pi.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * WCAG contrast maths, used only to derive the corrected text tokens in
 * [PiPalette]'s body.
 *
 * **Why a correction exists at all.** pi's theme files are the single source of
 * truth for colour (`docs/pi-android-ui-spec.md` §2.1, `:70-72`), and this app
 * keeps every one of their 56 tokens verbatim — `PiPalette`'s values are pi's
 * `dark.json` / `light.json` byte for byte. But a terminal prints one glyph per
 * cell on a background it does not own, while a phone paints 13–15 sp body text
 * on a surface the palette *does* choose, and §9 (`:831`) states the floor
 * outright: “正文 ≥4.5:1，元信息 ≥3:1”. §2.1 (`:98`) adds the one case it expects
 * the app to fix by hand: “`muted` 在浅色主题下需微调（pi 的 light 主题这项偏弱，App
 * 要给一个修正档）”.
 *
 * The resolution is the spec's own wording — *保真 + 一个修正档*: the token stays
 * pi's, and a derived variant is used **only** where that token is painted as
 * text on a surface, lifting it just far enough to clear the floor. Nothing here
 * runs in a loop over a palette; each derived value is computed once per
 * `PiPalette` instance (see the body of that class) and is excluded from
 * `equals`/`copy`, so an imported theme is not rewritten and no call site pays a
 * per-frame cost.
 */
internal object PiContrast {

    /** WCAG 2.x relative luminance of [color]. */
    fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** WCAG 2.x contrast ratio between two opaque colours; 1.0 … 21.0. */
    fun ratio(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        val (hi, lo) = if (a >= b) a to b else b to a
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * [foreground] moved toward the end of the luminance range that the
     * [background] is *not* on, until [minimum] is met — 1 % of the way at a
     * time, at most 100 steps, so the result is deterministic and the untouched
     * token is returned whenever it already passes.
     *
     * Blending toward white on a dark surface (and toward black on a light one)
     * is what keeps the hue recognisable: pi's greys stay grey, and a semantic
     * colour that needs lifting stays the same colour family. A token that cannot
     * reach [minimum] at all (a mid-grey on a mid-grey) returns the strongest
     * value the loop reached rather than an arbitrary one.
     */
    fun ensure(foreground: Color, background: Color, minimum: Double): Color {
        if (ratio(foreground, background) >= minimum) return foreground
        val target = if (luminance(background) < 0.5) Color.White else Color.Black
        var best = foreground
        for (step in 1..100) {
            val candidate = lerpColor(foreground, target, step / 100f)
            best = candidate
            if (ratio(candidate, background) >= minimum) return candidate
        }
        return best
    }

    /**
     * The lift that satisfies [minimum] against **every** surface in [backgrounds]
     * — the tool card has three state backgrounds, and one text token has to be
     * legible on all of them.
     */
    fun ensureAgainstAll(foreground: Color, backgrounds: List<Color>, minimum: Double): Color {
        var result = foreground
        for (background in backgrounds) {
            result = ensure(result, background, minimum)
        }
        return result
    }

    private fun lerpColor(from: Color, to: Color, fraction: Float): Color = Color(
        red = from.red + (to.red - from.red) * fraction,
        green = from.green + (to.green - from.green) * fraction,
        blue = from.blue + (to.blue - from.blue) * fraction,
        alpha = 1f,
    )
}
