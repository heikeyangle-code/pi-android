package app.pi.ui.blocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pi.rpc.PiImage
import app.pi.rpc.ToolCall
import app.pi.ui.theme.PiTheme

/**
 * `edit` — the call half of pi's edit renderer (`core/tools/renderers/edit.ts:171-237`).
 *
 * pi's card is `edit <path>` (`formatEditCall`, `:83-86`) followed by the diff its result
 * carried (`:108-111`: `result.details.diff` → `renderDiff`), or by the error text on
 * failure (`:97-106`). In this app the diff is already its own transcript row — the reducer
 * lifts `details.diff` out of the call and appends a [app.pi.rpc.ToolDiff] behind the card
 * (`rpc/.../Transcript.kt:1405-1442`), which `BlockRenderer` draws with `DiffBlock`. So this
 * block is exactly the half pi's renderer draws from the call: the title, and the error when
 * there is one.
 *
 * **The one thing this block deliberately does not do** is pi's preview diff. pi *computes*
 * it at render time by reading the target file and running the replacements in memory
 * (`renderers/edit.ts:184-193` → `computeEditsDiff`). The app has no channel to the
 * workspace's files from the render layer, and reading them itself would make the transcript
 * a second source of truth for what a file contains — the shape `docs/pi-sourced-lists.md`
 * documents twice over. The diff pi *did* send is the one that is drawn.
 *
 * `firstChangedLine` (`details.firstChangedLine`, `:75-76`) reaches the diff row already and
 * is not duplicated here.
 */
@Composable
internal fun EditBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    /** A tap on a returned image opens it full screen ([PiImageViewer]). */
    onImageClick: ((PiImage) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    val command = remember(item.args) { toolCommandText(item.args) }
    val path = remember(item.args) { argString(item.args, "file_path", "path").orEmpty() }
    val failed = state == ToolState.Failed
    // F31: the output's line count, scanned once per output. It used to be scanned here for
    // the footer's text; the count now goes to the header's reading instead, and the footer
    // has nothing left to print it into ([toolFooterText]).
    val lines = remember(item.output) { lineCount(item.output) }
    // Keyed on `lines` rather than on the whole `item.output` (which the other blocks use):
    // `lineCount` is exact about emptiness — 0 if and only if the result is empty — and that
    // is the only thing [toolFooterText] reads out of the output, so an Int key gives the same
    // value with fewer invalidations. `item.output` is still what feeds it, through `lines`.
    val footer = remember(lines, item.outputTruncated, state) {
        toolFooterText(item, state)
    }
    ToolActionMenu(command, item.output, null) {
        BlockColumn(modifier) {
            ToolCard(
                item,
                expanded,
                { expanded = !expanded },
            ) {
                ToolHeader(
                    item = item,
                    state = state,
                    title = "edit",
                    // pi's `formatEditCall` (`renderers/edit.js:51-54`):
                    //   `${fg("toolTitle", bold("edit"))} ${pathDisplay}`
                    // with `pathDisplay = renderToolPath(...)` → `fg("accent", …)`
                    // (`core/tools/render-utils.js:57-63`). `write` and `read` are the same shape.
                    subject = listOf(toolPathPart(path, fallback = "文件")),
                    right = toolHeaderReading(item, state, count = lines),
                    expanded = expanded,
                    expandable = failed,
                )
                if (expanded && failed) {
                    // pi's error branch (`renderers/edit.ts:97-106`): the result text, unless
                    // it is the preview's own error, which this app never has.
                    //
                    // The machine face, because this is pi's **tool result** rendered inside the
                    // tool card: rule #7 puts tool output in mono, and every other result body on
                    // these cards already is (`ToolBodyText`'s `mono`, `BlockChrome.MonoText`).
                    // It is deliberately not the `ErrorBlock`'s treatment — that block is a
                    // standalone error surface and its message is a sentence
                    // (`ProseText`), which stays as it is.
                    Text(text = item.output, style = PiTheme.text.monoSmall, color = palette.error)
                }
                ToolImages(item.images, onImageClick)

                ToolFooter(text = footer)
            }
        }
    }
}
