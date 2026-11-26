package app.pi.ui.render

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.pi.i18n.tr
import app.pi.ui.render.rich.FenceInfo
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import kotlinx.coroutines.delay

/** 音视频链接：按扩展名认（去掉 query / fragment），只认 http(s)。 */
internal enum class PiMediaKind { Audio, Video }

private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr")
private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "m3u8")

internal fun piMediaKind(url: String?): PiMediaKind? {
    val u = url?.trim() ?: return null
    if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return null
    val path = u.substringBefore('#').substringBefore('?')
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in AUDIO_EXT -> PiMediaKind.Audio
        in VIDEO_EXT -> PiMediaKind.Video
        else -> null
    }
}

/** 整段只是 `[文字](媒体URL)` 或裸媒体 URL 时取出 URL；否则 null。 */
internal fun piMediaFromParagraph(text: String): String? {
    val t = text.trim()
    if (t.isEmpty() || t.length > 2048) return null
    val m = LINK.matchEntire(t)
    val url = if (m != null) m.groupValues[1] else if (t.none { it.isWhitespace() }) t else return null
    return url.takeIf { piMediaKind(it) != null }
}

private val LINK = Regex("""^!?\[[^\]]*]\(\s*<?(\S+?)>?(?:\s+"[^"]*")?\s*\)$""")

@Composable
internal fun PiMediaCard(url: String, kind: PiMediaKind, modifier: Modifier = Modifier) {
    val palette = PiTheme.palette
    val uriHandler = LocalUriHandler.current
    PiRichCardFrame(
        code = url,
        info = FenceInfo(
            language = if (kind == PiMediaKind.Audio) "audio" else "video",
            title = null,
            attributes = emptyMap(),
            raw = "",
        ),
        palette = palette,
        menu = listOf(PiCardMenuItem(tr("在外部打开", "Open externally")) { runCatching { uriHandler.openUri(url) } }),
    ) {
        Box(modifier.fillMaxWidth().padding(8.dp)) {
            if (kind == PiMediaKind.Audio) PiAudioBody(url) else PiVideoBody(url)
        }
    }
}

private fun fmt(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun PlayButton(playing: Boolean, size: Int = 40, onClick: () -> Unit) {
    val palette = PiTheme.palette
    val iconColor = palette.contrastOn(palette.accent)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(palette.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size((size * 0.42f).dp)) {
            if (playing) {
                val barW = this.size.width * 0.27f
                val barH = this.size.height * 0.78f
                val top = (this.size.height - barH) / 2f
                val radius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
                drawRoundRect(
                    color = iconColor,
                    topLeft = androidx.compose.ui.geometry.Offset(this.size.width * 0.16f, top),
                    size = androidx.compose.ui.geometry.Size(barW, barH),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = iconColor,
                    topLeft = androidx.compose.ui.geometry.Offset(this.size.width * 0.57f, top),
                    size = androidx.compose.ui.geometry.Size(barW, barH),
                    cornerRadius = radius,
                )
            } else {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(this@Canvas.size.width * 0.24f, this@Canvas.size.height * 0.14f)
                    lineTo(this@Canvas.size.width * 0.86f, this@Canvas.size.height * 0.50f)
                    lineTo(this@Canvas.size.width * 0.24f, this@Canvas.size.height * 0.86f)
                    close()
                }
                drawPath(path = path, color = iconColor)
            }
        }
    }
}

@Composable
private fun PiAudioBody(url: String) {
    val palette = PiTheme.palette
    val holder = remember { arrayOfNulls<MediaPlayer>(1) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var dur by remember { mutableIntStateOf(0) }

    DisposableEffect(url) {
        onDispose { holder[0]?.release(); holder[0] = null }
    }
    LaunchedEffect(playing) {
        while (playing) {
            holder[0]?.let { pos = runCatching { it.currentPosition }.getOrDefault(pos) }
            delay(250)
        }
    }

    fun toggle() {
        val p = holder[0]
        if (p != null && !loading) {
            runCatching { if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true } }
            return
        }
        if (loading) return
        failed = false
        loading = true
        val mp = MediaPlayer()
        holder[0] = mp
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build(),
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { loading = false; dur = it.duration; it.start(); playing = true }
            mp.setOnCompletionListener { playing = false; pos = 0; runCatching { it.seekTo(0) } }
            mp.setOnErrorListener { m, _, _ ->
                loading = false; playing = false; failed = true
                m.release(); holder[0] = null; true
            }
            mp.prepareAsync()
        }.onFailure { loading = false; failed = true; mp.release(); holder[0] = null }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayButton(playing) { toggle() }
        Box(Modifier.weight(1f)) {
            if (failed) {
                Text(
                    tr("无法播放（网络或格式不支持），可从菜单外部打开", "Unable to play (network or format unsupported); open externally from menu"),
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                )
            } else {
                app.pi.ui.components.PiSlider(
                    value = if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = { f ->
                        if (dur > 0) { pos = (f * dur).toInt(); runCatching { holder[0]?.seekTo(pos) } }
                    },
                    enabled = dur > 0,
                    activeColor = palette.accent,
                )
            }
        }
        Text(
            if (loading) tr("加载中…", "Loading…") else "${fmt(pos)} / ${fmt(dur)}",
            style = PiTheme.text.monoSmall, color = palette.dim,
        )
    }
}

@Composable
private fun PiVideoBody(url: String) {
    var started by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val view = remember { arrayOfNulls<VideoView>(1) }
    val palette = PiTheme.palette

    DisposableEffect(url) {
        onDispose { view[0]?.stopPlayback(); view[0] = null }
    }

    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (started && !failed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        view[0] = this
                        setOnPreparedListener { mp ->
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) aspect = mp.videoWidth.toFloat() / mp.videoHeight
                            start(); playing = true
                        }
                        setOnCompletionListener { playing = false }
                        setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
                        setVideoURI(Uri.parse(url))
                    }
                },
            )
        }
        if (failed) {
            Text(
                tr("无法播放（网络或格式不支持），可从菜单外部打开", "Unable to play (network or format unsupported); open externally from menu"),
                style = PiTheme.text.monoSmall,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(12.dp),
            )
        } else {
            // 盖在上面的点击层：点一下播放/暂停；只消费点按，滚动照常穿过。
            Box(
                Modifier.fillMaxSize().clickable {
                    val v = view[0]
                    if (!started) { started = true }
                    else if (v != null) { if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true } }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (!playing) PlayButton(false, size = 52) {}
            }
        }
    }
}
