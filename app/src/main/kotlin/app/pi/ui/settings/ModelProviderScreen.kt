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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.RadioButton
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
 * 搬进「模型与供应商」的三个 `settings.json` 选择键。
 *
 * 分组屏用它过滤掉这三行（编辑器已经搬进来），设置栈用它把 `openSetting(key)`
 * 路由到本屏的就地编辑器 —— 两处必须是同一份名单，否则搜索点进分组屏会指向一个
 * 不存在的行，那是新的「点了没反应」。
 */
internal val MODEL_SELECTION_KEYS = setOf("defaultProvider", "defaultModel", "enabledModels")

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
                    item { RestartCard(data, lifecycle, coordinator) { note = it } }
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
                item { PiSettingsSectionHeader("说明") }
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
 *  - **官方目录直接列模型**：候选 = 已保存 + 扫描到 + **pi 目录里的** + 手输，且目录里的
 *    默认全勾 —— 官方文件里有的，不粘 Key 就能看见、能选（旧版目录只给已存在的 id 补
 *    标签，不产生候选，不扫描就一个都列不出）；
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
            // null = 保持当前默认（不写 settings.json 的默认两个键）。
            var defaultModelId by remember { mutableStateOf<String?>(null) }
            var currentDefault by remember { mutableStateOf("未设置") }
            var scanEndpoint by remember { mutableStateOf<String?>(null) }
            var scanNote by remember { mutableStateOf<String?>(null) }
            var scanError by remember { mutableStateOf<String?>(null) }
            var scanning by remember { mutableStateOf(false) }
            var saveSteps by remember { mutableStateOf<List<String>>(emptyList()) }
            var savedOk by remember { mutableStateOf(false) }
            var saveError by remember { mutableStateOf<String?>(null) }
            var catalog by remember { mutableStateOf<List<PiModelCatalog.Entry>>(emptyList()) }
            var imageOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
            val sheetScope = rememberCoroutineScope()

            val preset = PiProviderPresets.byId(presetId) ?: PiProviderPresets.all.first()

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
                catalog = entries
                apiKey = ""
                scanned = emptyList()
                manualIds = ""
                scanEndpoint = null
                scanNote = null
                scanError = null
                saveSteps = emptyList()
                savedOk = false
                saveError = null
                currentDefault = existing.providerId?.let { p ->
                    existing.modelId?.let { m -> "$p/$m" }
                } ?: "未设置"
                defaultModelId = null
                imageOverrides = emptyMap()
                // 已保存的 + pi 目录里的默认全勾：官方文件里有的都要能一眼看到、勾上。
                selected = existing.configuredModelIds.toSet() + entries.map { it.id }
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

            // 元数据两源，目录优先（与旧表单同序：目录是能力的权威，引擎快照可能已经带了
            // App 自己申报过的条目，读它先会让坏条目为自己作证）。
            val knownById = remember(availableModels, catalog, presetId) {
                val fromCatalog = catalog.associateBy { it.id }
                val fromEngine = availableModels.filter { it.provider == presetId }.associateBy { it.id }
                (fromCatalog.keys + fromEngine.keys).associateWith { id ->
                    val entry = fromCatalog[id]
                    val engine = fromEngine[id]
                    KnownModel(
                        name = entry?.name ?: engine?.name,
                        reasoning = entry?.reasoning ?: engine?.reasoning,
                        acceptsImages = entry?.acceptsImages ?: engine?.acceptsImages ?: false,
                        contextWindow = entry?.contextWindow ?: engine?.contextWindow,
                        maxTokens = entry?.maxTokens ?: engine?.maxTokens,
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
                    // 显式写、绝不省略：pi 把缺席的 input 填成纯文本（provider-composer:158）。
                    input = if (imageOverrides[id] ?: (known?.acceptsImages == true)) {
                        listOf("text", "image")
                    } else {
                        listOf("text")
                    },
                )
            }

            // ---------------------------------------------------------- 1 选厂商
            PiSettingsSectionHeader("1 选厂商")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                contentPadding = PaddingValues(horizontal = PiSettingsMetrics.cardPadding),
            ) {
                items(PiProviderPresets.all, key = { it.id }) { option ->
                    if (option.id == presetId) {
                        Button(onClick = { presetId = option.id }) { Text(option.displayName) }
                    } else {
                        OutlinedButton(onClick = { presetId = option.id }) { Text(option.displayName) }
                    }
                }
            }
            Text(
                buildString {
                    if (preset.id in configuredProviderIds) append("已配置 · ")
                    append(if (preset.builtInPi) "pi 内置" else "自定义")
                    append(" · ").append(preset.api)
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.cardPadding,
                    vertical = PiSpacing.small,
                ),
            )
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
            SheetNote("官方厂商的模型列表来自 pi 目录（models-store.json），已经在下面列好；扫描是可选的确认步骤。")

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

            // ------------------------------------------------- 4 勾选与默认
            PiSettingsSectionHeader("4 勾选与默认")
            SheetNote(
                "左侧勾选 = Ctrl+P 循环用哪些模型（只重写本厂商的条目，其他厂商与手写 pattern 保留）；" +
                    "右侧单选 = 保存后设为默认，第一项表示不动 settings.json。当前默认：$currentDefault",
            )
            OutlinedTextField(
                value = manualIds,
                onValueChange = { manualIds = it },
                label = { Text("手动模型名（逗号、空格或换行分隔）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { defaultModelId = null }
                    .padding(horizontal = PiSpacing.inline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = defaultModelId == null, onClick = { defaultModelId = null })
                Text("保持当前默认（$currentDefault）", style = MaterialTheme.typography.bodyMedium)
            }
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
                        if (!preset.builtInPi) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = imageOverrides[id] ?: false,
                                    onCheckedChange = { on -> imageOverrides = imageOverrides + (id to on) },
                                )
                                Text("支持图片输入", style = PiTheme.text.meta)
                            }
                        }
                    }
                    RadioButton(
                        selected = defaultModelId == id,
                        onClick = { defaultModelId = id },
                    )
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
                    enabled = !scanning && selected.isNotEmpty(),
                    onClick = {
                        val chosen = candidates.filter { it in selected }
                        val requestedDefault = defaultModelId
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
                                    configuredModelIds = existingIds.toSet(),
                                    setAsDefault = requestedDefault != null,
                                    defaultModelId = requestedDefault,
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
                                currentDefault = fresh.providerId?.let { p ->
                                    fresh.modelId?.let { m -> "$p/$m" }
                                } ?: currentDefault
                                defaultModelId = null
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
                    if (selected.isEmpty()) "至少要勾选一个模型" else "已选 ${selected.size} 个",
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
            SheetNote("一个厂商的模型一旦提供就替换该厂商的全部模型；勾选时要想清楚这是不是全部要用的模型。")
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
                        .padding(
                            start = PiSpacing.inline,
                            vertical = PiSettingsMetrics.notePaddingVertical,
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
 * What pi knows about one model, flattened from the two sources that can say:
 * pi's catalog and the engine's list. (Transplanted from the old credential form.)
 */
private data class KnownModel(
    val name: String?,
    val reasoning: Boolean?,
    val acceptsImages: Boolean,
    val contextWindow: Long?,
    val maxTokens: Long?,
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
    } else if (builtInPi) {
        append(" · pi 不认识它，勾选不会让 pi 认识它")
    } else {
        append(" · pi 不认识它，下面勾选的项会写进模型配置")
    }
}

/** 收起时也值得占一行：已启用、默认、等待重启、缺凭证。 */
private val PiModelInventory.Model.noteworthy: Boolean
    get() = enabled || isDefault || status != PiModelInventory.Status.READY

/** 超过 `PiSettingsCollapseAbove` 就默认收起（共用那一档数字）。 */
private val PiModelInventory.Provider.defaultExpanded: Boolean
    get() = models.size <= PiSettingsCollapseAbove
