package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.HostAdvanced
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.TagPill
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private val BUILTIN_CORE_TOOLS = setOf("read", "bash", "edit", "write", "grep", "find", "ls", "powershell")
private val BUILTIN_EXT_TOOLS = setOf("codemode", "tool_search", "mcp")
private val DEFAULT_CORE_TOOLS = listOf("read", "bash", "edit", "write")

/**
 * 重构后的「工具」专页（100% 对齐 Pi 1.6.0 SDK）：
 *  1. 去掉冗余的顶部「8 个工具白名单」列表行，直接把当前已注册的全部工具（内置 + 内置扩展 + 用户扩展/MCP）
 *     以分组开关呈现在页面主体，支持一键启停，并提供「设为新会话默认（写入 settings.json defaultTools）」与「恢复默认」；
 *  2. 官方 Pi 1.6.0 Codemode 工具编排设置（`codemode.mode` / `codemode.inlineBudget`）与实时生效状态标识；
 *  3. 官方 Pi 1.6.0 `shellCommandPrefix`（Bash 命令前缀）+ App 侧「工具输出默认展开」开关。
 */
@Composable
internal fun ToolsScreen(
    store: PiSettingsStore,
    freshness: Int,
    contentPadding: PaddingValues,
    advanced: () -> HostAdvanced?,
    extensionsAccess: () -> app.pi.host.ExtensionsAccess? = { null },
    onSettingWritten: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette
    var reloadEpoch by remember { mutableStateOf(0) }
    var tools by remember { mutableStateOf<List<HostAdvanced.ToolDetail>?>(null) }
    var toolsError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var editingSetting by remember { mutableStateOf<PiSetting?>(null) }
    var explainingSetting by remember { mutableStateOf<PiSetting?>(null) }
    var pendingReload by remember { mutableStateOf(false) }
    var reloadingResources by remember { mutableStateOf(false) }
    var togglingName by remember { mutableStateOf<String?>(null) }

    fun triggerReload() {
        val ext = extensionsAccess() ?: run {
            notice = tr("引擎未运行，配置将在下次启动时生效。", "Engine is not running; changes will apply on next launch.")
            return
        }
        if (reloadingResources) return
        reloadingResources = true
        scope.launch {
            runCatching { ext.ext.reload() }
                .onSuccess {
                    pendingReload = false
                    reloadEpoch++
                    notice = tr("已重载工具与扩展配置。", "Reloaded tools and extension settings.")
                }
                .onFailure {
                    toolsError = it.message ?: tr("重载失败", "Reload failed")
                }
            reloadingResources = false
        }
    }

    LaunchedEffect(reloadEpoch) {
        val api = advanced()
        if (api == null) {
            tools = emptyList()
            toolsError = tr(
                "引擎未运行：启动后即可实时查看和启停全部内置、扩展与 MCP 工具。",
                "Engine is not running. Start it to inspect and toggle all built-in, extension, and MCP tools live.",
            )
        } else {
            runCatching { api.tools() }
                .onSuccess {
                    tools = it
                    toolsError = null
                }
                .onFailure {
                    toolsError = it.message ?: tr("读取工具列表失败", "Failed to load tools")
                }
        }
    }

    val list = tools.orEmpty()
    val q = query.trim()
    val filtered = remember(list, q) {
        if (q.isEmpty()) list else list.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true) ||
                (it.source?.contains(q, ignoreCase = true) == true)
        }
    }
    val builtinCore = remember(filtered) { filtered.filter { it.name in BUILTIN_CORE_TOOLS } }
    val builtinExt = remember(filtered) { filtered.filter { it.name in BUILTIN_EXT_TOOLS } }
    val extraTools = remember(filtered) {
        filtered.filter { it.name !in BUILTIN_CORE_TOOLS && it.name !in BUILTIN_EXT_TOOLS }
    }
    val codemodeActive = list.any { it.name == "codemode" && it.active }

    val codemodeModeSetting = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey["codemode.mode"] }
    val codemodeBudgetSetting = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey["codemode.inlineBudget"] }
    val shellPathSetting = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey["shellPath"] }
    val shellPrefixSetting = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey["shellCommandPrefix"] }
    val expandDefaultSetting = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey["app.tools.expandByDefault"] }

    fun toggleTool(target: HostAdvanced.ToolDetail, enable: Boolean) {
        val api = advanced() ?: return
        if (togglingName != null) return
        val nextNames = list.filter { it.active && it.name != target.name }.map { it.name } +
            (if (enable) listOf(target.name) else emptyList())
        togglingName = target.name
        notice = null
        scope.launch {
            runCatching { api.setActiveTools(nextNames) }
                .onSuccess {
                    tools = list.map { item ->
                        if (item.name == target.name) item.copy(active = enable) else item
                    }
                    reloadEpoch++
                }
                .onFailure { toolsError = it.message ?: tr("切换工具失败", "Failed to toggle tool") }
            togglingName = null
        }
    }

    fun saveCurrentAsDefault() {
        val activeNames = list.filter { it.active }.map { it.name }
        if (activeNames.toSet() == DEFAULT_CORE_TOOLS.toSet() && activeNames.size == DEFAULT_CORE_TOOLS.size) {
            store.remove("defaultTools")
        } else {
            store.write("defaultTools", JsonArray(activeNames.map { JsonPrimitive(it) }))
        }
        onSettingWritten("defaultTools")
        notice = tr(
            "已将当前启用的 ${activeNames.size} 个工具保存为新会话默认（defaultTools）。",
            "Saved ${activeNames.size} active tools as the default for new sessions (defaultTools).",
        )
    }

    fun resetToDefaultTools() {
        store.remove("defaultTools")
        onSettingWritten("defaultTools")
        val api = advanced()
        if (api != null) {
            scope.launch {
                val availableDefaults = DEFAULT_CORE_TOOLS.filter { name -> list.any { it.name == name } }
                runCatching { api.setActiveTools(availableDefaults.ifEmpty { DEFAULT_CORE_TOOLS }) }
                    .onSuccess { reloadEpoch++ }
            }
        }
        notice = tr(
            "已恢复默认工具集（read / bash / edit / write）。",
            "Restored default tools (read / bash / edit / write).",
        )
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("工具", "Tools"),
            onBack = onBack,
            scrolled = scrolled,
            actions = {
                PiTopBarIcon(
                    onClick = { reloadEpoch++ },
                    contentDescription = tr("刷新工具列表", "Refresh tools"),
                    icon = Icons.Filled.Refresh,
                )
            },
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
            toolsError?.let { err ->
                item(key = "error") { PiInfoNote(err, tone = palette.warning) }
            }
            notice?.let { msg ->
                item(key = "notice") { PiInfoNote(msg, tone = palette.success) }
            }
            if (pendingReload) {
                item(key = "pending-reload") {
                    PiSettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.accent.copy(alpha = 0.08f))
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = PiSettingsMetrics.rowPaddingVertical,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("配置已保存，尚未重载", "Saved — Pending Reload"),
                                    style = app.pi.ui.components.PiType.rowTitle,
                                    color = palette.accent,
                                )
                                Text(
                                    tr("Codemode 与 Shell 配置需重载后在当前会话生效", "Reload to apply Codemode or Shell changes to the active session"),
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                )
                            }
                            PiSettingsChip(
                                text = if (reloadingResources) tr("重载中…", "Reloading…") else tr("立即重载", "Reload"),
                                active = true,
                                enabled = !reloadingResources,
                                onClick = { triggerReload() },
                            )
                        }
                    }
                }
            }

            // 1. 当前会话与默认工具总开关
            item(key = "tools-header") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val countLabel = if (tools == null) {
                            tr("正在读取工具…", "Loading tools…")
                        } else {
                            tr(
                                "全部工具 · ${list.count { it.active }} / ${list.size} 已启用",
                                "All Tools · ${list.count { it.active }} / ${list.size} Active",
                            )
                        }
                        Text(
                            countLabel,
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                        )
                    }
                    if (list.size > 8) {
                        PiEditorField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = tr("搜索工具名、描述或来源", "Search tool name, description, or source"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        )
                    }
                    if (list.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                            horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                        ) {
                            PiSettingsChip(
                                text = tr("设为新会话默认", "Save as Default"),
                                onClick = { saveCurrentAsDefault() },
                            )
                            PiSettingsChip(
                                text = tr("恢复默认 4 项", "Reset Defaults"),
                                onClick = { resetToDefaultTools() },
                            )
                        }
                    }
                }
            }

            if (builtinCore.isNotEmpty()) {
                item(key = "tools-core") {
                    ToolGroupCard(
                        title = tr("内置核心工具", "Built-in Core Tools"),
                        tools = builtinCore,
                        togglingName = togglingName,
                        onToggle = ::toggleTool,
                    )
                }
            }

            if (builtinExt.isNotEmpty()) {
                item(key = "tools-builtin-ext") {
                    ToolGroupCard(
                        title = tr("Pi 内置高级工具", "Pi Built-in Extension Tools"),
                        tools = builtinExt,
                        togglingName = togglingName,
                        onToggle = ::toggleTool,
                    )
                }
            }

            if (extraTools.isNotEmpty()) {
                item(key = "tools-extra") {
                    ToolGroupCard(
                        title = tr("扩展与 MCP 工具 · ${extraTools.size}", "Extension & MCP Tools · ${extraTools.size}"),
                        tools = extraTools,
                        togglingName = togglingName,
                        onToggle = ::toggleTool,
                    )
                }
            }

            // 2. Codemode 工具编排（Pi 1.6.0 官方 SDK 设置）
            if (codemodeModeSetting != null || codemodeBudgetSetting != null) {
                item(key = "codemode-section") {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                tr("Codemode 脚本工具编排", "Codemode Tool Orchestration"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.muted,
                            )
                            TagPill(
                                text = if (codemodeActive) tr("codemode 已启用", "codemode active") else tr("codemode 未启用", "codemode off"),
                                tone = if (codemodeActive) palette.success else palette.muted,
                            )
                        }
                        PiSettingsCard {
                            var first = true
                            codemodeModeSetting?.let { setting ->
                                first = false
                                PiSettingRow(
                                    setting = setting,
                                    valueText = setting.display(setting.current(store)),
                                    checked = false,
                                    onToggle = {},
                                    onOpen = { editingSetting = setting },
                                    onExplainEffect = { explainingSetting = setting },
                                )
                            }
                            codemodeBudgetSetting?.let { setting ->
                                if (!first) PiSettingsHairline()
                                PiSettingRow(
                                    setting = setting,
                                    valueText = setting.display(setting.current(store)),
                                    checked = false,
                                    onToggle = {},
                                    onOpen = { editingSetting = setting },
                                    onExplainEffect = { explainingSetting = setting },
                                )
                            }
                        }
                    }
                }
            }

            // 3. Shell 与工具卡显示（Pi 1.6.0 官方 shellPath / shellCommandPrefix + 展开行为）
            item(key = "shell-display") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(tr("Shell 与显示", "Shell & Display"))
                    PiSettingsCard {
                        var first = true
                        shellPathSetting?.let { setting ->
                            first = false
                            PiSettingRow(
                                setting = setting,
                                valueText = setting.display(setting.current(store)),
                                checked = false,
                                onToggle = {},
                                onOpen = { editingSetting = setting },
                                onExplainEffect = { explainingSetting = setting },
                            )
                        }
                        shellPrefixSetting?.let { setting ->
                            if (!first) PiSettingsHairline()
                            first = false
                            PiSettingRow(
                                setting = setting,
                                valueText = setting.display(setting.current(store)),
                                checked = false,
                                onToggle = {},
                                onOpen = { editingSetting = setting },
                                onExplainEffect = { explainingSetting = setting },
                            )
                        }
                        expandDefaultSetting?.let { setting ->
                            if (!first) PiSettingsHairline()
                            PiSettingRow(
                                setting = setting,
                                valueText = setting.display(setting.current(store)),
                                checked = setting.boolIn(store, false),
                                onToggle = { next ->
                                    store.write(setting.key, JsonPrimitive(next))
                                    onSettingWritten(setting.key)
                                },
                                onOpen = {},
                                onExplainEffect = {},
                            )
                        }
                    }
                }
            }
        }
    }

    editingSetting?.let { setting ->
        PiSettingEditorSheet(
            setting = setting,
            store = store,
            onDismiss = { editingSetting = null },
            onWritten = { written ->
                onSettingWritten(written.key)
                if (written.effective == app.pi.ui.components.EffectiveKind.Reload) {
                    pendingReload = true
                }
            },
        )
    }

    explainingSetting?.let { setting ->
        PiEffectiveDialog(
            kind = setting.effective,
            settingTitle = setting.title,
            onDismiss = { explainingSetting = null },
            onRunAction = if (setting.effective == app.pi.ui.components.EffectiveKind.Reload) {
                { triggerReload() }
            } else {
                null
            },
        )
    }
}

