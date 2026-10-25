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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiTheme

/**
 * 「配色方案」的选择层：`system` 主题的终端输入（底色 + 饱和度）。
 *
 * 只换**推导的输入**：ANSI 16 色始终是 pi 的默认值，改的是底色与饱和度倍率，然后由
 * `deriveSystemPalette` 解出 56 个 token。没有任何颜色计算在这里。
 *
 * 选一档不等于“只改一个主题文件”：宿主会把 `theme` 一并切到 `system`
 * （`PiSettingEditorHost` 的这一支），否则默认主题 `dark`/`light` 不经过这条推导，
 * 用户会以为设置坏了。
 */
@Composable
internal fun PiSystemPaletteSheet(
    currentWire: String,
    currentBackground: String,
    currentSaturation: String,
    onSetPreset: (id: String) -> Unit,
    onSetCustom: (hex: String) -> Unit,
    onSetSaturation: (wire: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentId = if (currentWire == PiSystemPalettes.CUSTOM_ID &&
        PiSystemPalettes.parseHex(currentBackground) != null
    ) {
        PiSystemPalettes.CUSTOM_ID
    } else {
        PiSystemPalettes.presetOf(currentWire).id
    }
    var hex by remember { mutableStateOf(currentBackground) }
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
            Text("配色方案", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PiSettingsMetrics.sheetBodyMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                PiSystemPalettes.presets.forEach { preset ->
                    OptionRow(
                        label = preset.label,
                        rgb = preset.background,
                        selected = preset.id == currentId,
                        onClick = {
                            onSetPreset(preset.id)
                            onDismiss()
                        },
                    )
                }

                // 自定义底色：输入 #RRGGBB，应用即切到 custom 档。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (currentId == PiSystemPalettes.CUSTOM_ID) {
                                PiTheme.palette.selectedBg
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(
                            start = PiSettingsMetrics.pageHorizontal,
                            end = PiSettingsMetrics.pageHorizontal,
                            top = PiSettingsMetrics.rowPaddingVertical,
                            bottom = PiSettingsMetrics.rowPaddingVertical,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
                ) {
                    Text(
                        if (currentId == PiSystemPalettes.CUSTOM_ID) "✓" else "○",
                        style = PiTheme.text.monoSmall,
                        color = if (currentId == PiSystemPalettes.CUSTOM_ID) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            PiTheme.palette.muted
                        },
                    )
                    Text("自定义底色", style = PiTheme.text.prose, color = MaterialTheme.colorScheme.onSurface)
                    PiEditorField(
                        value = hex,
                        onValueChange = { hex = it },
                        placeholder = "#RRGGBB",
                        modifier = Modifier.weight(1.2f),
                    )
                    TextButton(
                        enabled = PiSystemPalettes.parseHex(hex) != null,
                        onClick = {
                            onSetCustom(hex.trim().removePrefix("#"))
                            onDismiss()
                        },
                    ) { Text("应用") }
                }

                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                Text("饱和度", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
                Row(horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap)) {
                    PiSystemPalettes.saturations.forEach { (wire, _) ->
                        PiSettingsChip(
                            text = PiSystemPalettes.saturationLabel(wire),
                            onClick = { onSetSaturation(wire) },
                            active = wire == currentSaturation,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    rgb: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) PiTheme.palette.selectedBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.rowPaddingVertical,
                bottom = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Text(
            if (selected) "✓" else "○",
            style = PiTheme.text.monoSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else PiTheme.palette.muted,
        )
        Text(label, style = PiTheme.text.prose, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Swatch(rgb)
    }
}

@Composable
private fun Swatch(rgb: Int) {
    val shape = RoundedCornerShape(PiSettingsMetrics.badgeDotRadius)
    Box(
        Modifier
            .size(PiSettingsMetrics.cardIconSize)
            .clip(shape)
            .background(Color(0xFF000000L or (rgb.toLong() and 0xFFFFFFL)))
            .border(PiSettingsMetrics.hairline, MaterialTheme.colorScheme.outline, shape),
    )
}
