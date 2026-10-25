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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiSystemPalettePreset
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiTheme

/**
 * 「配色方案」的选择层：`system` 主题的终端调色板。
 *
 * ## 这一层在做什么（以及不做什么）
 *
 * 每一档只换**推导的输入** —— pi 的 ANSI 16 色（四档相同）加一个底色 —— 然后由
 * `deriveSystemPalette`（`PiSystemTheme.kt`）用它自己的对比度曲线解出 56 个 token。
 * 这里没有任何颜色计算：色块画的是这一档的**底色**（用户选的就是“终端是什么底色”），
 * 预览整套主题的活儿由选中之后的主界面自己完成。
 *
 * ## 为什么必须说明「只影响 system」
 *
 * `dark` / `light` 是写死的两套调色板，自定义主题文件是磁盘上的 token 表
 * （`PiThemeFiles`），两者都不经过 `PiSystemPaletteSource`，所以这一行改不动它们。
 * 不把这句话写在明面上，用户换完档只看到“有的主题没变”，会以为设置坏了。
 * 同一句还得说清明暗从哪来：`terminalAppearance`（`system-theme.ts:271-290`）按
 * 底色与前景的亮度判深浅，所以选「浅色底」会让 `system` 变成浅色主题，
 * 而手机自己的深色开关不参与这个判断。
 *
 * ## 写盘
 *
 * 选一档就是 [onSet] → `PiSettingsStore.write`（`app.*` 落到 `app-prefs.json`）→
 * 宿主 `PiSessionViewModel.onSettingWritten` → `refreshTheme` 重算整套主题，然后本层
 * 关闭。不再有“保存”按钮：这一行没有第二种状态，选了就是生效。
 */
@Composable
internal fun PiSystemPaletteSheet(
    /** 当前选中档的 id（设置里的原值；未知值由 [PiSystemPalettes.presetOf] 兜底）。 */
    currentWire: String,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentId = PiSystemPalettes.presetOf(currentWire).id
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
            Text(
                "配色方案",
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                "只影响 system 主题：dark / light 与自定义主题文件不受影响。" +
                    "system 的明暗由所选调色板的底色决定，不跟随系统的深浅色。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PiSettingsMetrics.sheetBodyMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                PiSystemPalettes.presets.forEach { preset ->
                    PiSystemPaletteOptionRow(
                        preset = preset,
                        selected = preset.id == currentId,
                        // 先写、再关：写下去的那一刻宿主就重算（`onSettingWritten` → `refreshTheme`），
                        // 关闭这一层不会取消那次重算（它在 `viewModelScope` 里）。
                        onClick = {
                            onSet(preset.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 一档一行：`✓/○ + 名称 + 一行说明 + 右侧底色块`，选中行铺 `selectedBg` 并带「当前」徽标。
 *
 * 几何照 `PiOptionPickerSheet` 的选项行（同一处 `06 §2` 的取值：行内 `14/10`、
 * `✓` 用等宽小字、选中填 `PiTheme.palette.selectedBg`），所以这两层在同一个设置栈里
 * 读起来是同一种行，而不是两套。
 */
@Composable
private fun PiSystemPaletteOptionRow(
    preset: PiSystemPalettePreset,
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
        Column(Modifier.weight(1f)) {
            Text(
                preset.label,
                style = PiTheme.text.prose,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                preset.description,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PiSystemPaletteSwatch(rgb = preset.background)
        if (selected) {
            PiSettingsBadge(label = "当前", tone = PiTheme.palette.accent)
        }
    }
}

/**
 * 右侧的底色块：画的是这一档的 `background`，所以“选哪一档 = 终端是什么底色”一眼可见。
 *
 * 带 1px 描边是必要的，不是装饰：浅色底（`#F5F5F5`）落在深色主题的 sheet 上会看不见，
 * 没有描边它就等于没画。
 */
@Composable
private fun PiSystemPaletteSwatch(rgb: Int) {
    val shape = RoundedCornerShape(PiSettingsMetrics.badgeDotRadius)
    Box(
        Modifier
            .size(PiSettingsMetrics.cardIconSize)
            .clip(shape)
            .background(swatchColor(rgb))
            .border(PiSettingsMetrics.hairline, MaterialTheme.colorScheme.outline, shape),
    )
}

/**
 * `0xRRGGBB` → 不透明 [Color]。
 *
 * 必须补 alpha：`PiAnsiPalette` / 本表存的是 `0xRRGGBB`（`PiSystemTheme.kt` 的约定），
 * 而 Compose 的 `Color(Int)` 把高位当 alpha —— 直接把 `0x1E1E1E` 交给它会得到一块
 * **全透明**的色块。同一条换算在 `PiSystemTheme.kt` 里叫 `composeColor`（private），
 * 这里只能是同一行表达式的第二处，注释指回它，避免有人以为这两个值不一样。
 */
private fun swatchColor(rgb: Int): Color = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFFL))
