package app.pi.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.system.exitProcess

// Golden-sample tests for the `system` theme port (`PiSystemTheme.kt`) and the OKHSL
// conversions under it (`PiOkhsl.kt`).
//
// **Where the numbers come from.** Every constant in the GOLDEN block below is the
// *output of pi's own implementation*, not of this port: `generateSystemThemeColors` from
// `packages/coding-agent/src/modes/interactive/theme/system-theme.ts` was run (under
// Node, on the unmodified TypeScript) for each input named beside it, and its 56 tokens
// were serialised in `PiSystemToken.entries` order as one hex triple per token. The
// probes in `okhslConversions()` come from `packages/tui/src/oklab.ts` the same way. That
// is what makes this a port test rather than a snapshot of whatever the Kotlin happens
// to print: an expected value here can only have come from pi.
//
// Upstream's `system-theme.test.ts` is **property**-based, not value-based (it asserts
// WCAG floors, lightness ordering, hue agreement and the fallback tiers), so the
// properties are ported too — `propertyChecks()` below is the same list of claims pi
// makes about its own derivation. The golden values add what pi's test does not: every
// token of every tier, pinned to pi's output.
//
// **Tolerance.** pi rounds each channel with `Math.round` (ties toward +infinity) and
// this port uses `java.lang.Math.round` for exactly that reason, so the two are expected
// to agree bit for bit; the goldens are compared with a ±1/255 per-channel allowance
// because a different `Math.pow`/`cbrt` ulp can flip the last channel, and the harness
// prints the worst delta it saw. The `text` tokens of a case that reported a foreground
// are pi's `""` ("the terminal's own colour"), which on a phone is that foreground —
// the same resolution upstream's own test helper `resolved()` does.
//
// **Bare JVM, no test framework.** Like the other harnesses under `app/src/test/kotlin`
// this is a `fun main()` with a hand-rolled counter: the app module has no JUnit
// dependency, and `tools/run-app-pure-checks.sh` is what compiles and runs these files.
// Registering this one needs two Compose classpath entries beyond the script's usual
// libraries, because the file it tests hands its result to `PiPalette`:
// `androidx.compose.ui:ui-graphics-android:1.8.3` and
// `androidx.compose.runtime:runtime-android:1.8.3` (Google Maven; take `classes.jar` out
// of the AAR). Those two alone, plus kotlin-stdlib, compile and run this closure:
//
//   java -cp <compiler jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
//     -jvm-target 17 -classpath <ui-graphics classes.jar>:<runtime classes.jar>:<stdlib> \
//     -d /tmp/out app/src/main/kotlin/app/pi/ui/theme/{PiOkhsl,PiSystemTheme,PiPalette,PiContrast}.kt \
//     app/src/test/kotlin/app/pi/ui/theme/PiSystemThemeTest.kt
//   java -cp /tmp/out:<same jars> app.pi.ui.theme.PiSystemThemeTestKt
//
// Nothing here touches Android. The assertions are written against `0xRRGGBB` ints; the
// only Compose use is `paletteHandOff()`, which reads `Color`'s channels to check that the
// 56 tokens really landed on the 56 fields of `PiPalette`.

private var passed = 0
private var failed = 0
private var worstDelta = 0
private val worstToken = StringBuilder()

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun checkTrue(name: String, condition: Boolean) {
    if (condition) {
        passed++
    } else {
        failed++
        println("FAIL $name")
    }
}

private fun checkNear(name: String, actual: Double, expected: Double, tolerance: Double) {
    if (abs(actual - expected) <= tolerance) {
        passed++
    } else {
        failed++
        println("FAIL $name\n  expected: $expected (tolerance $tolerance)\n  actual:   $actual")
    }
}

private fun hex(value: Int): String = "#%06X".format(value and 0xFFFFFF)

private fun channelDelta(first: Int, second: Int): Int {
    var worst = 0
    for (shift in intArrayOf(16, 8, 0)) {
        val delta = abs(((first shr shift) and 0xFF) - ((second shr shift) and 0xFF))
        if (delta > worst) worst = delta
    }
    return worst
}

// ============================================================================
// Inputs: the terminals the golden values were generated for
// ============================================================================

private fun ansiOf(hexes: String): IntArray =
    hexes.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16) }.toIntArray()

/** Dracula's 16 ANSI colours, as the terminal reports them. */
private const val DRACULA_ANSI =
    "21222C FF5555 50FA7B F1FA8C BD93F9 FF79C6 8BE9FD F8F8F2 6272A4 FF6E6E 69FF94 FFFFA5 D6ACFF FF92DF A4FFFF FFFFFF"

/** Solarized Light's 16 ANSI colours. */
private const val SOLARIZED_ANSI =
    "073642 DC322F 859900 B58900 268BD2 D33682 2AA198 EEE8D5 002B36 CB4B16 586E75 657B83 839496 6C71C4 93A1A1 FDF6E3"

private val DRACULA = PiAnsiPalette(0x282A36, ansiOf(DRACULA_ANSI), 0xF8F8F2)
private val DRACULA_NO_FOREGROUND = PiAnsiPalette(0x282A36, ansiOf(DRACULA_ANSI))
private val SOLARIZED = PiAnsiPalette(0xFDF6E3, IntArray(0), 0x657B83)
private val SOLARIZED_WITH_PALETTE = PiAnsiPalette(0xFDF6E3, ansiOf(SOLARIZED_ANSI), 0x657B83)
private val DARK_BACKGROUND_ONLY = PiAnsiPalette(0x1E1E1E, IntArray(0))
private val WHITE_BACKGROUND_ONLY = PiAnsiPalette(0xFFFFFF, IntArray(0))
private val MID_GRAY = PiAnsiPalette(0x808080, IntArray(0), 0xFFFFFF)

