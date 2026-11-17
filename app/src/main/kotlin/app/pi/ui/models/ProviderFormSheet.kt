package app.pi.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.models.ProviderForm
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsToggleRow
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch

/**
 * 自定义供应商（`models.json`）的表单：新增、编辑，以及**覆盖内置供应商**。
 *
 * SDK 里 `models.json` 的 provider 与内置供应商同 id 就是覆盖（只改 baseUrl / 请求头 / 兼容项，
 * 不必重新声明模型），走代理最常见——所以同名不是错误，只是说明白它在覆盖谁。字段对照 SDK 的
 * provider schema：`name` `baseUrl` `api` `apiKey` `authHeader` `headers` `models[]`，
 * 模型的 `cost` 四项（input / output / cacheRead / cacheWrite）单位是美元 / 百万 token。
 * 校验是 SDK 的事：非法会回滚，并带回字段精确的原话，原样显示。
 */
@Composable
internal fun ProviderFormSheet(
    initial: ProviderForm,
    isNew: Boolean,
    customIds: Set<String>,
    knownIds: Set<String>,
    host: () -> ModelsHost?,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var f by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 展开的模型（一次只展开一个，新加的自动展开）。
    var open by remember { mutableStateOf<Int?>(if (initial.models.size == 1) 0 else null) }

    val idCheck = if (isNew) checkNewProviderId(f.id, customIds, knownIds) else IdCheck.Ok
    val overriding = if (isNew) idCheck == IdCheck.OverridesBuiltin else initial.id in knownIds

    fun updateModel(i: Int, fn: (ProviderForm.ModelForm) -> ProviderForm.ModelForm) {
        f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) fn(x) else x })
    }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                when {
                    isNew && overriding -> "覆盖内置供应商"
                    isNew -> "添加自定义供应商"
                    overriding -> "编辑覆盖：${initial.id}"
                    else -> "编辑 ${initial.id}"
                },
                style = PiTheme.text.prose,
                color = PiTheme.palette.text,
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
            )

            // ---- 基本 ----
            ModelsCard {
                Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isNew) {
                        LabeledField("供应商 ID", f.id, "例如 my-proxy；与内置同名即覆盖它") { f = f.copy(id = it) }
                        when (idCheck) {
                            IdCheck.AlreadyCustom -> Text("models.json 里已经有「${f.id.trim()}」了，请直接编辑它。", style = PiTheme.text.meta, color = PiTheme.palette.error)
                            IdCheck.OverridesBuiltin -> Text("「${f.id.trim()}」是内置供应商：保存后只覆盖你填写的地址、请求头等，它自带的模型照常可用。", style = PiTheme.text.meta, color = PiTheme.palette.accent)
                            else -> Unit
                        }
                    }
                    LabeledField("显示名（可空）", f.name, "出现在列表里的名字") { f = f.copy(name = it) }
                    LabeledField("Base URL", f.baseUrl, "https://example.com/v1") { f = f.copy(baseUrl = it) }
                    Text("协议（api）", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in COMMON_APIS) PiSettingsChip(text = c.label, active = f.api == c.api, onClick = { f = f.copy(api = c.api) })
                    }
                    LabeledField("其他协议或留空", f.api, "openai-completions") { f = f.copy(api = it) }
                }
            }

            // ---- 凭据与请求 ----
            ModelsCard {
                Column {
                    Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabeledField("API Key", f.apiKey, "密钥、环境变量名，或 !命令", secret = true) { f = f.copy(apiKey = it) }
                        LabeledField("额外请求头（每行 名称: 值）", f.headersText, "X-Org: abc", singleLine = false) { f = f.copy(headersText = it) }
                    }
                    ModelsDivider()
                    PiSettingsToggleRow(
                        title = "用 Authorization 头发密钥",
                        supporting = "对应 authHeader：Bearer 方式；不开则按协议默认的头",
                        checked = f.authHeader,
                        onCheckedChange = { f = f.copy(authHeader = it) },
                    )
                }
            }

            // ---- 模型 ----
            ModelsSectionLabel(
                if (overriding && f.models.isEmpty()) "模型（可不填：沿用内置的）" else "模型（${f.models.size}）",
            )
            f.models.forEachIndexed { i, m ->
                ModelsCard {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { open = if (open == i) null else i }
                                .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                m.name.ifBlank { m.id }.ifBlank { "新模型" },
                                style = PiTheme.text.prose,
                                color = PiTheme.palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (open == i) "收起" else "编辑", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        }
                        if (open == i) {
                            ModelsDivider()
                            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                LabeledField("模型 ID", m.id, "gpt-x") { v -> updateModel(i) { it.copy(id = v) } }
                                LabeledField("显示名（可空）", m.name, "") { v -> updateModel(i) { it.copy(name = v) } }
                                LabeledField("上下文窗口（token，可空）", m.contextWindow, "128000") { v -> updateModel(i) { it.copy(contextWindow = v) } }
                                LabeledField("最大输出（token，可空）", m.maxTokens, "8192") { v -> updateModel(i) { it.copy(maxTokens = v) } }
                                Text("价格（美元 / 百万 token，全可空）", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LabeledField("输入", m.costInput, "3", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costInput = v) } }
                                    LabeledField("输出", m.costOutput, "15", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costOutput = v) } }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LabeledField("缓存读", m.costCacheRead, "0.3", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costCacheRead = v) } }
                                    LabeledField("缓存写", m.costCacheWrite, "3.75", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costCacheWrite = v) } }
                                }
                            }
                            ModelsDivider()
                            PiSettingsToggleRow("推理模型", null, m.reasoning, { v -> updateModel(i) { it.copy(reasoning = v) } })
                            ModelsDivider()
                            PiSettingsToggleRow("接受图片", null, m.acceptsImages, { v -> updateModel(i) { it.copy(acceptsImages = v) } })
                            ModelsDivider()
                            Row(Modifier.padding(ModelsCardPadding)) {
                                PiSettingsChip(text = "删除这个模型", onClick = {
                                    f = f.copy(models = f.models.filterIndexed { j, _ -> j != i })
                                    open = null
                                })
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
                PiSettingsChip(text = "添加模型", onClick = {
                    f = f.copy(models = f.models + ProviderForm.ModelForm())
                    open = f.models.lastIndex
                })
            }

            error?.let {
                Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error, modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small))
            }
        }

        // ---- 底栏：保存永远可见，不用滚到底 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryAction(if (saving) "保存中…" else "保存", enabled = !saving, onClick = {
                val id = (if (isNew) f.id else initial.id).trim()
                when {
                    id.isEmpty() -> { error = "供应商 ID 不能为空。"; return@PrimaryAction }
                    isNew && idCheck == IdCheck.AlreadyCustom -> { error = "models.json 里已经有「$id」了，请直接编辑它。"; return@PrimaryAction }
                }
                val h = host() ?: run { error = "引擎没在运行。"; return@PrimaryAction }
                when (val r = f.toJson()) {
                    is ProviderForm.Result.Invalid -> error = r.message
                    is ProviderForm.Result.Ok -> {
                        saving = true
                        error = null
                        scope.launch {
                            runCatching { h.advanced.setProvider(id, r.json) }
                                .onSuccess { onDone() }
                                .onFailure { error = it.message; saving = false }
                        }
                    }
                }
            })
            if (!isNew) {
                PiSettingsChip(text = if (overriding) "移除覆盖" else "删除供应商", enabled = !saving, onClick = {
                    val h = host() ?: return@PiSettingsChip
                    scope.launch {
                        runCatching { h.advanced.removeProvider(initial.id) }
                            .onSuccess { onDone() }
                            .onFailure { error = it.message }
                    }
                })
            }
            PiSettingsChip(text = "取消", onClick = onDismiss)
        }
    }
}
