package app.pi.ui.theme

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * sRGB / OKLab / OKLCH / OKHSL, the way pi converts them.
 *
 * This is a **line-by-line port of pi's own colour space module**
 * (`packages/tui/src/oklab.ts:1-233`, which is itself a port of Björn Ottosson's
 * reference implementation, MIT, <https://bottosson.github.io/posts/colorpicker/>).
 * It exists as its own file because `system` theme derivation
 * ([PiSystemTheme]) needs the whole chain — sRGB → OKLab, OKHSL lightness ↔ OKLab
 * lightness, OKHSL → sRGB through the gamut-cusp fit — and because a theme is a
 * colour: the app should not own a second, divergent answer to "what is this
 * hue's maximum saturation".
 *
 * **Why OKHSL and not HSL or OKLCH.** OKHSL's saturation is *relative to the sRGB
 * gamut at that hue and lightness*, so `s = 1` is the gamut edge rather than a
 * number that clips at some hues and is dull at others. pi's theme derivation
 * depends on that: it asks for a family's saturation at a lightness and expects
 * to get the same perceived colourfulness for red and for blue. An OKLCH chroma
 * would need gamut mapping to be usable at all, which is why [okhslToRgb] here is
 * the gamut-aware one and the OKLCH round trip is only used to read a lightness.
 *
 * Numbers, constants and operation *order* are pi's, down to the Halley steps and
 * the polynomial fit for maximum saturation. `PiThemeFiles.kt` carries a private
 * subset of the same maths for parsing `okhsl(...)` theme strings; the golden
 * tests in `PiSystemThemeTest` pin this file against pi's own output, so the two
 * cannot drift apart silently.
 *
 * **Not Android.** Nothing here touches `android.graphics` or Compose: colours
 * are [Rgb] (0-255 channels) and doubles, so the file runs in a bare JVM test.
 */
data class Rgb(val r: Int, val g: Int, val b: Int) {

    /** `0xRRGGBB`, the form [PiSystemTheme]'s token map and `PiAnsiPalette` use. */
    fun toHexRgb(): Int = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** `0xAARRGGBB`, the form Compose's `Color(Int)` takes. */
    fun toArgb(): Int = (0xFF shl 24) or toHexRgb()

    companion object {
        /** Channels of a `0xRRGGBB` literal. */
        fun of(hexRgb: Int): Rgb = Rgb(
            r = (hexRgb shr 16) and 0xFF,
            g = (hexRgb shr 8) and 0xFF,
            b = hexRgb and 0xFF,
        )
    }
}

/** OKLab: perceptual lightness 0-1 and the two opponent axes. */
data class OkLab(val l: Double, val a: Double, val b: Double)

/** OKLCH: OKLab in cylindrical form, hue in degrees (`0` for greys). */
data class OkLch(val l: Double, val c: Double, val h: Double)

/**
 * OKHSL: hue in degrees, saturation and lightness 0-1.
 *
 * [s] is relative to the most the sRGB gamut allows at that hue and lightness, so
 * any value in 0..1 is in gamut.
 */
data class OkHsl(val h: Double, val s: Double, val l: Double)

// ============================================================================
// Constants: Ottosson's matrices, pi's values verbatim
// ============================================================================

private val LINEAR_SRGB_TO_LMS: Array<DoubleArray> = arrayOf(
    doubleArrayOf(0.4122214694707629, 0.5363325372617349, 0.0514459932675022),
    doubleArrayOf(0.2119034958178251, 0.6806995506452344, 0.1073969535369405),
    doubleArrayOf(0.0883024591900564, 0.2817188391361215, 0.6299787016738222),
)
private val LMS_TO_LAB: Array<DoubleArray> = arrayOf(
    doubleArrayOf(0.210454268309314, 0.793617774702305, -0.0040720430116193),
    doubleArrayOf(1.9779985324311684, -2.42859224204858, 0.450593709617411),
    doubleArrayOf(0.0259040424655478, 0.7827717124575296, -0.8086757549230774),
)
private val LAB_TO_LMS: Array<DoubleArray> = arrayOf(
    doubleArrayOf(1.0, 0.3963377773761749, 0.2158037573099136),
    doubleArrayOf(1.0, -0.1055613458156586, -0.0638541728258133),
    doubleArrayOf(1.0, -0.0894841775298119, -1.2914855480194092),
)
private val LMS_TO_LINEAR_SRGB: Array<DoubleArray> = arrayOf(
    doubleArrayOf(4.0767416360759583, -3.3077115392580629, 0.2309699031821043),
    doubleArrayOf(-1.2684379732850315, 2.6097573492876882, -0.341319376002657),
    doubleArrayOf(-0.0041960761386756, -0.7034186179359362, 1.7076146940746117),
)

/**
 * Per sRGB channel (red, green, blue): the `(a, b)` half-plane where that channel
 * clips first, and the polynomial approximating the maximum saturation there
 * (`SATURATION_FIT`, `oklab.ts:81-95`).
 */
private val CLIP_PLANE: Array<DoubleArray> = arrayOf(
    doubleArrayOf(-1.8817031, -0.80936501),
    doubleArrayOf(1.8144408, -1.19445267),
    doubleArrayOf(0.13110758, 1.81333971),
)
private val SATURATION_FIT: Array<DoubleArray> = arrayOf(
    doubleArrayOf(1.19086277, 1.76576728, 0.59662641, 0.75515197, 0.56771245),
    doubleArrayOf(0.73956515, -0.45954404, 0.08285427, 0.12541073, -0.14503204),
    doubleArrayOf(1.35733652, -0.00915799, -1.1513021, -0.50559606, 0.00692167),
)

private const val K1 = 0.206
private const val K2 = 0.03

/** `(1 + K1) / (1 + K2)`, the OKHSL lightness curve's slope (`K3`, `oklab.ts:104`). */
private val K3: Double = (1.0 + K1) / (1.0 + K2)

// ============================================================================
// Lightness: OKLab <-> OKHSL
// ============================================================================

/**
 * OKLab lightness to OKHSL lightness (`oklabToOkhslLightness`, `oklab.ts:106`).
 *
 * The two are not the same curve: OKHSL's lightness is Ottosson's re-parameterised
 * one, and comparing a token's lightness against a surface's *needs* both sides in
 * the same space. pi converts at exactly the two places it must (the surface
 * lightness and the family saturation curve), so this is a public conversion rather
 * than an inline.
 */
fun oklabToOkhslLightness(x: Double): Double {
    val shifted = K3 * x - K1
    return 0.5 * (shifted + sqrt(shifted * shifted + 4.0 * K2 * K3 * x))
}

/** OKHSL lightness to OKLab lightness (`okhslToOklabLightness`, `oklab.ts:109`). */
fun okhslToOklabLightness(x: Double): Double = (x * x + K1 * x) / (K3 * (x + K2))

// ============================================================================
// sRGB transfer function
// ============================================================================

/** Linear to encoded sRGB channel, both 0-1 (`linearToSrgb`, `oklab.ts:112`). */
fun linearToSrgb(value: Double): Double =
    if (value > 0.0031308) 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055 else 12.92 * value

/** Encoded to linear sRGB channel, both 0-1 (`srgbToLinear`, `oklab.ts:115`). */
fun srgbToLinear(value: Double): Double =
    if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)

