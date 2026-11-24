package app.pi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import app.pi.ui.components.PiDivider
import app.pi.ui.components.PiIconSquareButton
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSheetActionBar
import app.pi.ui.components.PiTextAction
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「思考预算 (`thinkingBudgets`)」专用编辑器：固定 4 档 token 输入，留空 = 厂商默认。
 */
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
            Text(title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(description, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))
            keys.forEach { key ->
                PiEditorField(
                    value = values[key].orEmpty(),
                    onValueChange = { values[key] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = thinkingBudgetLabel(key),
                    placeholder = thinkingBudgetHint(key),
                    height = PiNumberFieldHeight,
                    trailing = {
                        Text("token", style = PiTheme.text.mono, color = PiTheme.palette.muted)
                    },
                )
                Spacer(Modifier.height(PiSpacing.small))
            }
            Spacer(Modifier.height(PiSpacing.inline))
            Text(
                if (invalid) tr("只接受非负整数", "Only non-negative integers are accepted")
                else tr("留空 = 用厂商默认", "Empty = provider default"),
                style = PiTheme.text.meta,
                color = if (invalid) PiTheme.palette.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.inline))
            PiDivider()
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
            PiSheetActionBar {
                if (keys.any { values[it].orEmpty().isNotBlank() }) {
                    PiTextAction(
                        label = tr("清空", "Clear"),
                        tone = PiTheme.palette.muted,
                        onClick = { keys.forEach { values[it] = "" } },
                    )
                }
                PiSecondaryButton(label = tr("取消", "Cancel"), onClick = onDismiss)
                PiPrimaryButton(
                    label = tr("保存", "Save"),
                    enabled = !invalid,
                    onClick = {
                        onSet(
                            keys.mapNotNull { key ->
                                values[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$key = $it" }
                            },
                        )
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

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
 * 「按模型压缩阈值覆盖 (`compaction.modelOverrides`)」结构化编辑器（零原生 M3 Button）。
 */
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
            Text(title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(description, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                PiDivider()
                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                PiSheetActionBar {
                    PiSecondaryButton(
                        label = tr("返回", "Back"),
                        onClick = { editingKey = null; draftError = null },
                    )
                    PiPrimaryButton(
                        label = tr("确定", "OK"),
                        onClick = {
                            val rTrim = reserveText.trim()
                            val kTrim = keepText.trim()
                            val rVal = if (rTrim.isEmpty()) null else rTrim.toIntOrNull()?.takeIf { it >= 0 }
                            val kVal = if (kTrim.isEmpty()) null else kTrim.toIntOrNull()?.takeIf { it >= 0 }
                            if ((rTrim.isNotEmpty() && rVal == null) || (kTrim.isNotEmpty() && kVal == null)) {
                                draftError = tr("只接受非负整数。", "Only non-negative integers are accepted.")
                                return@PiPrimaryButton
                            }
                            if (rVal == null && kVal == null) {
                                draftError = tr("请至少填写其中一项数值。", "Enter at least one value.")
                                return@PiPrimaryButton
                            }
                            val next = CompactionOverrideDraft(activeKey, rVal, kVal)
                            items = items.filterNot { it.modelKey == activeKey } + next
                            editingKey = null
                            draftError = null
                        },
                    )
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
                                Text(m.name.ifBlank { m.id }, style = PiType.rowTitle, color = MaterialTheme.colorScheme.onSurface)
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
                PiDivider()
                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                PiSheetActionBar {
                    PiSecondaryButton(label = tr("返回", "Back"), onClick = { pickingModel = false })
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
                            if (idx > 0) PiDivider()
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
                                PiIconSquareButton(
                                    icon = Icons.Filled.Delete,
                                    contentDescription = tr("删除覆盖", "Remove override"),
                                    tint = PiTheme.palette.muted,
                                    onClick = { items = items.filterNot { it.modelKey == item.modelKey } },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PiTextAction(
                        label = tr("添加模型覆盖", "Add model override"),
                        leadingIcon = Icons.Filled.Add,
                        onClick = { query = ""; pickingModel = true },
                    )
                }
                Spacer(Modifier.height(8.dp))
                PiDivider()
                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                PiSheetActionBar {
                    if (items.isNotEmpty()) {
                        PiTextAction(
                            label = tr("清空", "Clear"),
                            tone = PiTheme.palette.muted,
                            onClick = { items = emptyList() },
                        )
                    }
                    PiSecondaryButton(label = tr("取消", "Cancel"), onClick = onDismiss)
                    PiPrimaryButton(
                        label = tr("保存", "Save"),
                        onClick = {
                            onSet(formatCompactionOverrideEntries(items))
                            onDismiss()
                        },
                    )
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}

private fun parseThinkingBudget(text: String): Long? =
    text.trim().toLongOrNull()?.takeIf { it >= 0 }
