package app.pi.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * 图片取不到字节时画的占位：Markdown 图片与 HTML `<img>` 共用，和其它卡片同一套语言
 * （12 dp 圆角、6% 淡底、28% 边线）。
 *
 * 旧版是两行裸文字：「图片 / 图片地址：https://很长的整条 URL（当前无法显示）」，整条 URL 把
 * 一行撑成好几行，读者看到的是一坨地址而不是「这里本该有一张图」。现在：左边一个破图图标，
 * 右边 alt（没有就写「图片未能加载」）加一行缩短的来源（主机 + 路径，单行省略），整张卡点按
 * 复制完整地址。完整地址从不丢，只是不再占版面。
 */
@Composable
internal fun PiImageFailureCard(
    alt: String?,
    link: String?,
    altStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val full = link?.takeIf { it.isNotBlank() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(palette.mdCodeBlockBorder.copy(alpha = 0.06f))
            .border(BorderStroke(1.dp, palette.mdCodeBlockBorder.copy(alpha = 0.28f)), shape)
            .then(
                if (full != null) {
                    Modifier.clickable(onClickLabel = "复制图片地址") {
                        clipboard.setText(AnnotatedString(full))
                        copied = true
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "图片未能加载",
                style = altStyle,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    copied -> "已复制地址"
                    full == null -> "图片地址缺失"
                    else -> shortImageSource(full)
                },
                style = PiTheme.text.monoSmall,
                color = if (copied) palette.accent else palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 缩短来源：http(s) 只留「主机 + 路径」，`data:` 说明是内嵌数据，其它原样（单行省略由调用方做）。 */
internal fun shortImageSource(link: String): String {
    val text = link.trim()
    if (text.startsWith("data:", ignoreCase = true)) return "内嵌图片数据"
    val withoutScheme = text.substringAfter("://", text)
    return withoutScheme.removePrefix("www.").substringBefore('#').ifEmpty { text }
}
