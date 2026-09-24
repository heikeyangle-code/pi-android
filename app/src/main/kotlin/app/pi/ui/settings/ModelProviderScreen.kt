package app.pi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pi.packages.AgentLayout
import app.pi.packages.EngineRestartCoordinator
import app.pi.packages.ExtensionLifecycle
import app.pi.packages.PiCredentialService
import app.pi.packages.PiModelCatalog
import app.pi.packages.PiModelInventory
import app.pi.packages.PiModelScanner
import app.pi.packages.PiOfficialCatalog
import app.pi.packages.PiProviderPresets
import app.pi.rpc.PiResponses
import app.pi.runtime.PtyLauncher
import app.pi.ui.PiTopBar
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * 搬进「模型与供应商」的设置键：三个**选择**键（默认厂商 / 默认模型 / 循环列表）＋
 * pi「模型与推理」分组里那几行**推理**设置。
 *
 * 两处用同一份名单：分组屏不再渲染它们（编辑器已经搬进来），设置栈用它把
 * `openSetting(key)`（搜索结果、`/scoped-models` 焦点）路由到本屏的就地编辑器 ——
 * 名单分叉就会出现"点了没反应"。
 */
internal val MODEL_SELECTION_KEYS = setOf(
    "defaultProvider", "defaultModel", "enabledModels",
    "defaultThinkingLevel", "modelThinkingLevels", "thinkingBudgets",
    "hideThinkingBlock", "cacheWarming", "showCacheMissNotices",
)

