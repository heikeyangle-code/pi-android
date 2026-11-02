package app.pi.ui.render.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme

/**
 * The Compose **lightweight link card** (`§十一.3` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The URL model lives in `PiLinkCard.kt` (no network, no Open Graph, no invented
 * title/description/image). This file is only the row: a type label derived from
 * the path's extension, the host, the path, and a `↗` affordance.
 *
 * Deliberately absent, because none of it is in the URL:
 *
 * - no page title, description, site name or preview image;
 * - no favicon or remote asset — nothing in this file starts a request;
 * - no "safe/verified" styling. The card is a presentation of a string, not a
 *   claim about where it leads.
 *
 * [onOpen] is supplied by the caller so the card reuses whatever link handling
 * the transcript already has (in-app browser / external intent); the card itself
 * never launches anything. A URL the model refuses (not a URL, an unsupported
 * scheme, over the length bound) is rendered as the plain text the message
 * originally showed — [PiLinkPreview] is the safe entry point.
 */
@Composable
fun PiLinkPreview(
    url: String,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = remember(url) { (PiLinkCard.parse(url) as? LinkCardResult.Info)?.info }
    if (info != null) {
        PiLinkCardView(info = info, onOpen = onOpen, modifier = modifier)
    } else {
        Text(
            text = url,
            style = PiTheme.text.monoSmall,
            color = PiTheme.palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/** The card for an already-parsed link. */
@Composable
fun PiLinkCardView(
    info: PiLinkInfo,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.cardBg)
            .border(1.dp, palette.borderMuted.copy(alpha = 0.5f), shape)
            .clickable(onClickLabel = "打开链接") { onOpen(info.url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = info.kind.label,
            style = PiTheme.text.monoSmall,
            color = palette.accent,
            maxLines = 1,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = info.hostForDisplay,
                style = PiTheme.typography.styles.prose.copy(fontWeight = FontWeight.Medium),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = info.pathForDisplay,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "↗",
            style = PiTheme.text.monoSmall,
            color = palette.dim,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
