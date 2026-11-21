package app.pi.ui.settings

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel

/**
 * 「逐模型思考等级」的专用编辑器：**没有任何文本输入** —— 模型从清单点、等级从枚举点。
 *
 * 为什么必须有它：这一行原来是 `container = Object` 的通用列表编辑器，用户看到的只有
 * 一个「新增一项」输入框，而答案是一行 `"provider/modelId" = level` 的 JSON 文本 ——
 * 用户原话「点了也是一个输入框让自己填，我该填什么呢？所有的我都不想自己填」。
 *
 * 两件事都是 pi 的事实，不是本编辑器的发明：
 *
 *  - 键是 `Record<"provider/modelId", ModelThinkingLevel>`，**精确、大小写敏感**，模型 id
 *    自己可以带斜杠（`core/settings-manager.ts:805`、`docs/settings-audit-pi-gap.md:471`）；
 *  - 值就是 `defaultThinkingLevel` 那 7 档（`packages/ai/src/types.ts:83-84` 的
 *    `ThinkingLevel | "off"`；注册表里 `thinkingLevelOptions` 也是同一份 [PiThinkingLevel]），
 *    所以两颗选择器不会各说各话。
 *
 * 写回的语义照旧交给 host 的 `PiSetting.elementFromEntries`：空列表 = 删键（回到默认），
 * 不是写 `{}`。
 *
 * @param models 清单里的模型，`wire` 是 `厂商/模型`（清单页 `modelPickerOptions` 的同一份）。
 * @param initialEntries 当前值，`名字 = 值` 原文行（`PiSetting.editableEntries`）。
 */
/**
 * 「思考预算」的专用编辑器：**固定 4 行、只填数字**，键名不用手打。
 *
 * pi 的 `ThinkingBudgets` 只有四个键 `minimal` / `low` / `medium` / `high`，值是 token 数
 * （`packages/ai/src/types.ts:100-105`、`docs/settings-audit-pi-gap.md:263`）。pi **不给
 * 默认数字**：消费侧是 `?? 各厂商自己的默认`（`core/settings-manager.ts:1174-1176`、
 * `core/sdk.ts:370`），所以**留空就是正确用法** —— 这里不编造任何"官方数值"。
 *
 * 四行都空 = 整个键删掉（host 走 `store.remove`），不是写 `{}`。只接受非负整数：
 * 一个非数字写下去只会让这个思考等级退回厂商默认，静默无效（这就是以前手打那种行的
 * 下场）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiThinkingBudgetsSheet(
    title: String,
    description: String,
    initialEntries: List<String>,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val keys = THINKING_BUDGET_KEYS
    val values = remember(initialEntries) {
        mutableStateMapOf<String, String>().apply {
            initialEntries.forEach { line ->
                val name = line.substringBefore('=').trim()
                val value = line.substringAfter('=', "").trim()
                if (name.isNotEmpty()) put(name, value)
            }
        }
    }
    val invalid = keys.any { key ->
        val text = values[key].orEmpty().trim()
        text.isNotEmpty() && parseThinkingBudget(text) == null
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
            Text(
                title,
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                description,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            keys.forEach { key ->
                val tierLabel = thinkingBudgetLabel(key)
                PiEditorField(
                    value = values[key].orEmpty(),
                    onValueChange = { values[key] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tierLabel,
                    placeholder = thinkingBudgetHint(key),
                    height = PiNumberFieldHeight,
                    trailing = {
                        Text(
                            "token",
                            style = PiTheme.text.mono,
                            color = PiTheme.palette.muted,
                        )
                    },
                )
                Spacer(Modifier.height(PiSpacing.small))
            }
            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                if (invalid) {
                    tr("只接受非负整数", "Only non-negative integers are accepted")
                } else {
                    tr("留空 = 用厂商默认", "Empty = provider default")
                },
                style = PiTheme.text.meta,
                color = if (invalid) PiTheme.palette.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline, Alignment.End),
            ) {
                if (keys.any { values[it].orEmpty().isNotBlank() }) {
                    TextButton(
                        onClick = {
                            keys.forEach { values[it] = "" }
                        },
                    ) { Text(tr("清空", "Clear")) }
                }
                OutlinedButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
                Button(
                    enabled = !invalid,
                    onClick = {
                        // 只写有字的行；四行全空 = 空列表 = 删键（host 负责）。
                        onSet(
                            keys.mapNotNull { key ->
                                values[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$key = $it" }
                            },
                        )
                        onDismiss()
                    },
                ) { Text(tr("保存", "Save")) }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/** pi 的 `ThinkingBudgets` 四个合法键，顺序按 token 从小到大（`types.ts:100-105`）。 */
private val THINKING_BUDGET_KEYS = listOf("minimal", "low", "medium", "high")

