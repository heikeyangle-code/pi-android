package app.pi.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.rpc.UserMessage
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * `user-message` (docs/pi-android-ui-spec.md §7.4): a full-width container in
 * `userMessageBg`, 16dp radius, 14dp padding, with any images below the text and
 * the turn's timestamp once in the bottom-right corner.
 */
@Composable
fun UserMessageBlock(
    item: UserMessage,
    modifier: Modifier = Modifier,
    onImageClick: ((Int) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    BlockColumn(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PiShapes.card,
            color = palette.userMessageBg,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (item.text.isNotEmpty()) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.userMessageText,
                    )
                }
                if (item.images.isNotEmpty()) {
                    ImageGridBlock(
                        images = item.images,
                        modifier = Modifier.fillMaxWidth(),
                        onImageClick = onImageClick,
                    )
                }
                Text(
                    text = formatClock(item.ts),
                    modifier = Modifier.align(Alignment.End),
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }
        }
    }
}
