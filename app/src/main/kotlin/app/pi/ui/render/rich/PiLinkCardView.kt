package app.pi.ui.render.rich

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * The Compose **lightweight link card** (`§十一.3` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * Strictly URL-derived (no network, no Open Graph, no invented title/description/image),
 * styled in the app's unified minimalist card chrome (`palette.cardBg` + hairline border
 * + crisp Material vector glyph + subtle copy/open affordances).
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PiLinkCardView(
    info: PiLinkInfo,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(14.dp)
    val iconShape = RoundedCornerShape(10.dp)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    val iconBg = remember(palette) {
        lerp(palette.cardBg, palette.mdLink, 0.11f)
    }
    val tagBg = remember(palette) {
        lerp(palette.cardBg, palette.text, 0.06f)
    }
    val displayHost = remember(info.hostForDisplay) {
        info.hostForDisplay.removePrefix("www.")
    }
    val subtitle = remember(info) {
        val rawPath = info.pathForDisplay
        when {
            rawPath == "/" -> "${info.scheme}://${info.hostForDisplay}"
            else -> rawPath
        }
    }
    val extTag = remember(info.fileExtension, info.kind) {
        info.fileExtension
            ?.takeIf { it.isNotEmpty() && info.kind != LinkKind.WebPage }
            ?.uppercase()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.cardBg)
            .border(0.5.dp, palette.borderMuted.copy(alpha = 0.56f), shape)
            .combinedClickable(
                onClickLabel = tr("打开链接", "Open link"),
                onClick = { onOpen(info.url) },
                onLongClickLabel = tr("复制链接", "Copy link"),
                onLongClick = {
                    clipboard.setText(AnnotatedString(info.url))
                    copied = true
                },
            )
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(iconShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = linkKindIcon(info.kind),
                contentDescription = info.kind.label,
                tint = palette.mdLink,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = displayHost,
                    style = PiTheme.typography.styles.prose.copy(
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (extTag != null) {
                    Text(
                        text = extTag,
                        style = PiTheme.text.monoSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = palette.muted,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tagBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = if (copied) tr("已复制链接", "Link copied") else subtitle,
                style = PiTheme.text.monoSmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
                color = if (copied) palette.accent else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = tr("复制链接", "Copy link"),
                    ) {
                        clipboard.setText(AnnotatedString(info.url))
                        copied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                    contentDescription = tr("复制链接", "Copy link"),
                    tint = if (copied) palette.accent else palette.muted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = tr("打开链接", "Open link"),
                    tint = palette.dim,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

private fun linkKindIcon(kind: LinkKind): ImageVector = when (kind) {
    LinkKind.WebPage -> Icons.Outlined.Language
    LinkKind.Image -> Icons.Outlined.Image
    LinkKind.Document -> Icons.Outlined.Description
    LinkKind.Archive -> Icons.Outlined.FolderZip
    LinkKind.Code -> Icons.Outlined.Code
    LinkKind.Video -> Icons.Outlined.PlayCircle
    LinkKind.Audio -> Icons.Outlined.Audiotrack
    LinkKind.Other -> Icons.Outlined.Link
}
