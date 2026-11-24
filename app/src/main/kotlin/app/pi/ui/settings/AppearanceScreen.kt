package app.pi.ui.settings

import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiMonoFamily
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeLoader
import app.pi.ui.theme.PiThemeScope
import app.pi.ui.theme.PiTypographyProfile
import app.pi.ui.theme.contrastOn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * 现代 Material 3 外观设置专页：
 * - 顶部实时聊天预览卡（主题、字体、排版密度、字号、行距、时间戳、思考块状态即改即见）；
 * - 主题画廊（可视化色板卡片、跟随系统浅/深配对、一键导入 `.json` / 粘贴 JSON 并自动适配官方 Pi 1.6.0 Schema、自定义主题删除）；
 * - `system` 主题快捷底色圆点与自定义色盘；
 * - 全内联 1-tap 分段控制（阅读字体、排版密度、字号、行距、Mermaid 渲染、界面语言），告别繁琐的逐项弹窗；
 * - 彻底去除导入主题时冗长的“不支持字段/颜色回退”大段说明文字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    store: PiSettingsStore,
    agentDir: File,
    freshness: Int,
    contentPadding: PaddingValues,
    knownThemes: List<PiThemeEntry>,
    themeError: String?,
    onSettingWritten: (String) -> Unit,
    onBack: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()
    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    fun readStr(key: String, fallback: String): String =
        (store.read(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallback

    fun readBool(key: String, fallback: Boolean): Boolean =
        (store.read(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback

    val rawTheme = remember(store, freshness) { readStr("theme", "system") }
    val autoPair = remember(rawTheme) { parseThemePair(rawTheme) }
    val followSystem = autoPair != null
    val lightSlot = autoPair?.first ?: "light"
    val darkSlot = autoPair?.second ?: "dark"
    val fixedTheme = if (autoPair == null) rawTheme else if (systemDark) darkSlot else lightSlot

    var activePairSlot by remember { mutableStateOf(if (systemDark) PairSlot.Dark else PairSlot.Light) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showSystemPaletteSheet by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<PiThemeEntry?>(null) }

    val systemPaletteId = remember(store, freshness) { readStr(PiSystemPalettes.SETTING_KEY, PiSystemPalettes.DEFAULT_ID) }
    val systemPaletteBg = remember(store, freshness) { readStr(PiSystemPalettes.BACKGROUND_KEY, "#1E1E1E") }
    val systemPaletteSat = remember(store, freshness) {
        readStr(PiSystemPalettes.SATURATION_KEY, PiSystemPalettes.saturationWire(1.0))
    }

    val proseFont = remember(store, freshness) { PiTypographyProfile.proseFontOf(readStr("app.appearance.proseFont", "sans")) }
    val typography = remember(store, freshness) { PiTypographyProfile.presetOf(readStr("app.appearance.typography", "comfortable")) }
    val fontSize = remember(store, freshness) { PiTypographyProfile.fontSizeOf(readStr("app.appearance.fontSize", "normal")) }
    val lineHeight = remember(store, freshness) { PiTypographyProfile.lineHeightOf(readStr("app.appearance.lineHeight", "normal")) }
    val showTimestamps = remember(store, freshness) { readBool("app.appearance.showTimestamps", false) }
    val thinkingCollapsed = remember(store, freshness) { readBool("app.appearance.thinkingCollapsedByDefault", true) }
    val hideThinking = remember(store, freshness) { readBool("hideThinkingBlock", false) }
    val mermaidMode = remember(store, freshness) { readStr("markdown.mermaid", "streaming") }
    val language = remember(store, freshness) { readStr("app.appearance.language", "system") }

    val themeEntries = remember(knownThemes) {
        buildList {
            if (knownThemes.none { it.name == "system" }) add(PiThemeEntry("system", null, PiThemeScope.Builtin))
            if (knownThemes.none { it.name == "dark" }) add(PiThemeEntry("dark", null, PiThemeScope.Builtin))
            if (knownThemes.none { it.name == "light" }) add(PiThemeEntry("light", null, PiThemeScope.Builtin))
            knownThemes.forEach { entry ->
                if (none { it.name == entry.name }) add(entry)
            }
        }
    }

    fun writeStr(key: String, value: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    fun writeBool(key: String, value: Boolean) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    val usesSystemTheme = if (followSystem) {
        lightSlot == "system" || darkSlot == "system"
    } else {
        fixedTheme == "system"
    }

    val activeDisplayTheme = if (followSystem) {
        if (systemDark) darkSlot else lightSlot
    } else {
        fixedTheme
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("外观", "Appearance"),
            onBack = onBack,
            scrolled = scrolled,
            actions = {
                Surface(
                    onClick = { showImportSheet = true },
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = PiTheme.palette.accent,
                        )
                        Text(
                            tr("导入", "Import"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = 10.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. Hero WYSIWYG Live Preview Card
            item(key = "preview") {
                LiveAppearancePreviewCard(
                    activeThemeName = activeDisplayTheme,
                    showTimestamps = showTimestamps,
                    hideThinking = hideThinking,
                    thinkingCollapsed = thinkingCollapsed,
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }

            // 2. Theme Section (Follow System + Visual Gallery + System Palette Strip)
            item(key = "theme") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("主题", "Theme"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = PiTheme.palette.muted,
                        )
                        Text(
                            tr("${themeEntries.size} 套可用", "${themeEntries.size} available"),
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.dim,
                        )
                    }

                    if (!themeError.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                            shape = RoundedCornerShape(12.dp),
                            color = PiTheme.palette.toolErrorBg,
                        ) {
                            Text(
                                text = themeError,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Visual Theme Gallery
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PiSettingsMetrics.pageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(themeEntries, key = { it.name }) { entry ->
                            val isSelected = if (followSystem) {
                                val targetName = if (activePairSlot == PairSlot.Light) lightSlot else darkSlot
                                entry.name == targetName
                            } else {
                                entry.name == fixedTheme
                            }
                            ThemeGalleryCard(
                                entry = entry,
                                systemDark = systemDark,
                                freshness = freshness,
                                selected = isSelected,
                                onSelect = {
                                    if (followSystem) {
                                        val next = if (activePairSlot == PairSlot.Light) {
                                            "${entry.name}/$darkSlot"
                                        } else {
                                            "$lightSlot/${entry.name}"
                                        }
                                        writeStr("theme", next)
                                    } else {
                                        writeStr("theme", entry.name)
                                    }
                                },
                                onDelete = if (entry.scope == PiThemeScope.AgentDir || entry.scope == PiThemeScope.Project) {
                                    { deleteCandidate = entry }
                                } else {
                                    null
                                },
                            )
                        }
                        item(key = "import-card") {
                            ImportThemeGalleryTile(onClick = { showImportSheet = true })
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    // Follow System + System Palette Card
                    PiSettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (followSystem) {
                                        writeStr("theme", if (systemDark) darkSlot else lightSlot)
                                    } else {
                                        val nextLight = if (fixedTheme == "dark") "light" else fixedTheme
                                        val nextDark = if (fixedTheme == "light") "dark" else fixedTheme
                                        writeStr("theme", "$nextLight/$nextDark")
                                    }
                                }
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = PiSettingsMetrics.rowPaddingVertical,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("跟随系统浅/深色", "Follow System Light/Dark"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    tr("浅色与深色模式各指定一套主题", "Pick separate themes for light and dark modes"),
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(PiSpacing.inline))
                            PiSettingsSwitch(
                                checked = followSystem,
                                onCheckedChange = { on ->
                                    if (on) {
                                        val nextLight = if (fixedTheme == "dark") "light" else fixedTheme
                                        val nextDark = if (fixedTheme == "light") "dark" else fixedTheme
                                        writeStr("theme", "$nextLight/$nextDark")
                                    } else {
                                        writeStr("theme", if (systemDark) darkSlot else lightSlot)
                                    }
                                },
                            )
                        }

                        if (followSystem) {
                            PiSettingsHairline()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                        vertical = 10.dp,
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PairSlotChip(
                                    label = tr("浅色", "Light"),
                                    themeName = lightSlot,
                                    active = activePairSlot == PairSlot.Light,
                                    onClick = { activePairSlot = PairSlot.Light },
                                    modifier = Modifier.weight(1f),
                                )
                                PairSlotChip(
                                    label = tr("深色", "Dark"),
                                    themeName = darkSlot,
                                    active = activePairSlot == PairSlot.Dark,
                                    onClick = { activePairSlot = PairSlot.Dark },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        PiSettingsHairline()
                        SystemPaletteQuickRow(
                            activePresetId = systemPaletteId,
                            usesSystemTheme = usesSystemTheme,
                            onPickPreset = { presetId ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                store.write(PiSystemPalettes.SETTING_KEY, JsonPrimitive(presetId))
                                if (!usesSystemTheme) {
                                    store.write("theme", JsonPrimitive("system"))
                                    onSettingWritten("theme")
                                }
                                onSettingWritten(PiSystemPalettes.SETTING_KEY)
                            },
                            onOpenCustom = { showSystemPaletteSheet = true },
                        )
                    }
                }
            }

            // 3. Typography & Reading Section (All inline 1-tap selectors!)
            item(key = "typography") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    AppearanceSectionHeader(tr("阅读与排版", "Reading & Typography"))
                    PiSettingsCard {
                        // Reading Font cards (styled in their own FontFamily)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = 12.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                tr("阅读字体", "Reading Font"),
                                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FontChoiceTile(
                                    sample = tr("Aa 现代", "Aa Sans"),
                                    label = tr("无衬线", "Sans"),
                                    fontFamily = FontFamily.Default,
                                    selected = proseFont == PiTypographyProfile.FONT_SANS,
                                    onClick = { writeStr("app.appearance.proseFont", PiTypographyProfile.FONT_SANS) },
                                    modifier = Modifier.weight(1f),
                                )
                                FontChoiceTile(
                                    sample = tr("Aa 宋体", "Aa Serif"),
                                    label = tr("思源宋体", "Serif"),
                                    fontFamily = PiProseFamily,
                                    selected = proseFont == PiTypographyProfile.FONT_SERIF,
                                    onClick = { writeStr("app.appearance.proseFont", PiTypographyProfile.FONT_SERIF) },
                                    modifier = Modifier.weight(1f),
                                )
                                FontChoiceTile(
                                    sample = "Aa Mono",
                                    label = tr("等宽", "Mono"),
                                    fontFamily = PiMonoFamily,
                                    selected = proseFont == PiTypographyProfile.FONT_MONO,
                                    onClick = { writeStr("app.appearance.proseFont", PiTypographyProfile.FONT_MONO) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        PiSettingsHairline()

                        InlineSegmentRow(
                            title = tr("排版密度", "Density"),
                            options = listOf(
                                PiTypographyProfile.CLASSIC to tr("经典", "Classic"),
                                PiTypographyProfile.COMPACT to tr("紧凑", "Compact"),
                                PiTypographyProfile.COMFORTABLE to tr("舒适", "Cozy"),
                                PiTypographyProfile.LOOSE to tr("宽松", "Loose"),
                            ),
                            selected = typography,
                            onSelect = { writeStr("app.appearance.typography", it) },
                        )

                        PiSettingsHairline()

                        InlineSegmentRow(
                            title = tr("字号", "Font Size"),
                            options = listOf(
                                "small" to tr("小", "S"),
                                "normal" to tr("标准", "M"),
                                "large" to tr("大", "L"),
                                "xlarge" to tr("更大", "XL"),
                            ),
                            selected = fontSize,
                            onSelect = { writeStr("app.appearance.fontSize", it) },
                        )

                        PiSettingsHairline()

                        val isClassic = typography == PiTypographyProfile.CLASSIC
                        InlineSegmentRow(
                            title = tr("行距", "Line Height"),
                            subtitle = if (isClassic) tr("经典档保持固定行距", "Fixed in Classic preset") else null,
                            options = listOf(
                                "tight" to tr("紧", "Tight"),
                                "normal" to tr("标准", "Normal"),
                                "loose" to tr("松", "Loose"),
                            ),
                            selected = lineHeight,
                            enabled = !isClassic,
                            onSelect = { writeStr("app.appearance.lineHeight", it) },
                        )
                    }
                }
            }

            // 4. Chat Display & Rendering Section
            item(key = "display") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    AppearanceSectionHeader(tr("对话显示与渲染", "Chat Display & Rendering"))
                    PiSettingsCard {
                        AppearanceSwitchRow(
                            title = tr("显示时间戳", "Show Timestamps"),
                            subtitle = tr("消息旁显示时间，跨天插入日期分隔", "Show message times and day dividers"),
                            checked = showTimestamps,
                            onCheckedChange = { writeBool("app.appearance.showTimestamps", it) },
                        )
                        PiSettingsHairline()
                        AppearanceSwitchRow(
                            title = tr("思考块默认折叠", "Collapse Thinking by Default"),
                            subtitle = tr("收起为单行摘要，点击随时展开", "Show compact summary line; tap to expand"),
                            checked = thinkingCollapsed,
                            enabled = !hideThinking,
                            onCheckedChange = { writeBool("app.appearance.thinkingCollapsedByDefault", it) },
                        )
                        PiSettingsHairline()
                        AppearanceSwitchRow(
                            title = tr("隐藏思考块", "Hide Thinking Blocks"),
                            subtitle = tr("思考照常运行，仅不在对话流中展示", "Reasoning still runs, hidden from transcript"),
                            checked = hideThinking,
                            onCheckedChange = { writeBool("hideThinkingBlock", it) },
                        )
                        PiSettingsHairline()
                        InlineSegmentRow(
                            title = tr("Mermaid 图表渲染", "Mermaid Diagrams"),
                            options = listOf(
                                "streaming" to tr("流式实时", "Streaming"),
                                "final" to tr("完成后", "Final"),
                                "off" to tr("关闭", "Off"),
                            ),
                            selected = mermaidMode,
                            onSelect = { writeStr("markdown.mermaid", it) },
                        )
                    }
                }
            }

            // 5. Language Section
            item(key = "language") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    AppearanceSectionHeader(tr("语言", "Language"))
                    PiSettingsCard {
                        InlineSegmentRow(
                            title = tr("界面语言", "Display Language"),
                            options = listOf(
                                "system" to tr("跟随系统", "System"),
                                "zh" to "简体中文",
                                "en" to "English",
                            ),
                            selected = language,
                            onSelect = { writeStr(PiLocale.SETTING_KEY, it) },
                        )
                    }
                }
            }
        }
    }

    if (showSystemPaletteSheet) {
        PiSystemPaletteSheet(
            initialBackground = systemPaletteBg,
            initialSaturation = systemPaletteSat,
            onApply = { hex, saturationWire ->
                store.write(PiSystemPalettes.SETTING_KEY, JsonPrimitive(PiSystemPalettes.CUSTOM_ID))
                store.write(PiSystemPalettes.BACKGROUND_KEY, JsonPrimitive("#$hex"))
                store.write(PiSystemPalettes.SATURATION_KEY, JsonPrimitive(saturationWire))
                if (!usesSystemTheme) {
                    store.write("theme", JsonPrimitive("system"))
                    onSettingWritten("theme")
                }
                onSettingWritten(PiSystemPalettes.SETTING_KEY)
            },
            onDismiss = { showSystemPaletteSheet = false },
        )
    }

    if (showImportSheet) {
        ThemeImportSheet(
            agentDir = agentDir,
            systemDark = systemDark,
            onImported = { importedName ->
                showImportSheet = false
                if (followSystem) {
                    val next = if (activePairSlot == PairSlot.Light) {
                        "$importedName/$darkSlot"
                    } else {
                        "$lightSlot/$importedName"
                    }
                    store.write("theme", JsonPrimitive(next))
                } else {
                    store.write("theme", JsonPrimitive(importedName))
                }
                onSettingWritten("theme")
            },
            onDismiss = { showImportSheet = false },
        )
    }

    val pendingDelete = deleteCandidate
    if (pendingDelete != null) {
        PiSettingsDialog(
            onDismissRequest = { deleteCandidate = null },
            title = tr("删除主题「${pendingDelete.name}」", "Delete theme \"${pendingDelete.name}\""),
            body = tr("将删除本地主题文件，无法恢复。", "This removes the local theme file and cannot be undone."),
            confirmationLabel = tr("删除", "Delete"),
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { deleteCandidate = null },
            onConfirm = {
                val target = pendingDelete
                deleteCandidate = null
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { PiThemeLoader.deleteCustomTheme(target) }
                    if (deleted) {
                        if (fixedTheme == target.name && !followSystem) {
                            store.write("theme", JsonPrimitive("system"))
                        } else if (followSystem && (lightSlot == target.name || darkSlot == target.name)) {
                            val nextLight = if (lightSlot == target.name) "light" else lightSlot
                            val nextDark = if (darkSlot == target.name) "dark" else darkSlot
                            store.write("theme", JsonPrimitive("$nextLight/$nextDark"))
                        }
                        onSettingWritten("theme")
                    }
                }
            },
        )
    }
}

private enum class PairSlot { Light, Dark }

private fun parseThemePair(raw: String): Pair<String, String>? {
    val slash = raw.indexOf('/')
    if (slash <= 0 || raw.indexOf('/', slash + 1) != -1) return null
    val left = raw.substring(0, slash).trim()
    val right = raw.substring(slash + 1).trim()
    if (left.isEmpty() || right.isEmpty()) return null
    return left to right
}

@Composable
private fun AppearanceSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(
            start = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
            end = PiSettingsMetrics.pageHorizontal,
        ),
        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
        color = PiTheme.palette.muted,
    )
}

