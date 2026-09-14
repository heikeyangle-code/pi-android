package app.pi.ui.extension

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.pi.rpc.Ansi
import app.pi.ui.theme.PiPalette

/**
 * Which [PiPalette] colour an extension's ANSI foreground maps onto.
 *
 * Why this exists at all: an extension colours its chrome with
 * `ctx.ui.theme.fg("accent", text)`, and pi's `Theme.fg` returns that text
 * wrapped in SGR bytes (`modes/interactive/theme/theme.ts:323-327`). In a
 * terminal those bytes *are* the colour. Over RPC the same bytes arrive inside
 * an ordinary `string` field — `RpcExtensionUIRequest` has no colour channel
 * (`rpc-types.ts:246-281`) — so the app has to turn them back into a colour
 * itself. Dropping them (what the app did first) is honest but loses the one
 * thing the extension asked for; painting them is possible because both sides
 * are reading the same pi theme tokens.
 *
 * ## The rule, and why it is a threshold rather than "always nearest"
 *
 * The span's colour is matched against **pi's own declared tokens**, using pi's
 * own distance metric — a green-weighted Euclidean — copied from
 * `theme.ts:161-168`, and painted only when the nearest token is within
 * [MAX_TOKEN_DISTANCE]. Otherwise the caller's own colour is kept.
 *
 * That threshold is measured, not invented. pi quantises every theme colour
 * through `rgbTo256` (`theme.ts:164-193`) before putting it on the wire:
 * the app's engine runs with `TERM=xterm-256color` and no `COLORTERM`
 * (`runtime/PiRuntime.kt:233`), so
 * `getCapabilities().trueColor` is false (`tui/src/terminal-image.ts:74,131`)
 * and `createTheme` picks `"256color"` (`theme.ts:529`). Running both built-in
 * palettes through that quantiser and then through this matcher:
 *
 *  - every *foreground* token either ships survives the round trip within
 *    **507.4** (the worst is the light theme's `warning`), so no text colour pi
 *    ships is lost to the threshold;
 *  - the first *surface* token — a card or page background used as a text
 *    colour, which the wire mangles by 1263 and up — sits at **1263**;
 *  - 110 of the 118 built-in token colours are therefore honoured, and the 8
 *    that fall back are exactly the dark theme's surface tokens
 *    (`selectedBg`, `searchMatchBg`, `toolErrorBg`, `infoBg`, `toolPendingBg`,
 *    `userMessageBg`, `customMessageBg`, `toolSuccessBg`).
 *
 * A threshold of `1000` sits inside that gap. The guarantee it buys is the
 * useful one: **a colour is only ever painted when it is within ~47 RGB units of
 * what pi asked for**; beyond that the span keeps the caller's colour instead of
 * being repainted as a token that merely happens to be closest. Raising it to
 * cover every token would start painting `toolSuccessBg`-as-text (#283228 on a
 * #18181E canvas) for colours pi had made bright green.
 *
 * No colour is ever *derived*: the result is always one of the palette's own
 * values, so nothing here can drift from the user's theme. `background`, `dim`,
 * bold, italic and underline on a span are deliberately ignored — the app's
 * surfaces and type scale are its own (`PiPalette`'s contrast-corrected tokens
 * exist precisely to keep a phone's 13–15 sp prose legible), and foreground
 * colour is the one thing an extension cannot express any other way.
 */
private const val MAX_TOKEN_DISTANCE = 1000.0

/**
 * pi's declared theme tokens as packed ARGB, in [PiPalette]'s declaration order —
 * the 56 from `theme-schema.json` plus the 3 `export` surfaces.
 *
 * Only the *declared* properties are listed. The palette's five contrast-
 * corrected values (`bodyOnTool`, `contextOnTool`, `thinkingBodyOnCanvas`,
 * `metaOnCanvas`, `metaOnCard`) are the app's own derivations, so a span must
 * never be matched onto one: that would be attributing a colour to pi that pi
 * never emitted.
 *
 * This is the same set as `PiThemeFiles.tokens()`, which is `private` to that
 * file; should it ever become `internal`, this list can be deleted in favour of
 * it.
 *
 * Packed ints rather than [Color]s because the match runs once per span: the
 * caller hoists one array per palette (see [themeTokenArgb]) and then only the
 * winning entry is turned back into a [Color].
 */
private fun PiPalette.tokenArgb(): IntArray = intArrayOf(
    accent.toArgb(), border.toArgb(), borderAccent.toArgb(), borderMuted.toArgb(),
    success.toArgb(), error.toArgb(), warning.toArgb(), muted.toArgb(), dim.toArgb(),
    text.toArgb(), thinkingText.toArgb(), selectedBg.toArgb(), scrollbarTrack.toArgb(),
    scrollbarThumb.toArgb(), searchMatchBg.toArgb(), searchMatchText.toArgb(),
    userMessageBg.toArgb(), userMessageText.toArgb(), customMessageBg.toArgb(),
    customMessageText.toArgb(), customMessageLabel.toArgb(),
    toolPendingBg.toArgb(), toolSuccessBg.toArgb(), toolErrorBg.toArgb(),
    toolTitle.toArgb(), toolOutput.toArgb(),
    mdHeading.toArgb(), mdLink.toArgb(), mdLinkUrl.toArgb(), mdCode.toArgb(),
    mdCodeBlock.toArgb(), mdCodeBlockBorder.toArgb(), mdQuote.toArgb(),
    mdQuoteBorder.toArgb(), mdHr.toArgb(), mdListBullet.toArgb(),
    toolDiffAdded.toArgb(), toolDiffRemoved.toArgb(), toolDiffContext.toArgb(),
    syntaxComment.toArgb(), syntaxKeyword.toArgb(), syntaxFunction.toArgb(),
    syntaxVariable.toArgb(), syntaxString.toArgb(), syntaxNumber.toArgb(),
    syntaxType.toArgb(), syntaxOperator.toArgb(), syntaxPunctuation.toArgb(),
    thinkingOff.toArgb(), thinkingMinimal.toArgb(), thinkingLow.toArgb(),
    thinkingMedium.toArgb(), thinkingHigh.toArgb(), thinkingXhigh.toArgb(),
    thinkingMax.toArgb(),
    bashMode.toArgb(),
    pageBg.toArgb(), cardBg.toArgb(), infoBg.toArgb(),
)

/**
 * The match candidates for [this], to be built **once per palette** and passed to
 * [tokenColorFor] for every span — `toArgb()` is not free and a widget can carry
 * dozens of spans.
 */
fun PiPalette.themeTokenArgb(): IntArray = tokenArgb()

/** The theme token that painted [span], or null to keep the caller's own colour. */
fun tokenColorFor(span: Ansi.Span, tokens: IntArray): Color? {
    val foreground = span.foreground ?: return null
    val r = (foreground shr 16) and 0xFF
    val g = (foreground shr 8) and 0xFF
    val b = foreground and 0xFF

    var best = 0
    var bestDistance = Double.MAX_VALUE
    for (argb in tokens) {
        val dr = (r - ((argb shr 16) and 0xFF)).toDouble()
        val dg = (g - ((argb shr 8) and 0xFF)).toDouble()
        val db = (b - (argb and 0xFF)).toDouble()
        val distance = dr * dr * 0.299 + dg * dg * 0.587 + db * db * 0.114
        if (distance < bestDistance) {
            bestDistance = distance
            best = argb
        }
    }
    return if (bestDistance <= MAX_TOKEN_DISTANCE) Color(best) else null
}