/**
 * The panels of `PANELS` in pi's test (`system-theme.test.ts:40`), in `PiSystemToken`
 * terms. Only used to state pi's own claims about them.
 */
private val PANEL_TOKENS = listOf(
    PiSystemToken.USER_MESSAGE_BG,
    PiSystemToken.TOOL_PENDING_BG,
    PiSystemToken.TOOL_SUCCESS_BG,
    PiSystemToken.TOOL_ERROR_BG,
    PiSystemToken.SELECTED_BG,
)

// ============================================================================
// Assertions
// ============================================================================

/** Compare one derived case token by token against a golden line of 56 hex triples. */
private fun golden(case: String, expected: String, actual: PiSystemThemeColors) {
    val values = expected.trim().split(" ").filter { it.isNotEmpty() }
    check("$case: golden value count", values.size, PiSystemToken.entries.size)
    if (values.size != PiSystemToken.entries.size) return
    for ((index, token) in PiSystemToken.entries.withIndex()) {
        val want = values[index].toInt(16)
        val got = actual[token]
        val delta = channelDelta(want, got)
        if (delta > worstDelta) {
            worstDelta = delta
            worstToken.setLength(0)
            worstToken.append("$case/${token.wire}: ${hex(want)} vs ${hex(got)}")
        }
        if (delta > 1) {
            failed++
            println("FAIL $case/${token.wire}: expected ${hex(want)}, got ${hex(got)} (Δ$delta)")
        } else {
            passed++
        }
    }
}

private fun expectsOkhslToRgb(h: Double, s: Double, l: Double, r: Int, g: Int, b: Int) {
    val actual = okhslToRgb(h, s, l)
    check("okhslToRgb($h, $s, $l)", "${actual.r},${actual.g},${actual.b}", "$r,$g,$b")
}

private fun expectsRgbToOkhsl(hexRgb: Int, h: Double, s: Double, l: Double) {
    val actual = rgbToOkhsl(Rgb.of(hexRgb))
    checkNear("rgbToOkhsl(${hex(hexRgb)}) h", actual.h, h, 1e-9)
    checkNear("rgbToOkhsl(${hex(hexRgb)}) s", actual.s, s, 1e-9)
    checkNear("rgbToOkhsl(${hex(hexRgb)}) l", actual.l, l, 1e-9)
}

private fun expectsOkhslLightness(oklabL: Double, okhslL: Double) {
    checkNear("oklabToOkhslLightness($oklabL)", oklabToOkhslLightness(oklabL), okhslL, 1e-12)
}

private fun expectsOklab(hexRgb: Int, l: Double, a: Double, b: Double) {
    val actual = rgbToOklab(Rgb.of(hexRgb))
    checkNear("rgbToOklab(${hex(hexRgb)}) L", actual.l, l, 1e-12)
    checkNear("rgbToOklab(${hex(hexRgb)}) a", actual.a, a, 1e-12)
    checkNear("rgbToOklab(${hex(hexRgb)}) b", actual.b, b, 1e-12)
}

// ============================================================================
// Golden values, generated by pi's implementation
// ============================================================================

private fun goldenTokens() {
    // pi's `TOKEN_FAMILIES` order, which the golden lines are serialised in. Reordering
    // the enum would silently pair every value with the wrong token, so the order (and
    // the count) is pinned explicitly as well.
    check(
        "56 tokens in pi's order",
        PiSystemToken.entries.map { it.wire },
        listOf(
            "selectedBg", "searchMatchBg", "userMessageBg", "customMessageBg", "toolPendingBg",
            "toolSuccessBg", "toolErrorBg", "text", "userMessageText", "customMessageText",
            "toolTitle", "syntaxOperator", "syntaxPunctuation", "muted", "dim", "thinkingText",
            "toolOutput", "mdLinkUrl", "mdQuote", "mdQuoteBorder", "mdHr", "mdCodeBlockBorder",
            "toolDiffContext", "syntaxComment", "scrollbarTrack", "scrollbarThumb",
            "searchMatchText", "borderMuted", "accent", "borderAccent", "customMessageLabel",
            "mdCode", "mdListBullet", "syntaxType", "border", "mdLink", "syntaxKeyword",
            "syntaxVariable", "success", "mdCodeBlock", "toolDiffAdded", "bashMode",
            "syntaxNumber", "error", "toolDiffRemoved", "warning", "mdHeading",
            "syntaxFunction", "syntaxString", "thinkingOff", "thinkingMinimal", "thinkingLow",
            "thinkingMedium", "thinkingHigh", "thinkingXhigh", "thinkingMax",
        ),
    )
    // `SOLVE_ORDER` (`system-theme.ts:396-410`): every surface before the tokens drawn on
    // it. Derived from the rules, never hand-ordered, but pinned here so a lost rule edge
    // shows up as a failure instead of as slightly wrong text colours.
    check(
        "solve order, surfaces before the tokens on them",
        PI_SYSTEM_SOLVE_ORDER.map { it.wire },
        listOf(
            "userMessageBg", "toolPendingBg", "toolSuccessBg", "toolErrorBg", "selectedBg",
            "searchMatchBg", "customMessageBg", "text", "userMessageText", "toolTitle", "accent",
            "success", "error", "warning", "muted", "dim", "thinkingText", "customMessageText",
            "customMessageLabel", "toolOutput", "mdHeading", "mdLink", "mdLinkUrl", "mdCode",
            "mdQuote", "mdCodeBlockBorder", "mdListBullet", "mdCodeBlock", "toolDiffAdded",
            "toolDiffRemoved", "toolDiffContext", "syntaxComment", "syntaxKeyword",
            "syntaxFunction", "syntaxVariable", "syntaxString", "syntaxNumber", "syntaxType",
            "syntaxOperator", "syntaxPunctuation", "searchMatchText", "bashMode", "border",
            "borderAccent", "borderMuted", "mdQuoteBorder", "mdHr", "scrollbarTrack",
            "scrollbarThumb", "thinkingOff", "thinkingMinimal", "thinkingLow", "thinkingMedium",
            "thinkingHigh", "thinkingXhigh", "thinkingMax",
        ),
    )
}

