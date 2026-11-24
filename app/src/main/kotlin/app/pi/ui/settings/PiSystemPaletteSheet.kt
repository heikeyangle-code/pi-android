package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.components.PiDivider
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSheetActionBar
import app.pi.ui.components.PiSheetChromeMetrics
import app.pi.ui.components.PiSlider
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiTheme
import kotlin.math.roundToInt

/**
 * 「系统主题」调色弹层（Studio 重绘版，零原生 M3 Slider / Button）：
 * 选一个底色，其余 55 个 token 由 `deriveSystemPalette` 实时推导；仅点击「应用」才落盘。
 */
@Composable
internal fun PiSystemPaletteSheet(
    initialBackground: String,
    initialSaturation: String,
    onApply: (hex: String, saturationWire: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val initial = PiSystemPalettes.parseHex(initialBackground) ?: 0x1E1E1E
    var hsv by remember { mutableStateOf(rgbToHsv(initial)) }
    var hexText by remember { mutableStateOf(hexOf(hsvToRgb(hsv))) }
    var saturationWire by remember { mutableStateOf(initialSaturation) }

    fun setColor(rgb: Int) {
        hexText = hexOf(rgb)
        hsv = rgbToHsv(rgb)
    }

    fun setHsv(next: Triple<Float, Float, Float>) {
        hsv = next
        hexText = hexOf(hsvToRgb(next))
    }

    val hexValid = PiSystemPalettes.parseHex(hexText) != null
    val previewRgb = hsvToRgb(hsv)
    val previewPalette = remember(previewRgb, saturationWire) {
        val input = PiSystemPalettes.paletteOf(PiSystemPalettes.CUSTOM_ID, hexOf(previewRgb))
        app.pi.ui.theme.deriveSystemPalette(input, PiSystemPalettes.saturationOf(saturationWire))
    }

    PiSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(tr("系统调色", "System Palette"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                tr(
                    "底色自动推导整套 55 项主题色；滑杆仅实时预览，点「应用」才保存。",
                    "Base color derives all 55 theme tokens. Sliders preview locally until you tap Apply.",
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Live derived preview card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(previewPalette.pageBg)
                    .border(
                        PiSpacing.hairlineThin,
                        previewPalette.borderMuted.copy(alpha = 0.8f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                tr("实时推导预览", "Live Derived Preview"),
                                style = PiTheme.text.meta,
                                color = previewPalette.muted,
                            )
                            Text(
                                hexOf(previewRgb),
                                style = PiTheme.text.monoSmall,
                                color = previewPalette.text,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                previewPalette.accent,
                                previewPalette.success,
                                previewPalette.warning,
                                previewPalette.error,
                                previewPalette.mdHeading,
                            ).forEach { swatch ->
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(swatch),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(previewPalette.userMessageBg)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                tr("用户消息", "User"),
                                style = PiTheme.text.meta,
                                color = previewPalette.userMessageText,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(previewPalette.cardBg)
                                .border(PiSpacing.hairlineThin, previewPalette.borderMuted.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "val theme = \"system\"",
                                style = PiTheme.text.monoSmall,
                                color = previewPalette.text,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(tr("常用底色", "Preset Backgrounds"), style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PiSystemPalettes.presets.forEach { preset ->
                        val selected = hexOf(preset.background).equals(hexText.trim(), ignoreCase = true)
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(shape)
                                .background(Color(0xFF000000L or (preset.background.toLong() and 0xFFFFFFL)))
                                .border(
                                    width = if (selected) 2.dp else PiSpacing.hairlineThin,
                                    color = if (selected) PiTheme.palette.accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                    shape = shape,
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    setColor(preset.background)
                                },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                StudioColorSlider(
                    label = tr("色相", "Hue"),
                    reading = "${hsv.first.roundToInt()}°",
                    colors = (0..6).map { hueColor(it * 60f) },
                    fraction = hsv.first / 360f,
                    thumbColor = hueColor(hsv.first),
                    onChange = { f -> setHsv(Triple(f * 360f, hsv.second, hsv.third)) },
                )
                StudioColorSlider(
                    label = tr("饱和度", "Saturation"),
                    reading = "${(hsv.second * 100f).roundToInt()}%",
                    colors = listOf(greyOf(hsvToRgb(hsv)), hueColor(hsv.first)),
                    fraction = hsv.second,
                    thumbColor = Color(0xFF000000L or (previewRgb.toLong() and 0xFFFFFFL)),
                    onChange = { f -> setHsv(Triple(hsv.first, f, hsv.third)) },
                )
                StudioColorSlider(
                    label = tr("明度", "Brightness"),
                    reading = "${(hsv.third * 100f).roundToInt()}%",
                    colors = listOf(Color.Black, hueColor(hsv.first), Color.White),
                    fraction = hsv.third,
                    thumbColor = Color(0xFF000000L or (previewRgb.toLong() and 0xFFFFFFL)),
                    onChange = { f -> setHsv(Triple(hsv.first, hsv.second, f)) },
                )

                Spacer(Modifier.height(12.dp))

                Text(tr("色彩强度", "Color Intensity"), style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PiSystemPalettes.saturations.forEach { (wire, _) ->
                        PiSettingsChip(
                            text = PiSystemPalettes.saturationLabel(wire),
                            onClick = { saturationWire = wire },
                            active = wire == saturationWire,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                PiEditorField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        PiSystemPalettes.parseHex(text)?.let { rgb -> hsv = rgbToHsv(rgb) }
                    },
                    label = tr("底色 HEX", "Background HEX"),
                    placeholder = "#1E1E1E",
                    container = PiSheetChromeMetrics.sheetCardBg,
                    modifier = Modifier.fillMaxWidth(),
                    leading = {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF000000L or (previewRgb.toLong() and 0xFFFFFFL)))
                                .border(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp)),
                        )
                    },
                )
                if (!hexValid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("请输入有效的 6 位十六进制色值，例如 #1E1E1E。", "Enter a valid 6-digit hex color, e.g. #1E1E1E."),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.error,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            PiDivider()
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            PiSheetActionBar {
                PiSecondaryButton(
                    label = tr("取消", "Cancel"),
                    onClick = onDismiss,
                )
                PiPrimaryButton(
                    label = tr("应用", "Apply"),
                    enabled = hexValid,
                    onClick = {
                        onApply(hexText.trim().removePrefix("#"), saturationWire)
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

@Composable
private fun StudioColorSlider(
    label: String,
    reading: String,
    colors: List<Color>,
    fraction: Float,
    thumbColor: Color,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reading, style = PiTheme.text.monoSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        PiSlider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = onChange,
            trackHeight = 8.dp,
            thumbRadius = 10.dp,
            trackBrush = Brush.horizontalGradient(colors),
            thumbFillColor = thumbColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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

private fun hueColor(hue: Float): Color =
    Color(0xFF000000L or (hsvToRgb(Triple(hue, 1f, 1f)).toLong() and 0xFFFFFFL))

private fun greyOf(rgb: Int): Color {
    val hsv = rgbToHsv(rgb)
    return Color(0xFF000000L or (hsvToRgb(Triple(hsv.first, 0f, hsv.third)).toLong() and 0xFFFFFFL))
}
