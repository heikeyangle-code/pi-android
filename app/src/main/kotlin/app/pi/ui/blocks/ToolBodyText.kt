package app.pi.ui.blocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import app.pi.ui.render.PiCodeLanguage
import app.pi.ui.render.rememberPiHighlightedCode
import app.pi.ui.theme.PiTheme

/**
 * File content, the way pi's `read` and `write` renderers draw it.
 *
 * Two halves, both pi's:
 *
 *  - **Colour** comes from the app's existing highlight channel — the guest engine's
 *    highlight.js, reached through `rememberPiHighlightedCode`
 *    (`app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt:403`). pi highlights a
 *    file body by resolving the path's extension (`core/tools/renderers/read.ts:126-127`,
 *    `write.ts:111-114`), which is [PiCodeLanguage.forPath]; when the extension is not in
 *    pi's table, pi skips highlighting entirely rather than guessing (`theme.ts:1078-1086`),
 *    and so does this.
 *  - **Line numbers** are the app's, and they are *derived*, not invented: pi's terminal
 *    prints no line numbers at all (`read.ts:127` highlights and nothing more) — its only
 *    line information is the range on the call line (`:28-33`, from `args.offset`/`limit`),
 *    which is [startLine] here. Line *i* of the body is therefore `startLine + i`.
 *
 * Drawn as **one** `Text`, not one composable per line: a 200-line body would otherwise be
 * 200 layout nodes inside a row that recomposes on every 200 ms publication
 * (`rpc/.../Transcript.kt:618`). The gutter is part of the same string, padded to the width
 * of the largest number, which is what lines up in a monospace face.
 *
 * @param lines the body to paint, already capped by the caller (pi's preview count when the
 *   card is merely expanded, the app's budget when it is fully expanded).
 * @param startLine the number of the first line, from pi's `args.offset`.
 * @param path the path pi was asked for; only its extension matters, and only for colour.
 */
@Composable
internal fun SourceLines(
    lines: List<String>,
    startLine: Int,
    path: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    val palette = PiTheme.palette
    // The highlighter is asked for exactly the text that is sliced below, so character
    // offsets stay aligned even when a line was capped with "…".
    val code = remember(lines) { lines.joinToString("\n") }
    val language = remember(path) { PiCodeLanguage.forPath(path) }
    val highlighted = rememberPiHighlightedCode(code, language)
    val numbered = remember(highlighted, lines, startLine, palette.muted) {
        numberLines(lines, highlighted, startLine, palette.muted)
    }
    Text(
        text = numbered,
        style = PiTheme.text.mono,
        color = color,
        modifier = modifier,
    )
}

/**
 * [highlighted] with a dim line-number gutter in front of every line.
 *
 * A line whose highlight spans cross the newline is sliced out of [highlighted] by
 * character range, so every span pi's highlighter returned stays attached to its own
 * characters. Offsets are clamped rather than trusted: a highlighter that answered for a
 * different string must produce uncoloured text, not an exception in the middle of a
 * conversation (the same rule `buildPiCodeText` states for fences).
 */
private fun numberLines(
    lines: List<String>,
    highlighted: AnnotatedString,
    startLine: Int,
    gutterColor: Color,
): AnnotatedString = buildAnnotatedString {
    val gutter = (startLine + lines.size - 1).toString().length
    var offset = 0
    lines.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        pushStyle(SpanStyle(color = gutterColor))
        append((startLine + index).toString().padStart(gutter))
        append(NUMBER_GAP)
        pop()
        val start = offset.coerceIn(0, highlighted.length)
        val end = (offset + line.length).coerceIn(start, highlighted.length)
        if (start < end) append(highlighted.subSequence(start, end))
        offset += line.length + 1
    }
}

/** Two spaces between the gutter and the code; the monospace face does the rest. */
private const val NUMBER_GAP = "  "

/**
 * The last [max] lines of a shell result.
 *
 * pi shows the **tail** of a shell result when it is collapsed, not the head
 * (`core/tools/renderers/bash.ts:75-89`: `truncateToVisualLines(styledOutput,
 * BASH_PREVIEW_LINES, width)`), because a command's outcome is at the end — and pi's own
 * bash tool truncates to the *last* lines for the same reason
 * (`core/tools/bash.ts:234`). The count differs (pi's five are terminal rows); the end of
 * the output is what matters.
 */
internal fun tailLines(text: String, max: Int): String {
    if (max <= 0) return ""
    val lines = text.split('\n')
    return if (lines.size <= max) text else lines.takeLast(max).joinToString("\n")
}

/** How many lines a tail painted from a [total]-line body left out. */
internal fun hiddenLineCount(total: Int, painted: String): Int =
    (total - lineCount(painted)).coerceAtLeast(0)