private fun thinkingBudgetLabel(key: String): String = when (key) {
    "minimal" -> tr("极简 (minimal)", "Minimal (minimal)")
    "low" -> tr("低 (low)", "Low (low)")
    "medium" -> tr("中 (medium)", "Medium (medium)")
    "high" -> tr("高 (high)", "High (high)")
    else -> key
}

private fun thinkingBudgetHint(key: String): String? = when (key) {
    "minimal" -> tr("默认约 1024", "Default ~1024")
    "low" -> tr("默认约 2048", "Default ~2048")
    "medium" -> tr("默认约 8192", "Default ~8192")
    "high" -> tr("默认约 16384", "Default ~16384")
    else -> null
}

private data class BuiltInToolChoice(
    val id: String,
    val title: String,
    val detail: String,
    val groupLabel: String,
)

private fun builtinToolChoices(): List<BuiltInToolChoice> = listOf(
    BuiltInToolChoice("read", "read", tr("读取文件内容与图片", "Read file contents and images"), tr("默认内置", "Default built-in")),
    BuiltInToolChoice("bash", "bash", tr("在工作区执行 Shell 命令", "Execute shell commands in the workspace"), tr("默认内置", "Default built-in")),
    BuiltInToolChoice("edit", "edit", tr("按精确片段修改文件", "Edit files with exact replacements"), tr("默认内置", "Default built-in")),
    BuiltInToolChoice("write", "write", tr("创建或整份写入文件", "Create or overwrite files"), tr("默认内置", "Default built-in")),
    BuiltInToolChoice("grep", "grep", tr("按正则在目录中搜索文本", "Search text with regular expressions"), tr("可选内置", "Optional built-in")),
    BuiltInToolChoice("find", "find", tr("按 glob 模式查找文件", "Find files by glob pattern"), tr("可选内置", "Optional built-in")),
    BuiltInToolChoice("ls", "ls", tr("列出目录内容", "List directory contents"), tr("可选内置", "Optional built-in")),
    BuiltInToolChoice("codemode", "codemode", tr("通过内建扩展启用沙箱脚本执行", "Run sandboxed scripts via built-in extension"), tr("内置扩展", "Built-in extension")),
    BuiltInToolChoice("tool_search", "tool_search", tr("按需检索并激活外部工具，减少提示词占用", "Search and activate external tools on demand"), tr("内置扩展", "Built-in extension")),
)

private val DEFAULT_BUILTIN_TOOL_SET = setOf("read", "bash", "edit", "write")