/** Real-time WYSIWYG preview reacting to theme, font family, density, font size, line height, timestamps & thinking. */
@Composable
private fun LiveAppearancePreviewCard(
    activeThemeName: String,
    showTimestamps: Boolean,
    hideThinking: Boolean,
    thinkingCollapsed: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val profile = PiTheme.typography
    val proseStyle = profile.styles.prose.copy(fontFamily = profile.proseFontFamily())
    val codeStyle = profile.styles.code

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.pageBg,
        border = BorderStroke(PiSpacing.hairline, palette.borderMuted),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(profile.blockSpacing.coerceAtMost(12.dp)),
        ) {
            // Header badges: active theme + resolved prose metrics
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
                        activeThemeName,
                        style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                        color = palette.text,
                    )
                }
                Text(
                    "${proseStyle.fontSize.value.toInt()}sp / ${proseStyle.lineHeight.value.toInt()}sp",
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                )
            }

            // User bubble
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (showTimestamps) {
                    Text(
                        "14:20",
                        style = PiTheme.text.monoSmall,
                        color = palette.dim,
                        modifier = Modifier.padding(end = 6.dp, bottom = 2.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.userMessageBg,
                ) {
                    Text(
                        tr("帮我优化一下主题解析器", "Optimize the theme parser"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = proseStyle.copy(fontSize = (proseStyle.fontSize.value - 1f).coerceAtLeast(12f).sp),
                        color = palette.userMessageText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Thinking block preview (reacts live to hideThinking & thinkingCollapsed)
            if (!hideThinking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = if (thinkingCollapsed) 14.dp else 28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.thinkingMedium),
                    )
                    if (thinkingCollapsed) {
                        Text(
                            tr("思考 4s ▸", "Thought for 4s ▸"),
                            style = PiTheme.text.meta,
                            color = palette.thinkingText,
                        )
                    } else {
                        Text(
                            tr(
                                "检查 theme-schema.js 规范，补齐官方明暗检测并移除冗余提示。",
                                "Checking theme-schema.js spec, aligning appearance detection and trimming verbose warnings.",
                            ),
                            style = PiTheme.text.meta,
                            color = palette.thinkingBodyOnCanvas,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Assistant prose + inline code
            val sampleProse = buildAnnotatedString {
                append(tr("已对齐官方 ", "Aligned with official "))
                withStyle(
                    SpanStyle(
                        fontFamily = PiMonoFamily,
                        fontSize = profile.inlineCodeSizeSp.sp,
                        color = palette.mdCode,
                        background = palette.borderMuted.copy(alpha = 0.38f),
                    ),
                ) {
                    append(" Pi 1.6.0 ")
                }
                append(tr(" 主题规范，支持实时预览与无损导入。", " themes with live preview and clean import."))
            }
            Text(
                text = sampleProse,
                style = proseStyle,
                color = palette.text,
            )

            // Mini syntax-highlighted code snippet
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = palette.cardBg,
                border = BorderStroke(PiSpacing.hairlineThin, palette.mdCodeBlockBorder.copy(alpha = 0.5f)),
            ) {
                val codeSample = buildAnnotatedString {
                    withStyle(SpanStyle(color = palette.syntaxKeyword)) { append("val ") }
                    withStyle(SpanStyle(color = palette.syntaxVariable)) { append("theme") }
                    withStyle(SpanStyle(color = palette.syntaxOperator)) { append(" = ") }
                    withStyle(SpanStyle(color = palette.syntaxFunction)) { append("loadTheme") }
                    withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append("(") }
                    withStyle(SpanStyle(color = palette.syntaxString)) { append("\"$activeThemeName\"") }
                    withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append(")") }
                }
                Text(
                    text = codeSample,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = codeStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ThemeGalleryCard(
    entry: PiThemeEntry,
    systemDark: Boolean,
    freshness: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val preview by produceState<Pair<PiPalette, Boolean>?>(
        initialValue = when (entry.name) {
            "dark" -> PiPalette.Dark to true
            "light" -> PiPalette.Light to false
            else -> null
        },
        key1 = entry.name,
        key2 = entry.path,
        key3 = if (entry.name == "system") freshness else systemDark,
    ) {
        value = withContext(Dispatchers.IO) {
            PiThemeLoader.previewPalette(entry, systemDark)
        }
    }

    val p = preview?.first ?: PiTheme.palette
    val cardShape = RoundedCornerShape(14.dp)
    val borderColor = if (selected) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 2.dp else PiSpacing.hairline

    Surface(
        onClick = onSelect,
        modifier = Modifier
            .width(132.dp)
            .height(106.dp),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Miniature UI swatch area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(p.pageBg)
                    .padding(8.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Mini user bubble right-aligned
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            Modifier
                                .width(52.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(p.userMessageBg),
                        )
                    }
                    // Mini assistant text lines + accent pill
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier
                                .width(76.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(p.text.copy(alpha = 0.85f)),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .width(30.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(p.accent),
                            )
                            Box(
                                Modifier
                                    .width(20.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(p.mdHeading),
                            )
                            Box(
                                Modifier
                                    .width(16.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(p.success),
                            )
                        }
                    }
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(PiTheme.palette.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = PiTheme.palette.contrastOn(PiTheme.palette.accent),
                        )
                    }
                } else if (onDelete != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(p.cardBg.copy(alpha = 0.85f))
                            .clickable(onClick = onDelete),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = tr("删除主题", "Delete theme"),
                            modifier = Modifier.size(13.dp),
                            tint = p.error,
                        )
                    }
                }
            }

            // Theme label footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.name,
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.monoSmall.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.scope != PiThemeScope.Builtin) {
                    Text(
                        text = entry.scope.label,
                        style = PiTheme.text.meta.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportThemeGalleryTile(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(104.dp)
            .height(106.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(PiSpacing.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.FileUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = PiTheme.palette.accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr("导入主题", "Import"),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                ".json",
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairSlotChip(
    label: String,
    themeName: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (active) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainer
    val border = if (active) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(if (active) 1.5.dp else PiSpacing.hairlineThin, border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = PiTheme.text.meta,
                color = if (active) PiTheme.palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = themeName,
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SystemPaletteQuickRow(
    activePresetId: String,
    usesSystemTheme: Boolean,
    onPickPreset: (String) -> Unit,
    onOpenCustom: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 11.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("system 主题底色", "System Theme Base"),
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    tr("从底色按官方算法自动推导全部 56 个颜色", "Derives all 56 official tokens from one base color"),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                onClick = onOpenCustom,
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = PiTheme.palette.accent,
                    )
                    Text(
                        tr("调色", "Tune"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiSystemPalettes.presets.forEach { preset ->
                val picked = usesSystemTheme && activePresetId == preset.id
                val bgColor = Color(0xFF000000L or (preset.background.toLong() and 0xFFFFFFL))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(
                            width = if (picked) 2.5.dp else 1.dp,
                            color = if (picked) PiTheme.palette.accent else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onPickPreset(preset.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = preset.label,
                            modifier = Modifier.size(15.dp),
                            tint = PiTheme.palette.contrastOn(bgColor),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FontChoiceTile(
    sample: String,
    label: String,
    fontFamily: FontFamily,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainer
    val border = if (selected) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(if (selected) 1.5.dp else PiSpacing.hairlineThin, border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = sample,
                style = PiTheme.text.prose.copy(
                    fontFamily = fontFamily,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = label,
                style = PiTheme.text.meta,
                color = if (selected) PiTheme.palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InlineSegmentRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 11.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                options.forEach { (wire, label) ->
                    val active = wire == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                            .border(
                                width = if (active) PiSpacing.hairlineThin else 0.dp,
                                color = if (active) PiTheme.palette.accent.copy(alpha = 0.55f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable(enabled = enabled) { onSelect(wire) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = PiTheme.text.meta.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.48f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(PiSpacing.inline))
        PiSettingsSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * 主题导入面板：支持选择 `.json` 文件或粘贴 JSON，实时预览色板，
 * 导入时自动规范化为官方 Pi 1.6.0 Schema 并静默兼容第三方主题，不弹大段警告文字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeImportSheet(
    agentDir: File,
    systemDark: Boolean,
    onImported: (themeName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawJson by remember { mutableStateOf("") }
    var fileNameHint by remember { mutableStateOf<String?>(null) }
    var nameOverride by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val previewResult = remember(rawJson, fileNameHint, systemDark) {
        val text = rawJson.trim()
        if (text.isEmpty()) null else PiThemeLoader.previewThemeJson(text, fileNameHint, systemDark)
    }
    val preview = previewResult?.getOrNull()

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = queryDisplayName(context, uri)?.removeSuffix(".json")
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("empty")
                    displayName to text
                }
            }
            loaded.onSuccess { (name, text) ->
                fileNameHint = name
                rawJson = text
                importError = null
            }.onFailure {
                importError = tr("无法读取所选文件", "Could not read selected file")
            }
        }
    }

    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    tr("导入主题", "Import Theme"),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    tr(
                        "选择 .json 文件或粘贴配色 JSON，将自动保存至 ~/.pi/agent/themes。",
                        "Pick a .json file or paste theme JSON. Saved cleanly to ~/.pi/agent/themes.",
                    ),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Quick actions: Pick file / Paste clipboard
            val actionShape = RoundedCornerShape(9.dp)
            val sheetPalette = PiTheme.palette
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { openDoc.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = actionShape,
                    color = sheetPalette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, sheetPalette.borderMuted),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = sheetPalette.accent, modifier = Modifier.size(16.dp))
                        Text(tr("选择 .json 文件", "Pick .json"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = sheetPalette.text)
                    }
                }
                Surface(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!clip.isNullOrBlank()) {
                            rawJson = clip
                            importError = null
                        } else {
                            importError = tr("剪贴板为空", "Clipboard is empty")
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = actionShape,
                    color = sheetPalette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, sheetPalette.borderMuted),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = sheetPalette.accent, modifier = Modifier.size(16.dp))
                        Text(tr("粘贴剪贴板", "Paste"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = sheetPalette.text)
                    }
                }
            }

            // Live color swatch preview when JSON is valid
            if (preview != null) {
                val p = preview.palette
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = p.pageBg,
                    border = BorderStroke(PiSpacing.hairline, p.borderMuted),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = nameOverride.trim().ifEmpty { preview.name },
                                style = PiTheme.text.mono.copy(fontWeight = FontWeight.SemiBold),
                                color = p.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (preview.dark) tr("深色主题", "Dark theme") else tr("浅色主题", "Light theme"),
                                style = PiTheme.text.meta,
                                color = p.muted,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(p.cardBg, p.userMessageBg, p.accent, p.mdHeading, p.success, p.error).forEach { c ->
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(0.5.dp, p.borderMuted, CircleShape),
                                )
                            }
                        }
                    }
                }
            }

            PiEditorField(
                value = nameOverride,
                onValueChange = { nameOverride = it; importError = null },
                modifier = Modifier.fillMaxWidth(),
                label = tr("主题名称（可选，默认读取 JSON 中的 name）", "Theme name (optional; defaults to JSON name)"),
                placeholder = preview?.name ?: "my-theme",
                height = PiNumberFieldHeight,
                container = sheetPalette.cardBg,
                idleBorder = sheetPalette.borderMuted,
            )

            PiEditorField(
                value = rawJson,
                onValueChange = { rawJson = it; importError = null },
                modifier = Modifier.fillMaxWidth(),
                label = tr("主题 JSON 内容", "Theme JSON"),
                placeholder = "{ \"name\": \"catppuccin\", \"colors\": { ... } }",
                singleLine = false,
                minHeight = 148.dp,
                maxLines = 10,
                contentPadding = PiFieldPaddingBlock,
                container = sheetPalette.cardBg,
                idleBorder = sheetPalette.borderMuted,
            )

            val shownError = importError ?: previewResult?.exceptionOrNull()?.message
            if (!shownError.isNullOrBlank()) {
                Text(
                    text = shownError,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = onDismiss,
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = sheetPalette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, sheetPalette.borderMuted),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tr("取消", "Cancel"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = sheetPalette.text)
                    }
                }
                val canImport = preview != null && !importing
                Surface(
                    onClick = {
                        importing = true
                        importError = null
                        val chosenName = nameOverride.trim().ifEmpty { preview?.name }
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                PiThemeLoader.importThemeJson(
                                    rawText = rawJson,
                                    agentDir = agentDir,
                                    overrideName = chosenName,
                                    systemDark = systemDark,
                                )
                            }
                            importing = false
                            res.onSuccess { imported ->
                                onImported(imported.name)
                            }.onFailure { err ->
                                importError = err.message ?: tr("导入失败", "Import failed")
                            }
                        }
                    },
                    enabled = canImport,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (canImport) sheetPalette.accent else sheetPalette.cardBg,
                    border = if (canImport) null else BorderStroke(PiSpacing.hairlineThin, sheetPalette.borderMuted),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            tr("导入并应用", "Import & Apply"),
                            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                            color = if (canImport) sheetPalette.contrastOn(sheetPalette.accent) else sheetPalette.muted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
