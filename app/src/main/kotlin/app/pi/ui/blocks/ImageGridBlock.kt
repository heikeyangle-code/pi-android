package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.rpc.PiImage
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * `message-images` (docs/pi-android-ui-spec.md §7.4): one image fills the width,
 * two to four make a 2x2 grid, more than four show a grid with a `+N` badge.
 * 12dp radius, tap to open the full-screen viewer.
 *
 * The cells are drawn as labelled placeholders: this app has no image-loading
 * dependency yet, and a placeholder that states the mime type is honest where a
 * blank box would not be. Wiring a decoder in is a one-line swap inside
 * [ImageCell].
 */
@Composable
fun ImageGridBlock(
    images: List<PiImage>,
    modifier: Modifier = Modifier,
    onImageClick: ((Int) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    val spacing = 6.dp

    if (images.size == 1) {
        ImageCell(
            image = images[0],
            index = 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            onClick = onImageClick,
        )
        return
    }

    val visible = if (images.size > MAX_GRID_IMAGES) MAX_GRID_IMAGES else images.size
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        var index = 0
        while (index < visible) {
            val remaining = visible - index
            val inRow = if (remaining >= 2) 2 else 1
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                for (slot in 0 until inRow) {
                    val position = index + slot
                    val overflow = if (position == visible - 1 && images.size > MAX_GRID_IMAGES) {
                        images.size - MAX_GRID_IMAGES
                    } else {
                        0
                    }
                    ImageCell(
                        image = images[position],
                        index = position,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        onClick = onImageClick,
                        overflow = overflow,
                    )
                }
                if (inRow == 1) Spacer(Modifier.weight(1f))
            }
            index += inRow
        }
    }
}

@Composable
private fun ImageCell(
    image: PiImage,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: ((Int) -> Unit)? = null,
    overflow: Int = 0,
) {
    val palette = PiTheme.palette
    Surface(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable(onClickLabel = "查看第 ${index + 1} 张图片") { onClick(index) }
            } else {
                Modifier
            },
        ),
        shape = PiShapes.cardInner,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.borderMuted.copy(alpha = 0.5f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "图片 ${index + 1}",
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                )
                Text(
                    text = image.mimeType.ifEmpty { "image" },
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }
            if (overflow > 0) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.text,
                )
            }
        }
    }
}

/** Spec cap: more than four images collapse into a grid with a `+N` badge. */
private const val MAX_GRID_IMAGES = 4