@Composable
private fun ToolGroupCard(
    title: String,
    tools: List<HostAdvanced.ToolDetail>,
    togglingName: String?,
    onToggle: (HostAdvanced.ToolDetail, Boolean) -> Unit,
) {
    val palette = PiTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
        ModelsSectionLabel(title)
        PiSettingsCard {
            Column {
                tools.forEachIndexed { index, tool ->
                    if (index > 0) PiSettingsHairline()
                    val dotTone = when {
                        !tool.active -> palette.dim.copy(alpha = 0.4f)
                        tool.callable -> palette.success
                        else -> palette.warning
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ModelsCardPadding, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotTone),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = tool.name,
                                    style = PiTheme.text.mono.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (tool.active) palette.text else palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                tool.source?.takeIf { it.isNotBlank() && it != "builtin" }?.let { src ->
                                    TagPill(src, tone = palette.accent)
                                }
                            }
                            val desc = tool.description.lineSequence().firstOrNull()?.trim().orEmpty()
                            if (desc.isNotEmpty()) {
                                Text(
                                    text = desc,
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (tool.active && !tool.callable) {
                                Text(
                                    text = tr("已启用但当前不可直接调用（例如仅经 codemode 暴露）", "Active but not directly callable (e.g. exposed via codemode)"),
                                    style = PiTheme.text.meta,
                                    color = palette.warning,
                                )
                            }
                        }
                        PiSettingsSwitch(
                            checked = tool.active,
                            enabled = togglingName == null,
                            onCheckedChange = { on -> onToggle(tool, on) },
                        )
                    }
                }
            }
        }
    }
}