private fun goldenThemes() {
    // Tier 1 + a foreground: pi renders body text as the terminal's own `#f8f8f2`.
    val dracula = deriveSystemColors(DRACULA)
    check("dracula + fg: appearance", dracula.appearance, PiSystemAppearance.DARK)
    check("dracula + fg: no relaxation", dracula.relaxation, 0.0)
    golden("dracula + fg", GOLDEN_DRACULA_FG, dracula)

    // Tier 1 without a foreground: the same hues, computed neutral body text.
    val draculaNoForeground = deriveSystemColors(DRACULA_NO_FOREGROUND)
    check("dracula: appearance", draculaNoForeground.appearance, PiSystemAppearance.DARK)
    golden("dracula", GOLDEN_DRACULA, draculaNoForeground)

    // Tier 2: a light background, no palette. Families' own hues.
    val solarized = deriveSystemColors(SOLARIZED)
    check("solarized + fg: appearance", solarized.appearance, PiSystemAppearance.LIGHT)
    golden("solarized + fg", GOLDEN_SOLARIZED_FG, solarized)

    // Tier 1 on a light background.
    val solarizedPalette = deriveSystemColors(SOLARIZED_WITH_PALETTE)
    check("solarized + fg + palette: appearance", solarizedPalette.appearance, PiSystemAppearance.LIGHT)
    golden("solarized + fg + palette", GOLDEN_SOLARIZED_FG_PALETTE, solarizedPalette)

    // Tier 2, dark.
    val darkOnly = deriveSystemColors(DARK_BACKGROUND_ONLY)
    check("dark bg only: appearance", darkOnly.appearance, PiSystemAppearance.DARK)
    golden("dark bg only", GOLDEN_DARK_BG_ONLY, darkOnly)

    // Tier 2, white: a light background at the extreme where the curves are steepest.
    val whiteOnly = deriveSystemColors(WHITE_BACKGROUND_ONLY)
    check("white bg only: appearance", whiteOnly.appearance, PiSystemAppearance.LIGHT)
    golden("white bg only", GOLDEN_WHITE_BG_ONLY, whiteOnly)

    // Mid-grey: the levels do not fit, so they are relaxed by the smallest amount that does.
    val midGray = deriveSystemColors(MID_GRAY)
    check("mid gray: appearance", midGray.appearance, PiSystemAppearance.LIGHT)
    checkTrue("mid gray: the levels were relaxed", midGray.relaxation > 0.0)
    checkNear("mid gray: relaxation", midGray.relaxation, 1.9073486328125e-6, 1e-9)
    golden("mid gray + fg", GOLDEN_MID_GRAY_FG, midGray)

    // Tier 3: nothing reported at all. pi defers to the terminal; the phone derives from
    // pi's default ANSI 16 on pi's default background, so this line is what pi produces
    // for exactly that pair.
    val nothing = deriveSystemColors(null)
    check("nothing: appearance", nothing.appearance, PiSystemAppearance.DARK)
    golden("nothing (defaults)", GOLDEN_DEFAULT_TIER, nothing)
}

/**
 * The conversions, against pi's `oklab.ts` output for the same inputs.
 *
 * This is the part of the port where a plausible-looking rewrite would still "work" (a
 * theme would still build) but produce different colours, so it is checked directly:
 * OKHSL → sRGB at every family hue, sRGB → OKHSL on real terminal colours, the
 * OKLab ↔ OKHSL lightness curve, and OKLab itself.
 */
