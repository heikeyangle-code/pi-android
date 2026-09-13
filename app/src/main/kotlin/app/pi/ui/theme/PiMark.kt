package app.pi.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The π glyph — the product's only drawing (`brand-spec.md` §1).
 *
 * ## It answers
 *
 * 「这片空白是 PI 自己的地方，还是内容没加载出来？」 A screen with no content
 * shows a mark that is *this* app's; pi's own TUI has no logo to transcribe, so the
 * mark is the launcher icon's (`app/src/main/res/drawable/ic_launcher_foreground.xml`):
 * one path, no strokes, no gradients, no rotation — exactly the usage the brand
 * spec allows (`brand-spec.md` §1「只作为 App 图标 / 顶部标识 / 空态标识使用」).
 *
 * ## Where it belongs, and where it does not
 *
 * Empty states and the boot surface, replacing the generic Material icon inside a
 * circle that those two screens carry today
 * (`components/PiCommon.kt:238-248` in `PiEmptyState`, `screens/BootScreen.kt:59-69`).
 * It is **not** a decorative pattern: do not tile it, outline it, or give it a
 * gradient. [tint] exists so a screen passes the token it already uses for the
 * surrounding text rather than a colour chosen here.
 *
 * ## Why an [ImageVector] instead of a `res/drawable`
 *
 * Compose draws `ImageVector`s, and a vector under `app/src/main/res/` would make
 * this file depend on AAPT2 for the sake of nine path commands. The 108×108
 * viewport is the launcher's, kept so the two spellings of the mark cannot drift.
 * A screen sizes the mark through [size]; the viewport never changes, or the glyph
 * stretches.
 */

/**
 * The 108×108 viewport the path was drawn in — the launcher icon's own geometry
 * transcribed, not a design choice.
 */
internal const val PI_MARK_VIEWPORT: Float = 108f

/**
 * The path data of the mark, the launcher's `pathData` verbatim.
 *
 * Kept as text so this file and the SVG can be read against each other; the
 * `ImageVector` below is the only thing built from it.
 */
internal const val PI_MARK_PATH: String = "M30,38 H78 V46 H71 V74 H63 V46 H45 V74 H37 V46 H30 Z"

/**
 * The mark's geometry, identical to `PI_MARK_PATH` and decoded once for the
 * process.
 *
 * `androidx.compose.ui.graphics.vector.PathParser` left the public API, so the
 * alternative to these eight explicit commands is a private class cast. A
 * transcript row republishes every 200 ms (`05 §3.3` 的性能红线) and an empty
 * state can be on screen for the whole of a cold start, so the path is built once
 * through `by lazy` and never re-parsed.
 *
 * Coordinate system: [PI_MARK_VIEWPORT] square. The shape is a crossbar
 * (`M30,38 → H78`) with two legs, i.e. a geometric π with square terminals.
 */
internal val PiMarkVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "PiMark",
        defaultWidth = PI_MARK_VIEWPORT.dp,
        defaultHeight = PI_MARK_VIEWPORT.dp,
        viewportWidth = PI_MARK_VIEWPORT,
        viewportHeight = PI_MARK_VIEWPORT,
    ).apply {
        // The fill is a placeholder: `Icon` tints the whole vector with the colour
        // the caller passes, so the black here is never drawn. (`brand-spec.md`'s
        // `#8ABEB7` is the launcher's fill, i.e. `accent`; a light-theme launcher
        // needs it too, which is why the value lives in the resource, not here.)
        path(fill = SolidColor(Color.Black)) {
            moveTo(30f, 38f)
            horizontalLineTo(78f)
            verticalLineTo(46f)
            horizontalLineTo(71f)
            verticalLineTo(74f)
            horizontalLineTo(63f)
            verticalLineTo(46f)
            horizontalLineTo(45f)
            verticalLineTo(74f)
            horizontalLineTo(37f)
            verticalLineTo(46f)
            horizontalLineTo(30f)
            close()
        }
    }.build()
}

/**
 * The mark at [size] dp, tinted [tint].
 *
 * The content description is null by default on purpose: on both screens that
 * should use it, the mark sits *above* the sentence that explains the screen
 * (`PiEmptyState`'s title, `BootScreen`'s heading), so announcing it would repeat
 * that sentence. Pass [label] only where the mark is the only thing on screen and
 * therefore the only thing that can identify the app to a screen reader.
 *
 * @param size the drawn edge. `06 §2` uses 34 in an empty state and 30 as the
 *   fallback icon; `05 §3.9` proposes it for the boot surface, where the launcher's
 *   own 108 dp is far too large.
 * @param tint the fill. There is no default: pi's palette has no "logo colour", so
 *   a caller that does not know which token it wants has to decide — `muted` in an
 *   empty state (`06 §2` 空态), `onSurfaceVariant` if it replaces today's icon in
 *   the same circle.
 */
@Composable
fun PiMark(
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Icon(
        imageVector = PiMarkVector,
        contentDescription = label,
        modifier = modifier.size(size),
        tint = tint,
    )
}
