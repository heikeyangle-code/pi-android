package app.pi.ui.settings

import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiFontFeatures
import app.pi.ui.theme.PiGeistMonoFamily
import app.pi.ui.theme.PiJetBrainsMonoFamily
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiPlexSansFamily
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiSystemPalettes
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThemeEntry
import app.pi.ui.theme.PiThemeLoader
import app.pi.ui.theme.PiThemeScope
import app.pi.ui.theme.PiTypographyProfile
import app.pi.ui.theme.PiWenKaiFamily
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
    val codeFont = remember(store, freshness) { PiTypographyProfile.codeFontOf(readStr("app.appearance.codeFont", "jetbrains")) }
    val codeLigatures = remember(store, freshness) { readBool("app.appearance.codeLigatures", true) }
    val textSizeSp = remember(store, freshness) { PiTypographyProfile.textSizeOf(readStr("app.appearance.fontSize", "16")) }
    val density = remember(store, freshness) { PiTypographyProfile.densityOf(readStr("app.appearance.typography", PiTypographyProfile.COMFORTABLE)) }
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

    val activeProfile = PiTheme.typography
    val lhRatioText = remember(activeProfile.proseLineRatio) {
        String.format(java.util.Locale.US, "%.2f×", activeProfile.proseLineRatio)
    }
    val codeSizeLabel = remember(activeProfile.styles.code.fontSize.value) {
        formatSpCompact(activeProfile.styles.code.fontSize.value)
    }
    val showStickyPreview by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
    }
    val thinkingDisplay = when {
        hideThinking -> "hidden"
        thinkingCollapsed -> "collapsed"
        else -> "expanded"
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("外观", "Appearance"),
            onBack = onBack,
            scrolled = scrolled,
            actions = {
                app.pi.ui.components.PiSecondaryButton(
                    label = tr("导入", "Import"),
                    leadingIcon = Icons.Outlined.FileUpload,
                    compact = true,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = { showImportSheet = true },
                )
            },
        )

        // Sticky live specimen bar: docks right under the top bar once the Hero Preview Card scrolls away,
        // giving immediate eye-level feedback while tuning fonts, size, and reading density below.
        AnimatedVisibility(
            visible = showStickyPreview,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            StickyAppearancePreviewBar(
                activeThemeName = activeDisplayTheme,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PiSettingsMetrics.pageHorizontal,
                        vertical = 6.dp,
                    ),
            )
        }

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
                                    style = PiType.rowTitle,
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
                    AppearanceSectionHeader(tr("字体与排版", "Typography & Reading"))
                    PiSettingsCard {
                        // 1. Unified Whole-App Font selector (2x2 real CJK+Latin multi-weight specimen grid)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = 12.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tr("全局字体", "App Font"),
                                    style = PiType.rowTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    tr("全 App 界面与对话正文统一生效（含真实中英多字重）", "Applies to all UI & reading prose with native CJK+Latin weights"),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FontChoiceTile(
                                    sample = tr("永字排版 Aa", "Studio Aa"),
                                    label = tr("IBM Plex 黑体", "IBM Plex SC"),
                                    badge = tr("4 字重", "4 wts"),
                                    fontFamily = PiPlexSansFamily,
                                    selected = proseFont == PiTypographyProfile.FONT_SANS,
                                    onClick = { writeStr("app.appearance.proseFont", PiTypographyProfile.FONT_SANS) },
                                    modifier = Modifier.weight(1f),
                                )
                                FontChoiceTile(
                                    sample = tr("永字排版 Aa", "Studio Aa"),
                                    label = tr("思源宋体", "Han Serif"),
                                    badge = tr("双字重", "2 wts"),
                                    fontFamily = PiProseFamily,
                                    selected = proseFont == PiTypographyProfile.FONT_SERIF,
                                    onClick = { writeStr("app.appearance.proseFont", PiTypographyProfile.FONT_SERIF) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FontChoiceTile(
                                    sample = tr("永字排版 Aa", "Studio Aa"),
                                    label = tr("霞鹜文楷", "LXGW WenKai"),
                                    badge = tr("双字重", "2 wts"),
                                    fontFamily = PiWenKaiFamily,
                                    selected = proseFont == PiTypographyProfile.FONT_KAI,
                                    onClick = { writeStr("app.appearance.proseFont", PiTypographyProfile.FONT_KAI) },
                                    modifier = Modifier.weight(1f),
                                )
                                FontChoiceTile(
                                    sample = tr("永字排版 Aa", "Studio Aa"),
                                    label = tr("跟随系统", "System"),
                                    badge = tr("原生", "Native"),
                                    fontFamily = FontFamily.Default,
                                    selected = proseFont == PiTypographyProfile.FONT_SYSTEM,
                                    onClick = { writeStr("app.appearance.proseFont", PiTypographyProfile.FONT_SYSTEM) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        PiSettingsHairline()

                        // 2. Code & Terminal Font selector (pure machine text: code, diffs, paths, terminal, TUI)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = 12.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tr("代码与终端字体", "Code & Terminal Font"),
                                    style = PiType.rowTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    tr("专用于代码块、行内代码、Diff、路径与内置终端（6 字重含真斜体）", "Dedicated to code, diffs, paths & terminal (6 cuts w/ true italic)"),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val ligFeat = if (codeLigatures) PiFontFeatures.CODE_LIGATURES_ON else PiFontFeatures.CODE_LIGATURES_OFF
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FontChoiceTile(
                                    sample = "a -> b != 0",
                                    label = "JetBrains Mono",
                                    fontFamily = PiJetBrainsMonoFamily,
                                    fontFeatures = ligFeat,
                                    selected = codeFont == PiTypographyProfile.CODE_JETBRAINS,
                                    onClick = { writeStr("app.appearance.codeFont", PiTypographyProfile.CODE_JETBRAINS) },
                                    modifier = Modifier.weight(1f),
                                )
                                FontChoiceTile(
                                    sample = "a -> b != 0",
                                    label = "Geist Mono",
                                    fontFamily = PiGeistMonoFamily,
                                    fontFeatures = ligFeat,
                                    selected = codeFont == PiTypographyProfile.CODE_GEIST_MONO,
                                    onClick = { writeStr("app.appearance.codeFont", PiTypographyProfile.CODE_GEIST_MONO) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        PiSettingsHairline()

                        AppearanceSwitchRow(
                            title = tr("代码连字", "Code Ligatures"),
                            subtitle = tr("代码块启用 =>  !=  -> 连字，路径与 Diff 保持字面符号", "Render =>  !=  -> ligatures in code blocks; paths & diffs stay literal"),
                            checked = codeLigatures,
                            onCheckedChange = { writeBool("app.appearance.codeLigatures", it) },
                        )

                        PiSettingsHairline()

                        InlineSegmentRow(
                            title = tr("字号", "Font Size"),
                            subtitle = tr("正文 ${activeProfile.textSizeSp}sp · 代码 ${codeSizeLabel}sp", "Prose ${activeProfile.textSizeSp}sp · Code ${codeSizeLabel}sp"),
                            options = PiTypographyProfile.TEXT_SIZE_STOPS.map { sp -> sp.toString() to sp.toString() },
                            selected = textSizeSp.toString(),
                            onSelect = { writeStr("app.appearance.fontSize", it) },
                        )

                        PiSettingsHairline()

                        InlineSegmentRow(
                            title = tr("阅读密度", "Reading Density"),
                            subtitle = tr("行高 $lhRatioText · 段距 ${activeProfile.markdownBlock.value.toInt()}dp", "$lhRatioText · ${activeProfile.markdownBlock.value.toInt()}dp gap"),
                            options = listOf(
                                PiTypographyProfile.COMPACT to tr("紧凑", "Compact"),
                                PiTypographyProfile.COMFORTABLE to tr("舒适", "Comfortable"),
                                PiTypographyProfile.RELAXED to tr("舒展", "Relaxed"),
                            ),
                            selected = density,
                            onSelect = { writeStr("app.appearance.typography", it) },
                        )
                    }
                }
            }

            // 4. Chat Display & Rendering Section
            item(key = "display") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    AppearanceSectionHeader(tr("对话显示与渲染", "Chat Display & Rendering"))
                    PiSettingsCard {
                        InlineSegmentRow(
                            title = tr("思考过程", "Thinking Display"),
                            subtitle = when (thinkingDisplay) {
                                "hidden" -> tr("后台照常推理，仅隐藏卡片", "Runs silently, hidden")
                                "expanded" -> tr("直接展开推理全文", "Always expanded")
                                else -> tr("收起为单行摘要，点击展开", "Compact summary")
                            },
                            options = listOf(
                                "expanded" to tr("展开", "Expanded"),
                                "collapsed" to tr("折叠", "Collapsed"),
                                "hidden" to tr("隐藏", "Hidden"),
                            ),
                            selected = thinkingDisplay,
                            onSelect = { mode ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                when (mode) {
                                    "hidden" -> {
                                        store.write("hideThinkingBlock", JsonPrimitive(true))
                                        onSettingWritten("hideThinkingBlock")
                                    }
                                    "expanded" -> {
                                        store.write("hideThinkingBlock", JsonPrimitive(false))
                                        store.write("app.appearance.thinkingCollapsedByDefault", JsonPrimitive(false))
                                        onSettingWritten("hideThinkingBlock")
                                        onSettingWritten("app.appearance.thinkingCollapsedByDefault")
                                    }
                                    else -> {
                                        store.write("hideThinkingBlock", JsonPrimitive(false))
                                        store.write("app.appearance.thinkingCollapsedByDefault", JsonPrimitive(true))
                                        onSettingWritten("hideThinkingBlock")
                                        onSettingWritten("app.appearance.thinkingCollapsedByDefault")
                                    }
                                }
                            },
                        )
                        PiSettingsHairline()
                        AppearanceSwitchRow(
                            title = tr("显示时间戳", "Show Timestamps"),
                            subtitle = tr("消息旁显示时间，跨天插入日期分隔", "Show message times and day dividers"),
                            checked = showTimestamps,
                            onCheckedChange = { writeBool("app.appearance.showTimestamps", it) },
                        )
                        PiSettingsHairline()
                        InlineSegmentRow(
                            title = tr("Mermaid 图表渲染", "Mermaid Diagrams"),
                            options = listOf(
                                "streaming" to tr("实时", "Streaming"),
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
            confirmationTone = PiTheme.palette.error,
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

private fun formatSpCompact(sp: Float): String {
    val whole = sp.toInt()
    return if (kotlin.math.abs(sp - whole) < 0.05f) whole.toString() else String.format(java.util.Locale.US, "%.1f", sp)
}

/** Compact live specimen docked below PiTopBar while scrolling through font & typography controls. */
@Composable
private fun StickyAppearancePreviewBar(
    activeThemeName: String,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val profile = PiTheme.typography
    val proseStyle = profile.styles.prose.copy(
        fontFamily = profile.proseFontFamily(),
        fontFeatureSettings = profile.proseFontFeatures(),
    )
    val codeStyle = profile.styles.code.copy(
        fontFamily = profile.codeFontFamily(),
        fontFeatureSettings = profile.codeFontFeatures(allowLigatures = true),
    )
    val proseFontTag = when (profile.proseFont) {
        PiTypographyProfile.FONT_SERIF -> tr("思源宋体", "Serif")
        PiTypographyProfile.FONT_KAI -> tr("霞鹜文楷", "WenKai")
        PiTypographyProfile.FONT_SYSTEM -> tr("系统", "Sys")
        else -> tr("Plex 黑体", "Plex SC")
    }
    val codeFontTag = if (profile.codeFont == PiTypographyProfile.CODE_GEIST_MONO) "Geist Mono" else "JB Mono"
    val ratioLabel = remember(profile.proseLineRatio) {
        String.format(java.util.Locale.US, "%.2f×", profile.proseLineRatio)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = palette.pageBg,
        border = BorderStroke(PiSpacing.hairline, palette.borderMuted),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                    Text(
                        "$activeThemeName · $proseFontTag · $codeFontTag",
                        style = PiTheme.text.monoSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${profile.textSizeSp}sp · $ratioLabel",
                    style = PiTheme.text.monoSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = PiFontFeatures.TABULAR,
                    ),
                    color = palette.accent,
                    maxLines = 1,
                )
            }
            Text(
                text = buildAnnotatedString {
                    append(tr("排版实时预览：", "Live specimen: "))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(tr("清晰字阶", "crisp scale"))
                    }
                    append(tr(" 与 ", " & "))
                    withStyle(
                        SpanStyle(
                            fontFamily = profile.codeFontFamily(),
                            fontSize = profile.inlineCodeSizeSp.sp,
                            fontWeight = profile.inlineCodeWeight,
                            color = palette.mdCode,
                            background = palette.borderMuted.copy(alpha = 0.34f),
                            fontFeatureSettings = profile.codeFontFeatures(allowLigatures = false),
                        ),
                    ) {
                        append("\u2009inline\u2009")
                    }
                },
                style = proseStyle,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = palette.syntaxKeyword, fontWeight = FontWeight.Medium)) { append("val ") }
                    withStyle(SpanStyle(color = palette.syntaxVariable)) { append("ok") }
                    withStyle(SpanStyle(color = palette.syntaxOperator)) { append(" = ") }
                    withStyle(SpanStyle(color = palette.syntaxFunction)) { append("render") }
                    withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append("() ") }
                    withStyle(SpanStyle(color = palette.syntaxOperator)) { append("!= null -> true") }
                },
                style = codeStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Real-time WYSIWYG preview reacting to theme, font family, density, font size, timestamps & thinking. */
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
    val proseStyle = profile.styles.prose.copy(
        fontFamily = profile.proseFontFamily(),
        fontFeatureSettings = profile.proseFontFeatures(),
    )
    val codeStyle = profile.styles.code.copy(
        fontFamily = profile.codeFontFamily(),
        fontFeatureSettings = profile.codeFontFeatures(allowLigatures = true),
    )

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
            // Header badges: active theme + active font pair & resolved prose metrics
            val proseFontTag = when (profile.proseFont) {
                PiTypographyProfile.FONT_SERIF -> tr("思源宋体", "Serif")
                PiTypographyProfile.FONT_KAI -> tr("霞鹜文楷", "WenKai")
                PiTypographyProfile.FONT_SYSTEM -> tr("系统", "Sys")
                else -> tr("Plex 黑体", "Plex SC")
            }
            val codeFontTag = if (profile.codeFont == PiTypographyProfile.CODE_GEIST_MONO) "Geist Mono" else "JB Mono"
            val ratioLabel = remember(profile.proseLineRatio) {
                String.format(java.util.Locale.US, "%.2f×", profile.proseLineRatio)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "$proseFontTag · $codeFontTag · ${profile.textSizeSp}sp · $ratioLabel",
                    style = PiTheme.text.monoSmall.copy(fontFeatureSettings = PiFontFeatures.TABULAR),
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                            tr("已思考 ▸", "Thought ▸"),
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

            // Assistant prose + inline code + bold/italic specimen
            val sampleProse = buildAnnotatedString {
                append(tr("已对齐官方 ", "Aligned with "))
                withStyle(
                    SpanStyle(
                        fontFamily = profile.codeFontFamily(),
                        fontSize = profile.inlineCodeSizeSp.sp,
                        fontWeight = profile.inlineCodeWeight,
                        color = palette.mdCode,
                        background = palette.borderMuted.copy(alpha = 0.34f),
                        fontFeatureSettings = profile.codeFontFeatures(allowLigatures = false),
                    ),
                ) {
                    append("\u2009Pi 1.6.0\u2009")
                }
                append(tr(" 规范，", " themes with "))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(tr("多字重", "crisp weights"))
                }
                append(tr(" 与 ", " & "))
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append("true italic")
                }
                append(tr(" 即改即见。", " live preview."))
            }
            Text(
                text = sampleProse,
                style = proseStyle,
                color = palette.text,
            )

            // Mini syntax-highlighted code snippet (demonstrates italic comment, Medium keyword, & ligatures)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = palette.cardBg,
                border = BorderStroke(PiSpacing.hairlineThin, palette.mdCodeBlockBorder.copy(alpha = 0.5f)),
            ) {
                val codeSample = buildAnnotatedString {
                    withStyle(SpanStyle(color = palette.syntaxComment, fontStyle = FontStyle.Italic)) {
                        append("// Studio typography & ligatures\n")
                    }
                    withStyle(SpanStyle(color = palette.syntaxKeyword, fontWeight = FontWeight.Medium)) { append("val ") }
                    withStyle(SpanStyle(color = palette.syntaxVariable)) { append("ok") }
                    withStyle(SpanStyle(color = palette.syntaxOperator)) { append(" = ") }
                    withStyle(SpanStyle(color = palette.syntaxFunction)) { append("load") }
                    withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append("(") }
                    withStyle(SpanStyle(color = palette.syntaxString)) { append("\"$activeThemeName\"") }
                    withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append(") ") }
                    withStyle(SpanStyle(color = palette.syntaxOperator)) { append("!= null -> true") }
                }
                Text(
                    text = codeSample,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = codeStyle,
                    maxLines = 2,
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
                }
                if (onDelete != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(onClickLabel = tr("删除主题", "Delete theme"), onClick = onDelete),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(p.cardBg.copy(alpha = 0.90f)),
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
                        style = PiTheme.text.meta,
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
                    style = PiType.rowTitle,
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
            Spacer(Modifier.width(8.dp))
            app.pi.ui.components.PiSecondaryButton(
                label = tr("调色", "Tune"),
                leadingIcon = Icons.Outlined.Tune,
                compact = true,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onClick = onOpenCustom,
            )
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
    badge: String? = null,
    fontFeatures: String? = null,
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
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = sample,
                style = PiTheme.text.prose.copy(
                    fontFamily = fontFamily,
                    fontSize = 15.5.sp,
                    lineHeight = 21.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontFeatureSettings = fontFeatures,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = PiTheme.text.meta.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) PiTheme.palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Text(
                        text = badge,
                        style = PiTheme.text.meta.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                        color = if (selected) PiTheme.palette.accent.copy(alpha = 0.85f) else PiTheme.palette.dim,
                        maxLines = 1,
                    )
                }
            }
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
    val isDark = PiTheme.isDark
    val trackBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    val activeBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
    val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f)
    val activeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.75f else 0.90f)
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
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = subtitle,
                    style = PiTheme.text.monoSmall.copy(fontFeatureSettings = PiFontFeatures.TABULAR),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = trackBg,
            border = BorderStroke(PiSpacing.hairlineThin, trackBorder),
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
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) activeBg else Color.Transparent)
                            .border(
                                width = if (active) PiSpacing.hairlineThin else 0.dp,
                                color = if (active) activeBorder else Color.Transparent,
                                shape = RoundedCornerShape(7.dp),
                            )
                            .clickable(enabled = enabled) { onSelect(wire) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = PiTheme.text.meta.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                fontFeatureSettings = PiFontFeatures.TABULAR,
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
                style = PiType.rowTitle,
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

    app.pi.ui.components.PiSheet(onDismiss = onDismiss) {
        val sheetCardBg = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg
        val sheetPalette = PiTheme.palette
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                app.pi.ui.components.PiSecondaryButton(
                    label = tr("选择 .json 文件", "Pick .json"),
                    leadingIcon = Icons.Outlined.FolderOpen,
                    onClick = { openDoc.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f),
                )
                app.pi.ui.components.PiSecondaryButton(
                    label = tr("粘贴剪贴板", "Paste"),
                    leadingIcon = Icons.Outlined.ContentPaste,
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
                    modifier = Modifier.weight(1f),
                )
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
                container = sheetCardBg,
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
                container = sheetCardBg,
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

            val canImport = preview != null && !importing
            app.pi.ui.components.PiSheetActionBar {
                app.pi.ui.components.PiSecondaryButton(
                    label = tr("取消", "Cancel"),
                    onClick = onDismiss,
                )
                app.pi.ui.components.PiPrimaryButton(
                    label = tr("导入并应用", "Import & Apply"),
                    enabled = canImport,
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
                    modifier = Modifier.weight(1f),
                )
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