private fun okhslConversions() {
    // `okhslToRgb(hue, saturation, lightness)` — hue in degrees, the rest 0-1.
    expectsOkhslToRgb(0.0, 1.0, 0.5, 215, 0, 113)
    expectsOkhslToRgb(20.0, 0.92, 0.5, 213, 40, 65)
    expectsOkhslToRgb(52.0, 0.85, 0.5, 179, 92, 25)
    expectsOkhslToRgb(82.36, 1.0, 0.5, 153, 111, 0)
    expectsOkhslToRgb(158.68, 0.76, 0.5, 55, 137, 96)
    expectsOkhslToRgb(202.43, 0.74, 0.5, 44, 133, 140)
    expectsOkhslToRgb(231.49, 0.68, 0.5, 52, 128, 164)
    expectsOkhslToRgb(263.25, 0.6, 0.5, 81, 117, 190)
    expectsOkhslToRgb(295.0, 0.6, 0.5, 126, 99, 192)
    expectsOkhslToRgb(337.5, 0.85, 0.5, 194, 40, 164)
    expectsOkhslToRgb(231.49, 0.08, 0.05, 7, 9, 10)
    expectsOkhslToRgb(231.49, 0.08, 0.95, 239, 241, 242)
    expectsOkhslToRgb(231.49, 0.02, 0.999, 255, 255, 255)
    expectsOkhslToRgb(0.0, 0.0, 0.5, 119, 119, 119)
    expectsOkhslToRgb(120.0, 0.3, 0.0, 0, 0, 0)
    expectsOkhslToRgb(120.0, 0.3, 1.0, 255, 255, 255)
    expectsOkhslToRgb(120.0, 0.5, 0.8, 191, 207, 146)
    expectsOkhslToRgb(200.0, 1.0, 0.15, 0, 40, 43)
    expectsOkhslToRgb(300.0, 0.25, 0.65, 162, 152, 184)
    expectsRgbToOkhsl(0x000000, 0.0, 0.0, 0.0)
    expectsRgbToOkhsl(0xFFFFFF, 0.0, 0.0, 0.9999999999999996)
    expectsRgbToOkhsl(0x808080, 0.0, 0.0, 0.5357064639159033)
    expectsRgbToOkhsl(0xFF0000, 29.23388027962784, 1.0, 0.5680846563197032)
    expectsRgbToOkhsl(0x00FF00, 142.49534504144378, 0.9999999999999991, 0.844528971493631)
    expectsRgbToOkhsl(0x0000FF, 264.05202261637, 1.0, 0.36656533918708134)
    expectsRgbToOkhsl(0x282A36, 277.50866115135716, 0.18495257209798022, 0.1859319097259169)
    expectsRgbToOkhsl(0xFDF6E3, 90.09527864354544, 0.7704662342948178, 0.9691632328246712)
    expectsRgbToOkhsl(0x657B83, 221.8987843556808, 0.16968453776377018, 0.49922687242056524)
    expectsRgbToOkhsl(0x50FA7B, 148.0248891830255, 0.9353085188403327, 0.8498266448561244)
    expectsRgbToOkhsl(0xC0C0C0, 0.0, 0.0, 0.7763752595860821)
    expectsRgbToOkhsl(0x21222C, 280.48837738167435, 0.17688720321255635, 0.15202305857226345)
    expectsOkhslLightness(0.0, 0.0)
    expectsOkhslLightness(0.1, 0.029631373306766642)
    expectsOkhslLightness(0.2, 0.09907971332700129)
    expectsOkhslLightness(0.5, 0.42114056260896976)
    expectsOkhslLightness(0.8, 0.7673212034483867)
    expectsOkhslLightness(0.9, 0.8835659576242668)
    expectsOkhslLightness(0.95, 0.9417635177740233)
    expectsOkhslLightness(1.0, 1.0)
    expectsOklab(0x282A36, 0.28822897999753894, 0.002888372501584857, -0.02191376892779609)
    expectsOklab(0xFDF6E3, 0.9735283377477288, -0.00004332357084307903, 0.02605259040531005)
    expectsOklab(0x657B83, 0.5681654696657004, -0.02121901966518569, -0.019037924846702248)
    // OKHSL -> sRGB -> OKHSL -> sRGB is stable: the colour survives the 8-bit trip. The hue
    // is a weaker invariant than the colour, because one 1/255 step of one channel moves it
    // by up to a few degrees near black (and wraps 359.9 -> 0.1 on the red axis), so the
    // check below is a *circular* distance and the colour is the exact assertion.
    for (hue in listOf(0.0, 20.0, 82.36, 158.68, 202.43, 231.49, 295.0, 337.5)) {
        for (lightness in listOf(0.1, 0.3, 0.5, 0.7, 0.9)) {
            val forward = okhslToRgb(hue, 0.6, lightness)
            val back = rgbToOkhsl(forward)
            val again = okhslToRgb(back.h, back.s, back.l)
            check(
                "round trip $hue/$lightness",
                "${again.r},${again.g},${again.b}",
                "${forward.r},${forward.g},${forward.b}",
            )
            val hueDelta = abs(back.h - hue).let { if (it > 180.0) 360.0 - it else it }
            checkTrue("round trip $hue/$lightness: hue within 3 degrees ($hueDelta)", hueDelta < 3.0)
            checkTrue(
                "round trip $hue/$lightness: in gamut",
                forward.r in 0..255 && forward.g in 0..255 && forward.b in 0..255,
            )
        }
    }
    // The two ends of the OKHSL lightness curve, and grey's missing hue.
    check("black lightness", oklabToOkhslLightness(0.0), 0.0)
    check("white lightness", oklabToOkhslLightness(1.0), 1.0)
    check("grey has no hue", rgbToOkhsl(Rgb(128, 128, 128)).h, 0.0)
    check("grey has no saturation", rgbToOkhsl(Rgb(128, 128, 128)).s, 0.0)
    check("black has no saturation", rgbToOkhsl(Rgb(0, 0, 0)).s, 0.0)
    check("white has no saturation", rgbToOkhsl(Rgb(255, 255, 255)).s, 0.0)
    // WCAG: 1:1 for a colour against itself, 21:1 for black on white.
    checkNear("contrast of a colour with itself", wcagContrast(Rgb(40, 42, 54), Rgb(40, 42, 54)), 1.0, 1e-12)
    checkNear("black on white", wcagContrast(Rgb(0, 0, 0), Rgb(255, 255, 255)), 21.0, 1e-12)
    checkNear("relative luminance of white", relativeLuminance(Rgb(255, 255, 255)), 1.0, 1e-12)
}

// ============================================================================
// Golden values, generated by pi's implementation (see the file header)
// ============================================================================

