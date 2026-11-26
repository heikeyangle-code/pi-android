package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.ui.components.PiDivider
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSheetActionBar
import app.pi.ui.components.PiSheetChromeMetrics
import app.pi.ui.components.PiSlider
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiImportedTheme
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThemeAccent
import app.pi.ui.theme.PiThemeCanvas
import app.pi.ui.theme.PiThemeLoader
import app.pi.ui.theme.PiThemeMasterPreset
import app.pi.ui.theme.PiThemeMood
import app.pi.ui.theme.PiThemeStudio
import app.pi.ui.theme.contrastOn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 现代主题工坊弹层（Theme Studio）：
 * - 顶部 12 套国际顶级大师双极预设一键套用（Claude 暖陶 / Vesper 暮光 / Flexoki 墨纸 /
 *   Kanagawa 浮世绘 / Nord 极地冰 / Ayu 暖金 / Rosé Pine 暮松 / Catppuccin / Tokyo Night /
 *   Gruvbox 复古 / Everforest 暮林 / 极简石墨），自动随深浅模式切对应明暗版本（共 24 款）；
 * - 下方支持画布质感、8 枚精选强调色、0°–360° OKHSL 自由色相滑杆与 3 档色彩调性任意微调；
 * - 支持单套保存或「浅+深双版一键配对」，写出 100% 官方 Pi 1.6.0 Schema 主题文件到
 *   `~/.pi/agent/themes/<slug>.json`，与桌面端 Pi CLI/TUI 完全互通。
 */
