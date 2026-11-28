package app.pi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.components.EffectiveKind
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private const val NPM_PRESET_DEFAULT = "default"
private const val NPM_PRESET_MIRROR = "npmmirror"
private const val NPM_PRESET_PNPM = "pnpm"
private const val NPM_PRESET_BUN = "bun"

private val NPM_MIRROR_ARGS = listOf("npm", "--registry=https://registry.npmmirror.com")
private val NPM_PNPM_ARGS = listOf("pnpm")
private val NPM_BUN_ARGS = listOf("bun")

/**
 * 现代「包安装与命令选项」专页（100% 对齐 Pi 1.7.0 `SettingsManager` / `DefaultPackageManager`）：
 * - 包安装：`npmCommand` 一键切换常用预设（默认 npm / 国内镜像 npmmirror / pnpm / bun）或打开自定义命令列表编辑器；
 * - 技能与扩展启动：`enableSkillCommands` 即时切换 `/skill:name` 命令注册 + `app.extensions.args` 启动参数及一键重启提示；
 * - 隐私与分析：`enableAnalytics` 可选匿名分析开关（Pi 1.7.0 已移除 `enableInstallTelemetry`）。
 */
@Composable
internal fun PackageCommandOptionsScreen(
    store: PiSettingsStore,
    freshness: Int,
    contentPadding: PaddingValues,
    onSettingWritten: (String) -> Unit,
    onBack: () -> Unit,
    onRestartEngine: (() -> Unit)? = null,
    highlightKey: String? = null,
) {
    val palette = PiTheme.palette
    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    var editing by remember { mutableStateOf<PiSetting?>(null) }
    var explaining by remember { mutableStateOf<PiSetting?>(null) }
    var argsDirty by remember { mutableStateOf(false) }

    val byKey = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey }
    val npmCommandSetting = byKey["npmCommand"]
    val skillCommandsSetting = byKey["enableSkillCommands"]
    val extArgsSetting = byKey["app.extensions.args"]
    val analyticsSetting = byKey["enableAnalytics"]

    val npmArgs: List<String> = remember(store, freshness) {
        (store.read("npmCommand") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
    }
    val selectedNpmPreset: String? = remember(npmArgs) {
        when {
            npmArgs.isEmpty() || npmArgs == listOf("npm") -> NPM_PRESET_DEFAULT
            npmArgs == NPM_MIRROR_ARGS -> NPM_PRESET_MIRROR
            npmArgs == NPM_PNPM_ARGS -> NPM_PRESET_PNPM
            npmArgs == NPM_BUN_ARGS -> NPM_PRESET_BUN
            else -> null
        }
    }
    val npmValueBadge: String = remember(npmArgs, selectedNpmPreset, PiLocale.isZh) {
        when (selectedNpmPreset) {
            NPM_PRESET_DEFAULT -> "npm"
            NPM_PRESET_MIRROR -> tr("npmmirror", "npmmirror")
            NPM_PRESET_PNPM -> "pnpm"
            NPM_PRESET_BUN -> "bun"
            else -> npmArgs.firstOrNull()?.let { cmd ->
                if (npmArgs.size > 1) "$cmd +${npmArgs.size - 1}" else cmd
            } ?: "npm"
        }
    }
    val effectiveNpmCommandLine: String = remember(npmArgs) {
        if (npmArgs.isEmpty()) "npm" else npmArgs.joinToString(" ")
    }

    val skillCommandsEnabled = remember(store, freshness) {
        skillCommandsSetting?.boolIn(store, true) ?: true
    }
    val extArgsText = remember(store, freshness) {
        extArgsSetting?.textIn(store, "") ?: ""
    }
    val analyticsEnabled = remember(store, freshness) {
        analyticsSetting?.boolIn(store, false) ?: false
    }

    LaunchedEffect(highlightKey) {
        val targetIndex = when (highlightKey) {
            "npmCommand" -> 0
            "enableSkillCommands", "app.extensions.args" -> 1
            "enableAnalytics" -> 2
            else -> -1
        }
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }

    fun applyNpmPreset(presetId: String) {
        val entries = when (presetId) {
            NPM_PRESET_MIRROR -> NPM_MIRROR_ARGS
            NPM_PRESET_PNPM -> NPM_PNPM_ARGS
            NPM_PRESET_BUN -> NPM_BUN_ARGS
            else -> emptyList()
        }
        if (entries.isEmpty()) {
            store.remove("npmCommand")
        } else {
            store.write("npmCommand", JsonArray(entries.map(::JsonPrimitive)))
        }
        onSettingWritten("npmCommand")
    }

    fun writeBool(key: String, value: Boolean) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    val npmPresets = remember(PiLocale.isZh) {
        listOf(
            NPM_PRESET_DEFAULT to tr("默认 npm", "Default npm"),
            NPM_PRESET_MIRROR to tr("国内镜像", "npmmirror"),
            NPM_PRESET_PNPM to "pnpm",
            NPM_PRESET_BUN to "bun",
        )
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("包安装与命令选项", "Package & Command Options"),
            onBack = onBack,
            scrolled = scrolled,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. 包安装命令（npmCommand，直接传给 Pi SDK DefaultPackageManager）
            item(key = "package-install") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("包安装", "Package Installation"))
                    PiSettingsCard {
                        PiInlinePresetRow(
                            title = npmCommandSetting?.title ?: tr("包安装命令", "Package Install Command"),
                            description = tr(
                                "安装包与检查更新时调用的命令及参数；支持 npm、pnpm、bun 或自定义镜像源。",
                                "Command and arguments used when installing or updating packages (npm, pnpm, bun, or custom registry).",
                            ),
                            valueText = npmValueBadge,
                            presets = npmPresets,
                            selected = selectedNpmPreset,
                            onSelect = ::applyNpmPreset,
                            onCustomClick = npmCommandSetting?.let { s -> { editing = s } },
                            highlighted = highlightKey == "npmCommand",
                        )
                    }
                    AnimatedVisibility(visible = npmArgs.isNotEmpty()) {
                        PiInfoNote(
                            text = tr(
                                "当前安装命令：$effectiveNpmCommandLine",
                                "Active install command: $effectiveNpmCommandLine",
                            ),
                            tone = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 2. 技能命令与扩展启动参数
            item(key = "skills-and-startup") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("技能与扩展启动", "Skills & Extension Startup"))
                    PiSettingsCard {
                        if (skillCommandsSetting != null) {
                            PiSettingRow(
                                setting = skillCommandsSetting,
                                valueText = "",
                                checked = skillCommandsEnabled,
                                highlighted = highlightKey == "enableSkillCommands",
                                onToggle = { writeBool("enableSkillCommands", it) },
                                onOpen = {},
                                onExplainEffect = {},
                            )
                        }
                        if (extArgsSetting != null) {
                            if (skillCommandsSetting != null) PiSettingsHairline()
                            PiSettingRow(
                                setting = extArgsSetting,
                                valueText = extArgsText.ifBlank { tr("未设置", "Not set") },
                                checked = false,
                                highlighted = highlightKey == "app.extensions.args",
                                onToggle = {},
                                onOpen = { editing = extArgsSetting },
                                onExplainEffect = { explaining = extArgsSetting },
                            )
                        }
                    }
                    AnimatedVisibility(visible = argsDirty && onRestartEngine != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                            shape = PiSettingsCardShape,
                            color = palette.warning.copy(alpha = 0.08f),
                            border = BorderStroke(PiSpacing.hairlineThin, palette.warning.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = tr(
                                        "扩展启动参数已保存，重启引擎后才会传给新进程。",
                                        "Extension startup flags saved. Restart the engine to apply them.",
                                    ),
                                    modifier = Modifier.weight(1f),
                                    style = PiTheme.text.meta,
                                    color = palette.warning,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Surface(
                                    onClick = { onRestartEngine?.invoke() },
                                    shape = RoundedCornerShape(8.dp),
                                    color = palette.warning.copy(alpha = 0.16f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.RestartAlt,
                                            contentDescription = null,
                                            tint = palette.warning,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = tr("重启引擎", "Restart"),
                                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                            color = palette.warning,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 隐私与分析（Pi 1.7.0 仅保留可选 enableAnalytics；enableInstallTelemetry 已在上游移除）
            item(key = "privacy-analytics") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("隐私与分析", "Privacy & Analytics"))
                    PiSettingsCard {
                        if (analyticsSetting != null) {
                            PiSettingRow(
                                setting = analyticsSetting,
                                valueText = "",
                                checked = analyticsEnabled,
                                highlighted = highlightKey == "enableAnalytics",
                                onToggle = { writeBool("enableAnalytics", it) },
                                onOpen = {},
                                onExplainEffect = {},
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { setting ->
        PiSettingEditorSheet(
            setting = setting,
            store = store,
            onDismiss = { editing = null },
            onWritten = { written ->
                if (written.key == "app.extensions.args") argsDirty = true
                onSettingWritten(written.key)
            },
        )
    }

    explaining?.let { setting ->
        PiEffectiveDialog(
            kind = setting.effective,
            settingTitle = setting.title,
            onDismiss = { explaining = null },
            onRunAction = if (setting.effective == EffectiveKind.RestartEngine) onRestartEngine else null,
        )
    }
}