/**
 * Each constant is one case, **56 hex triples in `PiSystemToken.entries` order**, as pi's
 * own `generateSystemThemeColors` printed them for the input named above it. They are
 * split across lines in groups of eight tokens; the values themselves are untouched.
 */
/** dracula, background + palette + foreground (pi's `text`/`userMessageText`/`toolTitle` are `""` = this foreground) */
private const val GOLDEN_DRACULA_FG = "" +
    "45236C 393C04 45236C 670447 31384C 11441E 5F2220 F8F8F2 " +
    "F8F8F2 96A2C7 F8F8F2 96A2C7 96A2C7 96A2C7 7484B5 8E9CC3 " +
    "96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 " +
    "424D6E 8F9CC4 96A2C7 6C7DAF FF5ABE FF45BA FF5BBE FF5BBE " +
    "FF5BBE FF5BBE B27EF7 B787F8 B787F8 1EB6D0 39C15C 39C15C " +
    "39C15C 38B959 FF5BBE F9756F F9756F A5AC14 A5AC14 A5AC14 " +
    "39C15C 6272A4 964AE7 9B53EC 1597AD F500AD F800CC FF5555"
/** dracula, background + palette, no foreground reported */
private const val GOLDEN_DRACULA = "" +
    "45236C 393C04 45236C 670447 31384C 11441E 5F2220 DFE2E8 " +
    "DDDFE7 96A2C7 DDDFE7 96A2C7 96A2C7 96A2C7 7484B5 8E9CC3 " +
    "96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 96A2C7 " +
    "424D6E 8F9CC4 96A2C7 6C7DAF FF5ABE FF45BA FF5BBE FF5BBE " +
    "FF5BBE FF5BBE B27EF7 B787F8 B787F8 1EB6D0 39C15C 39C15C " +
    "39C15C 38B959 FF5BBE F9756F F9756F A5AC14 A5AC14 A5AC14 " +
    "39C15C 6272A4 964AE7 9B53EC 1597AD F500AD F800CC FF5555"
/** solarized light, background + foreground only (tier 2) */
private const val GOLDEN_SOLARIZED_FG = "" +
    "DFE7EB EDE3DE DFE7EB E6E4EE E4E6E6 DEE8E2 EEE3E2 4A5A60 " +
    "4A5A60 677075 49595F 677075 677075 677075 869095 7B858A " +
    "677075 677176 677176 677075 677075 677176 677075 677075 " +
    "E1E2E3 97A0A5 677075 9AA3A7 755AB5 8A70CA 755AB5 765BB6 " +
    "765BB6 755AB5 3C8DB2 2F7899 2F7798 287C82 337F59 337F59 " +
    "337F59 3F966B 337F59 C6253C C6253C 8E6701 8E6701 8E6701 " +
    "A65517 C3C7C9 B6C3CB 9EC1D4 A1B6DF B4A4E8 E583CC FE7177"
/** solarized light, background + palette + foreground (tier 1) */
private const val GOLDEN_SOLARIZED_FG_PALETTE = "" +
    "DFE7EE EBE5D7 DFE7EE F1E1E6 C6EDFA E4E8D8 EEE3E1 4A5B61 " +
    "4A5B61 007B96 4A5A60 007B96 007B96 007B96 009EBF 0092B0 " +
    "007B96 007C97 007C97 007B96 007B96 007C97 007B96 007B96 " +
    "BBEBFB 00AFD4 007B96 00B3D8 BD2972 D93D87 BD2972 BE2973 " +
    "BE2973 BD2972 2589D0 1975B3 1874B2 1F7E77 687800 687800 " +
    "687800 7B8E00 BD2972 C62A28 C62A28 8B6900 8C6900 8B6900 " +
    "687800 51D9FF 9BC7EC 8FC2ED 5ECBC1 EB90B3 9FA6DD F9786C"
/** dark background only (tier 2) */
private const val GOLDEN_DARK_BG_ONLY = "" +
    "1F343F 432A1B 1F343F 332B49 2E3133 21372A 4A2425 DDDFE0 " +
    "DADCDE 99A1A6 DBDDDE 99A1A6 99A1A6 99A1A6 7A8489 919A9F " +
    "99A1A6 98A1A6 98A1A6 99A1A6 99A1A6 98A1A6 99A1A6 99A1A6 " +
    "44494C 929BA0 98A1A6 727C81 A493D6 9D8BD4 A493D6 A493D6 " +
    "A493D6 A493D6 57A3C8 62AACD 62AACD 55B0B7 61B487 61B487 " +
    "61B487 57AD80 61B487 EB777A EB777A CA961D C9961C CA961D " +
    "DD8751 677176 627A86 50849E 5D81C9 926FE2 DC4ABD FE475A"
/** white background only (tier 2) */
private const val GOLDEN_WHITE_BG_ONLY = "" +
    "EDF1F3 F3EFEC EDF1F3 F0EFF5 EFF0F0 EDF1EE F4EFEE 494F52 " +
    "494F52 737D83 484E52 737D83 737D83 737D83 929BA0 879197 " +
    "737D83 737D83 737D83 737D83 737D83 737D83 737D83 737D83 " +
    "ECEDED A3ABAF 737D83 A7AEB2 8268C4 9580D1 8268C4 8368C4 " +
    "8368C4 8268C4 4A9AC0 3685A9 3685A9 2E8A91 3A8E64 3A8E64 " +
    "3A8E64 4BA476 3A8E64 DC2C44 DC2C44 9E7300 9E7300 9E7300 " +
    "B9601B CFD2D4 C4CED4 B0CCDB B1C2E3 BFB3E9 E79AD1 FE898B"
