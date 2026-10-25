package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.deriveSystemPalette

/**
 * 「系统主题」：选一个底色，其余 55 个 token 按 pi 的规则推导。
 *
 * 只产出一个输入 —— **底色**（外加推导函数的饱和度倍率），全部颜色由
 * `deriveSystemPalette` 算出来，与 pi 的 `system` 主题同一条规则。页面底色就是选的
 * 那个色，卡片 = 同色向白混 6%（对齐 pi 的 `#21252C → #282C34` 差值）。
 *
 * 应用时机：**只按「应用」时才写盘并切到 `system` 主题**。拖动滑杆只在页内更新预览，
 * 不会顺手改掉用户原来的 dark/light 选择。
 */
@Composable
internal fun PiSystemPaletteSheet(
    initialBackground: String,
    initialSaturation: String,
    onApply: (hex: String, saturationWire: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = PiSystemPalettes.parseHex(initialBackground) ?: 0x1E1E1E
    var hsv by remember { mutableStateOf(rgbToHsv(initial)) }
    var hexText by remember { mutableStateOf(hexOf(hsvToRgb(hsv))) }
    var saturationWire by remember { mutableStateOf(initialSaturation) }
    fun setColor(rgb: Int) {
        hexText = hexOf(rgb)
        hsv = rgbToHsv(rgb)
    }

    // 页内预览：真实推导一次（56 token），所以看到的就是应用后的样子。拖动时每帧
    // 重算一次（本机约 2 ms），只影响这几个色块，不触发全 App 重组。
    val preview: PiPalette = remember(hexText, saturationWire) {
        val hex = hexOf(PiSystemPalettes.parseHex(hexText) ?: hsvToRgb(hsv))
        deriveSystemPalette(
            PiSystemPalettes.paletteOf(PiSystemPalettes.CUSTOM_ID, hex),
            PiSystemPalettes.saturationOf(saturationWire),
        )
    }

    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text("系统主题", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            // 这一页比其它编辑器高：预览 + 三条滑杆 + HEX + 强度 + 常用底色。
            // 不套 `sheetBodyMax`（420）那层上限，让弹层按内容长高；真的超过一屏时
            // 外层 ModalBottomSheet 仍会把它撑到全高，这张 Column 内部再滚动。
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 预览四块等宽铺满：固定 52dp 的方块在小屏上会挤成一团，用 weight 平分。
                Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap)) {
                    Swatch("页面", preview.pageBg, Modifier.weight(1f))
                    Swatch("卡片", preview.cardBg, Modifier.weight(1f))
                    Swatch("文字", preview.text, Modifier.weight(1f))
                    Swatch("强调", preview.accent, Modifier.weight(1f))
                }
                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))

                ColourSlider(
                    label = "色相",
                    colors = (0..6).map { hueColor(it * 60f) },
                    fraction = hsv.first / 360f,
                    onChange = { f -> setColor(hsvToRgb(Triple(f * 360f, hsv.second, hsv.third))) },
                )
                ColourSlider(
                    label = "饱和度",
                    colors = listOf(greyOf(hsvToRgb(hsv)), hueColor(hsv.first)),
                    fraction = hsv.second,
                    onChange = { f -> setColor(hsvToRgb(Triple(hsv.first, f, hsv.third))) },
                )
                ColourSlider(
                    label = "明度",
                    colors = listOf(Color.Black, hueColor(hsv.first), Color.White),
                    fraction = hsv.third,
                    onChange = { f -> setColor(hsvToRgb(Triple(hsv.first, hsv.second, f))) },
                )

                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PiEditorField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            PiSystemPalettes.parseHex(text)?.let { rgb -> hsv = rgbToHsv(rgb) }
                        },
                        placeholder = "#RRGGBB",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(PiSettingsMetrics.rowGap))
                    TextButton(
                        enabled = PiSystemPalettes.parseHex(hexText) != null,
                        onClick = {
                            onApply(hexText.trim().removePrefix("#"), saturationWire)
                            onDismiss()
                        },
                    ) { Text("应用") }
                }

                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                Text("色彩强度", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap)) {
                    PiSystemPalettes.saturations.forEach { (wire, _) ->
                        PiSettingsChip(
                            text = PiSystemPalettes.saturationLabel(wire),
                            onClick = { saturationWire = wire },
                            active = wire == saturationWire,
                        )
                    }
                }

                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                Text("常用底色", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap)) {
                    PiSystemPalettes.presets.forEach { preset ->
                        Box(
                            Modifier
                                .size(PiSettingsMetrics.chipHeight)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    Color(0xFF000000L or (preset.background.toLong() and 0xFFFFFFL)),
                                )
                                .border(
                                    PiSettingsMetrics.hairline,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(percent = 50),
                                )
                                .clickable { setColor(preset.background) },
                        )
                    }
                }

            }
        }
    }
}