// ============================================================================
// sRGB <-> OKLab
// ============================================================================

private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray =
    DoubleArray(3) { row ->
        matrix[row][0] * vector[0] + matrix[row][1] * vector[1] + matrix[row][2] * vector[2]
    }

/** JavaScript's `value ** 3` — negative values keep their sign, as here. */
private fun cubed(value: Double): Double = value * value * value

/** Linear sRGB `[r, g, b]` (0-1, may leave the gamut) to OKLab (`oklab.ts:120`). */
fun oklabToLinearSrgb(lab: OkLab): DoubleArray {
    val lms = multiply(LAB_TO_LMS, doubleArrayOf(lab.l, lab.a, lab.b))
    return multiply(
        LMS_TO_LINEAR_SRGB,
        doubleArrayOf(cubed(lms[0]), cubed(lms[1]), cubed(lms[2])),
    )
}

/** Linear sRGB `[r, g, b]` (0-1) to OKLab (`linearSrgbToOklab`, `oklab.ts:125`). */
fun linearSrgbToOklab(rgb: DoubleArray): OkLab {
    val projected = multiply(LINEAR_SRGB_TO_LMS, rgb)
    val lms = doubleArrayOf(
        Math.cbrt(projected[0]),
        Math.cbrt(projected[1]),
        Math.cbrt(projected[2]),
    )
    val lab = multiply(LMS_TO_LAB, lms)
    return OkLab(lab[0], lab[1], lab[2])
}