/** mid grey + white foreground: the relaxed case */
private const val GOLDEN_MID_GRAY_FG = "" +
    "317C9E B45D19 317C9E 7F65C1 6C767B 35835B DC2D45 000000 " +
    "000000 000000 000000 000000 000000 000000 000000 000000 " +
    "000000 000000 000000 000000 000000 000000 000000 000000 " +
    "60696E 000000 000000 000000 000000 000000 000000 000000 " +
    "000000 000000 000000 000000 000000 000000 000000 000000 " +
    "000000 000000 000000 000000 000000 000000 000000 000000 " +
    "000000 3E4345 303A40 1F313A 172133 000000 000000 000000"
/** nothing reported (tier 3) = pi's default ANSI on pi's default background */
private const val GOLDEN_DEFAULT_TIER = "" +
    "00009C 323317 00009C 4E134D 313131 223720 551B15 DEDEDE " +
    "DCDCDC 9F9F9F DCDCDC 9F9F9F 9F9F9F 9F9F9F 828282 989898 " +
    "9F9F9F 9F9F9F 9F9F9F 9F9F9F 9F9F9F 9F9F9F 9F9F9F 9F9F9F " +
    "484848 999999 9F9F9F 7A7A7A FF0FFF F500F5 FF0FFF FF0FFF " +
    "FF0FFF FF0FFF 6293FF 6E9CFF 6E9CFF 30B5B4 5CB856 5CB856 " +
    "5CB856 4FB249 FF0FFF FF6756 FF6756 A6A61D A6A61D A6A61D " +
    "5CB856 6F6F6F 2863FF 336DFF 029595 DC00DC E600E6 FF4A3A"

// ============================================================================
// Tiers, defaults, and pi's own claims
// ============================================================================

private fun tiersAndDefaults() {
    check("DEFAULT_BACKGROUND", hex(DEFAULT_BACKGROUND), "#1E1E1E")
    check(
        "DEFAULT_ANSI is pi's BASIC_COLORS",
        DEFAULT_ANSI.joinToString(" ") { "%06X".format(it) },
        "000000 800000 008000 808000 000080 800080 008080 C0C0C0 " +
            "808080 FF0000 00FF00 FFFF00 0000FF FF00FF 00FFFF FFFFFF",
    )
    // Tier 3 is exactly tier 1 with the defaults, so the two must agree token for token.
    check(
        "nothing === defaults palette",
        deriveSystemColors(null).colors,
        deriveSystemColors(defaultAnsiPalette()).colors,
    )
    check(
        "defaultAnsiPalette is DEFAULT_BACKGROUND + DEFAULT_ANSI",
        defaultAnsiPalette(),
        PiAnsiPalette(DEFAULT_BACKGROUND, DEFAULT_ANSI),
    )
    // A palette that is not 16 long is "no palette", not an error (pi: `palette.length === 16`).
    checkTrue("a valid palette is reported as such", DRACULA.hasPalette)
    checkTrue("a short palette is not a palette", !PiAnsiPalette(0x282A36, intArrayOf(1, 2, 3)).hasPalette)
    check(
        "a short palette falls back to the family hues",
        deriveSystemColors(PiAnsiPalette(0x282A36, intArrayOf(1, 2, 3))).colors,
        deriveSystemColors(DRACULA_NO_FOREGROUND.copy(ansi = IntArray(0))).colors,
    )
}

/**
 * pi's own claims about its derivation (`system-theme.test.ts:44-100`), ported.
 *
 * These matter more than the golden values in one way: the goldens pin *this* port to
 * pi's current output, while these state *why* the output is acceptable — body text
 * readable, roles ordered by contrast, panels close to the background, hues from the
 * palette. A future pi change that broke one of those would still be a regression even
 * if the new golden values matched.
 */