@Composable
private fun Swatch(label: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PiSettingsMetrics.cardRadius)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PiSettingsMetrics.chipHeight * 2)
                .clip(shape)
                .background(color)
                .border(PiSettingsMetrics.hairline, MaterialTheme.colorScheme.outline, shape),
        )
        Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
        Text(label, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 一条渐变滑杆：点哪是哪、按住拖动也行。
 *
 * 没有用 M3 的 Slider：它的轨道是单色 + 主色进度，表达不了「色相」这种连续渐变；
 * 这一页唯一的交互就是选色，所以轨道本身就是色卡。
 */
@Composable
private fun ColourSlider(
    label: String,
    colors: List<Color>,
    fraction: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = PiSettingsMetrics.rowPaddingVertical)) {
        Text(label, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth
            var trackWidth by remember { mutableStateOf(0f) }
            fun fractionAt(offset: Offset): Float =
                if (trackWidth <= 0f) 0f else (offset.x / trackWidth).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PiSettingsMetrics.chipHeight)
                    .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
                    .background(Brush.horizontalGradient(colors))
                    .onSizeChanged { size -> trackWidth = size.width.toFloat() }
                    .pointerInput(colors) {
                        detectTapGestures { offset -> onChange(fractionAt(offset)) }
                    }
                    .pointerInput(colors) {
                        detectDragGestures { change, _ -> onChange(fractionAt(change.position)) }
                    },
            ) {
                val thumb = PiSettingsMetrics.cardIconSize
                Box(
                    Modifier
                        // 游标要在轨道内：`width * f - 半径` 在 f=1 时会把一半推出屏幕外，
                        // 改成「可移动距离 × f」，两端都贴边不越界。
                        .offset(x = (width - thumb) * fraction.coerceIn(0f, 1f))
                        .size(thumb)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White)
                        .border(PiSettingsMetrics.hairline, Color.Black, RoundedCornerShape(percent = 50)),
                )
            }
        }
        Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
    }
}

/** HSV（h 0..360、s/v 0..1）→ `0xRRGGBB`。 */
private fun hsvToRgb(hsv: Triple<Float, Float, Float>): Int {
    val h = ((hsv.first % 360f) + 360f) % 360f
    val s = hsv.second.coerceIn(0f, 1f)
    val v = hsv.third.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val rgb = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(value: Float): Int = ((value + m) * 255f).toInt().coerceIn(0, 255)
    return (channel(rgb.first) shl 16) or (channel(rgb.second) shl 8) or channel(rgb.third)
}

/** `0xRRGGBB` → HSV。 */
private fun rgbToHsv(rgb: Int): Triple<Float, Float, Float> {
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val s = if (max == 0f) 0f else delta / max
    return Triple((h + 360f) % 360f, s, max)
}

private fun hexOf(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

/** 纯色相（s=1、v=1），滑杆渐变用。 */
private fun hueColor(hue: Float): Color =
    Color(0xFF000000L or (hsvToRgb(Triple(hue, 1f, 1f)).toLong() and 0xFFFFFFL))

/** 同一色相但去掉饱和度的灰，用来画饱和度轨道左端。 */
private fun greyOf(rgb: Int): Color {
    val hsv = rgbToHsv(rgb)
    return Color(0xFF000000L or (hsvToRgb(Triple(hsv.first, 0f, hsv.third)).toLong() and 0xFFFFFFL))
}