/**
 * 「模型与供应商」——这台设备上**官方配置文件的那几份**的编辑器，一屏装下清单、
 * 导入、凭证、选择与重启，不再有第二屏。
 *
 * ## 官方文件 → 屏上分区（这一屏不造任何私有格式）
 *
 * | 官方文件 | 屏上 |
 * |---|---|
 * | `models.json`（厂商块 + 手写模型声明） | ③ 厂商卡的连接信息、模型清单、导入 sheet 的保存 |
 * | `auth.json`（凭证） | 厂商卡上的「已配置凭证 / 还没有凭证」、sheet 的 Key、删除厂商 |
 * | `settings.json` 的三个选择键 | ② 默认厂商 / 默认模型 / 循环模型（同一个 `PiSettingEditorSheet`） |
 * | `models-store.json`（pi 目录缓存） | 只读：导入 sheet 的候选列表优先列它、标签也取它 |
 *
 * 字段以 pi 源码为准：`models.json` 的 schema 是 `core/model-config.ts:199-215`（模型条目
 * 只有 `id` 必填，`:162-176`；文件带注释合法，`:267`）；凭证是 `core/auth-storage.ts:52`
 * 的 `Record<providerId, Credential>`；三个选择键在 `core/settings-manager.ts:751/108/139`。
 * **pi 自己从不写 `models.json`**（它的动态缓存是 `models-store.json`，
 * `core/model-runtime.ts:180`），所以这一屏的写入全部走合并：App 拥有自己的键，
 * pi 与用户已有的键原样保留（`PiModelsMerge`，19 条 harness 钉着）。
 *
 * ## AI 改了文件，这一屏怎么跟上
 *
 * 两条既有机制，这一屏都装上了：
 *
 *  1. **直读不缓存**：清单每次由 `PiCredentialService.inventory` 从文件原文拼装，
 *     绕开 settings store 的缓存 —— 对话里让模型写完 `models.json`/`auth.json`，
 *     读到的就是刚落盘的那份；
 *  2. **inotify 监视**：`PiDirectoryWatch` 盯着引擎 agent 目录与工作区 `.pi`
 *     （`PiCredentialService.MODEL_FILES` 四份文件，前缀匹配），文件一变
 *     `reloadTick++` → 200ms 合并一次重读。AI 新增的厂商/模型**当场出现在③**。
 *
 * 文件出现 ≠ 引擎已加载（pi 的真实边界：RPC 下没有任何命令调 `ModelRuntime.refresh()`，
 * `get_available_models` 返回启动期快照，`modes/rpc/rpc-mode.ts:490-493`），所以这类条目
 * 由 `PiModelInventory` 标 `PENDING_RESTART`，屏上给 ① 的重启卡 —— 说的是 pi 的事实，
 * 不是本屏的承诺。
 *
 * ## 官方没有、本屏自己加的（都只写官方文件）
 *
 * 状态标签（现在可用 / 等待重启 / 缺凭证）、探测扫描（App 侧知识，pi 的
 * `createProvider.fetchModels` 无内置实现）、删除厂商（`PiCredentialService.remove`，
 * 以前没有任何入口）、重启卡。订阅登录不在此屏：pi 的 RPC 命令联合里没有 login
 * （`modes/rpc/rpc-types.ts:20-74`），`/login` 只能去终端页 —— 这句话写在分组屏那行的
 * 描述里，屏上不重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProviderScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /** 全 App 同一套「需要重启」状态机，由设置栈持有（见 `PiSettingsStack`）。 */
    lifecycle: ExtensionLifecycle,
    /** 重启的执行者。null = 没有引擎入口：界面说清不能重启，不给假按钮。 */
    coordinator: EngineRestartCoordinator?,
    store: PiSettingsStore,
    /** `get_available_models`：引擎现在真正列出的模型。 */
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
    /** 直接打开导入 sheet 并预选该厂商（null = 预设第一个）。 */
    initialPresetId: String? = null,
    openImportSheet: Boolean = false,
    /** 直接打开三个选择键之一的编辑器（搜索与 `/scoped-models` 焦点入口）。 */
    initialEditorKey: String? = null,
    /** 一行写盘通知宿主：行上的读数与宿主的设置缓存都要跟着变。 */
    onSettingWritten: (String) -> Unit = {},
    /**
     * 屏自己那条写路径（导入 sheet 经 `PiEnginePreferences`）写完 settings.json 之后，
     * 宿主的缓存文档已过期 —— 和凭证表单当年的 `onFilesWritten` 是同一个约定。
     */
    onFilesWritten: () -> Unit = {},
) {
    val context = LocalContext.current
    // Keyed on the workspace path as well as the context: switching workspaces moves
    // the directory without touching the context, so `remember(context)` alone would
    // pin this screen to the workspace that was current when it first composed.
    val workspacePath = PtyLauncher.workspaceHost(context).absolutePath
    val layout = remember(context, workspacePath) {
        AgentLayout(
            context = context.applicationContext,
            hostWorkspace = PtyLauncher.workspaceHost(context),
        )
    }
    val service = remember(layout) { PiCredentialService(layout, layout.hostWorkspace) }
    val scope = rememberCoroutineScope()

    // 直读的原文 → 组装。整段都在 IO 线程上（pi 的模型目录可能有几百 KB），旧值一直留到
    // 新值算好，避免闪一下空白。
    var inventory by remember { mutableStateOf<PiModelInventory.Inventory?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    // ② 的行读的是 store（不是快照状态）：编辑器写完必须有一个会变的键让行重读 —— 组屏的
    // `freshness` 是同一个理由（`SettingsGroupScreen.kt:46-59`）。
    var editTick by remember { mutableStateOf(0) }

    // 引擎的答案异步到达；没有 provider 的记录跳过，不用空 id 造出一个厂商。
    val engineModels = remember(availableModels) {
        availableModels.mapNotNull { info ->
            val providerId = info.provider?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PiModelInventory.EngineModel(providerId = providerId, id = info.id, name = info.name)
        }
    }

    LaunchedEffect(reloadTick, engineModels) {
        // 一次写入会产生好几个事件（临时文件、改名、属性），200ms 合并成一次重读。
        if (reloadTick > 0) delay(200)
        inventory = withContext(Dispatchers.IO) { service.inventory(engineModels) }
    }
    LaunchedEffect(Unit) { onLoadAvailableModels() }

    // 外部改动：页面在组合里才装监视，onDispose 就停。AI 在对话里改的文件走的也是这条路。
    PiDirectoryWatch(
        directories = remember(service) { service.watchedDirectories() },
        names = remember { PiCredentialService.MODEL_FILES },
        onChanged = { reloadTick++ },
    )

    var note by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var editingKey by rememberSaveable { mutableStateOf(initialEditorKey) }
    var sheetOpen by rememberSaveable { mutableStateOf(openImportSheet) }
    var sheetPreset by rememberSaveable { mutableStateOf(initialPresetId) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // 三个选择键的描述符仍在注册表里（分组屏不再渲染它们：编辑入口搬进了本屏，注册表
    // 保留是为了首页摘要、搜索与 `/scoped-models` 焦点还能按 key 找到它们）。编辑器是同一把
    // `PiSettingEditorSheet`，只是换了门口。
    LaunchedEffect(editingKey) {
        if (editingKey != null && PiSettingsCatalog.byKey[editingKey] == null) editingKey = null
    }
    val editingDescriptor = editingKey?.let { PiSettingsCatalog.byKey[it] }
    if (editingDescriptor != null) {
        PiSettingEditorSheet(
            setting = editingDescriptor,
            store = store,
            onDismiss = { editingKey = null },
            onWritten = { written ->
                onSettingWritten(written.key)
                editTick++
            },
        )
    }

    // ② 的三个读数：store 的有效值（项目级覆盖在 store 里已合并），键带 editTick。
    val defaultProviderValue = remember(store, editTick) {
        (store.read("defaultProvider") as? JsonPrimitive)?.content
    }
    val defaultModelValue = remember(store, editTick) {
        (store.read("defaultModel") as? JsonPrimitive)?.content
    }
    val enabledModelsValue = remember(store, editTick) {
        ((store.read("enabledModels") as? JsonArray) ?: emptyList())
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
    }

    val data = inventory
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "模型与供应商", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            if (data == null) {
                item { Note("正在读取这台设备上的模型配置…") }
            } else {
                item { Summary(data) }
                if (data.modelsJsonError != null) {
                    item {
                        Note("模型配置现在读不出来（${data.modelsJsonError}）。修好之前，里面写的模型都不会生效。")
                    }
                }
                if (data.authJsonError != null) {
                    item {
                        Note("凭证现在读不出来（${data.authJsonError}）。下面「还没有凭证」的判断可能不准，pi 也可能起不来。")
                    }
                }
                if (!data.engineKnown) {
                    item {
                        Note("引擎现在没有运行，所以下面的模型都无法判断是不是已经能用。已经保存的配置不会因此丢失。")
                    }
                }
                if (data.providersPendingRestart.isNotEmpty()) {
                    item { RestartCard(data, lifecycle, coordinator) { question = it } }
                }
                note?.let { msg -> item { Note(msg) } }

                item { PiSettingsSectionHeader("当前与循环") }
                item {
                    LinkRow(
                        title = "默认模型",
                        value = defaultModelValue ?: "未设置（启动时再选）",
                        onClick = { editingKey = "defaultModel" },
                    )
                }
                item {
                    LinkRow(
                        title = "默认厂商",
                        value = defaultProviderValue ?: "未设置（跟随 pi 的默认）",
                        onClick = { editingKey = "defaultProvider" },
                    )
                }
                item {
                    LinkRow(
                        title = "循环模型",
                        value = if (enabledModelsValue.isEmpty()) {
                            "全部模型"
                        } else {
                            enabledModelsValue.joinToString("、")
                        },
                        onClick = { editingKey = "enabledModels" },
                    )
                }
                // 生效时机照 pi：三个键都在 settings.json，每个新会话重建 runtime 时重新读、
                // 重新解析循环作用域（`main.ts:788-797` 的 resolveModelScope 在 createRuntime
                // 闭包里，闭包每会话跑一次）——不是"改完要重启"。
                item { Note("这三个键就是 settings.json 里的选择。改完开一个新会话生效。") }

                item { PiSettingsSectionHeader("推理与上下文") }
                // pi 官方把这几行和上面三个选择键放在同一个「模型与推理」分组里；分组入口
                // 已按用户裁定删除，编辑器整组搬进这一屏 —— 点开还是同一把
                // `PiSettingEditorSheet`（搜索点到这些 key 也路由到这里）。
                item { SettingLinkRow("defaultThinkingLevel", store, editTick) { editingKey = it } }
                item { SettingLinkRow("modelThinkingLevels", store, editTick) { editingKey = it } }
                item { SettingLinkRow("thinkingBudgets", store, editTick) { editingKey = it } }
                item { SettingLinkRow("hideThinkingBlock", store, editTick) { editingKey = it } }
                item { SettingLinkRow("cacheWarming", store, editTick) { editingKey = it } }
                item { SettingLinkRow("showCacheMissNotices", store, editTick) { editingKey = it } }

                item {
                    PiSettingsSectionHeader(
                        label = "这台设备上配好的模型",
                        count = "${data.providers.size} 个厂商",
                    )
                }
                if (data.providers.isEmpty()) {
                    item { Note("还没有配置任何厂商。点下面的「导入模型」选一个厂商、粘上 Key。") }
                }

                items(data.providers, key = { it.id }) { provider ->
                    val isOpen = expanded[provider.id] ?: provider.defaultExpanded
                    ProviderCard(
                        provider = provider,
                        expanded = isOpen,
                        onToggle = { expanded[provider.id] = !isOpen },
                        onOpenCredentials = {
                            sheetPreset = provider.id
                            sheetOpen = true
                        },
                        onDelete = { deleteTarget = provider.id },
                    )
                }

                item {
                    OutlinedButton(
                        onClick = {
                            sheetPreset = null
                            sheetOpen = true
                        },
                        modifier = Modifier.padding(
                            horizontal = PiSettingsMetrics.pageHorizontal,
                            vertical = PiSpacing.unit,
                        ),
                    ) { Text("导入模型") }
                }
                item {
                    // 「本地模型（llama.cpp）」原来是分组屏里的一行 Action，入口随分组一起
                    // 删除后搬到这里：同一个导入 sheet，只是预选到本地端点那一档。
                    OutlinedButton(
                        onClick = {
                            sheetPreset = "llamacpp"
                            sheetOpen = true
                        },
                        modifier = Modifier.padding(
                            horizontal = PiSettingsMetrics.pageHorizontal,
                            vertical = PiSpacing.unit,
                        ),
                    ) { Text("本地模型（llama.cpp / LM Studio）") }
                }
                item { PiSettingsSectionHeader("说明") }
                item { Note("订阅登录（Anthropic / OpenAI / GitHub Copilot 等订阅账号）去 工作区 → 终端 里运行 /login；这一屏写的是 API Key。") }
                item { Note("「pi 目录」是 pi 自带的模型定义；「手写申报」写在你的模型配置里；「覆盖」只改 pi 目录里的某几个字段。") }
                item { Note("一个厂商的模型一旦手写申报，就会替换这个厂商在 pi 目录里的同名条目；没动的厂商不受影响。") }
                item { Note("这一屏读写的都是 pi 的官方文件（models.json、auth.json、settings.json）；对话里让 AI 改这些文件，列表会自动跟上。") }
                item { Spacer(Modifier.height(PiSettingsMetrics.groupGap)) }
            }
        }
    }

    if (sheetOpen) {
        ImportSheet(
            initialPresetId = sheetPreset,
            service = service,
            availableModels = availableModels,
            enabledPatterns = enabledModelsValue,
            onClose = { sheetOpen = false },
            onSaved = {
                reloadTick++
                onFilesWritten()
            },
        )
    }

    val del = deleteTarget
    if (del != null) {
        PiSettingsDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除厂商",
            body = "会从凭证和模型配置里删掉「$del」（auth.json 的凭证条目 + models.json 的厂商块）。" +
                "settings.json 里的默认厂商、循环列表不动：如果它正是当前默认，删完请在上面的「默认厂商」里换一个。",
            confirmationLabel = "删除",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    val error = withContext(Dispatchers.IO) { service.remove(del) }
                    if (error == null) {
                        reloadTick++
                        note = "已删除「$del」的凭证与厂商块。"
                    } else {
                        note = error
                    }
                }
            },
            dismissalLabel = "取消",
            onDismissButton = { deleteTarget = null },
        )
    }

    val asked = question
    if (asked != null) {
        PiSettingsDialog(
            onDismissRequest = { question = null },
            title = "重启引擎",
            body = asked,
            confirmationLabel = "重启",
            onConfirm = {
                question = null
                val engine = coordinator
                if (engine == null) {
                    note = "重启没有接上：配置已经保存好，重启 App 或引擎之后生效。"
                } else {
                    scope.launch {
                        note = when (val outcome = engine.confirm("用户确认重启引擎以加载新模型")) {
                            is EngineRestartCoordinator.Outcome.Ok -> {
                                // 引擎换了，模型列表要重新问一次；这里不是猜它已经好了。
                                onLoadAvailableModels()
                                reloadTick++
                                "引擎已重启。新配置的模型现在应该出现在模型列表里了。"
                            }

                            is EngineRestartCoordinator.Outcome.Refused -> outcome.message
                            is EngineRestartCoordinator.Outcome.Failed -> outcome.message
                        }
                    }
                }
            },
            dismissalLabel = "取消",
            onDismissButton = {
                question = null
                lifecycle.cancelRestart()
            },
        )
    }
}

