package app.pi.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * The bottom bar's three destinations, drawn as the app's own 18-unit single-stroke glyphs.
 *
 * These are **not** Material icons, and that is the point. The v2 design
 * (`design/ui-refactor/design-demos/direction-b-v2.html`, the `Icon` function's
 * `chat` / `workbench` / `settings` arms) draws the app's icons as 18-unit stroked
 * paths — no fill, `stroke-width 1.5`, round caps and joins. A Material glyph in
 * the bar reads as a different weight and a different drawing language from the
 * rest of the surface, which is why the design does not use one there.
 *
 * The paths are the design's, character for character, and are parsed by
 * [PathParser] verbatim rather than transcribed into builder calls: the chat
 * bubble is four arcs, and a silent geometry error in an arc would be invisible
 * to every check this project can run on the machine that wrote it. [PathParser]
 * reads the exact same SVG syntax the design's markup uses, and
 * `addPath(pathData = …)` takes what it returns (`List<PathNode>`) — the builder
 * has no String overload.
 *
 * One notation difference from the design's markup, and only one: the settings
 * knobs are `<circle cx cy r>` elements there, and this Compose version's path
 * builder has no `circle()` helper, so each is written as the two half-arcs that
 * are the same circle (`M cx-r cy a r r 0 1 0 2r 0 a r r 0 1 0 -2r 0`). The
 * centre, the radius and the stroked, unfilled result are unchanged.
 */
object PiNavGlyphs {

    /** The design's own grid: every glyph is drawn inside `0 0 18 18`. */
    private const val VIEWPORT = 18f

    /** The design's stroke weight for every icon in the app. */
    private const val STROKE = 1.5f

    /**
     * The conversation glyph — the design's `chat`.
     *
     * The design's markup, unchanged:
     * `M2.9 6.1A2.6 2.6 0 015.5 3.5h7A2.6 2.6 0 0115.1 6.1v3.5a2.6 2.6 0 01-2.6 2.6H8.3l-3.6 2.4v-2.4h-.2A2.6 2.6 0 012.9 9.6z`
     */
    val Chat: ImageVector = glyph(
        name = "PiNavChat",
        pathData = "M2.9 6.1A2.6 2.6 0 015.5 3.5h7A2.6 2.6 0 0115.1 6.1v3.5a2.6 2.6 0 01-2.6 " +
            "2.6H8.3l-3.6 2.4v-2.4h-.2A2.6 2.6 0 012.9 9.6z",
    )

    /**
     * The project glyph — the design's `workbench`: three list rules and one
     * full-height rule.
     *
     * The design's markup, unchanged: `M3 4.6h6.6M3 9h6.6M3 13.4h4.2M13.4 3.6v10.8`
     */
    val Workbench: ImageVector = glyph(
        name = "PiNavWorkbench",
        pathData = "M3 4.6h6.6M3 9h6.6M3 13.4h4.2M13.4 3.6v10.8",
    )

    /**
     * The settings glyph — the design's `settings`: two slider rules, each with
     * its knob drawn as a stroked circle rather than a filled one.
     *
     * The design's rules, unchanged: `M2.8 6.2h5.4M11.4 6.2h3.8M2.8 11.8h3.4M9.4 11.8h5.8`.
     * Its two circles, `cx=9.9 cy=6.2 r=1.5` and `cx=7.6 cy=11.8 r=1.5`, are the
     * two half-arcs at the end of the path below — same centre, same radius, same
     * stroke.
     */
    val Settings: ImageVector = glyph(
        name = "PiNavSettings",
        pathData = "M2.8 6.2h5.4M11.4 6.2h3.8M2.8 11.8h3.4M9.4 11.8h5.8" +
            "M8.4 6.2a1.5 1.5 0 103 0a1.5 1.5 0 10-3 0" +
            "M6.1 11.8a1.5 1.5 0 103 0a1.5 1.5 0 10-3 0",
    )

    /**
     * Build one stroke-only glyph.
     *
     * `fill = null` is what makes these outlines instead of filled shapes. The
     * stroke colour here is a placeholder that can never be seen: [PiNavGlyph]
     * recolours the whole graphic through `ColorFilter.tint`, and the vector's
     * own tint is left unspecified so that filter is the only thing deciding the
     * colour.
     */
    private fun glyph(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).addPath(
            // The builder wants the parsed node list, not the string: `addPath`
            // is a member of `Builder` (importing it is a compile error), and the
            // sibling `PathData` helper also takes a `PathBuilder` lambda, not a
            // path string. `PathParser` is the only entry point that reads one.
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}

/**
 * Draw one [PiNavGlyphs] entry in a single flat [color].
 *
 * `Image` rather than `Icon`: these vectors carry their own stroke, and `Icon`
 * decides for itself whether to tint — it skips the colour filter when the
 * vector's tint is unspecified — so the same call would colour some glyphs and
 * leave others black depending on how they were built. Tinting here is one code
 * path for all three, and it is the only thing that sets their colour.
 */
@Composable
fun PiNavGlyph(vector: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Image(
        imageVector = vector,
        contentDescription = null,
        modifier = modifier,
        // The design draws the 18-unit grid into a 20-unit box (`<Icon s={20}/>`:
        // `width/height 20`, `viewBox 0 0 18 18`), so the glyph is scaled up to
        // fill the caller's box rather than drawn at its intrinsic 18dp and
        // centred — `ContentScale.Fit` is that scaling, and `None` would draw it
        // a size small.
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(color),
    )
}