@Composable
internal fun PiThemeStudioSheet(
    agentDir: File,
    initialDark: Boolean,
    onSaved: (PiImportedTheme) -> Unit,
    onSavedPair: ((light: PiImportedTheme, dark: PiImportedTheme) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isDark by remember { mutableStateOf(initialDark) }
    var activePreset by remember { mutableStateOf<PiThemeMasterPreset?>(PiThemeStudio.masterPresets.first()) }
    var selectedCanvas by remember {
        mutableStateOf(PiThemeStudio.masterPresets.first().canvasFor(initialDark))
    }
    var selectedAccent by remember {
        mutableStateOf<PiThemeAccent?>(PiThemeStudio.accents.firstOrNull { it.id == "terracotta" })
    }
    var accentHue by remember { mutableDoubleStateOf(PiThemeStudio.masterPresets.first().accentHue) }
    var monochrome by remember { mutableStateOf(PiThemeStudio.masterPresets.first().monochrome) }
    var mood by remember { mutableStateOf(PiThemeStudio.masterPresets.first().mood) }

    var themeName by remember {
        mutableStateOf(PiThemeStudio.masterPresets.first().nameFor(initialDark))
    }
    var userEditedName by remember { mutableStateOf(false) }
    var saveDualPair by remember { mutableStateOf(onSavedPair != null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun applyMasterPreset(preset: PiThemeMasterPreset, dark: Boolean) {
        activePreset = preset
        selectedCanvas = preset.canvasFor(dark)
        accentHue = preset.accentHue
        monochrome = preset.monochrome
        mood = preset.mood
        selectedAccent = PiThemeStudio.accents.firstOrNull {
            it.monochrome == preset.monochrome && (it.monochrome || kotlin.math.abs(it.hue - preset.accentHue) < 2.0)
        }
        if (!userEditedName) {
            themeName = preset.nameFor(dark)
        }
    }

    fun syncAutoName(canvas: PiThemeCanvas, accent: PiThemeAccent?, hue: Double) {
        if (!userEditedName) {
            themeName = PiThemeStudio.suggestedName(canvas, accent, hue)
        }
    }

    val previewPalette = remember(selectedCanvas, accentHue, monochrome, mood) {
        PiThemeStudio.synthesize(
            canvas = selectedCanvas,
            accentHue = accentHue,
            monochrome = monochrome,
            mood = mood,
        )
    }

    val hueTrackColors = remember(isDark, mood) {
        PiThemeStudio.hueTrackColors(dark = isDark, mood = mood)
    }

    val canvases = if (isDark) PiThemeStudio.darkCanvases else PiThemeStudio.lightCanvases
    val canSave = themeName.trim().isNotEmpty() && !saving

    PiSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(
                tr("主题工坊", "Theme Studio"),
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(
                    "一键选用顶级预设或自由微调 OKHSL 色相，自动通过 WCAG AA+ 校验并存为标准 Pi 主题。",
                    "Start from iconic master presets or tune freely in OKHSL with WCAG AA+ contrast, saved as portable Pi theme JSON.",
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Real-time WYSIWYG preview card
            StudioPreviewCard(
                palette = previewPalette,
                title = themeName.trim().ifEmpty { selectedCanvas.slugEn },
            )

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 390.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 0. Iconic Master Presets + Dark/Light Polarity Switch
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("大师预设", "Master Presets"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PiSettingsChip(
                                text = tr("深色", "Dark"),
                                active = isDark,
                                onClick = {
                                    if (!isDark) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isDark = true
                                        val p = activePreset
                                        if (p != null) {
                                            applyMasterPreset(p, dark = true)
                                        } else {
                                            val next = PiThemeStudio.darkCanvases.first()
                                            selectedCanvas = next
                                            syncAutoName(next, selectedAccent, accentHue)
                                        }
                                    }
                                },
                            )
                            PiSettingsChip(
                                text = tr("浅色", "Light"),
                                active = !isDark,
                                onClick = {
                                    if (isDark) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isDark = false
                                        val p = activePreset
                                        if (p != null) {
                                            applyMasterPreset(p, dark = false)
                                        } else {
                                            val next = PiThemeStudio.lightCanvases.first()
                                            selectedCanvas = next
                                            syncAutoName(next, selectedAccent, accentHue)
                                        }
                                    }
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PiThemeStudio.masterPresets.forEach { preset ->
                            val picked = activePreset?.id == preset.id
                            val pCanvas = preset.canvasFor(isDark)
                            val pDot = PiThemeStudio.presetDotColor(preset, isDark)
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    applyMasterPreset(preset, dark = isDark)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (picked) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(
                                    width = if (picked) 1.5.dp else PiSpacing.hairlineThin,
                                    color = if (picked) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(pCanvas.pageBg)
                                            .border(1.dp, pCanvas.borderMuted, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(pDot),
                                        )
                                    }
                                    Text(
                                        text = preset.label,
                                        style = PiTheme.text.meta.copy(
                                            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                // 1. Curated Tactile Canvases
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("画布质感", "Canvas Surface"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        canvases.forEach { canvas ->
                            val active = canvas.id == selectedCanvas.id
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    activePreset = null
                                    selectedCanvas = canvas
                                    syncAutoName(canvas, selectedAccent, accentHue)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(
                                    width = if (active) 1.5.dp else PiSpacing.hairlineThin,
                                    color = if (active) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(canvas.pageBg)
                                            .border(1.dp, canvas.borderMuted, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(canvas.cardBg),
                                        )
                                    }
                                    Text(
                                        text = canvas.label,
                                        style = PiTheme.text.meta.copy(
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Curated Designer Accents + Calibrated OKHSL Hue Fine-Tune
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("主题强调色", "Accent Color"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (monochrome) {
                                tr("无色极简", "Monochrome")
                            } else {
                                "${Math.round(((accentHue % 360.0) + 360.0) % 360.0)}°"
                            },
                            style = PiTheme.text.monoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PiThemeStudio.accents.forEach { swatch ->
                            val picked = selectedAccent?.id == swatch.id
                            val dotColor = PiThemeStudio.swatchColor(swatch, dark = isDark, mood = mood)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                        .border(
                                            width = if (picked) 2.dp else 1.dp,
                                            color = if (picked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            activePreset = null
                                            selectedAccent = swatch
                                            accentHue = swatch.hue
                                            monochrome = swatch.monochrome
                                            syncAutoName(selectedCanvas, swatch, swatch.hue)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (picked) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = swatch.label,
                                            modifier = Modifier.size(14.dp),
                                            tint = PiTheme.palette.contrastOn(dotColor),
                                        )
                                    }
                                }
                                Text(
                                    text = swatch.label,
                                    style = PiTheme.text.meta.copy(fontSize = 10.5.sp),
                                    color = if (picked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    // Smooth OKHSL hue slider (gamut-locked so every angle stays refined)
                    PiSlider(
                        value = ((((accentHue % 360.0) + 360.0) % 360.0) / 360.0).toFloat(),
                        onValueChange = { fraction ->
                            val nextHue = (fraction * 360.0).coerceIn(0.0, 360.0)
                            activePreset = null
                            accentHue = nextHue
                            monochrome = false
                            selectedAccent = null
                            syncAutoName(selectedCanvas, null, nextHue)
                        },
                        trackHeight = 8.dp,
                        thumbRadius = 10.dp,
                        trackBrush = Brush.horizontalGradient(hueTrackColors),
                        thumbFillColor = previewPalette.accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 3. Mood (Minimal / Balanced / Vibrant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("色彩调性", "Tone"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PiThemeMood.entries.forEach { option ->
                            val active = option == mood
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    mood = option
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(
                                    width = if (active) 1.5.dp else PiSpacing.hairlineThin,
                                    color = if (active) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = option.label,
                                        style = PiTheme.text.meta.copy(
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = if (active) PiTheme.palette.accent else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Portable Theme Name
                PiEditorField(
                    value = themeName,
                    onValueChange = {
                        themeName = it
                        userEditedName = true
                        saveError = null
                    },
                    label = tr("主题名称（存为 ~/.pi/agent/themes/*.json）", "Theme Name (~/.pi/agent/themes/*.json)"),
                    placeholder = "Claude Dark",
                    container = PiSheetChromeMetrics.sheetCardBg,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (onSavedPair != null) {
                    val pairNames = remember(activePreset, themeName, selectedCanvas.dark) {
                        val preset = activePreset
                        if (preset != null && !userEditedName) {
                            preset.lightName to preset.darkName
                        } else {
                            val base = themeName.trim()
                                .replace(Regex("\\s*(Dark|Light|Day|Night)$", RegexOption.IGNORE_CASE), "")
                                .ifBlank { "Custom" }
                            "$base Light" to "$base Dark"
                        }
                    }
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            saveDualPair = !saveDualPair
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = PiSheetChromeMetrics.sheetCardBg,
                        border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = tr("同时生成浅/深双版并自动配对", "Save Light + Dark pair & auto-switch"),
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (saveDualPair) {
                                        tr("将保存「${pairNames.first}」+「${pairNames.second}」并随系统切换", "Saves \"${pairNames.first}\" + \"${pairNames.second}\" for system auto-switch")
                                    } else {
                                        tr("仅保存当前预览的这一套主题", "Saves only the currently previewed theme")
                                    },
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            PiSettingsSwitch(
                                checked = saveDualPair,
                                onCheckedChange = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    saveDualPair = it
                                },
                            )
                        }
                    }
                }

                if (saveError != null) {
                    Text(
                        text = saveError!!,
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            PiDivider()
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            PiSheetActionBar {
                PiSecondaryButton(
                    label = tr("取消", "Cancel"),
                    onClick = onDismiss,
                )
                PiPrimaryButton(
                    label = when {
                        saving -> tr("保存中…", "Saving…")
                        saveDualPair && onSavedPair != null -> tr("保存双版并应用", "Save Pair & Apply")
                        else -> tr("保存并应用", "Save & Apply")
                    },
                    enabled = canSave,
                    onClick = {
                        saving = true
                        saveError = null
                        val targetName = themeName.trim()
                        val targetDark = selectedCanvas.dark
                        val targetPalette = previewPalette
                        val presetSnap = activePreset
                        val editedSnap = userEditedName
                        val hueSnap = accentHue
                        val monoSnap = monochrome
                        val moodSnap = mood
                        val canvasSnap = selectedCanvas
                        val pairCallback = onSavedPair
                        scope.launch {
                            if (saveDualPair && pairCallback != null) {
                                val lightCanvas = if (canvasSnap.dark) {
                                    presetSnap?.lightCanvas ?: PiThemeStudio.counterpartCanvas(canvasSnap)
                                } else {
                                    canvasSnap
                                }
                                val darkCanvas = if (canvasSnap.dark) {
                                    canvasSnap
                                } else {
                                    presetSnap?.darkCanvas ?: PiThemeStudio.counterpartCanvas(canvasSnap)
                                }
                                val (lightName, darkName) = if (presetSnap != null && !editedSnap) {
                                    presetSnap.lightName to presetSnap.darkName
                                } else {
                                    val base = targetName
                                        .replace(Regex("\\s*(Dark|Light|Day|Night)$", RegexOption.IGNORE_CASE), "")
                                        .ifBlank { "Custom" }
                                    "$base Light" to "$base Dark"
                                }
                                val pairResult = withContext(Dispatchers.IO) {
                                    val lightPalette = PiThemeStudio.synthesize(lightCanvas, hueSnap, monoSnap, moodSnap)
                                    val darkPalette = PiThemeStudio.synthesize(darkCanvas, hueSnap, monoSnap, moodSnap)
                                    val savedLight = PiThemeLoader.saveCustomTheme(lightName, false, lightPalette, agentDir)
                                        .getOrElse { return@withContext Result.failure(it) }
                                    val savedDark = PiThemeLoader.saveCustomTheme(darkName, true, darkPalette, agentDir)
                                        .getOrElse { return@withContext Result.failure(it) }
                                    Result.success(savedLight to savedDark)
                                }
                                saving = false
                                pairResult.onSuccess { (savedLight, savedDark) ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pairCallback(savedLight, savedDark)
                                }.onFailure { err ->
                                    saveError = err.message ?: tr("主题保存失败", "Failed to save theme")
                                }
                            } else {
                                val result = withContext(Dispatchers.IO) {
                                    PiThemeLoader.saveCustomTheme(
                                        name = targetName,
                                        dark = targetDark,
                                        palette = targetPalette,
                                        agentDir = agentDir,
                                    )
                                }
                                saving = false
                                result.onSuccess { saved ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSaved(saved)
                                }.onFailure { err ->
                                    saveError = err.message ?: tr("主题保存失败", "Failed to save theme")
                                }
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

@Composable
private fun StudioPreviewCard(
    palette: PiPalette,
    title: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.pageBg)
            .border(PiSpacing.hairlineThin, palette.borderMuted, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                    Text(
                        text = title,
                        style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.mdHeading,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(palette.accent, palette.success, palette.warning, palette.error).forEach { swatch ->
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(swatch),
                        )
                    }
                }
            }

            // User bubble
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(9.dp))
                    .background(palette.userMessageBg)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    tr("按官方 SDK 同步主题色", "Sync theme via official SDK"),
                    style = PiTheme.text.meta,
                    color = palette.userMessageText,
                )
            }

            // Tool success row with diff counts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.toolSuccessBg)
                    .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "edit · theme.mjs",
                    style = PiTheme.text.monoSmall,
                    color = palette.toolTitle,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("+42", style = PiTheme.text.monoSmall, color = palette.toolDiffAdded)
                    Text("-18", style = PiTheme.text.monoSmall, color = palette.toolDiffRemoved)
                }
            }

            // Code block sample (syntax + plain ink)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.cardBg)
                    .border(PiSpacing.hairlineThin, palette.mdCodeBlockBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = palette.syntaxKeyword)) { append("val ") }
                            withStyle(SpanStyle(color = palette.syntaxFunction)) { append("theme ") }
                            withStyle(SpanStyle(color = palette.syntaxOperator)) { append("= ") }
                            withStyle(SpanStyle(color = palette.syntaxType)) { append("Theme") }
                            withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append("(") }
                            withStyle(SpanStyle(color = palette.syntaxString)) { append("\"studio\"") }
                            withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append(")") }
                        },
                        style = PiTheme.text.monoSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "// plain code · AA+ contrast",
                        style = PiTheme.text.monoSmall,
                        color = palette.mdCodeBlock,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
