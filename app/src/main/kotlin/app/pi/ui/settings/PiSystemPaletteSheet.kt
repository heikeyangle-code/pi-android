package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiTheme
import kotlin.math.roundToInt

/**
 * 「系统主题」：选一个底色，其余 55 个 token 按 pi 的规则推导。
 *
 * 只产出一个输入 —— **底色**（外加推导函数的饱和度倍率），全部颜色由
 * `deriveSystemPalette` 算出来，与 pi 的 `system` 主题同一条规则。页面底色就是选的
 * 那个色，卡片 = 同色向白混 6%（对齐 pi 的 `#21252C → #282C34` 差值）。
 *
 * 应用时机：**只按「应用」时才写盘并切到 `system` 主题**。拖动滑杆只改这一页的
 * HSV / HEX 读数，不会顺手改掉用户原来的 dark/light 选择。
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

    // 滑杆改的是 HSV 本身，**不能**再走 `setColor`（RGB → HSV）：色相 360° 与 0° 是
    // 同一个红，饱和度 0 的灰、明度 0 的黑也会把色相读回 0 —— 那样拖到右端游标会跳回
    // 左端、拖到黑/灰再拖回来颜色就丢了。这里只把 HSV 换成新值，再由它算 HEX。
    fun setHsv(next: Triple<Float, Float, Float>) {
        hsv = next
        hexText = hexOf(hsvToRgb(next))
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
            Text(
                "底色决定其余颜色；滑杆只改这一页，按「应用」才写盘。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.rowGap))
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 顺序按“先选、再微调、再确认”排：常用底色 → 三条滑杆 → 强度 →
                // HEX → 应用。预览色带按用户要求删掉了，滑杆本身和预设色块就是预览。
                Text("常用底色", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap)) {
                    PiSystemPalettes.presets.forEach { preset ->
                        val selected = hexOf(preset.background) == hexText
                        val shape = RoundedCornerShape(PiSettingsMetrics.cardRadius)
                        Box(
                            Modifier
                                .size(PiSettingsMetrics.cardIconSize * 2)
                                .clip(shape)
                                .background(
                                    Color(0xFF000000L or (preset.background.toLong() and 0xFFFFFFL)),
                                )
                                .border(
                                    if (selected) PiSettingsMetrics.hairline * 2 else PiSettingsMetrics.hairline,
                                    if (selected) PiTheme.palette.accent else MaterialTheme.colorScheme.outline,
                                    shape,
                                )
                                .clickable { setColor(preset.background) },
                        )
                    }
                }
                Spacer(Modifier.height(PiSpacing.unit))

                ColourSlider(
                    label = "色相",
                    reading = "${hsv.first.roundToInt()}°",
                    colors = (0..6).map { hueColor(it * 60f) },
                    fraction = hsv.first / 360f,
                    onChange = { f -> setHsv(Triple(f * 360f, hsv.second, hsv.third)) },
                )
                ColourSlider(
                    label = "饱和度",
                    reading = "${(hsv.second * 100f).roundToInt()}%",
                    colors = listOf(greyOf(hsvToRgb(hsv)), hueColor(hsv.first)),
                    fraction = hsv.second,
                    onChange = { f -> setHsv(Triple(hsv.first, f, hsv.third)) },
                )
                ColourSlider(
                    label = "明度",
                    reading = "${(hsv.third * 100f).roundToInt()}%",
                    colors = listOf(Color.Black, hueColor(hsv.first), Color.White),
                    fraction = hsv.third,
                    onChange = { f -> setHsv(Triple(hsv.first, hsv.second, f)) },
                )

                Spacer(Modifier.height(PiSpacing.unit))

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

                Spacer(Modifier.height(PiSpacing.unit))

                Text("底色 HEX", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                PiEditorField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        PiSystemPalettes.parseHex(text)?.let { rgb -> hsv = rgbToHsv(rgb) }
                    },
                    placeholder = "#RRGGBB",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(PiSettingsMetrics.rowGap))
                Button(
                    enabled = PiSystemPalettes.parseHex(hexText) != null,
                    onClick = {
                        onApply(hexText.trim().removePrefix("#"), saturationWire)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("应用")
                }
                Spacer(Modifier.height(PiSpacing.unit))

            }
        }
    }
}

/**
 * 一条色相 / 饱和度 / 明度滑杆。
 *
 * 轨道渐变、游标白圈都由我们自己画，但**手势和布局全部交给 M3 的 `Slider`**：
 *
 *  - 游标由 `Slider` 沿轨道居中放置，不再手写 `offset`（旧版从轨道顶端量，偏了 4dp）；
 *  - 拖动 / 点按 / 两端都能到 0 和 1，由 `SliderState` 处理，不再和 tap/drag 两套
 *    `pointerInput` 互相吃事件；
 *  - 48dp 的触摸高度、无障碍和纵向滚动的手势仲裁也都是 `Slider` 自带的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColourSlider(
    label: String,
    reading: String,
    colors: List<Color>,
    fraction: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = PiSettingsMetrics.rowPaddingVertical)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reading, style = PiTheme.text.monoSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            thumb = { ColourThumb() },
            track = { ColourTrack(colors) },
        )
    }
}

/** 游标：18dp 白圈 + 1px 黑边，压在渐变轨道上。 */
@Composable
private fun ColourThumb() {
    Box(
        Modifier
            .size(PiSettingsMetrics.cardIconSize)
            .clip(CircleShape)
            .background(Color.White)
            .border(PiSettingsMetrics.hairline, Color.Black, CircleShape),
    )
}

/** 轨道：13dp 高的圆角色带（`chipHeight / 2`，与芯片同一档圆角）。 */
@Composable
private fun ColourTrack(colors: List<Color>) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(PiSettingsMetrics.chipHeight / 2)
            .clip(RoundedCornerShape(percent = 50))
            .background(Brush.horizontalGradient(colors)),
    )
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
