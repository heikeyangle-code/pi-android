package app.pi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.packages.AgentLayout
import app.pi.packages.EngineRestartCoordinator
import app.pi.packages.ExtensionLifecycle
import app.pi.packages.PiCredentialService
import app.pi.packages.PiModelScanner
import app.pi.packages.PiProviderPresets
import app.pi.rpc.PiResponses
import app.pi.runtime.PtyLauncher
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "选厂商 → 粘 Key → 扫描 → 勾选 → 保存" form.
 *
 * ## What this screen is, from pi's source
 *
 * It is **not** a transcription of a pi screen: pi has no credential editor. In
 * pi's TUI that job belongs to the login flow behind `/login` and to the OAuth
 * providers, and none of it is reachable over RPC — the `RpcCommand` union
 * (`modes/rpc/rpc-types.ts:20-74`) has no login/logout command at all. What pi
 * *does* settle, and what this screen writes, is the file format:
 *
 *  - credentials go to `<agentDir>/auth.json`, `Record<providerId, Credential>`
 *    (`core/auth-storage.ts:52`), written `0600` under pi's own lock
 *    (`:76-100`; the mode applies only at creation, `:24-25`);
 *  - providers and models go to `<agentDir>/models.json`, top-level
 *    `{ "providers": { "<id>": {…} } }` (`core/model-config.ts` `ModelsConfigSchema`,
 *    `:212-215`); in a model entry **only `id` is required** (`:162-176`), and the
 *    file is parsed with `stripJsonComments` (`:267`) so comments are legal.
 *    **pi never writes this file** — its dynamic model cache is `models-store.json`
 *    (`core/model-runtime.ts:180`) — and the runtime reads it only at startup
 *    (`:172-176`) and in `refresh()` (`:699`);
 *  - the *selection* — `defaultProvider` / `defaultModel` / `enabledModels` —
 *    goes to `settings.json` (`core/settings-manager.ts:108`, `:109`, `:139`).
 *
 * Two facts the screen states out loud rather than implying:
 *
 *  1. **"扫描模型" is the app's knowledge, not pi's.** `createProvider` accepts an
 *     optional `fetchModels` hook (`packages/ai/src/models.ts:763`, called at
 *     `:831`) and **no built-in provider implements it**; pi's catalogs are the
 *     static tables under `packages/ai/src/providers/`. The `GET /models` shapes
 *     in [PiModelScanner] are therefore app-side, and a failed scan never blocks
 *     the manual path (`Failed.allowManual` is always true).
 *  2. **A new provider needs a restart.** nothing on the RPC surface calls
 *     `ModelRuntime.refresh()` (`core/model-runtime.ts:699`) — `get_available_models`
 *     returns the cached snapshot (`modes/rpc/rpc-mode.ts:490-492`). pi's docs say
 *     `/model` reloads it, and in pi that is the **TUI** doing it
 *     (`modes/interactive/model-catalog-refresh.ts:22` calls `refresh`); over
 *     `--mode rpc` there is no such trigger.
 *
 * Also app-side, with no pi counterpart: the whole Compose navigation, the
 * step numbering and the restart confirmation (which reuses
 * [EngineRestartCoordinator], the same machine the package screen uses).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiCredentialScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /** The engine restart, exactly as [app.pi.packages.PiPackagesHost] takes it. */
    restartEngine: (suspend (reason: String, allowInterrupt: Boolean) -> EngineRestartCoordinator.Outcome)? = null,
    isTurnRunning: () -> Boolean = { false },
    /** `llamacpp` when opened from 本地模型, otherwise the first preset. */
    initialPresetId: String? = null,
    /** `get_available_models`: pi's metadata for models it already knows. */
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
    /**
     * Called after a successful save. `PiEnginePreferences` writes
     * `settings.json` through a store of its own, so the host's cached settings
     * documents are stale afterwards; the caller uses this to drop that cache.
     */
    onFilesWritten: () -> Unit = {},
) {
    val context = LocalContext.current
    val layout = remember(context) {
        AgentLayout(
            context = context.applicationContext,
            hostWorkspace = PtyLauncher.workspaceHost(context),
        )
    }
    val service = remember(layout) { PiCredentialService(layout, layout.hostWorkspace) }
    val lifecycle = remember { ExtensionLifecycle() }
    val coordinator = remember(layout, restartEngine) {
        restartEngine?.let { engine ->
            EngineRestartCoordinator(
                lifecycle = lifecycle,
                isTurnRunning = isTurnRunning,
                restartEngine = engine,
            )
        }
    }
    val scope = rememberCoroutineScope()
    val paths = remember(service) { service.paths() }

    var presetId by remember { mutableStateOf(initialPresetId ?: PiProviderPresets.all.first().id) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    var maskedKey by remember { mutableStateOf<String?>(null) }
    var modelsFileError by remember { mutableStateOf<String?>(null) }
    var existingIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanned by remember { mutableStateOf<List<PiModelScanner.ScannedModel>>(emptyList()) }
    var manualIds by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var defaultModelId by remember { mutableStateOf<String?>(null) }
    var scanEndpoint by remember { mutableStateOf<String?>(null) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var saveSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var savedOk by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var restartQuestion by remember { mutableStateOf<String?>(null) }
    var restartNote by remember { mutableStateOf<String?>(null) }

    val preset = PiProviderPresets.byId(presetId) ?: PiProviderPresets.all.first()

    // Opening the form is the pre-fill step: `prefill` reads auth.json and
    // models.json, so "新增" and "编辑已有" are the same screen with the same
    // fields (the requirement recorded in docs/known-gaps.md §E9).
    LaunchedEffect(presetId) {
        val existing = withContext(Dispatchers.IO) { service.prefill(presetId) }
        baseUrl = existing.baseUrl ?: preset.baseUrl
        api = existing.api ?: preset.api
        maskedKey = existing.maskedKey
        modelsFileError = existing.modelsFileError
        existingIds = existing.configuredModelIds
        apiKey = ""
        scanned = emptyList()
        manualIds = ""
        scanEndpoint = null
        scanNote = null
        scanError = null
        saveSteps = emptyList()
        savedOk = false
        saveError = null
        restartNote = null
        // Already-configured models start checked, so re-entering the screen to
        // add one model cannot silently drop the others (pi replaces a provider's
        // whole `models` array — docs/custom-provider.md:684).
        selected = existing.configuredModelIds.toSet()
        // `modelId` is the *current* default model, whatever provider it belongs
        // to (`PiCredentialService.prefill`), so it is only a default here when
        // the current provider is this one.
        defaultModelId = existing.modelId?.takeIf { existing.providerId == presetId }
            ?: existing.configuredModelIds.firstOrNull()
    }

    // The metadata source. Loaded once on entry; without an engine (or before a
    // provider exists) this stays empty and every scanned id is labelled as an
    // app default, which is the honest state.
    LaunchedEffect(Unit) { onLoadAvailableModels() }

    val manualList = remember(manualIds) {
        manualIds.split(',', '\n', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val candidates = remember(scanned, manualList, existingIds) {
        (scanned.map { it.id } + manualList + existingIds).distinct()
    }
    val knownById = remember(availableModels, presetId) {
        availableModels.filter { it.provider == presetId }.associateBy { it.id }
    }

    fun choicesFor(ids: List<String>): List<PiCredentialService.ModelChoice> = ids.map { id ->
        val known = knownById[id]
        PiCredentialService.ModelChoice(
            id = id,
            name = known?.name,
            reasoning = known?.reasoning,
            contextWindow = known?.contextWindow,
            maxTokens = known?.maxTokens,
            input = when {
                known == null -> emptyList()
                known.acceptsImages -> listOf("text", "image")
                else -> listOf("text")
            },
            // The label the UI draws; the flag itself is not written to pi.
            defaultsApplied = known == null,
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("厂商凭证") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            Note(
                "写入的是 pi 自己的文件：凭证 <agentDir>/auth.json（0600）、厂商与模型 " +
                    "<agentDir>/models.json、选择项 settings.json。不新建格式，也不经 settings.json 存 Key。",
            )
            Note(
                "本次写入的 host 路径：" + paths.first + "（pi 在 guest 里读它）；镜像：" + paths.second +
                    "。models.json 没有锁——pi 只在启动时读它，而且从不写它。",
            )
            if (modelsFileError != null) {
                Note("models.json 当前无法被 pi 解析：$modelsFileError。修好之前，写入的厂商不会生效。")
            }

            // ------------------------------------------------------------ 1 厂商
            PiSectionHeader("1 选厂商")
            Note(
                "pi 内置 10 家厂商的 baseUrl 与 api 抄自 packages/ai/src/providers/（不是猜的）；" +
                    "标「App 侧」的三条 pi 没有内置 provider（Ollama 与自定义端点要由 models.json 提供，" +
                    "正是这个界面在写的那份文件）。",
            )
            PiProviderPresets.all.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { presetId = option.id }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option.id == presetId, onClick = { presetId = option.id })
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            (if (option.builtInPi) "pi 内置" else "App 侧新增") + " · " + option.api,
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // --------------------------------------------------------------- 2 Key
            PiSectionHeader("2 粘 Key")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (maskedKey != null) {
                Note(
                    "auth.json 里已经有这个厂商的 Key（$maskedKey）。App 不回显 Key，" +
                        "pi 也不会回显，所以“留空＝保持原样”做不到：空 Key 会被写入端拒绝" +
                        "（auth.json 的 Key 不能为空）。要换 Key 就重新粘贴。",
                )
            }
            if (preset.scanStyle == PiProviderPresets.ScanStyle.Keyless) {
                Note(
                    "这个端点不校验鉴权，但 pi 仍然要求 auth.json 里有凭证，否则不会列出该厂商的模型" +
                        "（docs/models.md:37）。留空会写入占位值「" +
                        PiProviderPresets.KEYLESS_PLACEHOLDER + "」。",
                )
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = api,
                onValueChange = { api = it },
                label = { Text("api（协议实现，pi 的 KnownApi 字面量）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Note(
                "api 不是装饰：它决定 pi 用哪套流式实现（packages/ai/src/types.ts:17-27）。写错时" +
                    "厂商会注册成功、第一条消息才失败，所以这里从预设带出来，不要凭印象改。",
            )

            // ------------------------------------------------------------ 3 扫描
            PiSectionHeader("3 检测并扫描模型")
            Note(
                "“扫描模型”不是 pi 的能力，是 App 侧知识：pi 的 createProvider 有一个可选的 fetchModels 钩子" +
                    "（packages/ai/src/models.ts:763，调用点 :831），但没有任何内置 provider 实现它；" +
                    "pi 的清单是 providers/ 下的静态表。这里按厂商分派 GET /models 等请求，成功即等于 Key 可用。",
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !scanning,
                    onClick = {
                        scope.launch {
                            scanning = true
                            scanError = null
                            scanNote = null
                            scanEndpoint = null
                            try {
                                when (val result = service.probe(preset, apiKey, baseUrl.ifBlank { null })) {
                                    is PiModelScanner.Result.Ok -> {
                                        scanned = result.models
                                        scanEndpoint = result.endpoint
                                        scanNote = result.note
                                        // One tap for the common case: scan, then save.
                                        // A selection the user already made is kept.
                                        if (selected.isEmpty()) {
                                            selected = result.models.map { it.id }.toSet()
                                            defaultModelId = defaultModelId
                                                ?: result.models.firstOrNull()?.id
                                        }
                                    }

                                    is PiModelScanner.Result.Failed -> {
                                        // `allowManual` is always true, so a failure is
                                        // never a dead end — the field below stays usable.
                                        scanError = result.message + "\n" + result.suggestion
                                        scanEndpoint = result.endpoint
                                    }
                                }
                            } finally {
                                // The button is disabled while scanning, so a thrown
                                // scan must not leave it stuck forever.
                                scanning = false
                            }
                        }
                    },
                ) {
                    Text(if (scanning) "正在扫描…" else "检测并扫描模型")
                }
                if (scanEndpoint != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(scanEndpoint.orEmpty(), style = PiTheme.text.monoSmall)
                }
            }
            if (scanNote != null) Note(scanNote.orEmpty())
            if (scanError != null) Note(scanError.orEmpty())

            // ------------------------------------------------------ 4 勾选与保存
            PiSectionHeader("4 勾选并保存")
            Note(
                "左边勾选参与 Ctrl+P 循环的模型（写进 settings.json 的 enabledModels，" +
                    "settings-manager.ts:139）；右边选默认模型（defaultModel）。" +
                    "扫到的 id 能对上 pi 已有清单的用 pi 的元数据，对不上的用 App 默认值并标注。",
            )
            OutlinedTextField(
                value = manualIds,
                onValueChange = { manualIds = it },
                label = { Text("手动模型名（逗号、空格或换行分隔）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            candidates.forEach { id ->
                val known = knownById[id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = id in selected,
                        onCheckedChange = { on ->
                            selected = if (on) selected + id else selected - id
                            if (!on && defaultModelId == id) defaultModelId = null
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(id, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (known != null) {
                                "pi 元数据：" + known.name +
                                    (known.contextWindow?.let { " · 上下文 $it" } ?: "")
                            } else {
                                "默认值，可改（pi 的清单里没有匹配到它）"
                            },
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(selected = defaultModelId == id, onClick = { defaultModelId = id })
                }
            }
            if (candidates.isEmpty()) {
                Note("还没有候选模型：先扫描，或在上面的字段里手动填一个模型名。")
            }

            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !scanning && selected.isNotEmpty(),
                    onClick = {
                        val chosen = candidates.filter { it in selected }
                        scope.launch {
                            saveError = null
                            saveSteps = emptyList()
                            savedOk = false
                            val result = withContext(Dispatchers.IO) {
                                service.save(
                                    preset = preset,
                                    apiKey = apiKey,
                                    baseUrl = baseUrl,
                                    api = api,
                                    choices = choicesFor(chosen),
                                    defaultModelId = defaultModelId?.takeIf { it in selected },
                                )
                            }
                            saveSteps = result.steps
                            savedOk = result.ok
                            if (result.ok) {
                                // The settings documents changed on disk through
                                // another writer, so the host's cached copy is
                                // stale from here on.
                                onFilesWritten()
                                val restart = result.restart
                                if (restart != null) {
                                    // Same machine as the package screen: the files
                                    // are on disk, pi has not seen them, and the user
                                    // is told rather than restarted silently.
                                    lifecycle.installSucceeded(restart.changes, restart.detail)
                                    restartNote = restart.detail
                                }
                            } else {
                                saveError = result.steps.lastOrNull()
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selected.isEmpty()) "至少要勾选一个模型" else "已选 ${selected.size} 个",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            saveSteps.forEach { step -> Note(step) }
            if (saveError != null) Note(saveError.orEmpty())

            if (savedOk && restartNote != null) {
                Note(restartNote.orEmpty())
                OutlinedButton(
                    onClick = {
                        val engine = coordinator
                        if (engine == null) {
                            restartNote = "重启未接入：设置页还没有拿到引擎的 restart()。" +
                                "文件已经写好，重启 App 或引擎后生效。"
                        } else {
                            when (engine.request("新增厂商需要被 pi 重新加载")) {
                                ExtensionLifecycle.RequestOutcome.NeedsConfirmation -> {
                                    val waiting =
                                        lifecycle.current as? ExtensionLifecycle.State.AwaitingConfirmation
                                    restartQuestion = waiting?.question ?: "重启引擎以加载新厂商？"
                                }

                                ExtensionLifecycle.RequestOutcome.WaitingForTurn ->
                                    restartNote = "有回合正在运行。重启会中断模型调用与正在执行的工具，" +
                                        "所以这次没有重启；回合结束后再点一次。"

                                else -> restartNote = "当前没有待重启的资源变更（可能已经重启过）。"
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("重启引擎")
                }
            }

            PiSectionHeader("说明")
            Note(
                "models.json 允许注释：pi 在解析前会过 stripJsonComments" +
                    "（packages/coding-agent/src/core/model-config.ts:267），所以手写的带注释文件不会被判成损坏。",
            )
            Note(
                "一个厂商的 models 一旦提供就替换该厂商的全部模型，不是追加；provider 块本身覆盖在内置同名" +
                    "provider 之上（docs/custom-provider.md:684、:33）。所以勾选时要想清楚这是不是全部要用的模型。",
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    val question = restartQuestion
    if (question != null) {
        AlertDialog(
            onDismissRequest = { restartQuestion = null },
            title = { Text("重启引擎") },
            text = { Text(question) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restartQuestion = null
                        val engine = coordinator
                        if (engine != null) {
                            scope.launch {
                                restartNote = when (val outcome = engine.confirm("用户确认重启引擎")) {
                                    is EngineRestartCoordinator.Outcome.Ok ->
                                        "引擎已重启；新厂商现在会出现在模型列表里。"

                                    is EngineRestartCoordinator.Outcome.Refused -> outcome.message
                                    is EngineRestartCoordinator.Outcome.Failed -> outcome.message
                                }
                            }
                        }
                    },
                ) { Text("重启") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        restartQuestion = null
                        lifecycle.cancelRestart()
                    },
                ) { Text("取消") }
            },
        )
    }
}

/** One paragraph of explanation, in the muted style the settings screens use. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