/** sRGB channels 0-255 to OKLab (`rgbToOklab`, `oklab.ts:130`). */
fun rgbToOklab(rgb: Rgb): OkLab = linearSrgbToOklab(
    doubleArrayOf(
        srgbToLinear(rgb.r / 255.0),
        srgbToLinear(rgb.g / 255.0),
        srgbToLinear(rgb.b / 255.0),
    ),
)

/**
 * Linear sRGB to channels 0-255, **rounded** and clipped per channel
 * (`linearSrgbToRgb`, `oklab.ts:135`).
 *
 * `Math.round` and not `kotlin.math.round`: JavaScript's `Math.round` breaks ties
 * toward positive infinity, and so does `java.lang.Math.round`, while Kotlin's
 * `round` breaks them to even. Every colour pi emits has been through this
 * rounding, so the tie rule is part of the contract, not an implementation detail.
 */
fun linearSrgbToRgb(linear: DoubleArray): Rgb = Rgb(
    r = Math.round(min(1.0, max(0.0, linearToSrgb(linear[0]))) * 255.0).toInt(),
    g = Math.round(min(1.0, max(0.0, linearToSrgb(linear[1]))) * 255.0).toInt(),
    b = Math.round(min(1.0, max(0.0, linearToSrgb(linear[2]))) * 255.0).toInt(),
)

/** sRGB channels 0-255 to OKLCH (`colorToOklch`, `colors.ts:117`). */
fun rgbToOklch(rgb: Rgb): OkLch {
    val lab = rgbToOklab(rgb)
    val hue = (atan2(lab.b, lab.a) * 180.0 / PI + 360.0) % 360.0
    return OkLch(lab.l, hypot(lab.a, lab.b), hue)
}

// ============================================================================
// OKHSL -> sRGB (gamut cusp fit)
// ============================================================================

/** Rate of change of each cube-root LMS component along the chroma direction `(a, b)`. */
private fun lmsSlopes(a: Double, b: Double): DoubleArray = DoubleArray(3) { row ->
    LAB_TO_LMS[row][1] * a + LAB_TO_LMS[row][2] * b
}

/** Largest saturation (`C/L`) inside sRGB for hue `(a, b)`: the fit plus one Halley step. */
private fun maxSaturation(a: Double, b: Double): Double {
    // `SATURATION_FIT.findIndex(([[x, y]], index) => index === 2 || x * a + y * b > 1)`:
    // the third channel is the unconditional answer, so the loop always stops.
    var channel = 2
    for (index in 0..2) {
        if (index == 2 || CLIP_PLANE[index][0] * a + CLIP_PLANE[index][1] * b > 1.0) {
            channel = index
            break
        }
    }
    val fit = SATURATION_FIT[channel]
    val weights = LMS_TO_LINEAR_SRGB[channel]
    val saturation = fit[0] + fit[1] * a + fit[2] * b + fit[3] * a * a + fit[4] * a * b

    val slopes = lmsSlopes(a, b)
    var f = 0.0
    var f1 = 0.0
    var f2 = 0.0
    for (index in 0..2) {
        val base = 1.0 + saturation * slopes[index]
        f += weights[index] * cubed(base)
        f1 += weights[index] * 3.0 * slopes[index] * base * base
        f2 += weights[index] * 6.0 * slopes[index] * slopes[index] * base
    }
    return saturation - (f * f1) / (f1 * f1 - 0.5 * f * f2)
}

/** OKLab lightness and chroma of the most saturated sRGB colour of hue `(a, b)`. */
private data class Cusp(val lightness: Double, val chroma: Double)

private fun cusp(a: Double, b: Double): Cusp {
    val saturation = maxSaturation(a, b)
    val linear = oklabToLinearSrgb(OkLab(1.0, saturation * a, saturation * b))
    val lightness = Math.cbrt(1.0 / max(linear[0], max(linear[1], linear[2])))
    return Cusp(lightness, lightness * saturation)
}

