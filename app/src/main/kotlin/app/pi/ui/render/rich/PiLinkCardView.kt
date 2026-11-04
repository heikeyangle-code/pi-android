package app.pi.ui.render.rich

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

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

/**
 * The card for an already-parsed link.
 *
 * 与代码块 / 树 / CSV 同一套卡片语言：12 dp 圆角、`mdCodeBlockBorder` 的 6% 淡底 + 28% 边线，
 * 类型用左侧的淡紫胶囊（与语言胶囊同款）。点按打开；**长按复制链接** —— 卡片替换了
 * 一行可选中的 URL 文字，所以必须保留"拿到这条链接原文"的办法。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PiLinkCardView(
    info: PiLinkInfo,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.mdCodeBlockBorder.copy(alpha = 0.06f))
            .border(1.dp, palette.mdCodeBlockBorder.copy(alpha = 0.28f), shape)
            .combinedClickable(
                onClickLabel = "打开链接",
                onClick = { onOpen(info.url) },
                onLongClickLabel = "复制链接",
                onLongClick = {
                    clipboard.setText(AnnotatedString(info.url))
                    copied = true
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = info.kind.label,
            style = PiTheme.text.monoSmall,
            color = palette.accent,
            maxLines = 1,
            modifier = Modifier
                .background(palette.accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
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
            text = if (copied) "已复制" else "↗",
            style = PiTheme.text.monoSmall,
            color = if (copied) palette.accent else palette.muted,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