/**
 * 「内建工具 (`defaultTools`)」可视化开关面板：取代旧版纯文本框。
 *
 * 严格保持 Pi SDK 语义：选中集合刚好等于默认四件套（`read / bash / edit / write`）时回传空列表，
 * 由 host 调用 `store.remove("defaultTools")` 恢复默认；否则按勾选顺序写入完整白名单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiDefaultToolsSheet(
    title: String,
    description: String,
    initialEntries: List<String>,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val toolChoices = builtinToolChoices()
    val initialSelected = remember(initialEntries) {
        val cleaned = initialEntries.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) DEFAULT_BUILTIN_TOOL_SET else cleaned.toSet()
    }
    var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
    val customExtras = remember(initialSelected) {
        val known = toolChoices.map { it.id }.toSet()
        initialSelected.filterNot { it in known }
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
            Text(title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                description,
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
                toolChoices.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = PiSettingsMetrics.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    val checked = item.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - item.id else selected + item.id
                            }
                            .padding(vertical = PiOptionRowPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${item.title} · ${item.groupLabel}",
                                style = PiTheme.text.prose,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                item.detail,
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(PiSpacing.inline))
                        Switch(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + item.id else selected - item.id
                            },
                        )
                    }
                }
                if (customExtras.isNotEmpty()) {
                    Spacer(Modifier.height(PiSpacing.small))
                    Text(
                        tr(
                            "保留配置中的其他工具名：${customExtras.joinToString(", ")}",
                            "Preserved custom tools: ${customExtras.joinToString(", ")}",
                        ),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                if (selected.isEmpty()) {
                    tr(
                        "已全部取消：保存将恢复默认四件套（如需彻底禁用全部内建工具，请在「Pi 文件」手动写 []）。",
                        "All unchecked: saving resets to the 4 defaults (to disable all built-ins, set [] in settings.json).",
                    )
                } else if (selected == DEFAULT_BUILTIN_TOOL_SET) {
                    tr(
                        "当前即默认四件套（read / bash / edit / write）。",
                        "Default tool set active (read / bash / edit / write).",
                    )
                } else {
                    tr(
                        "将启用 ${selected.size} 个工具：${selected.joinToString(", ")}",
                        "Will enable ${selected.size} tools: ${selected.joinToString(", ")}",
                    )
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline, Alignment.End),
            ) {
                if (selected != DEFAULT_BUILTIN_TOOL_SET) {
                    TextButton(onClick = { selected = DEFAULT_BUILTIN_TOOL_SET }) { Text(tr("恢复默认", "Reset default")) }
                }
                OutlinedButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
                Button(
                    onClick = {
                        val ordered = toolChoices.map { it.id }.filter { it in selected } +
                            customExtras.filter { it in selected }
                        val result = if (ordered.isEmpty() || ordered.toSet() == DEFAULT_BUILTIN_TOOL_SET) {
                            emptyList()
                        } else {
                            ordered
                        }
                        onSet(result)
                        onDismiss()
                    },
                ) { Text(tr("保存", "Save")) }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

private data class CompactionOverrideDraft(
    val modelKey: String,
    val reserveTokens: Int?,
    val keepRecentTokens: Int?,
)

private fun parseCompactionOverrideEntries(entries: List<String>): List<CompactionOverrideDraft> =
    entries.mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val key = line.substring(0, idx).trim()
        val raw = line.substring(idx + 1).trim()
        if (key.isEmpty()) return@mapNotNull null
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val reserve = (obj?.get("reserveTokens") as? JsonPrimitive)?.content?.toIntOrNull()
        val keep = (obj?.get("keepRecentTokens") as? JsonPrimitive)?.content?.toIntOrNull()
        CompactionOverrideDraft(key, reserve, keep)
    }

private fun formatCompactionOverrideEntries(items: List<CompactionOverrideDraft>): List<String> =
    items.mapNotNull { item ->
        val fields = buildList {
            item.reserveTokens?.let { add("\"reserveTokens\":$it") }
            item.keepRecentTokens?.let { add("\"keepRecentTokens\":$it") }
        }
        if (fields.isEmpty()) null else "${item.modelKey} = {${fields.joinToString(",")}}"
    }

/**
 * 「按模型压缩阈值覆盖 (`compaction.modelOverrides`)」结构化编辑器：取代手写 JSON 文本框。
 *
 * 从 SDK 真实模型目录挑选 `provider/modelId`（也允许手输），再用两个纯数字框填
 * `reserveTokens` 与 `keepRecentTokens`，彻底告别手敲 JSON。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiCompactionOverridesSheet(
    title: String,
    description: String,
    initialEntries: List<String>,
    availableModels: List<PiResponses.ModelInfo>,
    onLoadAvailableModels: () -> Unit,
    onSet: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (availableModels.isEmpty()) onLoadAvailableModels()
    }
    var items by remember(initialEntries) { mutableStateOf(parseCompactionOverrideEntries(initialEntries)) }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var pickingModel by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var reserveText by remember { mutableStateOf("") }
    var keepText by remember { mutableStateOf("") }
    var draftError by remember { mutableStateOf<String?>(null) }

    fun beginEdit(key: String, existing: CompactionOverrideDraft?) {
        editingKey = key
        pickingModel = false
        reserveText = existing?.reserveTokens?.toString().orEmpty()
        keepText = existing?.keepRecentTokens?.toString().orEmpty()
        draftError = null
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
            Text(title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                description,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))

            val activeKey = editingKey
            if (activeKey != null) {
                Text(activeKey, style = PiTheme.text.mono, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(PiSpacing.unit))
                PiEditorField(
                    value = reserveText,
                    onValueChange = { reserveText = it; draftError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("预留上下文 (reserveTokens)", "Reserve tokens (reserveTokens)"),
                    placeholder = tr("留空则使用全局设置，如 16384", "Empty = global setting, e.g. 16384"),
                    height = PiNumberFieldHeight,
                    trailing = { Text("token", style = PiTheme.text.mono, color = PiTheme.palette.muted) },
                )
                Spacer(Modifier.height(PiSpacing.small))
                PiEditorField(
                    value = keepText,
                    onValueChange = { keepText = it; draftError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("保留近期内容 (keepRecentTokens)", "Keep recent tokens (keepRecentTokens)"),
                    placeholder = tr("留空则使用全局设置，如 20000", "Empty = global setting, e.g. 20000"),
                    height = PiNumberFieldHeight,
                    trailing = { Text("token", style = PiTheme.text.mono, color = PiTheme.palette.muted) },
                )
                draftError?.let {
                    Spacer(Modifier.height(PiSpacing.small))
                    Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error)
                }
                Spacer(Modifier.height(PiSpacing.unit))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline, Alignment.End),
                ) {
                    OutlinedButton(onClick = { editingKey = null; draftError = null }) { Text(tr("返回", "Back")) }
                    Button(
                        onClick = {
                            val rTrim = reserveText.trim()
                            val kTrim = keepText.trim()
                            val rVal = if (rTrim.isEmpty()) null else rTrim.toIntOrNull()?.takeIf { it >= 0 }
                            val kVal = if (kTrim.isEmpty()) null else kTrim.toIntOrNull()?.takeIf { it >= 0 }
                            if ((rTrim.isNotEmpty() && rVal == null) || (kTrim.isNotEmpty() && kVal == null)) {
                                draftError = tr("只接受非负整数。", "Only non-negative integers are accepted.")
                                return@Button
                            }
                            if (rVal == null && kVal == null) {
                                draftError = tr("请至少填写其中一项数值。", "Enter at least one value.")
                                return@Button
                            }
                            val next = CompactionOverrideDraft(activeKey, rVal, kVal)
                            items = items.filterNot { it.modelKey == activeKey } + next
                            editingKey = null
                            draftError = null
                        },
                    ) { Text(tr("确定", "OK")) }
                }
            } else if (pickingModel) {
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("搜索可用模型，或直接输入 provider/modelId", "Search models or enter provider/modelId"),
                    height = PiNumberFieldHeight,
                )
                Spacer(Modifier.height(PiSpacing.small))
                val needle = query.trim()
                val matched = remember(availableModels, needle) {
                    if (needle.isEmpty()) availableModels
                    else availableModels.filter { m ->
                        val full = "${m.provider.orEmpty()}/${m.id}"
                        full.contains(needle, ignoreCase = true) || m.name.contains(needle, ignoreCase = true)
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PiSettingsMetrics.sheetListMax)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (needle.contains('/') && needle.substringBefore('/').isNotBlank() && needle.substringAfter('/').isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { beginEdit(needle, items.firstOrNull { it.modelKey == needle }) }
                                .padding(vertical = PiOptionRowPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tr("使用自定义键：$needle", "Use custom key: $needle"),
                                style = PiTheme.text.mono,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    matched.forEach { m ->
                        val key = "${m.provider.orEmpty()}/${m.id}"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { beginEdit(key, items.firstOrNull { it.modelKey == key }) }
                                .padding(vertical = PiOptionRowPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name.ifBlank { m.id }, style = PiTheme.text.prose, color = MaterialTheme.colorScheme.onSurface)
                                Text(key, style = PiTheme.text.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(tr("选择 ›", "Select ›"), style = PiTheme.text.meta, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (matched.isEmpty() && !needle.contains('/')) {
                        Text(
                            if (availableModels.isEmpty()) {
                                tr(
                                    "未读到已连接模型；可直接输入 provider/modelId 添加。",
                                    "No connected models loaded; enter provider/modelId directly.",
                                )
                            } else {
                                tr("没有匹配的模型。", "No matching models.")
                            },
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(PiSpacing.small))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = { pickingModel = false }) { Text(tr("返回", "Back")) }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = PiSettingsMetrics.sheetBodyMax)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (items.isEmpty()) {
                        Text(
                            tr(
                                "还没有按模型覆盖；全部模型使用上面的全局压缩阈值。",
                                "No per-model overrides yet; all models use the global compaction settings above.",
                            ),
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        items.forEachIndexed { idx, item ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    thickness = PiSettingsMetrics.hairline,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { beginEdit(item.modelKey, item) }
                                    .padding(vertical = PiOptionRowPadding),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.modelKey, style = PiTheme.text.mono, color = MaterialTheme.colorScheme.onSurface)
                                    val detail = buildList {
                                        item.reserveTokens?.let { add(tr("预留 $it", "Reserve $it")) }
                                        item.keepRecentTokens?.let { add(tr("保留近期 $it", "Keep recent $it")) }
                                    }.joinToString(" · ")
                                    Text(detail, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { items = items.filterNot { it.modelKey == item.modelKey } }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = tr("删除覆盖", "Remove override"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(PiSpacing.unit))
                    TextButton(onClick = { query = ""; pickingModel = true }) {
                        Text(tr("+ 添加模型覆盖", "+ Add model override"))
                    }
                }
                HorizontalDivider(
                    thickness = PiSettingsMetrics.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline, Alignment.End),
                ) {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { items = emptyList() }) { Text(tr("清空", "Clear")) }
                    }
                    OutlinedButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
                    Button(
                        onClick = {
                            onSet(formatCompactionOverrideEntries(items))
                            onDismiss()
                        },
                    ) { Text(tr("保存", "Save")) }
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

/** 思考预算的一格：空、非负整数，其它都不收。超出 `Long` 的数字同样不收（那是打字错误）。 */
private fun parseThinkingBudget(text: String): Long? =
    text.trim().toLongOrNull()?.takeIf { it >= 0 }