private fun propertyChecks() {
    val terminals = listOf(
        "dracula" to DRACULA,
        "solarizedLight" to SOLARIZED,
        "backgroundOnly" to DARK_BACKGROUND_ONLY,
        "midGray" to MID_GRAY,
    )

    // 1. Body text keeps the WCAG 4.5:1 floor on the background and its panels.
    for ((name, terminal) in terminals) {
        val colors = deriveSystemColors(terminal)
        val background = Rgb.of(terminal.background)
        val text = Rgb.of(colors[PiSystemToken.TEXT])
        checkTrue(
            "$name: text ≥4.5:1 on the background",
            wcagContrast(text, background) >= 4.5,
        )
        checkTrue(
            "$name: text ≥4.5:1 on selectedBg",
            wcagContrast(text, Rgb.of(colors[PiSystemToken.SELECTED_BG])) >= 4.5,
        )
        checkTrue(
            "$name: toolTitle ≥4.5:1 on toolErrorBg",
            wcagContrast(
                Rgb.of(colors[PiSystemToken.TOOL_TITLE]),
                Rgb.of(colors[PiSystemToken.TOOL_ERROR_BG]),
            ) >= 4.5,
        )
    }

    // 2. Foreground roles are ordered by contrast, and panels stay close to the background.
    for ((name, terminal) in terminals) {
        val colors = deriveSystemColors(terminal)
        val backgroundL = rgbToOklab(Rgb.of(terminal.background)).l
        fun offset(token: PiSystemToken): Double = rgbToOklab(Rgb.of(colors[token])).l - backgroundL
        // On mid-grey the levels collapse to the strongest reachable colour.
        if (name != "midGray") {
            checkTrue("$name: |text| > |muted|", abs(offset(PiSystemToken.TEXT)) > abs(offset(PiSystemToken.MUTED)))
            checkTrue(
                "$name: |muted| > |dim|",
                abs(offset(PiSystemToken.MUTED)) > abs(offset(PiSystemToken.DIM)),
            )
        }
        val dark = colors.appearance == PiSystemAppearance.DARK
        for (panel in PANEL_TOKENS) {
            checkTrue(
                "$name: ${panel.wire} is close to the background",
                wcagContrast(Rgb.of(colors[panel]), Rgb.of(terminal.background)) < 2.0,
            )
            checkTrue(
                "$name: ${panel.wire} is ${if (dark) "lighter" else "darker"} than the background",
                (offset(panel) > 0.0) == dark,
            )
        }
    }

    // 3. Body text takes the terminal's foreground where it is strong enough, and the
    //    palette decides hues.
    check(
        "dracula: text is the terminal foreground",
        deriveSystemColors(DRACULA)[PiSystemToken.TEXT],
        0xF8F8F2,
    )
    // Solarized's foreground is below 4.5:1 on its own background, so text is darkened.
    checkTrue(
        "solarized: text is not the terminal foreground",
        deriveSystemColors(SOLARIZED)[PiSystemToken.TEXT] != 0x657B83,
    )
    val errorHue = rgbToOklch(Rgb.of(deriveSystemColors(DRACULA)[PiSystemToken.ERROR])).h
    val paletteHue = rgbToOklch(Rgb.of(DRACULA.ansi[1])).h
    checkTrue(
        "dracula: error keeps the palette's hue (${errorHue} vs ${paletteHue})",
        abs(errorHue - paletteHue) < 8.0,
    )

    // 4. Zero saturation renders greyscale — pi uses it for the first frame, before the
    //    terminal's colours have arrived.
    val grey = deriveSystemColors(DRACULA, saturation = 0.0)
    for (token in listOf(PiSystemToken.ERROR, PiSystemToken.SUCCESS, PiSystemToken.ACCENT)) {
        checkTrue(
            "saturation 0: ${token.wire} is grey",
            rgbToOklch(Rgb.of(grey[token])).c < 0.005,
        )
    }
    // Greyscale still has to obey the readability floor: the WCAG fix-up runs after the
    // paint, not only when a palette supplied the saturation.
    checkTrue(
        "saturation 0: body text still meets 4.5:1",
        wcagContrast(Rgb.of(grey[PiSystemToken.TEXT]), Rgb.of(DRACULA.background)) >= 4.5,
    )
}

/**
 * The last step — 56 tokens onto [PiPalette]'s 56 named fields — is a hand-written
 * mapping, and the one place a typo would still produce a compiling, plausible-looking
 * palette with two colours swapped. This walks every field and compares it back to the
 * token map, so the hand-off is covered rather than trusted.
 */