// ===========================================================================
// 导入 sheet：以前是独立一屏的 4 步 wizard（PiCredentialScreen），现在是这一屏里
// 的一张卡，保存后清单就地刷新、重启提示回到① —— 不再跳屏。
// ===========================================================================

/**
 * 「选厂商 → 粘 Key → 扫描 → 勾选 → 保存」，一张滚动卡走完（对齐 pi 的 login-dialog
 * 节奏：同一张卡推进，从不换屏）。
 *
 * 与旧表单的三处行为差异，都是用户报的「导入完了也有 bug」：
 *
 *  - **官方目录直接列模型**：候选 = 已保存 + 扫描到 + **pi 目录里的** + 手输 —— 官方
 *    文件里有的，不粘 Key 就能看见、能选（旧版目录只给已存在的 id 补标签，不产生
 *    候选，不扫描就一个都列不出）。初始勾选见 prefill：循环范围没设 = 全勾，设了 =
 *    只勾本来轮得到的（上次主动排除的不会因为打开过表单又被勾回来）；
 *  - **默认模型不抢**：单选第一项是「保持当前默认」（null = 不写这两个键）；只有用户
 *    明确选了某个模型才写（`ModelSelectionPlan.writeDefault`）；
 *  - **保存后就地刷新**：只重读「已配置」那几个字段，本次保存的步骤显示不被清掉（旧版
 *    表单保存后 prefill 不重跑，凭证状态要退出重进才对）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    initialPresetId: String?,
    service: PiCredentialService,
    availableModels: List<PiResponses.ModelInfo>,
    /** 当前 `enabledModels` 范围（settings.json 的有效值），决定目录模型初始勾不勾。 */
    enabledPatterns: List<String>,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = PiSettingsMetrics.groupGap),
        ) {
            Text(
                "导入模型",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.cardPadding,
                    vertical = PiSpacing.small,
                ),
            )

            var presetId by remember(initialPresetId) {
                mutableStateOf(initialPresetId ?: PiProviderPresets.all.first().id)
            }
            var apiKey by remember { mutableStateOf("") }
            var baseUrl by remember { mutableStateOf("") }
            var api by remember { mutableStateOf("") }
            var maskedKey by remember { mutableStateOf<String?>(null) }
            var credentialPresent by remember { mutableStateOf(false) }
            var configuredProviderIds by remember { mutableStateOf<List<String>>(emptyList()) }
            var modelsFileError by remember { mutableStateOf<String?>(null) }
            var authFileError by remember { mutableStateOf<String?>(null) }
            var existingIds by remember { mutableStateOf<List<String>>(emptyList()) }
            var scanned by remember { mutableStateOf<List<PiModelScanner.ScannedModel>>(emptyList()) }
            var manualIds by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
            // 打开表单时的初始勾选 —— 保存时「动没动」的对账基准（见 ModelSelectionPlan）。
            var initialChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
            var scanEndpoint by remember { mutableStateOf<String?>(null) }
            var scanNote by remember { mutableStateOf<String?>(null) }
            var scanError by remember { mutableStateOf<String?>(null) }
            var scanning by remember { mutableStateOf(false) }
            var saveSteps by remember { mutableStateOf<List<String>>(emptyList()) }
            var savedOk by remember { mutableStateOf(false) }
            var saveError by remember { mutableStateOf<String?>(null) }
            var storeCatalog by remember { mutableStateOf<List<PiModelCatalog.Entry>>(emptyList()) }
            // 官方目录（41 个厂商 + 全部模型元数据）与它的读取问题；还有那个竖列表选择器的开关。
            var officialProviders by remember { mutableStateOf<List<PiOfficialCatalog.Provider>>(emptyList()) }
            var officialProblems by remember { mutableStateOf<List<String>>(emptyList()) }
            var pickerOpen by remember { mutableStateOf(false) }
            var pickerQuery by remember { mutableStateOf("") }
            val sheetScope = rememberCoroutineScope()

            val preset = PiProviderPresets.byId(presetId) ?: PiProviderPresets.all.first()

            // **官方目录一次读完**（41 份 `<provider>.json` + SHA-256 校验）：提供商列表、
            // 每个厂商的模型、以及它们的 baseUrl/api/上下文/价格/图片能力都来自这份文件。
            // 用户裁定：不自己维护表；升级 `PI_VERSION` 换载荷，列表自动跟着变。
            LaunchedEffect(Unit) {
                val result = withContext(Dispatchers.IO) { service.officialCatalog() }
                officialProviders = result.providers
                officialProblems = result.problems
            }
            val officialCounts = remember(officialProviders) {
                officialProviders.associate { it.id to it.models.size }
            }
            // 选择列表 = 官方 41 个（有手写预设的用预设的连接信息，其余从官方数据现构）
            // + App 自建的 3 个（ollama / llama.cpp / 自定义）。
            val providerChoices = remember(officialProviders) {
                officialProviders.map { provider ->
                    PiProviderPresets.byId(provider.id) ?: presetFromOfficial(provider)
                } + PiProviderPresets.all.filterNot { it.builtInPi }
            }
            val officialForPreset = remember(officialProviders, presetId) {
                officialProviders.firstOrNull { it.id == presetId }?.models.orEmpty()
            }

            // prefill + 目录一次读完（IO 线程）：分开两个 effect 会有目录晚到、
            // 「默认全勾」漏掉目录那批的竞态。切厂商即重置，和旧表单同一个键。
            LaunchedEffect(presetId) {
                val (existing, entries) = withContext(Dispatchers.IO) {
                    service.prefill(presetId) to service.catalogModels(presetId)
                }
                baseUrl = existing.baseUrl ?: preset.baseUrl
                api = existing.api ?: preset.api
                maskedKey = existing.maskedKey
                credentialPresent = existing.credentialPresent
                configuredProviderIds = existing.configuredProviderIds
                modelsFileError = existing.modelsFileError
                authFileError = existing.authFileError
                existingIds = existing.configuredModelIds
                // `models-store.json` 的条目只是兜底：真正的来源是下面那份派生出来的
                // `catalog`（官方目录优先，且官方晚到时也会跟着变）。
                storeCatalog = entries
                apiKey = ""
                scanned = emptyList()
                manualIds = ""
                scanEndpoint = null
                scanNote = null
                scanError = null
                saveSteps = emptyList()
                savedOk = false
                saveError = null
                // 初始勾选 = 「申报过的全部 + pi 目录里在循环范围内的」：
                //  - 申报过的（手写 models[]）必须全勾 —— 取消勾选会在保存时删掉它的
                //    定义，自定义厂商就再也没有这个模型了；
                //  - 目录里的按 enabledModels 范围过滤：范围没设 = 全部可循环 = 全勾
                //    （首次导入的默认形态）；范围设了 = 只勾本来就轮得到的，上次主动
                //    排除的模型不会因为"打开过表单"又被勾回来（Bug①）。
                // 判据与清单页同一个：PiModelInventory.matches。
                val inScope = { id: String ->
                    enabledPatterns.isEmpty() ||
                        enabledPatterns.any { PiModelInventory.matches(it, presetId, id) }
                }
                selected = existing.configuredModelIds.toSet() +
                    entries.map { it.id }.filter(inScope).toSet()
                initialChecked = selected
            }

            // 目录的**派生值**，两个官方来源取"更新的那个"：
            //  - `models-store.json` 是 pi 自己刷新过的缓存（RPC 启动时后台刷，含 pi.dev 的覆盖
            //    目录，`core/remote-catalog-provider.ts` 的 `DEFAULT_CATALOG_BASE_URL`），所以
            //    同一个 id 以它为准；
            //  - 随包静态目录（41 份 JSON）永远在，补 store 里没有的厂商/模型。
            // 引擎快照与我们的扫描都不进这里 —— 它们是下面 `knownById` 的后备。
            // 做成派生值是因为两个来源都是异步读到的：写成状态就会停在"打开表单那一刻"。
            val catalog = remember(officialForPreset, storeCatalog) {
                if (storeCatalog.isEmpty()) {
                    officialForPreset.map { it.toCatalogEntry() }
                } else {
                    val byId = LinkedHashMap<String, PiModelCatalog.Entry>()
                    officialForPreset.forEach { model -> byId[model.id] = model.toCatalogEntry() }
                    storeCatalog.forEach { entry -> byId[entry.id] = entry }
                    byId.values.toList()
                }
            }

            // 官方目录晚到时补一次初始勾选：**只在用户还没有任何勾选时**才动，绝不覆盖
            // 用户已经做过的选择（打开表单时官方数据可能还没读完）。
            LaunchedEffect(officialForPreset, presetId) {
                if (officialForPreset.isEmpty() || selected.isNotEmpty()) return@LaunchedEffect
                val inScope = { id: String ->
                    enabledPatterns.isEmpty() ||
                        enabledPatterns.any { PiModelInventory.matches(it, presetId, id) }
                }
                selected = officialForPreset.map { it.id }.filter(inScope).toSet()
                initialChecked = selected
            }

            val manualList = remember(manualIds) {
                manualIds.split(',', '\n', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
            val catalogIds = remember(catalog) { catalog.map { it.id }.toSet() }
            val scannedIds = remember(scanned) { scanned.map { it.id }.toSet() }
            // 候选 = 已保存（编辑时置顶）+ 扫描到 + pi 目录 + 手输。
            val candidates = remember(scanned, manualList, existingIds, catalogIds) {
                (existingIds + scanned.map { it.id } + catalogIds + manualList).distinct()
            }

            // 元数据三源，**官方优先、扫描兜底**（用户裁定：pi 官方文件有数据就用官方的，
            // pi 暂时没有的才用这次 API 扫到的）：pi 目录（models-store.json）→ 引擎快照
            // → 扫描里厂商自己给的数据。引擎排在扫描前是因为它是 pi 组合后的官方视图；
            // 扫描数据只在 pi 一无所知时补位 —— 补的正是"模型官方的"那几个字段（上下文、
            // 最大输出、图片、价格），这也是价格以前永远不显示的原因：扫描器只读了 id。
            val scannedById = remember(scanned) { scanned.associateBy { it.id } }
            val knownById = remember(availableModels, catalog, presetId, scanned) {
                val fromCatalog = catalog.associateBy { it.id }
                val fromEngine = availableModels.filter { it.provider == presetId }.associateBy { it.id }
                (fromCatalog.keys + fromEngine.keys + scannedById.keys).associateWith { id ->
                    val entry = fromCatalog[id]
                    val engine = fromEngine[id]
                    val scan = scannedById[id]
                    KnownModel(
                        name = entry?.name ?: engine?.name ?: scan?.displayName,
                        reasoning = entry?.reasoning ?: engine?.reasoning,
                        acceptsImages = entry?.acceptsImages ?: engine?.acceptsImages
                            ?: scan?.acceptsImages ?: false,
                        contextWindow = entry?.contextWindow ?: engine?.contextWindow
                            ?: scan?.contextWindow,
                        maxTokens = entry?.maxTokens ?: engine?.maxTokens ?: scan?.maxTokens,
                        costInput = entry?.costInput ?: engine?.inputCost ?: scan?.costInputPerMillion,
                        costOutput = entry?.costOutput ?: engine?.outputCost ?: scan?.costOutputPerMillion,
                    )
                }
            }

            fun choicesFor(ids: List<String>): List<PiCredentialService.ModelChoice> = ids.map { id ->
                val known = knownById[id]
                PiCredentialService.ModelChoice(
                    id = id,
                    name = known?.name,
                    reasoning = known?.reasoning,
                    contextWindow = known?.contextWindow,
                    maxTokens = known?.maxTokens,
                    // 只说知道的：官方目录或厂商自己说了"支持图片"才写 ["text","image"]，
                    // 否则**不写这个键** —— 省略时 pi 自己按纯文本处理
                    // （`provider-composer.ts:158`），那才是官方默认。以前无论知不知道都写
                    // ["text"]，等于替厂商断言一次它没说过的话，界面也因此不得不问用户
                    // "支持图片吗"（用户裁定：这个不让用户选）。
                    input = if (known?.acceptsImages == true) listOf("text", "image") else emptyList(),
                    costInput = known?.costInput,
                    costOutput = known?.costOutput,
                )
            }

            // ---------------------------------------------------------- 1 选厂商
            PiSettingsSectionHeader("1 选厂商")
            // **竖列表 + 搜索，不是横滑 chips**（用户："横选了啥时候也看不完"）：官方 41 个
            // 加 App 自建 3 个，一屏能翻、能搜，长名字也不会被挤没。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        preset.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        buildString {
                            append(preset.id)
                            officialCounts[preset.id]?.let { append(" · ").append(it).append(" 个模型") }
                            if (preset.id in configuredProviderIds) append(" · 已配置")
                            append(if (preset.builtInPi) " · pi 官方" else " · App 自建")
                        },
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { pickerOpen = !pickerOpen }) {
                    Text(if (pickerOpen) "收起" else "更换")
                }
            }
            if (pickerOpen) {
                val query = pickerQuery.trim().lowercase()
                val shownChoices = providerChoices.filter { option ->
                    query.isEmpty() ||
                        option.id.lowercase().contains(query) ||
                        option.displayName.lowercase().contains(query)
                }
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    label = { Text("搜厂商（名字或 id）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    shownChoices.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    presetId = option.id
                                    pickerOpen = false
                                    pickerQuery = ""
                                }
                                .padding(
                                    horizontal = PiSettingsMetrics.cardPadding,
                                    vertical = PiSpacing.small,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (option.id == presetId) "✓" else "○",
                                style = PiTheme.text.monoSmall,
                                color = if (option.id == presetId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    PiTheme.palette.muted
                                },
                            )
                            Spacer(Modifier.width(PiSpacing.small))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    option.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    buildString {
                                        append(option.id)
                                        officialCounts[option.id]?.let {
                                            append(" · ").append(it).append(" 个模型")
                                        }
                                        if (option.id in configuredProviderIds) append(" · 已配置")
                                    },
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (shownChoices.isEmpty()) {
                        Text(
                            "没有匹配的厂商",
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = PiSettingsMetrics.cardPadding,
                                vertical = PiSpacing.small,
                            ),
                        )
                    }
                }
            }
            if (officialProblems.isNotEmpty()) {
                SheetNote(
                    "官方目录有 ${officialProblems.size} 处读不出来，下面的厂商可能少几个：" +
                        officialProblems.joinToString("；"),
                )
            }
            if (modelsFileError != null) {
                SheetNote("模型配置当前无法被 pi 解析：$modelsFileError。修好之前，写入的厂商不会生效。")
            }
            if (authFileError != null) {
                SheetNote("凭证当前无法被读取，这个页面显示的厂商不是全部。修好之前 pi 不会启动。")
            }

            // ----------------------------------------------------------- 2 粘 Key
            PiSettingsSectionHeader("2 粘 Key")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
            )
            if (credentialPresent) {
                val shown = maskedKey?.let { "（$it）" }.orEmpty()
                SheetNote("这个厂商已经保存过凭证$shown。Key 留空就不改动它；要换新的就粘贴一条。")
            }
            if (preset.scanStyle == PiProviderPresets.ScanStyle.Keyless) {
                SheetNote(
                    "这个端点不校验鉴权，但 pi 仍要求填写凭证，否则不会列出该厂商的模型。" +
                        "还没有凭证时留空会写入占位值「" + PiProviderPresets.KEYLESS_PLACEHOLDER + "」。",
                )
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
            )
            OutlinedTextField(
                value = api,
                onValueChange = { api = it },
                label = { Text("api（协议实现）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
            )
            SheetNote("写错 api 时厂商会注册成功、第一条消息才失败。请从上面的预设带出来，不要凭印象改。")
            SheetNote("扫描即可确认 Key 可用；厂商自己给的上下文/价格/图片会被带上，没给的用 pi 默认值。")

            // --------------------------------------------------------- 3 扫描
            PiSettingsSectionHeader("3 检测并扫描模型")
            SheetNote("扫描成功即表示 Key 可用。pi 目录里已经列出的厂商可以跳过这一步直接保存。")
            Row(
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.cardPadding,
                    vertical = PiSpacing.small,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !scanning,
                    onClick = {
                        sheetScope.launch {
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
                                        // 扫描到的并入勾选、保留已做的勾选；默认模型一个都
                                        // 不动（写不写由「保持当前默认」那一项决定）。
                                        selected = selected + result.models.map { it.id }
                                    }

                                    is PiModelScanner.Result.Failed -> {
                                        // Failed.allowManual 恒为 true，失败永远不是死路。
                                        scanError = result.message + "\n" + result.suggestion
                                        scanEndpoint = result.endpoint
                                    }
                                }
                            } finally {
                                // 按钮在扫描中禁用，但抛出的异常也必须把状态放回来。
                                scanning = false
                            }
                        }
                    },
                ) {
                    Text(if (scanning) "正在扫描…" else "检测并扫描模型")
                }
                if (scanEndpoint != null) {
                    Spacer(Modifier.width(PiSpacing.inline))
                    Text(scanEndpoint.orEmpty(), style = PiTheme.text.monoSmall)
                }
            }
            if (scanNote != null) SheetNote(scanNote.orEmpty())
            if (scanError != null) SheetNote(scanError.orEmpty())

            // ------------------------------------------------- 4 勾选要导入的模型
            PiSettingsSectionHeader("4 勾选要导入的模型")
            SheetNote(
                "一个勾 = 这个模型。上下文、价格、是否支持图片一律先取 pi 官方目录，" +
                    "pi 没有的用上面扫描到的厂商数据；两边都没有就不写，交给 pi 用默认值。",
            )
            OutlinedTextField(
                value = manualIds,
                onValueChange = { manualIds = it },
                label = { Text("手动模型名（逗号、空格或换行分隔）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
            )
            candidates.forEach { id ->
                val known = knownById[id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSpacing.inline),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = id in selected,
                        onCheckedChange = { on ->
                            selected = if (on) selected + id else selected - id
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(id, style = PiTheme.text.mono)
                        Text(
                            candidateMeta(
                                id = id,
                                known = known,
                                fromScan = id in scannedIds,
                                fromCatalog = id in catalogIds,
                                alreadySaved = id in existingIds,
                                builtInPi = preset.builtInPi,
                            ),
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (candidates.isEmpty()) {
                SheetNote("pi 目录里还没有这个厂商的模型：点上面的扫描，或手动填一个模型名。")
            }

            Row(
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.cardPadding,
                    vertical = PiSpacing.inline,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !scanning && (selected.isNotEmpty() || preset.builtInPi),
                    onClick = {
                        val chosen = candidates.filter { it in selected }
                        sheetScope.launch {
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
                                    configuredModelIds = initialChecked,
                                    // pi 官方目录已经知道的模型**不申报**：官方数据就是权威，
                                    // 写一条只会把它替换成我们手上的副本（价格会归零、上下文
                                    // 会退回 128k）。只有官方不认识的才用扫描到的数据写定义。
                                    officiallyKnownIds = catalogIds,
                                )
                            }
                            saveSteps = result.steps
                            savedOk = result.ok
                            if (result.ok) {
                                onSaved()
                                // B2：只重读「已配置」那几个字段与错误态，本次保存的步骤
                                // 显示不清掉 —— 旧表单整段 prefill 会把 savedOk 抹回 false，
                                // 用户看到的是「刚保存完结果就没了」。
                                val fresh = withContext(Dispatchers.IO) { service.prefill(presetId) }
                                maskedKey = fresh.maskedKey
                                credentialPresent = fresh.credentialPresent
                                configuredProviderIds = fresh.configuredProviderIds
                                existingIds = fresh.configuredModelIds
                                modelsFileError = fresh.modelsFileError
                                authFileError = fresh.authFileError
                                // 对账基准随保存前进：下一次保存只对"这次改了什么"负责。
                                initialChecked = selected
                            } else {
                                saveError = result.steps.lastOrNull()
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
                Spacer(Modifier.width(PiSpacing.inline))
                Text(
                    when {
                        selected.isNotEmpty() -> "已选 ${selected.size} 个"
                        // 官方厂商不申报模型（models[] 一律不写），空着保存 = 只写凭证与
                        // 连接块、循环列表一个字节不动 —— 「已有模型、只想补个 Key」不该
                        // 被一个勾选门槛挡住（Bug②）。
                        preset.builtInPi -> "不勾也能保存（不改循环列表）"
                        else -> "至少要勾选一个模型"
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            saveSteps.forEach { step -> SheetNote(step) }
            if (saveError != null) SheetNote(saveError.orEmpty())
            if (savedOk) {
                SheetNote("保存完成。回到上面的清单：新厂商如果标着「等待重启」，去那张卡重启引擎。")
            }
            SheetNote("手写的模型配置可以带注释，不会被判成损坏。")
            SheetNote("只为 pi 官方不认识的模型写定义；官方目录里有的不会被覆盖，价格与上下文仍来自 pi。")
            Spacer(Modifier.height(PiSettingsMetrics.groupGap))
        }
    }
}

/** One paragraph of explanation, muted settings style (screen width). */
@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(
            horizontal = PiSettingsMetrics.pageHorizontal,
            vertical = PiSpacing.small,
        ),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Same, card padding — for text inside the import sheet. */
@Composable
private fun SheetNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(
            horizontal = PiSettingsMetrics.cardPadding,
            vertical = PiSpacing.small,
        ),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ===========================================================================
// 以下为原 PiModelsScreen 的清单渲染（Summary / RestartCard / LinkRow /
// ProviderCard / ModelLine / StatusTag），逐件搬入、未改画法。
// ===========================================================================

@Composable
private fun Summary(data: PiModelInventory.Inventory) {
    val ready = data.count(PiModelInventory.Status.READY)
    val pending = data.count(PiModelInventory.Status.PENDING_RESTART)
    val noCredential = data.count(PiModelInventory.Status.MISSING_CREDENTIAL)
    Text(
        buildString {
            append("${data.providers.size} 个厂商 · ${data.models.size} 个模型 · 现在可用 $ready 个")
            if (pending > 0) append(" · 等待重启 $pending 个")
            if (noCredential > 0) append(" · 缺凭证 $noCredential 个")
        },
        modifier = Modifier.padding(
            horizontal = PiSettingsMetrics.pageHorizontal,
            vertical = PiSpacing.unit,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun RestartCard(
    data: PiModelInventory.Inventory,
    lifecycle: ExtensionLifecycle,
    coordinator: EngineRestartCoordinator?,
    onMessage: (String) -> Unit,
) {
    val providers = data.providersPendingRestart
    PiSettingsCard(modifier = Modifier.padding(top = PiSpacing.small)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            Text(
                "有 ${providers.size} 个厂商已经配好了，但引擎还没有加载它们。",
                style = MaterialTheme.typography.bodyMedium,
                color = PiTheme.palette.warning,
            )
            Text(
                providers.joinToString("、") { it.id } +
                    " 下的模型要重启引擎之后才会出现在模型选择器里。已保存的配置不会丢失。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.gutter))
            OutlinedButton(
                onClick = {
                    if (coordinator == null) {
                        onMessage("重启没有接上：配置已经保存好，重启 App 或引擎之后生效。")
                    } else {
                        when (coordinator.request("新配置的模型需要被 pi 重新加载")) {
                            ExtensionLifecycle.RequestOutcome.NeedsConfirmation -> {
                                val waiting = lifecycle.current as? ExtensionLifecycle.State.AwaitingConfirmation
                                onMessage(waiting?.question ?: "重启引擎以加载新配置的模型？")
                            }

                            ExtensionLifecycle.RequestOutcome.WaitingForTurn ->
                                onMessage(
                                    "有回合正在运行。重启会中断模型调用与正在执行的工具，" +
                                        "所以这次没有重启；回合结束后再点一次。",
                                )

                            else -> onMessage("当前没有待重启的变更（可能已经重启过）。")
                        }
                    }
                },
            ) { Text("重启引擎") }
        }
    }
}

/** 注册表里的一行设置，就地编辑：标题取官方标题，读数与分组屏用同一个 `summaryText`。 */
@Composable
private fun SettingLinkRow(
    key: String,
    store: PiSettingsStore,
    editTick: Int,
    onEdit: (String) -> Unit,
) {
    val setting = PiSettingsCatalog.byKey[key] ?: return
    val value = remember(store, editTick, key) { PiSettingsCatalog.summaryText(store, key) }
    LinkRow(title = setting.title, value = value, onClick = { onEdit(key) })
}

@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                value,
                // 三个值都是机器值（model id、provider id、pattern 列表），与设置里其他行的
                // 读数同一张机器字脸（`PiSettingsRows.kt` 的 valueMono）。
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("修改", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ProviderCard(
    provider: PiModelInventory.Provider,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenCredentials: () -> Unit,
    onDelete: () -> Unit,
) {
    val shown = if (expanded) provider.models else provider.models.filter { it.noteworthy }
    PiSettingsCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            Text(
                PiProviderPresets.byId(provider.id)?.displayName ?: provider.name ?: provider.id,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    append(provider.id)
                    provider.api?.let { append(" · ").append(it) }
                    append(" · ").append(if (provider.hasCredential) "已配置凭证" else "还没有凭证")
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.small))
            if (provider.models.isEmpty()) {
                Text(
                    "pi 的目录里还没有这个厂商的模型；导入时会读到模型清单。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.forEach { model -> ModelLine(model) }
            if (provider.models.size > shown.size) {
                PiSettingsListExpander(
                    label = "还有 ${provider.models.size - shown.size} 个 pi 目录里的模型",
                    onClick = onToggle,
                )
            } else if (provider.models.size > PiSettingsCollapseAbove) {
                PiSettingsListExpander(label = "收起", onClick = onToggle)
            }
            Row {
                Text(
                    if (provider.hasCredential) "换 Key 或改模型" else "填写凭证并导入模型",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clickable(onClick = onOpenCredentials)
                        .padding(vertical = PiSettingsMetrics.notePaddingVertical),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "删除",
                    modifier = Modifier
                        .clickable(onClick = onDelete)
                        // `start` 属四参重载、`vertical` 属两参重载，混用四个 padding
                        // 候选全部对不上（CI 在 e888c4b 报的就是这一行）——上下用显式的
                        // top/bottom 表达同一个值。
                        .padding(
                            start = PiSpacing.inline,
                            top = PiSettingsMetrics.notePaddingVertical,
                            bottom = PiSettingsMetrics.notePaddingVertical,
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiTheme.palette.warning,
                )
            }
        }
    }
}

@Composable
private fun ModelLine(model: PiModelInventory.Model) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PiSettingsMetrics.supportingGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.name ?: model.id,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    if (model.name != null && model.name != model.id) append(model.id).append(" · ")
                    append(originText(model))
                    if (model.enabled) append(" · 循环列表")
                    if (model.isDefault) append(" · 默认")
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusTag(model.status)
    }
}

/** 三句不同的话，对应三种不同的下一步动作。 */
@Composable
private fun StatusTag(status: PiModelInventory.Status) {
    val (text, color) = when (status) {
        PiModelInventory.Status.READY -> "现在可用" to MaterialTheme.colorScheme.primary
        PiModelInventory.Status.PENDING_RESTART -> "等待重启" to MaterialTheme.colorScheme.tertiary
        PiModelInventory.Status.MISSING_CREDENTIAL -> "缺凭证" to MaterialTheme.colorScheme.onSurfaceVariant
        PiModelInventory.Status.UNKNOWN -> "无法判断" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

private fun originText(model: PiModelInventory.Model): String {
    val parts = buildList {
        if (PiModelInventory.Origin.PI_CATALOG in model.origins) add("pi 目录")
        if (PiModelInventory.Origin.DECLARED in model.origins) add("手写申报")
        if (PiModelInventory.Origin.OVERRIDDEN in model.origins) add("覆盖")
    }
    return when {
        parts.isEmpty() -> "pi 自带"
        parts.size == 1 -> parts.first()
        else -> parts.joinToString(" + ")
    }
}

/**
 * 官方目录里的一档，在**没有手写预设**时构成一个可选厂商：连接信息取它第一个模型的
 * `api`/`baseUrl`，探测方式由 `api` 推导（anthropic → x-api-key，google → key 查询，
 * 其余 OpenAI 兼容）。41 个官方厂商里有一批是这样来的 —— 这正是"不自己维护表"。
 */
private fun presetFromOfficial(provider: PiOfficialCatalog.Provider): PiProviderPresets.Preset {
    val sample = provider.models.firstOrNull()
    val api = sample?.api ?: "openai-completions"
    val anthropic = api.contains("anthropic", ignoreCase = true)
    val google = api.contains("google", ignoreCase = true)
    return PiProviderPresets.Preset(
        id = provider.id,
        displayName = provider.id,
        baseUrl = sample?.baseUrl.orEmpty(),
        api = api,
        scanStyle = when {
            anthropic -> PiProviderPresets.ScanStyle.Anthropic
            google -> PiProviderPresets.ScanStyle.Google
            else -> PiProviderPresets.ScanStyle.OpenAiCompatible
        },
        authHeader = !anthropic && !google,
        builtInPi = true,
        group = "官方",
    )
}

/**
 * 官方目录的一条 → App 自己的目录条目。映射写在这里而不是 `PiOfficialCatalog` 里：
 * 那个对象要能单独进 bare-JVM harness，不能引用 App 的目录类型。
 */
private fun PiOfficialCatalog.Model.toCatalogEntry(): PiModelCatalog.Entry = PiModelCatalog.Entry(
    id = id,
    name = name,
    reasoning = reasoning,
    acceptsImages = acceptsImages,
    contextWindow = contextWindow,
    maxTokens = maxTokens,
    costInput = costInputPerMillion,
    costOutput = costOutputPerMillion,
)

/**
 * What is known about one model, flattened from the three sources that can say —
 * **official first**: pi's catalog (`models-store.json`), the engine's composed list,
 * then the vendor's own data from this session's scan (user ruling: 官方有就用官方的，
 * pi 没有的用 API 扫到的). Cost rides along because pi's picker prints it per million.
 */
private data class KnownModel(
    val name: String?,
    val reasoning: Boolean?,
    val acceptsImages: Boolean,
    val contextWindow: Long?,
    val maxTokens: Long?,
    val costInput: Double? = null,
    val costOutput: Double? = null,
)

/**
 * One line under a candidate model: where this row came from, then what pi knows.
 * (Transplanted from the old credential form; wording unchanged.)
 */
private fun candidateMeta(
    id: String,
    known: KnownModel?,
    fromScan: Boolean,
    fromCatalog: Boolean,
    alreadySaved: Boolean,
    builtInPi: Boolean,
): String = buildString {
    val sources = buildList {
        if (fromScan) add("扫描到")
        if (fromCatalog) add("pi 目录里有")
        if (alreadySaved) add("已保存")
        if (isEmpty()) add("手动填写")
    }
    append(sources.joinToString(" · "))
    if (known != null) {
        append(" · ").append(known.name?.takeIf { it != id } ?: "pi 认识它")
        known.contextWindow?.let { append(" · 上下文 ").append(it) }
        if (known.acceptsImages) append(" · 支持图片")
        // 价格：官方目录/引擎/厂商扫描任意一处给了就显示（用户报「连价格都不显示」——
        // 扫描器以前只读 id，自建厂商的价格因此永远是空的）。
        known.costInput?.let { append(" · $").append(trimCostText(it)).append("/M 入") }
        known.costOutput?.let { append(" · $").append(trimCostText(it)).append("/M 出") }
    } else if (builtInPi) {
        append(" · pi 不认识它，勾选不会让 pi 认识它")
    } else {
        append(" · pi 不认识它，下面勾选的项会写进模型配置")
    }
}

/** 价格读数：去掉尾随 0（`3.0` → `3`、`0.2700` → `0.27`），不引入新的数字格式。 */
private fun trimCostText(value: Double): String =
    String.format(java.util.Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

/** 收起时也值得占一行：已启用、默认、等待重启、缺凭证。 */
private val PiModelInventory.Model.noteworthy: Boolean
    get() = enabled || isDefault || status != PiModelInventory.Status.READY

/** 超过 `PiSettingsCollapseAbove` 就默认收起（共用那一档数字）。 */
private val PiModelInventory.Provider.defaultExpanded: Boolean
    get() = models.size <= PiSettingsCollapseAbove
