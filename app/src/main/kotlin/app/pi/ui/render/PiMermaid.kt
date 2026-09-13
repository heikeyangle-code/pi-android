package app.pi.ui.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.pi.ui.theme.PiPalette

/**
 * pi's ` ```mermaid ` rendering, on the Kotlin side of the seam.
 *
 * pi does not hand mermaid to highlight.js: it renders the diagram to box-drawing
 * art with `grok-mermaid` and then colours each run by its **semantic class**
 * (`modes/interactive/components/mermaid.ts:38-56`). Producing the art is a layout
 * job, so it stays in the guest next to pi's own `grok-mermaid` (the `/mermaid`
 * route in `pi-extensions/pi-highlight/service.ts`); deciding what each class
 * *looks like* is this app's, exactly as the hljs scope table is
 * ([app.pi.highlight.PiHighlightScopes]).
 *
 * The classes are `grok-mermaid`'s (`dist/types.d.ts`):
 *
 *  - `border`    — box outlines, subgraph frames, compartment rules
 *  - `text`      — node / participant / compartment labels
 *  - `edge`      — connector lines and arrowheads
 *  - `edgeLabel` — text sitting on an edge
 *  - `title`     — the `mermaid: <kind>` header of a source box
 *  - `none`      — blank filler
 */
internal enum class PiMermaidClass {
    Border,
    Text,
    Edge,
    EdgeLabel,
    Title,
    None,
}

/** One run of adjacent cells sharing one semantic class. */
internal data class PiMermaidRun(val text: String, val kind: PiMermaidClass)

/**
 * A rendered diagram.
 *
 * [warnings] is `grok-mermaid`'s own list of source its flowchart grammar could not
 * read — advisory, never a reason to withhold the art
 * (`grok-mermaid/dist/types.d.ts`), and shown the way pi shows it: alongside the
 * drawing, not instead of it.
 *
 * `grok-mermaid`'s `width` is deliberately **not** carried. pi uses it for one
 * decision — "wider than the terminal → keep the source instead"
 * (`components/mermaid.ts:76`) — and a phone replaces that with the horizontal
 * scroll every other code block in this app already has, because a diagram the user
 * can scroll is worth more than the mermaid source they cannot read. The service
 * still reports the number in its response; only the client ignores it.
 */
internal data class PiMermaidArt(
    val rows: List<List<PiMermaidRun>>,
    val warnings: List<String> = emptyList(),
)

/**
 * `grok-mermaid`'s class string → [PiMermaidClass].
 *
 * An unknown class becomes [PiMermaidClass.None] rather than being interpolated
 * into the drawing: pi's `styleSpan` has no default branch, so an unknown class
 * there returns `undefined` and `Array.join` prints the literal text `undefined`
 * into the diagram. That is a bug worth not reproducing; an uncoloured run is the
 * harmless reading of the same input.
 */
internal fun piMermaidClassOf(raw: String?): PiMermaidClass = when (raw) {
    "border" -> PiMermaidClass.Border
    "text" -> PiMermaidClass.Text
    "edge" -> PiMermaidClass.Edge
    "edgeLabel" -> PiMermaidClass.EdgeLabel
    "title" -> PiMermaidClass.Title
    else -> PiMermaidClass.None
}

/**
 * The art as text, coloured per run — pi's `themedLines` + `styleSpan`
 * (`components/mermaid.ts:38-56`), in one `AnnotatedString` instead of one
 * ANSI-wrapped row per line.
 *
 * `title` is `accent` **and bold** in pi (`:49`), which is why it is not just
 * another colour entry. `none` keeps whatever base the caller draws with — pi emits
 * the whole art as *inline code*, so an unclassed run is code-coloured, not
 * specially coloured, and the caller passes `mdCode` as that base.
 */
internal fun piMermaidText(art: PiMermaidArt, palette: PiPalette): AnnotatedString = buildAnnotatedString {
    art.rows.forEachIndexed { index, row ->
        if (index > 0) append('\n')
        for (run in row) {
            withStyle(piMermaidStyle(run.kind, palette)) { append(run.text) }
        }
    }
}

private fun piMermaidStyle(kind: PiMermaidClass, palette: PiPalette): SpanStyle = when (kind) {
    PiMermaidClass.Border -> SpanStyle(color = palette.borderMuted)
    PiMermaidClass.Text -> SpanStyle(color = palette.text)
    PiMermaidClass.Edge -> SpanStyle(color = palette.accent)
    PiMermaidClass.EdgeLabel -> SpanStyle(color = palette.muted)
    PiMermaidClass.Title -> SpanStyle(color = palette.accent, fontWeight = FontWeight.Bold)
    PiMermaidClass.None -> SpanStyle()
}

/**
 * The "the drawing is incomplete" line, or `null` when the art is whole.
 *
 * pi appends exactly this kind of sentence after the drawing when it is settled and
 * `warnings` is non-empty (`components/mermaid.ts:77-82`), in `warning` — the same
 * token this app paints it with. The wording is the app's (pi's is English and says
 * "not rendered", which is not quite true: the art *is* there, part of the source
 * just is not in it).
 */
internal fun piMermaidWarning(art: PiMermaidArt): String? {
    val first = art.warnings.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val more = art.warnings.size - 1
    return if (more > 0) "Mermaid 图未完整渲染：$first（另有 $more 处）" else "Mermaid 图未完整渲染：$first"
}