/** Chroma where the constant-lightness line at [lightness] leaves the sRGB gamut. */
private fun maxChroma(a: Double, b: Double, lightness: Double, peak: Cusp): Double {
    if (lightness <= peak.lightness) return (peak.chroma * lightness) / peak.lightness
    // Upper half: triangle edge, then one Halley step against each channel reaching 1.
    val t = (peak.chroma * (lightness - 1.0)) / (peak.lightness - 1.0)
    val slopes = lmsSlopes(a, b)
    val lms = doubleArrayOf(
        lightness + t * slopes[0],
        lightness + t * slopes[1],
        lightness + t * slopes[2],
    )
    val cubes = doubleArrayOf(cubed(lms[0]), cubed(lms[1]), cubed(lms[2]))
    val first = doubleArrayOf(
        3.0 * slopes[0] * lms[0] * lms[0],
        3.0 * slopes[1] * lms[1] * lms[1],
        3.0 * slopes[2] * lms[2] * lms[2],
    )
    val second = doubleArrayOf(
        6.0 * slopes[0] * slopes[0] * lms[0],
        6.0 * slopes[1] * slopes[1] * lms[1],
        6.0 * slopes[2] * slopes[2] * lms[2],
    )
    // `Math.min(...steps)` with `Number.MAX_VALUE` for "this channel never fits".
    var step = Double.MAX_VALUE
    for (row in 0..2) {
        val weights = LMS_TO_LINEAR_SRGB[row]
        val f = weights[0] * cubes[0] + weights[1] * cubes[1] + weights[2] * cubes[2] - 1.0
        val f1 = weights[0] * first[0] + weights[1] * first[1] + weights[2] * first[2]
        val f2 = weights[0] * second[0] + weights[1] * second[1] + weights[2] * second[2]
        val u = f1 / (f1 * f1 - 0.5 * f * f2)
        if (u >= 0.0) {
            val candidate = -f * u
            if (candidate < step) step = candidate
        }
    }
    return t + step
}

/** OKHSL's chroma reference points at lightness [l] and hue `(a, b)`: `[c0, cMid, cMax]`. */
private data class ChromaStops(val c0: Double, val cMid: Double, val cMax: Double)

private fun chromaStops(l: Double, a: Double, b: Double): ChromaStops {
    val peak = cusp(a, b)
    val cMax = maxChroma(a, b, l, peak)
    val k = cMax / min(
        l * (peak.chroma / peak.lightness),
        (1.0 - l) * (peak.chroma / (1.0 - peak.lightness)),
    )
    val midS = 0.11516993 +
        1.0 /
        (
            7.4477897 +
                4.1590124 * b +
                a *
                (
                    -2.19557347 +
                        1.75198401 * b +
                        a *
                        (
                            -2.13704948 -
                                10.02301043 * b +
                                a * (-4.24894561 + 5.38770819 * b + 4.69891013 * a)
                            )
                    )
            )
    val midT = 0.11239642 +
        1.0 /
        (
            1.6132032 -
                0.68124379 * b +
                a *
                (
                    0.40370612 +
                        0.90148123 * b +
                        a * (-0.27087943 + 0.6122399 * b + a * (0.00299215 - 0.45399568 * b - 0.14661872 * a))
                    )
            )
    val cMid = 0.9 * k * sqrt(
        sqrt(1.0 / (Math.pow(1.0 / (l * midS), 4.0) + Math.pow(1.0 / ((1.0 - l) * midT), 4.0))),
    )
    val c0 = sqrt(
        1.0 / (Math.pow(1.0 / (l * 0.4), 2.0) + Math.pow(1.0 / ((1.0 - l) * 0.8), 2.0)),
    )
    return ChromaStops(c0, cMid, cMax)
}