private fun paletteHandOff() {
    val derived = deriveSystemColors(DRACULA)
    val palette = deriveSystemPalette(DRACULA)
    fun argb(color: Color): Int =
        (Math.round(color.red * 255f) shl 16) or
            (Math.round(color.green * 255f) shl 8) or
            Math.round(color.blue * 255f)

    fun checkField(name: String, token: PiSystemToken, color: Color) {
        check("deriveSystemPalette: $name is $token", argb(color), derived[token])
    }
    checkField("accent", PiSystemToken.ACCENT, palette.accent)
    checkField("border", PiSystemToken.BORDER, palette.border)
    checkField("borderAccent", PiSystemToken.BORDER_ACCENT, palette.borderAccent)
    checkField("borderMuted", PiSystemToken.BORDER_MUTED, palette.borderMuted)
    checkField("success", PiSystemToken.SUCCESS, palette.success)
    checkField("error", PiSystemToken.ERROR, palette.error)
    checkField("warning", PiSystemToken.WARNING, palette.warning)
    checkField("muted", PiSystemToken.MUTED, palette.muted)
    checkField("dim", PiSystemToken.DIM, palette.dim)
    checkField("text", PiSystemToken.TEXT, palette.text)
    checkField("thinkingText", PiSystemToken.THINKING_TEXT, palette.thinkingText)
    checkField("selectedBg", PiSystemToken.SELECTED_BG, palette.selectedBg)
    checkField("scrollbarTrack", PiSystemToken.SCROLLBAR_TRACK, palette.scrollbarTrack)
    checkField("scrollbarThumb", PiSystemToken.SCROLLBAR_THUMB, palette.scrollbarThumb)
    checkField("searchMatchBg", PiSystemToken.SEARCH_MATCH_BG, palette.searchMatchBg)
    checkField("searchMatchText", PiSystemToken.SEARCH_MATCH_TEXT, palette.searchMatchText)
    checkField("userMessageBg", PiSystemToken.USER_MESSAGE_BG, palette.userMessageBg)
    checkField("userMessageText", PiSystemToken.USER_MESSAGE_TEXT, palette.userMessageText)
    checkField("customMessageBg", PiSystemToken.CUSTOM_MESSAGE_BG, palette.customMessageBg)
    checkField("customMessageText", PiSystemToken.CUSTOM_MESSAGE_TEXT, palette.customMessageText)
    checkField("customMessageLabel", PiSystemToken.CUSTOM_MESSAGE_LABEL, palette.customMessageLabel)
    checkField("toolPendingBg", PiSystemToken.TOOL_PENDING_BG, palette.toolPendingBg)
    checkField("toolSuccessBg", PiSystemToken.TOOL_SUCCESS_BG, palette.toolSuccessBg)
    checkField("toolErrorBg", PiSystemToken.TOOL_ERROR_BG, palette.toolErrorBg)
    checkField("toolTitle", PiSystemToken.TOOL_TITLE, palette.toolTitle)
    checkField("toolOutput", PiSystemToken.TOOL_OUTPUT, palette.toolOutput)
    checkField("mdHeading", PiSystemToken.MD_HEADING, palette.mdHeading)
    checkField("mdLink", PiSystemToken.MD_LINK, palette.mdLink)
    checkField("mdLinkUrl", PiSystemToken.MD_LINK_URL, palette.mdLinkUrl)
    checkField("mdCode", PiSystemToken.MD_CODE, palette.mdCode)
    checkField("mdCodeBlock", PiSystemToken.MD_CODE_BLOCK, palette.mdCodeBlock)
    checkField("mdCodeBlockBorder", PiSystemToken.MD_CODE_BLOCK_BORDER, palette.mdCodeBlockBorder)
    checkField("mdQuote", PiSystemToken.MD_QUOTE, palette.mdQuote)
    checkField("mdQuoteBorder", PiSystemToken.MD_QUOTE_BORDER, palette.mdQuoteBorder)
    checkField("mdHr", PiSystemToken.MD_HR, palette.mdHr)
    checkField("mdListBullet", PiSystemToken.MD_LIST_BULLET, palette.mdListBullet)
    checkField("toolDiffAdded", PiSystemToken.TOOL_DIFF_ADDED, palette.toolDiffAdded)
    checkField("toolDiffRemoved", PiSystemToken.TOOL_DIFF_REMOVED, palette.toolDiffRemoved)
    checkField("toolDiffContext", PiSystemToken.TOOL_DIFF_CONTEXT, palette.toolDiffContext)
    checkField("syntaxComment", PiSystemToken.SYNTAX_COMMENT, palette.syntaxComment)
    checkField("syntaxKeyword", PiSystemToken.SYNTAX_KEYWORD, palette.syntaxKeyword)
    checkField("syntaxFunction", PiSystemToken.SYNTAX_FUNCTION, palette.syntaxFunction)
    checkField("syntaxVariable", PiSystemToken.SYNTAX_VARIABLE, palette.syntaxVariable)
    checkField("syntaxString", PiSystemToken.SYNTAX_STRING, palette.syntaxString)
    checkField("syntaxNumber", PiSystemToken.SYNTAX_NUMBER, palette.syntaxNumber)
    checkField("syntaxType", PiSystemToken.SYNTAX_TYPE, palette.syntaxType)
    checkField("syntaxOperator", PiSystemToken.SYNTAX_OPERATOR, palette.syntaxOperator)
    checkField("syntaxPunctuation", PiSystemToken.SYNTAX_PUNCTUATION, palette.syntaxPunctuation)
    checkField("thinkingOff", PiSystemToken.THINKING_OFF, palette.thinkingOff)
    checkField("thinkingMinimal", PiSystemToken.THINKING_MINIMAL, palette.thinkingMinimal)
    checkField("thinkingLow", PiSystemToken.THINKING_LOW, palette.thinkingLow)
    checkField("thinkingMedium", PiSystemToken.THINKING_MEDIUM, palette.thinkingMedium)
    checkField("thinkingHigh", PiSystemToken.THINKING_HIGH, palette.thinkingHigh)
    checkField("thinkingXhigh", PiSystemToken.THINKING_XHIGH, palette.thinkingXhigh)
    checkField("thinkingMax", PiSystemToken.THINKING_MAX, palette.thinkingMax)
    checkField("bashMode", PiSystemToken.BASH_MODE, palette.bashMode)
    checkTrue(
        "deriveSystemPalette: every colour is opaque",
        palette.run {
            listOf(
                accent, border, borderAccent, borderMuted, success, error, warning, muted, dim, text,
                thinkingText, selectedBg, scrollbarTrack, scrollbarThumb, searchMatchBg, searchMatchText,
                userMessageBg, userMessageText, customMessageBg, customMessageText, customMessageLabel,
                toolPendingBg, toolSuccessBg, toolErrorBg, toolTitle, toolOutput, mdHeading, mdLink,
                mdLinkUrl, mdCode, mdCodeBlock, mdCodeBlockBorder, mdQuote, mdQuoteBorder, mdHr,
                mdListBullet, toolDiffAdded, toolDiffRemoved, toolDiffContext, syntaxComment,
                syntaxKeyword, syntaxFunction, syntaxVariable, syntaxString, syntaxNumber, syntaxType,
                syntaxOperator, syntaxPunctuation, thinkingOff, thinkingMinimal, thinkingLow,
                thinkingMedium, thinkingHigh, thinkingXhigh, thinkingMax, bashMode, pageBg, cardBg,
                infoBg,
            ).all { it.alpha == 1f }
        },
    )
    // The canvas tokens are pi's exporter rule, not derivation output: on a dark theme both
    // are dimmed copies of the message surface, and the page always the dimmer one.
    checkTrue(
        "deriveSystemPalette: a dark theme dims the canvas",
        palette.pageBg.red < palette.cardBg.red && palette.cardBg.red < palette.userMessageBg.red,
    )
}

fun main() {
    goldenTokens()
    okhslConversions()
    goldenThemes()
    tiersAndDefaults()
    propertyChecks()
    paletteHandOff()

    println(
        "Pi system theme: $passed checks passed, $failed failed; " +
            "worst golden channel delta $worstDelta/255 ($worstToken)",
    )
    if (failed > 0) {
        println("harness: FAILED")
        exitProcess(1)
    }
    println("harness: OK")
}
