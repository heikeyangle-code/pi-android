package app.pi.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.models.ProviderForm
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsDialog
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsToggleRow
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch

/**
 * 自定义供应商（`models.json`）表单：新增、编辑，以及覆盖内置供应商。
 * 字段严格对齐 SDK provider schema：`name` `baseUrl` `api` `apiKey` `authHeader` `headers` `models[]`。
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
    val clipboard = LocalClipboardManager.current
    var f by remember(initial) { mutableStateOf(initial) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var open by remember(initial) { mutableStateOf<Int?>(if (initial.models.size == 1) 0 else null) }

    val idCheck = if (isNew) checkNewProviderId(f.id, customIds, knownIds) else IdCheck.Ok
    val overriding = if (isNew) idCheck == IdCheck.OverridesBuiltin else initial.id in knownIds
    val palette = PiTheme.palette

    fun updateModel(i: Int, fn: (ProviderForm.ModelForm) -> ProviderForm.ModelForm) {
        f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) fn(x) else x })
    }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    when {
                        isNew && overriding -> tr("覆盖内置供应商", "Override Built-in Provider")
                        isNew -> tr("添加自定义供应商", "Add Custom Provider")
                        overriding -> tr("编辑内置覆盖：${initial.id}", "Edit Override: ${initial.id}")
                        else -> tr("编辑自定义供应商：${initial.id}", "Edit Provider: ${initial.id}")
                    },
                    style = PiType.title,
                    color = palette.text,
                )
                Text(
                    tr("写入 ~/.pi/agent/models.json 并由 Pi SDK 实时校验", "Writes to ~/.pi/agent/models.json with live Pi SDK validation"),
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }

            // ---- 1. 基本信息与协议 ----
            ModelsSectionLabel(tr("基本信息与端点", "Endpoint & Protocol"))
            ModelsCard {
                Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isNew) {
                        LabeledField(
                            label = tr("供应商 ID", "Provider ID"),
                            value = f.id,
                            placeholder = tr("例如 my-proxy；与内置同名即覆盖它", "e.g. my-proxy; matches a built-in ID to override it"),
                        ) { f = f.copy(id = it) }
                        when (idCheck) {
                            IdCheck.AlreadyCustom -> Text(
                                tr(
                                    "models.json 里已经有「${f.id.trim()}」了，请直接编辑它。",
                                    "models.json already defines \"${f.id.trim()}\". Please edit it directly.",
                                ),
                                style = PiTheme.text.meta,
                                color = palette.error,
                            )
                            IdCheck.OverridesBuiltin -> Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = palette.accent.copy(alpha = 0.10f),
                                border = BorderStroke(0.5.dp, palette.accent.copy(alpha = 0.30f)),
                            ) {
                                Text(
                                    tr(
                                        "「${f.id.trim()}」是内置供应商：保存后只覆盖你填写的地址与请求头，内置模型照常可用。",
                                        "\"${f.id.trim()}\" is a built-in provider: saving overrides only the URL and headers while keeping built-in models.",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = palette.accent,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                            else -> Unit
                        }
                    }
                    LabeledField(
                        label = tr("显示名（可选）", "Display Name (optional)"),
                        value = f.name,
                        placeholder = tr("列表里展示的名称，留空则使用 ID", "Name shown in list; defaults to ID"),
                    ) { f = f.copy(name = it) }
                    LabeledField(
                        label = "Base URL",
                        value = f.baseUrl,
                        placeholder = "https://api.example.com/v1",
                    ) { f = f.copy(baseUrl = it) }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            tr("接口协议（api）", "API Protocol"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = palette.text.copy(alpha = 0.82f),
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (c in COMMON_APIS) {
                                PiSettingsChip(
                                    text = c.label,
                                    active = f.api == c.api,
                                    onClick = { f = f.copy(api = c.api) },
                                )
                            }
                        }
                    }
                    LabeledField(
                        label = tr("协议标识（可自填或留空）", "Protocol Identifier (custom or blank)"),
                        value = f.api,
                        placeholder = "openai-completions",
                    ) { f = f.copy(api = it) }
                }
            }

            // ---- 2. 凭据与请求头 ----
            ModelsSectionLabel(tr("凭据与请求头", "Credentials & Headers"))
            ModelsCard {
                Column {
                    Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledField(
                            label = "API Key",
                            value = f.apiKey,
                            placeholder = tr("直接粘贴密钥、环境变量名，或 !命令", "Paste key, env var name, or !command"),
                            secret = true,
                            labelTrailing = {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.accent.copy(alpha = 0.12f))
                                        .clickable {
                                            val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                            if (pasted.isNotEmpty()) f = f.copy(apiKey = pasted)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.ContentPaste,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        tr("粘贴", "Paste"),
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                        color = palette.accent,
                                    )
                                }
                            },
                        ) { f = f.copy(apiKey = it) }
                        LabeledField(
                            label = tr("额外请求头（每行 名称: 值）", "Extra Headers (Name: Value per line)"),
                            value = f.headersText,
                            placeholder = "X-Org: my-org",
                            singleLine = false,
                        ) { f = f.copy(headersText = it) }
                    }
                    ModelsDivider()
                    PiSettingsToggleRow(
                        title = tr("用 Authorization Bearer 头发密钥", "Send Key via Authorization Bearer"),
                        supporting = tr("对应 authHeader；关闭则使用协议默认认证头", "Maps to authHeader; off uses the protocol default header"),
                        checked = f.authHeader,
                        onCheckedChange = { f = f.copy(authHeader = it) },
                    )
                }
            }

            // ---- 3. 自定义模型 ----
            ModelsSectionLabel(
                if (overriding && f.models.isEmpty()) tr("模型（可选：沿用内置目录）", "Models (optional: inherits built-in)")
                else tr("模型（${f.models.size}）", "Models (${f.models.size})"),
                trailing = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.accent.copy(alpha = 0.13f))
                            .clickable {
                                f = f.copy(models = f.models + ProviderForm.ModelForm())
                                open = f.models.lastIndex
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            tr("添加模型", "Add Model"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.accent,
                        )
                    }
                },
            )
            f.models.forEachIndexed { i, m ->
                val expanded = open == i
                ModelsCard(accent = expanded) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { open = if (expanded) null else i }
                                .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    m.name.ifBlank { m.id }.ifBlank { tr("新模型 #${i + 1}", "New Model #${i + 1}") },
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                    color = palette.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (m.id.isNotBlank() && m.name.isNotBlank() && m.name != m.id) {
                                    Text(m.id, style = PiTheme.text.monoSmall, color = palette.dim, maxLines = 1)
                                }
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = palette.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (expanded) {
                            ModelsDivider()
                            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                LabeledField(tr("模型 ID", "Model ID"), m.id, "gpt-4o") { v -> updateModel(i) { it.copy(id = v) } }
                                LabeledField(tr("显示名（可选）", "Display Name (optional)"), m.name, "") { v -> updateModel(i) { it.copy(name = v) } }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LabeledField(tr("上下文窗口", "Context Window"), m.contextWindow, "128000", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(contextWindow = v) } }
                                    LabeledField(tr("最大输出", "Max Output"), m.maxTokens, "8192", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(maxTokens = v) } }
                                }
                                Text(
                                    tr("价格（美元 / 百万 token，均可选）", "Pricing (USD / 1M tokens, optional)"),
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LabeledField(tr("输入", "Input"), m.costInput, "3", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costInput = v) } }
                                    LabeledField(tr("输出", "Output"), m.costOutput, "15", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costOutput = v) } }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LabeledField(tr("缓存读", "Cache Read"), m.costCacheRead, "0.3", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costCacheRead = v) } }
                                    LabeledField(tr("缓存写", "Cache Write"), m.costCacheWrite, "3.75", Modifier.weight(1f)) { v -> updateModel(i) { it.copy(costCacheWrite = v) } }
                                }
                            }
                            ModelsDivider()
                            PiSettingsToggleRow(tr("支持深度思考（推理模型）", "Reasoning Model"), null, m.reasoning, { v -> updateModel(i) { it.copy(reasoning = v) } })
                            ModelsDivider()
                            PiSettingsToggleRow(tr("支持视觉图片输入", "Accepts Images"), null, m.acceptsImages, { v -> updateModel(i) { it.copy(acceptsImages = v) } })
                            ModelsDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(ModelsCardPadding),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                DestructiveAction(
                                    text = tr("删除该模型", "Remove Model"),
                                    onClick = {
                                        f = f.copy(models = f.models.filterIndexed { j, _ -> j != i })
                                        open = null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            error?.let {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.error.copy(alpha = 0.10f),
                    border = BorderStroke(0.5.dp, palette.error.copy(alpha = 0.35f)),
                ) {
                    Text(
                        it,
                        style = PiTheme.text.meta,
                        color = palette.error,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // ---- 吸底操作栏 ----
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = palette.cardBg,
            border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.45f)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isNew) {
                    DestructiveAction(
                        text = if (overriding) tr("移除覆盖", "Remove") else tr("删除", "Delete"),
                        enabled = !saving,
                        onClick = { confirmDelete = true },
                    )
                }
                SecondaryAction(
                    text = tr("取消", "Cancel"),
                    onClick = onDismiss,
                )
                PrimaryAction(
                    text = if (saving) tr("保存中…", "Saving…") else tr("保存配置", "Save"),
                    enabled = !saving,
                    fullWidth = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val id = (if (isNew) f.id else initial.id).trim()
                        when {
                            id.isEmpty() -> {
                                error = tr("供应商 ID 不能为空。", "Provider ID cannot be empty.")
                                return@PrimaryAction
                            }
                            isNew && idCheck == IdCheck.AlreadyCustom -> {
                                error = tr("models.json 里已经有「$id」了，请直接编辑它。", "models.json already defines \"$id\". Please edit it directly.")
                                return@PrimaryAction
                            }
                        }
                        val h = host() ?: run {
                            error = tr("引擎没在运行。", "Engine is not running.")
                            return@PrimaryAction
                        }
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
                    },
                )
            }
        }
    }

    if (confirmDelete && !isNew) {
        PiSettingsDialog(
            onDismissRequest = { confirmDelete = false },
            title = if (overriding) tr("移除「${initial.id}」的自定义覆盖？", "Remove override for \"${initial.id}\"?")
            else tr("删除自定义供应商「${initial.id}」？", "Delete provider \"${initial.id}\"?"),
            body = if (overriding) {
                tr("移除后将恢复使用该内置供应商的默认地址与模型目录。", "Restores the built-in provider's default endpoint and model catalog.")
            } else {
                tr("将从 models.json 中删除此供应商及其自定义模型。", "Removes this custom provider and its models from models.json.")
            },
            confirmationLabel = if (overriding) tr("移除覆盖", "Remove Override") else tr("删除", "Delete"),
            confirmationTone = palette.error,
            onConfirm = {
                confirmDelete = false
                val h = host() ?: return@PiSettingsDialog
                saving = true
                scope.launch {
                    runCatching { h.advanced.removeProvider(initial.id) }
                        .onSuccess { onDone() }
                        .onFailure { error = it.message; saving = false }
                }
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmDelete = false },
        )
    }
}
