package app.pi.ui.blocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.protocol.PiImage
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolDiff
import app.pi.ui.theme.PiTheme

/**
 * `edit` — unified single-card edit renderer matching official Pi (`core/tools/renderers/edit.ts`).
 *
 * When the transcript carries a paired [ToolDiff] for this call, the diff stats (`+N −M`) live
 * directly on the header and tapping the card expands the unified diff inline — no duplicate
 * second diff card. On failure, tapping expands the error output.
 */
@Composable
internal fun EditBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    pairedDiff: ToolDiff? = null,
    /** A tap on a returned image opens it full screen ([PiImageViewer]). */
    onImageClick: ((PiImage) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    var expanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }
    val command = remember(item.args) { toolCommandText(item.args) }
    val path = remember(item.args, pairedDiff) {
        argString(item.args, "file_path", "path")?.takeIf { it.isNotBlank() }
            ?: pairedDiff?.path.orEmpty()
    }
    val failed = state == ToolState.Failed
    val hasDiff = pairedDiff != null && pairedDiff.diffText.isNotEmpty()
    val expandable = failed || hasDiff
    val copyOutput = remember(pairedDiff, item.output) {
        pairedDiff?.diffText?.takeIf { it.isNotEmpty() } ?: item.output
    }
    val footer = remember(item.outputTruncated, state, hasDiff, PiLocale.isZh) {
        if (hasDiff && !item.outputTruncated) null else toolFooterText(item, state)
    }
    ToolActionMenu(command, copyOutput, null) {
        BlockColumn(modifier) {
            ToolCard(
                item,
                expanded,
                { expanded = !expanded },
                expandable = expandable,
            ) {
                ToolHeader(
                    item = item,
                    state = state,
                    title = "edit",
                    subject = listOf(toolPathPart(path, fallback = tr("文件", "file"))),
                    right = if (state == ToolState.Rejected) TOOL_REJECTED_READING else null,
                    trailing = if (pairedDiff != null && (pairedDiff.added > 0 || pairedDiff.removed > 0)) {
                        { DiffStatsBadges(pairedDiff.added, pairedDiff.removed) }
                    } else {
                        null
                    },
                    expanded = expanded,
                    expandable = expandable,
                )
                if (expanded) {
                    if (failed && item.output.isNotEmpty()) {
                        Text(text = item.output, style = PiTheme.text.monoSmall, color = palette.error)
                    }
                    if (hasDiff && pairedDiff != null) {
                        DiffBodyContent(item = pairedDiff)
                    }
                }
                ToolImages(item.images, onImageClick)
                ToolFooter(text = footer)
            }
        }
    }
}