/**
 * OKHSL to sRGB channels 0-255, clipped per channel (`okhslToRgb`, `oklab.ts:194`).
 *
 * Chroma rises from 0 through `cMid` at `s = 0.8` to `cMax` at `s = 1`: that is the
 * whole point of OKHSL — `s = 1` is the gamut edge at *that* hue and lightness, so
 * every saturation in 0..1 is reachable and none clips.
 *
 * [saturation] and [lightness] are clamped instead of rejected. pi's `okhslColor`
 * wrapper throws outside 0..1, but this function is only ever called from the
 * derivation with values that are in range by construction (`pi` multiplies
 * saturations together), and a theme must not fail to build over a rounding error
 * in the last bit.
 */
fun okhslToRgb(hue: Double, saturation: Double, lightness: Double): Rgb {
    val s = saturation.coerceIn(0.0, 1.0)
    val l = lightness.coerceIn(0.0, 1.0)
    val oklabLightness = okhslToOklabLightness(l)
    var lab = OkLab(oklabLightness, 0.0, 0.0)
    if (oklabLightness > 0.0 && oklabLightness < 1.0 && s > 0.0) {
        val angle = 2.0 * PI * (((hue % 360.0) + 360.0) % 360.0) / 360.0
        val a = cos(angle)
        val b = sin(angle)
        val stops = chromaStops(oklabLightness, a, b)
        val chroma = if (s < 0.8) {
            val t = 1.25 * s
            val k1 = 0.8 * stops.c0
            (t * k1) / (1.0 - (1.0 - k1 / stops.cMid) * t)
        } else {
            val t = 5.0 * (s - 0.8)
            val k1 = (0.2 * stops.cMid * stops.cMid * 1.25 * 1.25) / stops.c0
            stops.cMid + (t * k1) / (1.0 - (1.0 - k1 / (stops.cMax - stops.cMid)) * t)
        }
        lab = OkLab(oklabLightness, chroma * a, chroma * b)
    }
    return linearSrgbToRgb(oklabToLinearSrgb(lab))
}

/**
 * sRGB channels 0-255 to OKHSL (`rgbToOkhsl`, `oklab.ts:222`).
 *
 * Hue is in degrees and `0` for greys, saturation and lightness 0-1. This is the
 * inverse of [okhslToRgb] through the same cusp/chroma stops, so
 * `okhslToRgb(rgbToOkhsl(c))` returns [c] (up to the 8-bit rounding).
 */
fun rgbToOkhsl(rgb: Rgb): OkHsl {
    val lab = rgbToOklab(rgb)
    val chroma = hypot(lab.a, lab.b)
    val lightness = oklabToOkhslLightness(lab.l)
    if (chroma < 1e-9 || lightness <= 0.0 || lightness >= 1.0) return OkHsl(0.0, 0.0, lightness)

    val hue = (atan2(lab.b, lab.a) * 180.0 / PI + 360.0) % 360.0
    val stops = chromaStops(lab.l, lab.a / chroma, lab.b / chroma)
    val saturation = if (chroma < stops.cMid) {
        val k1 = 0.8 * stops.c0
        0.8 * (chroma / (k1 + (1.0 - k1 / stops.cMid) * chroma))
    } else {
        val k1 = (0.2 * stops.cMid * stops.cMid * 1.25 * 1.25) / stops.c0
        val offset = chroma - stops.cMid
        0.8 + 0.2 * (offset / (k1 + (1.0 - k1 / (stops.cMax - stops.cMid)) * offset))
    }
    return OkHsl(hue, saturation.coerceIn(0.0, 1.0), lightness)
}

// ============================================================================
// WCAG 2 contrast
// ============================================================================

/** WCAG 2 relative luminance of an sRGB colour (`relativeLuminance`, `system-theme.ts:243`). */
fun relativeLuminance(rgb: Rgb): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linear(rgb.r) + 0.7152 * linear(rgb.g) + 0.0722 * linear(rgb.b)
}

/**
 * WCAG 2 contrast ratio, 1-21 (`wcagContrast`, `system-theme.ts:256`).
 *
 * pi's derivation uses this for exactly two decisions — whether a panel is light
 * (or dark) enough to carry extreme body text, and whether a text token actually
 * reaches the 4.5:1 floor — so it is part of the algorithm, not a UI nicety, and
 * lives next to the colours rather than in `PiContrast` (which re-lifts pi's own
 * tokens and never runs during derivation).
 */
fun wcagContrast(first: Rgb, second: Rgb): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}
