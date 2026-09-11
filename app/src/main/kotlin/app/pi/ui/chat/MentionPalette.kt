package app.pi.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * The `@` mention list: the files fd offered for the token being typed.
 *
 * It is pi's editor autocomplete popup (`CombinedAutocompleteProvider`, the
 * `@` arm at `/root/pi-src/packages/tui/src/autocomplete.ts:299-311`) on a touch
 * screen, and each row carries the two facts pi shows: the base name as the label
 * and the path as the description (`autocomplete.ts:801-805`).
 *
 * Two things it deliberately does not do:
 *
 *  - **No empty state.** pi returns no suggestions at all when there is nothing to
 *    offer and the popup never appears (`autocomplete.ts:305`), so the caller hides
 *    this composable rather than drawing "no files" - an empty popup would claim
 *    the candidate list is a surface that can be empty, which it is not.
 *  - **No fall-through to send.** pi's `@` branch accepts the completion and stops;
 *    only the `/` branch falls through to submitting
 *    (`packages/tui/src/components/editor.ts:784-802`). Tapping a row here inserts
 *    the mention and nothing else.
 *
 * The height cap matches the `/` palette (`SlashPalette.kt`): on a phone the
 * composer must stay reachable while a list is open.
 */
@Composable
fun MentionPalette(
    candidates: List<PiFileMentions.Item>,
    onPick: (PiFileMentions.Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = PiShapes.card,
        tonalElevation = 2.dp,
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 260.dp).padding(vertical = 4.dp),
        ) {
            items(candidates, key = { it.value }) { item ->
                MentionPaletteRow(item = item, onClick = { onPick(item) })
            }
        }
    }
}

@Composable
private fun MentionPaletteRow(item: PiFileMentions.Item, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.description?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
