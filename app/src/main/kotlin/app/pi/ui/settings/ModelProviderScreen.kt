package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import app.pi.ui.components.PiSheet
import app.pi.packages.PiModelsDev
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.packages.AgentLayout
import app.pi.packages.EngineRestartCoordinator
import app.pi.packages.ExtensionLifecycle
import app.pi.packages.PendingModelRestart
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * `get_available_models` 返回启动期快照，`modes/rpc/rpc-mode.ts:490-493`），所以重启卡的判据
 * 是**本 App 自己写过模型/凭证文件**（`PendingModelRestart`，写入在 `PiCredentialService`，
 * 引擎开始服务时清位），不是"清单里的模型不在引擎快照里"—— 后者什么也没干的时候也成立，
 * 用户原话是「明明啥也没干，也显示到什么模型没生效，非让我重启」。
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
    // 「推理」那段默认收起：六行都是低频旋钮，平铺会把三行选择和厂商卡挤到屏外。
    var reasoningOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // 导入后的单条模型编辑：点清单里的一行打开，值从"申报过的定义 → 引擎快照 →
    // pi 目录"依次取（见 `loadModelEditTarget`）。
    var modelEdit by remember { mutableStateOf<ModelEditTarget?>(null) }
    /** 「删除这个模型」的确认框（删的是那份申报，不是目录）。 */
    var deleteModel by remember { mutableStateOf<ModelEditTarget?>(null) }

    // 三个选择键的描述符仍在注册表里（分组屏不再渲染它们：编辑入口搬进了本屏，注册表
    // 保留是为了首页摘要、搜索与 `/scoped-models` 焦点还能按 key 找到它们）。编辑器是同一把
    // `PiSettingEditorSheet`，只是换了门口。
    LaunchedEffect(editingKey) {
        if (editingKey != null && PiSettingsCatalog.byKey[editingKey] == null) editingKey = null
    }

    // ② 的三个读数：store 的有效值（项目级覆盖在 store 里已合并）。
    //
    // 键里必须同时有 `editTick`（本屏自己写完 → 行重读）与 `reloadTick`（**外部**改完 →
    // `PiDirectoryWatch` 重读清单）：只带 editTick 时，AI/终端改了 `settings.json`，下面的
    // 清单已经按新值标「默认」，而这三行还停在旧值上 —— 同一屏两个答案。`reloadTick` 是
    // 文件事件驱动的，不是每帧都变，所以这里不会变成每帧读盘（`store.read` 本身也只是内存读）。
    val defaultProviderValue = remember(store, editTick, reloadTick) {
        (store.read("defaultProvider") as? JsonPrimitive)?.content
    }
    val defaultModelValue = remember(store, editTick, reloadTick) {
        (store.read("defaultModel") as? JsonPrimitive)?.content
    }
    val enabledModelsValue = remember(store, editTick, reloadTick) {
        ((store.read("enabledModels") as? JsonArray) ?: emptyList())
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
    }

    // 删除是"文件已经删了、引擎快照还没刷"的一件事：pi 没有删厂商的 RPC，引擎会把删掉的
    // 厂商/模型继续列到下一次启动。用户点完删除后它若还留在屏幕上，就是"点了没反应"。
    // 这里按 id 本地隐藏，直到重启引擎（restartPending 归零）为止 —— 不改文件、不改引擎，
    // 也不影响对话页的模型选择器（那只读引擎的实时回答）。
    var hiddenProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hiddenModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    val data = inventory?.let { list ->
        if (hiddenProviders.isEmpty() && hiddenModels.isEmpty()) {
            list
        } else {
            list.copy(
                providers = list.providers.mapNotNull { provider ->
                    if (provider.id in hiddenProviders) {
                        null
                    } else {
                        provider.copy(
                            models = provider.models.filterNot { model ->
                                "${provider.id}/${model.id}" in hiddenModels
                            },
                        )
                    }
                },
            )
        }
    }
    // 引擎重启完成（本 App 记的"等待重启"归零）就是快照重读的时刻：本地隐藏可以撤了，
    // 让清单回到"文件说什么就显示什么"。
    LaunchedEffect(inventory?.restartPending) {
        if (inventory?.restartPending == false) {
            hiddenProviders = emptySet()
            hiddenModels = emptySet()
        }
    }

    // 三个选择键的候选**一律从清单派生**，`remember` 键在清单与读数上：不新增每帧计算，
    // 也不再额外读盘（清单本来就是这一屏从文件原文拼出来的那一份）。以前 `defaultModel`
    // 注册成 `options = emptyList()`（`PiSettingsRegistry.kt:403-414`），选择器里只有一个
    // 「自定义值」输入框 —— 用户只能手打，而手打的模型名不在清单里时 pi **不报错**、静默
    // 退回占位模型（`docs/engine-exit-review.md:55`）。
    val modelOptions = remember(data) { modelPickerOptions(data) }
    val providerOptions = remember(data) { providerPickerOptions(data) }
    val cyclePresets = remember(data) { cycleModelPresets(data) }
    // 两键都已设置、却明显对不上时的那一句话（没有清单就不猜，见 modelProviderMismatch）。
    val selectionMismatch = remember(data, defaultProviderValue, defaultModelValue) {
        modelProviderMismatch(data, defaultProviderValue, defaultModelValue)
    }
    val unknownModelCheck = remember(data) { { typed: String -> unknownModelWarning(data, typed) } }
    // 只在这一屏用的 store 装饰器：写 `defaultModel` 时**两个键一起写**（pi 用
    // `defaultProvider` 决定由谁提供 `defaultModel`），并且把两键拼成选择器的 wire
    // （`厂商/模型`），选中态与「当前默认」于是只有一个判据。其余键原样转给 store。
    val selectionStore = remember(store, data) {
        DefaultModelPairStore(base = store, providers = data?.providers.orEmpty())
    }

    // 列表型的两行（逐模型思考等级 / 思考预算）写完的值：空列表 = 删键（pi 的未设置），
    // 不是写 `{}`；写法和 `PiSettingEditorSheet` 的 List 分支同一条规则。
    val writeEntries: (PiSetting, List<String>) -> Unit = { setting, entries ->
        if (entries.isEmpty()) {
            store.remove(setting.key)
        } else {
            store.write(setting.key, setting.elementFromEntries(entries))
        }
        onSettingWritten(setting.key)
        editTick++
    }

    val editingDescriptor = editingKey?.let { PiSettingsCatalog.byKey[it] }
    if (editingDescriptor != null) {
        val edited = editingDescriptor.key
        // 这两行有自己的点选编辑器（没有文本输入）；其余行仍是同一把 `PiSettingEditorSheet`，
        // 选项与快捷预设都由清单派生（用 `copy`，不另开"候选从哪来"的入口）。
        val entries = editingDescriptor.editableEntries(
            store.read(edited) ?: editingDescriptor.defaultValue,
        )
        when (edited) {
            "modelThinkingLevels" -> PiModelThinkingLevelsSheet(
                title = editingDescriptor.title,
                description = editingDescriptor.description,
                initialEntries = entries,
                models = modelOptions,
                onSet = { written -> writeEntries(editingDescriptor, written) },
                onDismiss = { editingKey = null },
            )

            "thinkingBudgets" -> PiThinkingBudgetsSheet(
                title = editingDescriptor.title,
                description = editingDescriptor.description,
                initialEntries = entries,
                onSet = { written -> writeEntries(editingDescriptor, written) },
                onDismiss = { editingKey = null },
            )

            else -> PiSettingEditorSheet(
                setting = when (edited) {
                    "defaultModel" -> editingDescriptor.copy(options = modelOptions)
                    "defaultProvider" -> editingDescriptor.copy(options = providerOptions)
                    "enabledModels" -> editingDescriptor.copy(presets = cyclePresets)
                    else -> editingDescriptor
                },
                store = selectionStore,
                onDismiss = { editingKey = null },
                // 只有这两行的选项多到一屏翻不完（清单可能上百个模型 / 几十个厂商）。
                pickerSearchHint = when (edited) {
                    "defaultModel" -> "搜模型（厂商或模型名）"
                    "defaultProvider" -> "搜厂商（名字或 id）"
                    else -> null
                },
                pickerWarning = selectionMismatch,
                pickerCustomCheck = if (edited == "defaultModel") unknownModelCheck else null,
                pickerClearLabel = if (edited == "defaultModel") "跟随 pi 默认（清除）" else null,
                onWritten = { written ->
                    onSettingWritten(written.key)
                    // 默认模型那一行一次写两个键（`defaultProvider` 也跟着变）：宿主与这一屏的
                    // 两行读数都要跟着刷新 —— 少通知一个 key，另一行就会停在旧值上。
                    if (written.key == "defaultModel") onSettingWritten("defaultProvider")
                    editTick++
                },
            )
        }
    }
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
                // 只在「App 写过模型/凭证文件」+「有引擎可以重启」时出现。引擎没跑
                // （`coordinator == null`）就一个按钮都不放：按了只能弹一句提示的假按钮
                // 比没有按钮更坏。重启成功 → 引擎开始服务时标记被清 → 这一行自己消失。
                if (data.restartPending && coordinator != null) {
                    item {
                        RestartCard(
                            lifecycle = lifecycle,
                            coordinator = coordinator,
                            onQuestion = { question = it },
                            onNote = { note = it },
                        )
                    }
                }
                note?.let { msg -> item { Note(msg) } }

                // ---- 默认模型 / 默认厂商 / 循环模型：三行都是点开在清单里点选，不再手打。
                // 原来这里的「当前与循环」节标题已删：设置面不再有节标题（统一口径）。
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
                // 两键对不上时先在这里说一句：选模型时两个键一起写，所以这种不一致只可能
                // 来自手输（点过「仍然使用」）、外部改 settings.json，或者只换了厂商。
                // 判据只读清单（`modelProviderMismatch`，remember 在 data 与两个读数上）。
                selectionMismatch?.let { mismatch -> item { Note(mismatch, tone = PiTheme.palette.warning) } }
                // 生效时机照 pi：三个键都在 settings.json，每个新会话重建 runtime 时重新读、
                // 重新解析循环作用域（`main.ts:788-797` 的 resolveModelScope 在 createRuntime
                // 闭包里，闭包每会话跑一次）——不是"改完要重启"。
                item { Note("这三个键就是 settings.json 里的选择。改完开一个新会话生效。") }

                // ---- 厂商卡与两个导入入口（没有节标题，也没有「N 个厂商」：统一口径）。
                // 下面每张卡的模型副行写的就是「pi 目录 / 手写申报 / 覆盖」这三个词，
                // 解释跟着它们放在同一段（页尾不再堆说明）。
                if (data.providers.isNotEmpty()) {
                    item {
                        Note("「pi 目录」是 pi 自带的模型定义；「手写申报」写在你的模型配置里（同名条目会被它替换）；「覆盖」只改目录里的字段。")
                    }
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
                        onEditModel = { model ->
                            scope.launch {
                                modelEdit = withContext(Dispatchers.IO) {
                                    loadModelEditTarget(service, availableModels, model.providerId, model.id)
                                }
                            }
                        },
                    )
                }

                item {
                    TextButton(
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
                    TextButton(
                        onClick = {
                            sheetPreset = "llama.cpp"
                            sheetOpen = true
                        },
                        modifier = Modifier.padding(
                            horizontal = PiSettingsMetrics.pageHorizontal,
                            vertical = PiSpacing.unit,
                        ),
                    ) { Text("本地模型（llama.cpp / LM Studio）") }
                }

                // ---- 推理：默认收起的一段，六行一个都不删，点开就是原来的编辑器。
                // 折叠头从节标题换成统一的行：标题 + 一行说明 + chevron（展开时转 90°），
                // 不再有「展开 / 收起」这种文字尾巴。
                item {
                    PiSettingsCard(modifier = Modifier.padding(top = PiSettingsMetrics.groupGap)) {
                        PiSettingsEntryRow(
                            title = "推理",
                            supporting = "思考等级、预算、思考块与缓存提示：点开就是原来的编辑器",
                            onClick = { reasoningOpen = !reasoningOpen },
                            expanded = reasoningOpen,
                        )
                    }
                }
                if (reasoningOpen) {
                    // pi 官方把这几行和上面三个选择键放在同一个「模型与推理」分组里；分组入口
                    // 已按用户裁定删除，编辑器整组搬进这一屏 —— 点开还是同一把
                    // `PiSettingEditorSheet`（搜索点到这些 key 也路由到这里）。
                    item { SettingLinkRow("defaultThinkingLevel", store, editTick, reloadTick) { editingKey = it } }
                    item { SettingLinkRow("modelThinkingLevels", store, editTick, reloadTick) { editingKey = it } }
                    item { SettingLinkRow("thinkingBudgets", store, editTick, reloadTick) { editingKey = it } }
                    item { SettingSwitchRow("hideThinkingBlock", store, editTick, reloadTick, onSettingWritten) }
                    item { SettingLinkRow("cacheWarming", store, editTick, reloadTick) { editingKey = it } }
                    item { SettingSwitchRow("showCacheMissNotices", store, editTick, reloadTick, onSettingWritten) }
                }

                // ---- 页尾只留两句（用户裁定：说明不堆在页尾）。
                item { Note("这一屏读写的都是 pi 的官方文件（models.json、auth.json、settings.json）；对话里让 AI 改这些文件，列表会自动跟上。") }
                // 终端页是半成品：只说事实（订阅登录要它、它还不完善），不再把用户支使过去。
                item { Note("订阅登录（OAuth）要走终端，终端页还不完善；这一屏写的是 API Key。") }
                item { Spacer(Modifier.height(PiSettingsMetrics.groupGap)) }
            }
        }
    }

    if (sheetOpen) {
        ImportSheet(
            initialPresetId = sheetPreset,
            service = service,
            availableModels = availableModels,
            // 清单里的厂商：让"官方目录里没有、models.json 里却已经有"的厂商在 sheet 里
            // 有一档自己的连接信息（以前它会静默退化成 openai，保存就把块写到 openai 名下）。
            knownProviders = data?.providers.orEmpty(),
            enabledPatterns = enabledModelsValue,
            onClose = { sheetOpen = false },
            onSaved = {
                reloadTick++
                onFilesWritten()
            },
        )
    }

    val editTarget = modelEdit
    if (editTarget != null) {
        ModelEditSheet(
            title = "编辑模型能力",
            subtitle = editTarget.subtitle,
            initial = editTarget.fields,
            canReset = editTarget.canReset,
            canDelete = editTarget.canDelete,
            onDelete = {
                modelEdit = null
                deleteModel = editTarget
            },
            onReset = {
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        service.resetModel(editTarget.providerId, editTarget.modelId)
                    }
                    if (error == null) {
                        reloadTick++
                        onFilesWritten()
                        note = "已恢复 pi 的目录定义：${editTarget.modelId}（重启引擎后生效）"
                        modelEdit = null
                    } else {
                        note = error
                    }
                }
            },
            onClose = { modelEdit = null },
            onSave = { fields ->
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        service.updateModel(editTarget.providerId, fields.toModel(editTarget.modelId))
                    }
                    if (error == null) {
                        reloadTick++
                        onFilesWritten()
                        note = "已保存模型能力：${editTarget.modelId}（重启引擎后生效）"
                        modelEdit = null
                    } else {
                        note = error
                    }
                }
            },
        )
    }

    val deleting = deleteModel
    if (deleting != null) {
        PiSettingsDialog(
            onDismissRequest = { deleteModel = null },
            title = "删除模型",
            body = "会从 models.json 里删掉「${deleting.modelId}」这条定义。这个厂商不是 pi 自带的，" +
                "删掉后它就从模型列表里消失；需要时重新导入即可。",
            confirmationLabel = "删除",
            onConfirm = {
                deleteModel = null
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        service.resetModel(deleting.providerId, deleting.modelId)
                    }
                    if (error == null) {
                        hiddenModels = hiddenModels + "${deleting.providerId}/${deleting.modelId}"
                        reloadTick++
                        onFilesWritten()
                        note = "已删除模型：${deleting.modelId}（重启引擎后生效）"
                    } else {
                        note = error
                    }
                }
            },
            dismissalLabel = "取消",
            onDismissButton = { deleteModel = null },
        )
    }

    val del = deleteTarget
    if (del != null) {
        PiSettingsDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除厂商",
            body = "会从凭证和模型配置里删掉「$del」（auth.json 的凭证条目 + models.json 的厂商块）。" +
                "如果它正是当前默认模型，默认厂商/默认模型会一起清掉（否则新会话会指向一个不存在的厂商）；" +
                "循环列表里「$del/…」的条目也会一并移除。",
            confirmationLabel = "删除",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    val error = withContext(Dispatchers.IO) { service.remove(del) }
                    if (error == null) {
                        hiddenProviders = hiddenProviders + del
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
                                // 重启成功了，App 那本「改完还没重启」的账当场结清：主清位点是
                                // 引擎开始服务那一刻（`PiEngineSession.probeServing`），但那次
                                // 探针是异步的，这一屏此刻已经要重读清单了 —— 只靠探针的话，
                                // 标记会在下面这次重读之后才清，卡片要多留一屏（而"重启成功卡片
                                // 却不消失"正是这一版要删掉的状态）。
                                PendingModelRestart.clear()
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
 * 「选厂商 → 粘 Key → 勾模型 → 保存」，一张 sheet 走完（对齐 pi 的 login-dialog
 * 节奏：同一张卡推进，从不换屏）。
 *
 * 这一版的顺序与折叠规则是用户裁定（「这一块太乱了」「导入再优化一下」）：
 *
 *  - **一进来就看见厂商列表**：已配置的排最前并标「已配置 · N 个模型」，未配置在后；
 *    点一个即选中并收起（「更换」还能再展开）。没传 `initialPresetId` 时**不默认 openai**
 *    —— 未选厂商时下面三步都不出现，先让用户点一个；
 *  - **已配置的厂商不再问 Key**：「2 粘 Key」收成一行「已存凭证（sk-…）· 留空＝沿用」，
 *    「更换」展开才出现 Key，`Base URL` / `api` 收进「高级」（默认收起）；扫描只是一个
 *    按钮行，不占节标题；
 *  - **模型列表能搜、能全选**：按 id 或名字过滤，标题行给「已勾选 N / 共 M」；
 *  - **保存固定在底部**：内容区滚动，按钮与状态不动；这次保存会删掉已申报的模型定义时，
 *    按钮上方先说一行「这次会删除 N 个模型定义」。
 *
 * 行为上没变的三条（都是用户报的「导入完了也有 bug」）：
 *
 *  - **官方目录直接列模型**：候选 = 已保存 + 扫描到 + **pi 目录里的** + 手输 —— 官方
 *    文件里有的，不粘 Key 就能看见、能选（旧版目录只给已存在的 id 补标签，不产生
 *    候选，不扫描就一个都列不出）。初始勾选见 prefill：循环范围没设 = 全勾，设了 =
 *    只勾本来轮得到的（上次主动排除的不会因为打开过表单又被勾回来）；
 *  - **默认模型不抢**：这张 sheet **不写** `defaultProvider` / `defaultModel`
 *    （`PiCredentialService.save` 没有这条分支）。要改默认模型，用清单页的「默认模型」
 *    那一行（`DefaultModelPairStore` 一次写两个键）；
 *  - **保存后就地刷新**：只重读「已配置」那几个字段，本次保存的步骤显示不被清掉（旧版
 *    表单保存后 prefill 不重跑，凭证状态要退出重进才对）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    initialPresetId: String?,
    service: PiCredentialService,
    availableModels: List<PiResponses.ModelInfo>,
    /**
     * 清单里的厂商（这一屏刚读到的那份）。两件事：给「官方目录里没有、但 `models.json`
     * 里已经有」的厂商现构一档（AI 手写 `models.json` 时用的 id 只存在于那份文件里，
     * 而它恰恰是用户从厂商卡点进来的那个 id）；以及把已配置的厂商排在列表最前、
     * 标出它们的模型数。
     */
    knownProviders: List<PiModelInventory.Provider>,
    /** 当前 `enabledModels` 范围（settings.json 的有效值），决定目录模型初始勾不勾。 */
    enabledPatterns: List<String>,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    // 未选厂商 = 空串（不是 null）：`presetId` 还要当记忆键与 effect 键用，空语义只留一种。
    // 没传 `initialPresetId` 时**不默认 openai** —— 下面的 2/3 两节直接不出现（用户裁定：
    // 先让用户点一个，而不是替他选一个官方厂商）。
    var presetId by remember(initialPresetId) { mutableStateOf(initialPresetId.orEmpty()) }
    // 自建厂商的显示名（models.json 的 name）。留空时按端点地址推一个（api.foo.com），
    // 不再让所有自建厂商都叫「自定义（OpenAI 兼容）」。
    var providerName by remember(presetId) { mutableStateOf("") }
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
    // 每条模型的能力覆盖：只记**用户真的改过**的那些 id，没改的走 knownById。
    var overrides by remember { mutableStateOf<Map<String, ModelFields>>(emptyMap()) }
    // models.dev 的索引：只在扫描回来的模型缺能力时取一次（缓存 7 天）。它补的是
    // 厂商不发布的那部分 —— 主要是思考档位（thinkingLevelMap）。
    var devIndex by remember {
        mutableStateOf<Pair<Map<String, PiModelsDev.Entry>, Map<String, PiModelsDev.Entry>>?>(null)
    }
    // 正在展开编辑的那一条（一次只开一条，表单比列表行高得多）。
    var editId by remember { mutableStateOf<String?>(null) }
    var storeCatalog by remember { mutableStateOf<List<PiModelCatalog.Entry>>(emptyList()) }
    // 官方目录（41 个厂商 + 全部模型元数据）与它的读取问题。
    var officialProviders by remember { mutableStateOf<List<PiOfficialCatalog.Provider>>(emptyList()) }
    var officialProblems by remember { mutableStateOf<List<String>>(emptyList()) }
    // 厂商列表**一进来就展开**：藏在「更换」后面时，用户根本不知道"已经有厂商也能在这里换"
    // —— 这正是「已有厂商，再点导入模型，列表里只有三个」的另一半。
    var pickerOpen by remember { mutableStateOf(true) }
    var pickerQuery by remember { mutableStateOf("") }
    // 已配置厂商的 Key 与「高级」（Base URL / api）都默认收起；换厂商即回到收起。
    var keyOpen by remember(presetId) { mutableStateOf(false) }
    var advancedOpen by remember(presetId) { mutableStateOf(false) }
    var modelQuery by remember(presetId) { mutableStateOf("") }
    val sheetScope = rememberCoroutineScope()

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
    // 选择列表 = 官方目录（有手写预设的用预设的连接信息，其余从官方数据现构）
    // + `PiProviderPresets.all` 里剩下的（App 自建的 ollama / llama.cpp / 自定义，
    // 以及目录读不到时的官方那十档）。
    //
    // **基线是预设表，不是目录。** 目录整份读不到时（载荷换了依赖树布局、还没解包），
    // 原来这里只剩「App 自建」那三个，用户报的正是「已有厂商，再点导入模型，列表里
    // 只有三个，搜也搜不出来」；目录正常时它仍然排在前（41+），两边都成立。
    val providerChoices = remember(officialProviders) {
        val fromCatalog = officialProviders.map { provider ->
            PiProviderPresets.byId(provider.id) ?: presetFromOfficial(provider)
        }
        val covered = fromCatalog.mapTo(HashSet()) { it.id }
        fromCatalog + PiProviderPresets.all.filterNot { it.id in covered }
    }
    // **选中的那一档必须从同一张表里取。** 这里曾经是 `byId(presetId) ?: all.first()`：
    // 手写表只有 13 个 id，官方目录有 42 个，于是选中 `kimi-coding` 这类官方独有的厂商时
    // 它会**静默退化成 openai** —— 标题写着 OpenAI、Base URL 填的是 api.openai.com、
    // 保存时 `save(preset = preset)` 把厂商块与凭证写到 `openai` 名下（覆盖用户真正的
    // openai 配置）。
    //
    // 现在：表里没有时按该厂商**自己的**连接信息现构一档「自定义厂商」（见
    // `customPresetFromInventory`）；连厂商都找不到时只是占位（下面禁用保存并说明），
    // **绝不回退到任何官方厂商**。厂商卡传进来的 `initialPresetId` 就是
    // `models.json`/`auth.json` 里的 id，它不一定在官方目录里 —— 那正是这条路。
    val knownProvider = remember(knownProviders, presetId) {
        knownProviders.firstOrNull { it.id == presetId }
    }
    val presetProblem = remember(providerChoices, knownProvider, presetId) {
        if (presetId.isNotEmpty() && providerChoices.none { it.id == presetId } && knownProvider == null) {
            "这个厂商既不在模型配置里、也不在官方目录里：先在上面重新选一个厂商。"
        } else {
            null
        }
    }
    val preset = remember(providerChoices, knownProvider, presetId) {
        providerChoices.firstOrNull { it.id == presetId }
            ?: knownProvider?.let { customPresetFromInventory(it) }
            ?: placeholderPreset(presetId)
    }
    val officialForPreset = remember(officialProviders, presetId) {
        officialProviders.firstOrNull { it.id == presetId }?.models.orEmpty()
    }

    // 已配置的厂商 = 清单里有凭证/厂商块的（`knownProviders`），加上 auth.json 里出现过的
    // id（`configuredProviderIds`：清单还没刷新时也认得）。两份都已在内存里，不读盘。
    val configuredIds = remember(knownProviders, configuredProviderIds) {
        configuredProviderIds.toSet() +
            knownProviders.filter { it.hasCredential || it.configured }.map { it.id }
    }
    // 「N 个模型」：清单里的实际模型数优先（那是这台设备上真正配着的），没有才用目录的。
    val modelCounts = remember(knownProviders, officialCounts) {
        val fromInventory = knownProviders.associate { it.id to it.models.size }
        fromInventory + officialCounts.filterKeys { it !in fromInventory }
    }
    // 已配置的排最前，其余保持目录/预设的原顺序。
    val orderedChoices = remember(providerChoices, configuredIds) {
        val (configured, pending) = providerChoices.partition { it.id in configuredIds }
        configured + pending
    }
    val shownChoices = remember(orderedChoices, pickerQuery) {
        val query = pickerQuery.trim().lowercase()
        if (query.isEmpty()) {
            orderedChoices
        } else {
            orderedChoices.filter { option ->
                option.id.lowercase().contains(query) || option.displayName.lowercase().contains(query)
            }
        }
    }

    // prefill + 目录一次读完（IO 线程）：分开两个 effect 会有目录晚到、
    // 「默认全勾」漏掉目录那批的竞态。切厂商即重置，和旧表单同一个键。
    LaunchedEffect(presetId) {
        if (presetId.isEmpty()) return@LaunchedEffect
        val (existing, entries) = withContext(Dispatchers.IO) {
            service.prefill(presetId) to service.catalogModels(presetId)
        }
        baseUrl = existing.baseUrl ?: preset.baseUrl
        api = existing.api ?: preset.api
        providerName = existing.displayName.orEmpty()
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

    // 连接信息晚到的补偿：`LaunchedEffect(presetId)` 用的是**打开那一刻**的 `preset`，而
    // 官方目录是异步读到的 —— 那一刻 `preset` 可能还是 `models.json` 里现构的自定义档
    // （`api`/`baseUrl` 都空）。`auth.json` 里有、`models.json` 里没有块的厂商（`/login`
    // 出来的）正好落到这条路上：保存时 `PiConfigFiles.upsert` 拒"Base URL 不能为空"，
    // 而那个框在已配置厂商上默认收起 —— 用户只看到页脚一行红字，却找不到要填的框。
    // 只补**空着**的字段，绝不覆盖用户已经输入或 `models.json` 里已有的值。
    LaunchedEffect(preset.baseUrl, preset.api) {
        if (baseUrl.isBlank()) baseUrl = preset.baseUrl
        if (api.isBlank()) api = preset.api
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
        if (presetId.isEmpty() || officialForPreset.isEmpty() || selected.isNotEmpty()) return@LaunchedEffect
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

    // 跨厂商的官方目录索引 —— 中转站几乎都在转**别家的模型**：`gpt-4o`、`claude-sonnet-4-5`、
    // `deepseek-chat` 的真数据（上下文、最大输出、图片能力、reasoning）就在 pi 随包的目录里，
    // 只是不在**这一家**的 `<provider>.json` 里。没有这一步，这些 id 全部落到 pi 的默认值
    // （128k / 16k / 仅文本），用户看到的就是「导入来的模型数据是随便填的」。
    //
    // 两个键：**精确 id**，以及 OpenRouter 那种 `vendor/model` 的最后一段。价格**不跨厂商借**：
    // 中转站的定价和模型原厂不是一回事，借来就是把账单写错。
    val officialAcross = remember(officialProviders, presetId) {
        val exact = HashMap<String, PiOfficialCatalog.Model>()
        val tail = HashMap<String, PiOfficialCatalog.Model>()
        officialProviders.forEach { provider ->
            // 这一家自己的目录由 `catalog` 负责，不在这里重复占位。
            if (provider.id == presetId) return@forEach
            provider.models.forEach { model ->
                exact.putIfAbsent(model.id, model)
                tail.putIfAbsent(model.id.substringAfterLast('/'), model)
            }
        }
        exact to tail
    }

    val knownById = remember(availableModels, catalog, officialAcross, presetId, scanned, devIndex) {
        val fromCatalog = catalog.associateBy { it.id }
        val fromEngine = availableModels.filter { it.provider == presetId }.associateBy { it.id }
        (fromCatalog.keys + fromEngine.keys + scannedById.keys).associateWith { id ->
            val entry = fromCatalog[id]
            val engine = fromEngine[id]
            val scan = scannedById[id]
            // 别家厂商目录里的同 id 模型：`pi` 自己的数据，只是这一家没有。
            val cross = officialAcross.first[id] ?: officialAcross.second[id.substringAfterLast('/')]
            // models.dev：pi 目录的上游，覆盖 232 家；同样先精确、再裸 id。它排最后，
            // 只补前面几处都不知道的那几项（尤其是思考档位）。
            //
            // **价格只认精确匹配**：裸 id 命中可能是别家厂商的同名模型（同一个 `gpt-5`
            // 在十几家下面都有），把它的价写到这台设备上就是把账单写错。
            val devExact = devIndex?.first?.get("$presetId/$id")
            val dev = devExact ?: devIndex?.second?.get(id)
            KnownModel(
                name = entry?.name ?: engine?.name ?: scan?.displayName ?: cross?.name
                    ?: dev?.name,
                // 思考能力：目录 → 引擎 → **这次扫描**（厂商自己说的）→ 跨厂商目录。
                // 以前只认目录/引擎，所以「明明支持思考、目录里没有」的模型永远显示不出来。
                reasoning = entry?.reasoning ?: engine?.reasoning ?: scan?.reasoning
                    ?: cross?.reasoning ?: dev?.reasoning,
                acceptsImages = entry?.acceptsImages ?: engine?.acceptsImages
                    ?: scan?.acceptsImages ?: cross?.acceptsImages ?: dev?.acceptsImages ?: false,
                contextWindow = entry?.contextWindow ?: engine?.contextWindow
                    ?: scan?.contextWindow ?: cross?.contextWindow ?: dev?.contextWindow,
                maxTokens = entry?.maxTokens ?: engine?.maxTokens ?: scan?.maxTokens
                    ?: cross?.maxTokens ?: dev?.maxTokens,
                // 价格只认这一家/这次扫描的（cross 是别家的价，不属于这台设备；
                // models.dev 只在前面都没有时兜底，它是模型的原厂价）。
                costCacheRead = entry?.costCacheRead ?: engine?.cacheReadCost
                    ?: scan?.costCacheReadPerMillion ?: devExact?.costCacheRead,
                costCacheWrite = entry?.costCacheWrite ?: engine?.cacheWriteCost
                    ?: scan?.costCacheWritePerMillion ?: devExact?.costCacheWrite,
                costInput = entry?.costInput ?: engine?.inputCost ?: scan?.costInputPerMillion
                    ?: devExact?.costInput,
                costOutput = entry?.costOutput ?: engine?.outputCost ?: scan?.costOutputPerMillion
                    ?: devExact?.costOutput,
                samplingParams = scan?.samplingParams ?: entry?.samplingParams,
                // 思考档位：这一家目录 → 别家目录里的同 id 模型（pi 的目录是 models.dev 的
                // 快照，自带 thinkingLevelMap）→ models.dev（联网兜底）。中转站转的
                // `claude-*`/`gpt-*` 因此没网也能拿到档位。
                thinkingLevelMap = entry?.thinkingLevelMap ?: cross?.thinkingLevelMap
                    ?: dev?.thinkingLevelMap,
                costTiers = entry?.costTiers ?: devExact?.costTiers,
            )
        }
    }

    // 列表里的搜索：按 id 或名字过滤。remember 在源数据 + 查询上，不每次重组重算。
    val shownCandidates = remember(candidates, modelQuery, knownById) {
        val query = modelQuery.trim()
        if (query.isEmpty()) {
            candidates
        } else {
            candidates.filter { id ->
                id.contains(query, ignoreCase = true) ||
                    knownById[id]?.name?.contains(query, ignoreCase = true) == true
            }
        }
    }

    fun choicesFor(ids: List<String>): List<PiCredentialService.ModelChoice> = ids.map { id ->
        val known = knownById[id]
        val override = overrides[id]
        PiCredentialService.ModelChoice(
            id = id,
            name = known?.name,
            reasoning = override?.reasoning ?: known?.reasoning,
            contextWindow = override?.contextWindow ?: known?.contextWindow,
            maxTokens = override?.maxTokens ?: known?.maxTokens,
            // 只说知道的：官方目录或厂商自己说了"支持图片"才写 ["text","image"]，
            // 否则**不写这个键** —— 省略时 pi 自己按纯文本处理
            // （`provider-composer.ts:158`），那才是官方默认。以前无论知不知道都写
            // ["text"]，等于替厂商断言一次它没说过的话，界面也因此不得不问用户
            // "支持图片吗"（用户裁定：这个不让用户选）。
            input = if ((override?.images ?: known?.acceptsImages) == true) {
                listOf("text", "image")
            } else {
                emptyList()
            },
            costInput = override?.costInput ?: known?.costInput,
            costOutput = override?.costOutput ?: known?.costOutput,
            costCacheRead = override?.costCacheRead ?: known?.costCacheRead,
            costCacheWrite = override?.costCacheWrite ?: known?.costCacheWrite,
            samplingParams = known?.samplingParams,
            thinkingLevelMap = known?.thinkingLevelMap,
            costTiers = known?.costTiers,
        )
    }

    // 这次保存会删掉几个**已申报**的模型定义：对账基准（`initialChecked`）里被取消勾选的、
    // 且真在 models.json 里申报过的那些（取消勾选 = pi 那边同 id 的整条定义被删掉，
    // `provider-composer.ts:203-206` 的整条替换反过来就是"没有定义"）。保存成功后
    // `initialChecked` 前移到本次勾选，这一行自己消失。
    val deletingCount = remember(initialChecked, selected, existingIds) {
        val declared = existingIds.toSet()
        (initialChecked - selected).count { it in declared }
    }

    // 保存：一次写凭证 + 厂商块 + 循环列表（`PiCredentialService.save`）。不写默认模型 ——
    // 默认那两把键在清单页的「默认模型」一行里写（`DefaultModelPairStore`）。
    val save: () -> Unit = {
        // 改过能力的模型即使没勾选也要写：用户点开编辑器就是在配置它，保存时不该被
        // 一个勾选框丢掉。
        val chosen = candidates.filter { it in selected || it in overrides.keys }
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
                    // 会退回 128k）。官方不认识的（还没更新的新模型）必须申报
                    // —— 那是"增加"，否则模型永远不出现。
                    officiallyKnownIds = catalogIds,
                    displayName = providerName.ifBlank { suggestedProviderName(baseUrl, preset) },
                    // 用户改过能力的模型必须**申报**，哪怕它是 pi 官方目录里的：
                    // 只有 `models[]` 里的一条才能承载这次修改（同 id 整条替换）。
                    forceDeclared = overrides.keys,
                    // **这个厂商那份**目录读到了没有，不是"整份目录读到过没有"：
                    // `PiOfficialCatalog` 对单份坏掉的 `<provider>.json` 只记 problem 并继续，
                    // 于是整份 `officialProviders` 非空、这一家却是空。传错的话
                    // `PiCredentialService` 会把"官方认识"判断成"一个都不认识"，把扫描/引擎
                    // 数据写进 `models[]` —— pi 同 id 整条替换，价格 0、上下文 128k
                    // （§M11 那个事故）。
                    officialCatalogRead = officialForPreset.isNotEmpty(),
                )
            }
            // 失败时最后一步就是错误句，它在固定页脚里显示 —— 正文不再重复一遍。
            saveSteps = if (result.ok) result.steps else result.steps.dropLast(1)
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
    }

    PiSheet(onDismiss = onClose) {
        // 内容区自己滚，底部那行（保存 + 状态）固定 —— 用户不用滚过整张模型列表才能按保存。
        Column(Modifier.fillMaxWidth()) {
            Text(
                "导入模型",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.cardPadding,
                    vertical = PiSpacing.small,
                ),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    // `fill = false`：内容短时 sheet 就短，内容长时才撑到剩余高度再滚。
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                // ------------------------------------------------------ 1 选厂商
                // （节标题已删，动作词并进下面这一行的标题。）
                // **竖列表 + 搜索，不是横滑 chips**（用户："横选了啥时候也看不完"）：官方目录
                // 正常时 41 档、目录读不到时预设表的 13 档，一屏能翻、能搜，长名字也不会被挤没。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            // 「1 选厂商」这个节标题已删，动作词并进这一行的标题。
                            if (presetId.isEmpty()) "选厂商" else preset.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (presetId.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            if (presetId.isEmpty()) {
                                "点下面的一个厂商"
                            } else {
                                buildString {
                                    append(preset.id)
                                    modelCounts[preset.id]?.let { append(" · ").append(it).append(" 个模型") }
                                    if (preset.id in configuredIds) append(" · 已配置")
                                    append(if (preset.builtInPi) " · pi 官方" else " · App 自建")
                                }
                            },
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { pickerOpen = !pickerOpen }) {
                        Text(if (pickerOpen) "收起" else "更换")
                    }
                }
                // 说不出选中的厂商时**先别让保存**：保存写的是 models.json 的厂商块与 auth.json
                // 的凭证，写到猜出来的厂商名下就是覆盖别人的配置（这个 sheet 修的就是这种事故）。
                presetProblem?.let { SheetNote(it) }
                if (officialProblems.isNotEmpty()) {
                    // 两种失败要分开说：目录整份读不到（载荷换了依赖树布局、还没解包）时列表
                    // 只有预设表里的那些，一句话说清来源；只有某几份文件坏了时，逐份念出来才有用
                    // —— problems 里那串候选路径是给诊断看的，不是屏幕文案。
                    if (officialProviders.isEmpty()) {
                        SheetNote("官方目录读不到，下面的厂商只有 App 自带的 ${providerChoices.size} 个。")
                    } else {
                        SheetNote(
                            "官方目录有 ${officialProblems.size} 处读不出来，下面的厂商可能少几个：" +
                                officialProblems.joinToString("；"),
                        )
                    }
                }
                if (modelsFileError != null) {
                    SheetNote("模型配置当前无法被 pi 解析：$modelsFileError。修好之前，写入的厂商不会生效。")
                }
                if (authFileError != null) {
                    SheetNote("凭证当前无法被读取，这个页面显示的厂商不是全部。修好之前 pi 不会启动。")
                }
                if (pickerOpen) {
                    PiEditorField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        label = "搜厂商（名字或 id）",
                        height = 44.dp,
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
                                        // 「已配置」是这一屏最重要的一格：它决定下面的 Key/扫描
                                        // 要不要填。机器 id 放最后。
                                        buildList {
                                            if (option.id in configuredIds) add("已配置")
                                            modelCounts[option.id]?.let { add("$it 个模型") }
                                            add(option.id)
                                        }.joinToString(" · "),
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

                // 未选厂商时 2/3 两节都不出现：先让用户点一个厂商，而不是对着一个空表单。
                if (presetId.isNotEmpty()) {
                    // ------------------------------------------------------ 2 粘 Key
                    // （节标题已删：下面的「API Key」输入框 / 已存凭证就是这一步。）
                    if (credentialPresent && !keyOpen) {
                        // 已配置的厂商：一行说清"凭证已经在文件里、留空就是沿用"，
                        // 想看/换 Key 才展开（展开后与未配置时同一个表单）。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.cardPadding,
                                    vertical = PiSpacing.small,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                buildString {
                                    append(if (maskedKey != null) "已存凭证（$maskedKey）" else "已存凭证")
                                    append(" · 留空＝沿用")
                                },
                                modifier = Modifier.weight(1f),
                                style = PiTheme.text.monoSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { keyOpen = true }) { Text("更换") }
                        }
                    } else {
                        PiEditorField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = "API Key",
                            height = 44.dp,
                                                        modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
                        )
                        if (preset.scanStyle == PiProviderPresets.ScanStyle.Keyless) {
                            SheetNote(
                                "这个端点不校验鉴权，留空会写入占位值「" +
                                    PiProviderPresets.KEYLESS_PLACEHOLDER + "」。",
                            )
                        }
                        Row(
                            modifier = Modifier.padding(
                                horizontal = PiSettingsMetrics.cardPadding,
                                vertical = PiSpacing.unit,
                            ),
                        ) {
                            TextButton(onClick = { advancedOpen = !advancedOpen }) {
                                Text(if (advancedOpen) "收起高级" else "高级")
                            }
                        }
                        if (advancedOpen) {
                            PiEditorField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = "Base URL",
                                height = 44.dp,
                                                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = PiSettingsMetrics.cardPadding,
                                        vertical = PiSpacing.small,
                                    ),
                            )
                            PiEditorField(
                                value = api,
                                onValueChange = { api = it },
                                label = "api（协议实现）",
                                height = 44.dp,
                                                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = PiSettingsMetrics.cardPadding,
                                        vertical = PiSpacing.small,
                                    ),
                            )
                        }
                    }

                    // ------------------------------------------------------ 扫描（一个按钮行）
                    Row(
                        modifier = Modifier.padding(
                            horizontal = PiSettingsMetrics.cardPadding,
                            vertical = PiSpacing.small,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = !scanning,
                            onClick = {
                                // 6 MB 的 models.dev 索引在扫描**之后**单独取，不占
                                // 「正在扫描…」这段等待。
                                var needDev = false
                                sheetScope.launch {
                                    scanning = true
                                    scanError = null
                                    scanNote = null
                                    scanEndpoint = null
                                    try {
                                        // 已配置厂商的 Key 是收起的：留空时 `probe` 用
                                        // `auth.json` 里那条去探测，否则"已配置"的厂商
                                        // 反而扫描不了（见 `PiCredentialService.probe`）。
                                        when (val result = service.probe(preset, apiKey, baseUrl.ifBlank { null })) {
                                            is PiModelScanner.Result.Ok -> {
                                                scanned = result.models
                                                scanEndpoint = result.endpoint
                                                scanNote = result.note
                                                // 厂商只回了 id、或没说会不会思考的模型：补一次
                                                // models.dev（缓存 7 天、失败就跳过）。
                                                needDev = result.models.any {
                                                    it.contextWindow == null || it.reasoning == null
                                                }
                                                // 扫描到的并入勾选、保留已做的勾选；默认模型一个都
                                                // 不动（这张 sheet 本来也不写默认模型）。
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
                                    if (needDev) {
                                        devIndex = withContext(Dispatchers.IO) {
                                            service.ensureModelsDevIndex()
                                        }
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

                    // ------------------------------------------------------ 3 勾选模型
                    // （节标题已删，动作词并进下面这一行的标题。）
                    Row(
                        modifier = Modifier.padding(
                            horizontal = PiSettingsMetrics.cardPadding,
                            vertical = PiSpacing.small,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "勾选模型 · 已勾选 ${selected.size} / 共 ${candidates.size}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(onClick = { selected = selected + candidates }) { Text("全选") }
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                    }
                    PiEditorField(
                        value = modelQuery,
                        onValueChange = { modelQuery = it },
                        label = "搜模型（id 或名字）",
                        height = 44.dp,
                                                modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
                    )
                    PiEditorField(
                        value = manualIds,
                        onValueChange = { manualIds = it },
                        label = "手动模型名（逗号、空格或换行分隔）",
                        height = 44.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSpacing.small),
                    )
                    // 一个端点可能有几百个模型（OpenRouter 规模）。候选列表放在一个**有界高度**
                    // 的 LazyColumn 里：只组合屏幕内那几行，滚多少画多少，不再按上限截断。
                    // 高度必须有界 —— 外层 Column 已经在纵向滚动，无界的 LazyColumn 会被
                    // Compose 直接拒绝（infinity constraints）。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        LazyColumn {
                            items(shownCandidates, key = { id -> id }) { id ->
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
                            TextButton(onClick = { editId = if (editId == id) null else id }) {
                                Text(if (id in overrides) "已改" else "能力")
                            }
                        }
                        if (editId == id) {
                            ModelFieldsForm(
                                initial = overrides[id] ?: fieldsFromKnown(known),
                                onChanged = { fields -> overrides = overrides + (id to fields) },
                            )
                            if (id in overrides) {
                                TextButton(
                                    onClick = { overrides = overrides - id },
                                    modifier = Modifier.padding(horizontal = PiSpacing.inline),
                                ) { Text("恢复导入时的值") }
                            }
                            Spacer(Modifier.height(PiSpacing.small))
                        }
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        SheetNote("pi 目录里还没有这个厂商的模型：点上面的扫描，或手动填一个模型名。")
                    } else if (shownCandidates.isEmpty()) {
                        SheetNote("没有匹配的模型")
                    }
                    saveSteps.forEach { step -> SheetNote(step) }
                }
            }
            // 底部固定在 sheet 上：保存按钮、本次会删除什么、结果/下一步，都在这几行里。
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PiSettingsMetrics.cardPadding,
                        vertical = PiSpacing.small,
                    ),
            ) {
                if (deletingCount > 0) {
                    Text(
                        "这次会删除 $deletingCount 个模型定义",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.warning,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        // `presetProblem` 非空 = 说不出这一档是谁：保存会写错厂商名下的块与凭证。
                        enabled = presetId.isNotEmpty() && presetProblem == null && !scanning &&
                            (selected.isNotEmpty() || preset.builtInPi || overrides.isNotEmpty()),
                        onClick = save,
                    ) { Text("保存") }
                    Spacer(Modifier.width(PiSpacing.inline))
                    Text(
                        // 一行状态：没选厂商说下一步，有错报错，否则报勾选数与保存门槛。
                        when {
                            presetId.isEmpty() -> "先选一个厂商"
                            saveError != null ->
                                saveError.orEmpty().lineSequence().firstOrNull().orEmpty()

                            savedOk -> "已保存；新厂商若标「等待重启」就回清单页重启引擎"
                            selected.isNotEmpty() -> "已勾选 ${selected.size} 个"
                            // 官方厂商不申报模型（models[] 一律不写），空着保存 = 只写凭证与
                            // 连接块、循环列表一个字节不动 —— 「已有模型、只想补个 Key」不该
                            // 被一个勾选门槛挡住（Bug②）。
                            preset.builtInPi -> "不勾也能保存（不改循环列表）"
                            overrides.isNotEmpty() -> "会保存 ${overrides.size} 条模型的能力"
                            else -> "至少要勾选一个模型"
                        },
                        style = PiTheme.text.meta,
                        color = if (saveError != null) {
                            PiTheme.palette.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * One paragraph of explanation, muted settings style (screen width).
 *
 * [tone] 只换字色，不换底色/间距：设置页的提示条本来就是「同一张底 + 不同字色」
 * （`PiInfoNote` 的同一个约定）。目前只有「厂商和模型对不上」那一行用 warning 色。
 */
@Composable
private fun Note(text: String, tone: Color? = null) {
    Text(
        text,
        modifier = Modifier.padding(
            horizontal = PiSettingsMetrics.pageHorizontal,
            vertical = PiSpacing.small,
        ),
        style = PiTheme.text.meta,
        color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
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
    // 「N 个模型」只数**这台设备上配置过的**：`data.models` 把 pi 目录里这个厂商的全部模型
    // 都算进来了，只配一个 openrouter 会写成「1 个厂商 · 381 个模型」—— 那个数字是 pi 的
    // 目录规模，不是用户配置的规模（注册表那行叫的是「已导入的模型」）。手写申报、覆盖、
    // 引擎里出现过的都算；只有 pi 目录里有的不算。
    val configured = data.models.count { model ->
        model.origins.isEmpty() ||
            PiModelInventory.Origin.DECLARED in model.origins ||
            PiModelInventory.Origin.OVERRIDDEN in model.origins
    }
    Text(
        buildString {
            append("${data.providers.size} 个厂商 · $configured 个模型 · 现在可用 $ready 个")
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
    lifecycle: ExtensionLifecycle,
    coordinator: EngineRestartCoordinator,
    /** 真要重启时就地问一句：这条路径必须弹确认框。 */
    onQuestion: (String) -> Unit,
    /** 其余结果（有回合在跑 / 已经重启过 / 正在装包）只在列表顶部说一句。 */
    onNote: (String) -> Unit,
) {
    PiSettingsCard(modifier = Modifier.padding(top = PiSpacing.small)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
        ) {
            Text(
                "刚改过模型或凭证的配置，pi 只在引擎启动时读它们 —— 重启一次才生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = PiTheme.palette.warning,
            )
            Spacer(Modifier.height(PiSpacing.gutter))
            TextButton(
                onClick = {
                    // 卡片本身就是"有改动等着"的证据，而状态机只认它自己的账：先把它
                    // 记进去，`requestRestart` 才会从 NeedsRestart 走到 NeedsConfirmation。
                    // 不记的话答案是 `NothingPending`，用户点「重启引擎」只会看到一句话、
                    // 引擎一次也不会真的重启（这正是这一版要修的 bug 的另一半）。
                    if (!lifecycle.pendingRestart) {
                        lifecycle.noteExternalChange(
                            changes = listOf("模型与凭证的配置"),
                            detail = "刚改过模型或凭证的配置，重启一次才生效。",
                        )
                    }
                    // 路由：只有 NeedsConfirmation 该弹确认框；其余全是"一句话说明"，绝不再
                    // 借 `question` 弹一个带「重启」按钮的框（用户看到的是「有回合正在运行」
                    // 却也给了个重启按钮）。
                    when (coordinator.request("新配置的模型需要被 pi 重新加载")) {
                        ExtensionLifecycle.RequestOutcome.NeedsConfirmation -> {
                            val waiting = lifecycle.current as? ExtensionLifecycle.State.AwaitingConfirmation
                            onQuestion(waiting?.question ?: "重启引擎以加载新配置的模型？")
                        }

                        ExtensionLifecycle.RequestOutcome.WaitingForTurn -> onNote(
                            "有回合正在运行。重启会中断模型调用与正在执行的工具，" +
                                "所以这次没有重启；回合结束后再点一次。",
                        )

                        ExtensionLifecycle.RequestOutcome.AlreadyReady ->
                            onNote("引擎已经在用这份配置了，不需要再重启。")

                        ExtensionLifecycle.RequestOutcome.BusyWithPackageCommand ->
                            onNote("正在安装包或重启引擎，等它结束再点。")

                        ExtensionLifecycle.RequestOutcome.NothingPending ->
                            onNote("还没有需要重启的改动。")
                    }
                },
            ) { Text("重启引擎") }
        }
    }
}

/** 注册表里的一行设置，就地编辑：标题取官方标题，读数与分组屏用同一个 `summaryText`。 */
/**
 * 开关类的设置行：**就地切换**，不打开编辑器。
 *
 * 为什么必须有这一支：`PiSettingEditorSheet` 对 `PiRowKind.Switch` 什么也不画
 * （`PiSettingEditorHost.kt` 的 `Switch, Action -> Unit`）——它假设开关在行上。原来
 * 「隐藏思考块」「缓存未命中提示」这两行走的是 [SettingLinkRow]，于是点一下什么也不发生；
 * 用户报的「推理那些很多点不动」就是这两行。
 */
@Composable
private fun SettingSwitchRow(
    key: String,
    store: PiSettingsStore,
    editTick: Int,
    reloadTick: Int,
    onSettingWritten: (String) -> Unit,
) {
    val setting = PiSettingsCatalog.byKey[key] ?: return
    val checked = remember(store, editTick, reloadTick, key) {
        ((setting.current(store)) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    }
    PiSettingsToggleRow(
        title = setting.title,
        supporting = setting.description,
        checked = checked,
        onCheckedChange = { next ->
            store.write(key, JsonPrimitive(next))
            onSettingWritten(key)
        },
    )
}

@Composable
private fun SettingLinkRow(
    key: String,
    store: PiSettingsStore,
    editTick: Int,
    /** 外部改动（`PiDirectoryWatch` → `reloadTick`）也要让读数重读，理由同上面三行。 */
    reloadTick: Int,
    onEdit: (String) -> Unit,
) {
    val setting = PiSettingsCatalog.byKey[key] ?: return
    val value = remember(store, editTick, reloadTick, key) { PiSettingsCatalog.summaryText(store, key) }
    LinkRow(title = setting.title, value = value, onClick = { onEdit(key) })
}

@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit) {
    // 值行：标题 + 值（13 等宽一行）+ chevron。原来行尾的「修改」文字已删 ——
    // 统一后的行没有文字尾巴，可点这件事由 chevron 说。
    PiSettingsValueRow(title = title, value = value, onClick = onClick)
}

@Composable
private fun ProviderCard(
    provider: PiModelInventory.Provider,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenCredentials: () -> Unit,
    onDelete: () -> Unit,
    onEditModel: (PiModelInventory.Model) -> Unit,
) {
    val shown = if (expanded) provider.models else provider.models.filter { it.noteworthy }
    // 标题先认 `models.json` 里的 `name`（用户/导入时给这个厂商起的名字），再退到预设的
    // 显示名，最后才是 id。反过来的话，自建厂商会一律显示成「自定义（OpenAI 兼容）」——
    // 用户报的「为什么写的不是供应商本身的名字」。
    val title = provider.name ?: PiProviderPresets.byId(provider.id)?.displayName ?: provider.id
    PiSettingsCard(
        // 相邻的厂商卡要有边界：同一个底色贴在一起会读成一整块（用户报「混在一起」）。
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 卡头：厂商名 + 模型数 + 展开箭头，整条可点。底色比卡身高一档，像一顶帽子。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onToggle)
                    .padding(
                        horizontal = PiSettingsMetrics.cardPaddingLoose,
                        vertical = PiSettingsMetrics.cardPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        "${provider.models.size} 个模型 · " +
                            if (provider.hasCredential) "已配置凭证" else "还没有凭证",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier
                        .size(PiSettingsMetrics.chevronSize)
                        .then(if (expanded) Modifier.rotate(90f) else Modifier),
                    tint = PiTheme.palette.muted,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(PiSettingsMetrics.cardPaddingLoose),
            ) {
                if (provider.models.isEmpty()) {
                    Text(
                        "pi 的目录里还没有这个厂商的模型；导入时会读到模型清单。",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                shown.forEach { model -> ModelLine(model, onEdit = { onEditModel(model) }) }
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
}

@Composable
private fun ModelLine(model: PiModelInventory.Model, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
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
    val color = when (status) {
        PiModelInventory.Status.READY -> MaterialTheme.colorScheme.primary
        PiModelInventory.Status.PENDING_RESTART -> MaterialTheme.colorScheme.tertiary
        PiModelInventory.Status.NOT_LISTED -> MaterialTheme.colorScheme.onSurfaceVariant
        PiModelInventory.Status.MISSING_CREDENTIAL -> MaterialTheme.colorScheme.onSurfaceVariant
        PiModelInventory.Status.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(statusText(status), style = MaterialTheme.typography.labelMedium, color = color)
}

/**
 * 一个状态的措辞，**只有这一处**：清单页的标签与默认模型选择器的副标题都用它。
 *
 * 新造第二套说法会让同一份清单在两个地方各说各话（标签写「等待重启」、选择器里写
 * 「需重启引擎」），而这两句话说的是同一件事，用户没法知道它们等价。
 */
private fun statusText(status: PiModelInventory.Status): String = when (status) {
    PiModelInventory.Status.READY -> "现在可用"
    PiModelInventory.Status.PENDING_RESTART -> "等待重启"
    // 「pi 未列出」只说 pi 现在的回答（目录里的模型要等一次启动才进快照，App 外改的文件
    // 也不经过本 App 的记账），不替用户推测"你该重启了"。
    PiModelInventory.Status.NOT_LISTED -> "pi 未列出"
    PiModelInventory.Status.MISSING_CREDENTIAL -> "缺凭证"
    PiModelInventory.Status.UNKNOWN -> "无法判断"
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

// ===========================================================================
// 三个选择键：候选一律从清单派生（默认模型 / 默认厂商 / 循环模型 / 逐模型思考等级）
// ===========================================================================

/**
 * 「快捷添加」最多列几枚 chip —— 循环模型那一行。
 *
 * 这排 chip 是横向滚动的（`PiListEditorSheet`），而一份真实清单可能上百个模型
 * （openrouter 一家就几百）：上百枚 chip 就是用户说的「横选了啥时候也看不完」。
 * 超出的模型照样能手输 —— 这一行从来都允许手输。
 */
private const val MAX_MODEL_PRESETS = 40

/** `enabledModels` 原有的通配符预设（注册表那一行是它们的唯一出处，这里不抄第二份）。 */
private fun enabledModelWildcards(): List<String> =
    PiSettingsCatalog.byKey["enabledModels"]?.presets.orEmpty()

/**
 * 清单里的模型，写成 `厂商/模型` —— 循环模型那一行的快捷添加。
 *
 * 用 `厂商/模型` 而不是裸模型 id：`enabledModels` 的通配模式按 `provider/modelId` 与裸
 * modelId 各匹配一次（`PiModelInventory.matches` / pi 的 `resolveModelScope`），裸 id 在
 * 两家有同名模型时会指错厂商。
 */
private fun modelPresets(data: PiModelInventory.Inventory?): List<String> =
    data?.providers.orEmpty()
        .flatMap { provider -> provider.models.map { model -> "${provider.id}/${model.id}" } }
        .distinct()
        .take(MAX_MODEL_PRESETS)

/**
 * 循环模型那行的快捷添加：清单里的模型在前（点一枚加一条），**原有的通配符预设在后面**。
 * 通配符是这一行原来唯一的入口，一个都不能少，所以模型让出几枚位置给它们。
 */
private fun cycleModelPresets(data: PiModelInventory.Inventory?): List<String> {
    val wildcards = enabledModelWildcards()
    val room = (MAX_MODEL_PRESETS - wildcards.size).coerceAtLeast(0)
    return (modelPresets(data).take(room) + wildcards).distinct()
}

/**
 * 默认模型选择器的每一行：`厂商 / 模型`，副标题是一句话的状态（措辞复用 [statusText]）。
 *
 * `wire` 是 `厂商/模型`，不是裸模型 id：同名模型可以出现在多个厂商下（官方目录里
 * `gemini-*` 同时在 google 与 google-vertex，openrouter 转发的更多），而这一行选中之后
 * 要写的是**这个厂商**下的这个模型。解码在 [DefaultModelPairStore] 里（写两个键）。
 */
private fun modelPickerOptions(data: PiModelInventory.Inventory?): List<PiOption> =
    data?.providers.orEmpty().flatMap { provider ->
        provider.models.map { model ->
            PiOption(
                wire = "${provider.id}/${model.id}",
                label = "${provider.id} / ${model.id}",
                description = buildList {
                    add(statusText(model.status))
                    if (model.isDefault) add("当前默认")
                }.joinToString(" · "),
            )
        }
    }

/**
 * 默认厂商的选项，三层：
 *
 *  1. **清单里出现的厂商**放在最前，带「已配置 / N 个模型」—— 自定义厂商（AI 手写
 *     `models.json` 的那些）只有这里点得到，写死的 40 项里没有它们；
 *  2. `PiProviderPresets` 的官方预设补其后（去重，清单里已有的以清单为准，连接信息也是）；
 *  3. 注册表原来的 40 项里前两层没有的 —— 不丢：`minimax-cn` 这类「清单里还没出现、
 *     官方目录里也没有」的厂商，删掉它们只会把手打的范围扩大，那不是改善。
 */
private fun providerPickerOptions(data: PiModelInventory.Inventory?): List<PiOption> {
    val fromInventory = data?.providers.orEmpty().map { provider ->
        PiOption(
            wire = provider.id,
            // 与厂商卡的标题同一个判据（`ProviderCard`）：预设里的官方显示名优先。
            label = PiProviderPresets.byId(provider.id)?.displayName ?: provider.name ?: provider.id,
            description = buildList {
                add(provider.id)
                add(if (provider.configured) "已配置" else "未配置")
                if (provider.models.isNotEmpty()) add("${provider.models.size} 个模型")
            }.joinToString(" · "),
        )
    }
    val taken = fromInventory.mapTo(LinkedHashSet()) { it.wire }
    val fromPresets = PiProviderPresets.all.filter { taken.add(it.id) }.map { preset ->
        PiOption(
            wire = preset.id,
            label = preset.displayName,
            description = if (preset.builtInPi) "pi 官方厂商" else "App 自建",
        )
    }
    val fromRegistry = PiSettingsCatalog.byKey["defaultProvider"]?.options.orEmpty()
        .filter { taken.add(it.wire) }
    return fromInventory + fromPresets + fromRegistry
}

/**
 * 两键都已设置、却明显对不上时的那一句话；null = 不打扰。
 *
 * 只在能**证明**不一致时才说：厂商不在清单里、或这个厂商还没有任何模型（信息不足）都返回
 * null。判据只读清单，不额外读盘，也不会每帧重算（调用点 remember 在清单与两个读数上）。
 *
 * 为什么值得一行字：`selectModel` 把两键**一起**写，所以这种不一致只可能来自手输（用户点过
 * 「仍然使用」）、外部改 `settings.json`，或者只换了厂商 —— 而 pi 的解析顺序会让模型落回
 * 占位模型，不报错（`docs/engine-exit-review.md:55`）。
 */
private fun modelProviderMismatch(
    data: PiModelInventory.Inventory?,
    provider: String?,
    model: String?,
): String? {
    if (provider.isNullOrBlank() || model.isNullOrBlank()) return null
    val known = data?.providers?.firstOrNull { it.id == provider } ?: return null
    if (known.models.isEmpty()) return null
    val normalized = model.removePrefix("$provider/")
    if (known.models.any { it.id == normalized }) return null
    return "厂商和模型对不上：现在会写 $provider / $model"
}

/**
 * 手输的模型名在清单里找不到时给的那句话（选择器里的**写前**警告，pi 那边是静默退回占位
 * 模型：`docs/engine-exit-review.md:55` 实测 `defaultModel` 写成不存在的模型，退出码 0、
 * stderr 空，模型退回 `contextWindow: 0`）。找到就不打扰；清单还没读到时也不猜。
 */
private fun unknownModelWarning(data: PiModelInventory.Inventory?, typed: String): String? {
    val providers = data?.providers.orEmpty()
    if (providers.isEmpty()) return null
    if (modelWire(typed, providers, null) != null) return null
    return "清单里没有这个模型：pi 会静默退回占位模型"
}

/**
 * 选择器的 wire → 真正要写的 `(厂商, 模型 id)`；null = 清单里没有它。
 *
 * wire 有两种形态：选择器给的 `厂商/模型`（模型 id 本身可以带斜杠，比如 openrouter 的
 * `anthropic/claude-*`），以及用户手输的裸模型 id。判定顺序是"先认整串是某个模型的 id，
 * 再认斜杠前是厂商" —— 反过来的话 `openrouter/anthropic/claude-x` 会被拆成
 * `openrouter` + `anthropic/claude-x`（对），但裸的 `anthropic/claude-x` 会被拆成
 * `anthropic` 厂商（可能是错的），而它作为 openrouter 的模型 id 是精确的。
 */
private fun modelWire(
    wire: String,
    providers: List<PiModelInventory.Provider>,
    preferredProvider: String?,
): Pair<String, String>? {
    val trimmed = wire.trim()
    if (trimmed.isEmpty()) return null
    val owners = providers.filter { provider -> provider.models.any { it.id == trimmed } }.map { it.id }
    if (owners.isNotEmpty()) {
        val provider = preferredProvider?.takeIf { it in owners } ?: owners.first()
        return provider to trimmed
    }
    val slash = trimmed.indexOf('/')
    if (slash > 0) {
        val provider = trimmed.substring(0, slash)
        val model = trimmed.substring(slash + 1)
        if (model.isNotEmpty() && providers.any { it.id == provider }) return provider to model
    }
    return null
}

/**
 * 只在这一屏用的 store 装饰器：`defaultModel` 与 `defaultProvider` 是**一对**键。
 *
 * 为什么需要它：pi 用 `defaultProvider` 决定由谁提供 `defaultModel`
 * （`core/settings-manager.ts:108-109`），而这一屏的模型选择器一次选择就同时决定两者。
 * 只写 `defaultModel` 会让 `defaultProvider` 继续指着上一个厂商，选的模型根本不是那个厂商
 * 的；而手打一个 pi 不认识的模型，pi 不报错、静默退回占位模型。导入 sheet 不写这两个键 ——
 * 要改默认模型，就是这一行。
 *
 * 三条行为，其余键原样转给 [base]：
 *
 *  - `write("defaultModel", "厂商/模型")` → **两个键一起写**；清单里找不到这个模型时只写
 *    `defaultModel`（用户点过「仍然使用」，此时不猜厂商，猜错会覆盖另一个厂商的配置）；
 *  - `remove("defaultModel")` → 两个键一起删（选择器底部的「跟随 pi 默认」；只删模型会让
 *    `defaultProvider` 继续把厂商钉住）；
 *  - `read("defaultModel")` → 两键一致时返回 `厂商/模型`，于是选择器能标出当前那一行。
 *
 * 只交给本屏的编辑器用（行上的读数仍读原始 store）：这是"编辑时的一对键"的语义，不是
 * `settings.json` 的真实形状。
 */
private class DefaultModelPairStore(
    private val base: PiSettingsStore,
    private val providers: List<PiModelInventory.Provider>,
) : PiSettingsStore by base {

    private fun providerValue(): String? = base.read(DEFAULT_PROVIDER_KEY)?.primitiveText()

    override fun read(key: String): JsonElement? {
        if (key != DEFAULT_MODEL_KEY) return base.read(key)
        val stored = base.read(DEFAULT_MODEL_KEY)?.primitiveText() ?: return null
        val provider = providerValue() ?: return JsonPrimitive(stored)
        val wire = modelWire(stored, providers, provider) ?: return JsonPrimitive(stored)
        return JsonPrimitive("${wire.first}/${wire.second}")
    }

    override fun write(key: String, value: JsonElement) {
        if (key != DEFAULT_MODEL_KEY) {
            base.write(key, value)
            return
        }
        val wire = value.primitiveText().orEmpty()
        val resolved = modelWire(wire, providers, providerValue())
        if (resolved == null) {
            base.write(DEFAULT_MODEL_KEY, JsonPrimitive(wire))
            return
        }
        // 先写厂商再写模型：中途失败时 `defaultModel` 还是旧的、`defaultProvider` 已经是新的，
        // 反过来则是"新模型 + 旧厂商"—— 两种都错，但前者至少模型名仍然属于配置过的厂商。
        base.write(DEFAULT_PROVIDER_KEY, JsonPrimitive(resolved.first))
        base.write(DEFAULT_MODEL_KEY, JsonPrimitive(resolved.second))
    }

    override fun remove(key: String) {
        if (key != DEFAULT_MODEL_KEY) {
            base.remove(key)
            return
        }
        base.remove(DEFAULT_PROVIDER_KEY)
        base.remove(DEFAULT_MODEL_KEY)
    }
}

private const val DEFAULT_MODEL_KEY = "defaultModel"
private const val DEFAULT_PROVIDER_KEY = "defaultProvider"


/**
 * 官方目录里的一档，在**没有手写预设**时构成一个可选厂商：连接信息取它第一个模型的
 * `api`/`baseUrl`，探测方式由 `api` 推导（anthropic → x-api-key，google → key 查询，
 * 其余 OpenAI 兼容）。41 个官方厂商里有一批是这样来的 —— 这正是"不自己维护表"。
 */
private fun presetFromOfficial(provider: PiOfficialCatalog.Provider): PiProviderPresets.Preset {
    val sample = provider.models.firstOrNull()
    val api = sample?.api ?: "openai-completions"
    val scanStyle = scanStyleFor(api)
    return PiProviderPresets.Preset(
        id = provider.id,
        displayName = provider.id,
        baseUrl = sample?.baseUrl.orEmpty(),
        api = api,
        scanStyle = scanStyle,
        authHeader = scanStyle == PiProviderPresets.ScanStyle.OpenAiCompatible,
        builtInPi = true,
        group = "官方",
    )
}

/**
 * 连接信息 → 探测方式（anthropic → x-api-key，google → key 查询，其余 OpenAI 兼容）。
 * 官方目录里的一档与 `models.json` 里已有的自定义厂商共用这一个判据。
 */
private fun scanStyleFor(api: String): PiProviderPresets.ScanStyle = when {
    api.contains("anthropic", ignoreCase = true) -> PiProviderPresets.ScanStyle.Anthropic
    api.contains("google", ignoreCase = true) -> PiProviderPresets.ScanStyle.Google
    else -> PiProviderPresets.ScanStyle.OpenAiCompatible
}

/**
 * `models.json` 里已有、但既不在官方目录也不在预设表里的厂商 —— 导入 sheet 的「自定义厂商」。
 *
 * 这是 `byId(presetId) ?: all.first()` 那个洞的补丁：AI 手写 `models.json` / `auth.json`
 * 时用的厂商 id 只存在于那份文件里，而厂商卡传进来的 `initialPresetId` 就是它。以前它
 * 静默退化成 `openai` —— 标题写着 OpenAI、Base URL 填的是 api.openai.com，保存时把厂商块
 * 与凭证写到 openai 名下，覆盖用户真正的 OpenAI 配置。
 *
 * `displayName` 就用 id：那份文件里没有显示名，编一个只会让标题与文件里的 id 对不上。
 */
private fun customPresetFromInventory(provider: PiModelInventory.Provider): PiProviderPresets.Preset {
    val api = provider.api ?: ""
    val scanStyle = scanStyleFor(api)
    return PiProviderPresets.Preset(
        id = provider.id,
        displayName = provider.id,
        baseUrl = provider.baseUrl.orEmpty(),
        api = api,
        scanStyle = scanStyle,
        authHeader = scanStyle == PiProviderPresets.ScanStyle.OpenAiCompatible,
        builtInPi = false,
        group = "自定义",
    )
}

/**
 * 说不出选中的厂商时的**占位档**：表里、清单里都没有这个 id。保存被禁用（见
 * `presetProblem`），但标题与 id 仍然照实显示 —— 不冒充任何官方厂商。
 */
private fun placeholderPreset(providerId: String): PiProviderPresets.Preset =
    PiProviderPresets.Preset(
        id = providerId,
        displayName = providerId,
        baseUrl = "",
        api = "",
        scanStyle = PiProviderPresets.ScanStyle.OpenAiCompatible,
        authHeader = true,
        builtInPi = false,
        group = "自定义",
    )

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
    costCacheRead = costCacheReadPerMillion,
    costCacheWrite = costCacheWritePerMillion,
    thinkingLevelMap = thinkingLevelMap,
    samplingParams = samplingParams,
    costTiers = costTiers,
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
    val costCacheRead: Double? = null,
    val costCacheWrite: Double? = null,
    val samplingParams: JsonObject? = null,
    val thinkingLevelMap: JsonObject? = null,
    val costTiers: JsonArray? = null,
)

/**
 * One line under a candidate model: where this row came from, then what pi knows.
 * (Transplanted from the old credential form; wording unchanged.)
 */
/**
 * 一条模型现在"以为"的能力 → 编辑表单的初始值。
 *
 * `acceptsImages` 在 [KnownModel] 里是非空布尔（目录/引擎/扫描任何一处说了就算），所以
 * 这里只能取 true/false；表单不会因为用户没碰开关就写它（见 `ModelFieldsForm` 的
 * `imagesTouched`），未知的模型因此仍然只写用户真改过的那几项。
 */
/** 导入后"点一条模型改能力"的目标：预填值与它是不是申报过的。 */
private data class ModelEditTarget(
    val providerId: String,
    val modelId: String,
    val subtitle: String,
    val fields: ModelFields,
    /**
     * 能不能"恢复 pi 默认"：只有**申报过、且 pi 目录里确实有它**才成立。
     * 自建厂商的模型在目录里没有定义，删掉这条申报等于把它从列表里删掉 —— 不是"恢复默认"。
     */
    val canReset: Boolean,
    /** 申报过、且目录里没有 → 删这条申报 = 这个模型消失；需要确认，故与 canReset 分开。 */
    val canDelete: Boolean,
)

/**
 * 预填的三源，优先级固定：**申报过的定义**（用户自己的当前值，最准）→ **引擎快照**
 * （pi 组合后的视图，含目录与覆盖）→ **pi 目录**（还没被引擎加载的模型）。
 *
 * 三处都没有 = 一条谁也不知道的模型（手写 id）：表单全空，保存时只写用户真填的那几项。
 * 顺序不能反：目录在申报之前会把用户上次改的值盖掉，一打开就"变回官方值"。
 */
private suspend fun loadModelEditTarget(
    service: PiCredentialService,
    engineModels: List<PiResponses.ModelInfo>,
    providerId: String,
    modelId: String,
): ModelEditTarget {
    val declared = service.declaredModel(providerId, modelId)
    val engine = engineModels.firstOrNull { it.provider == providerId && it.id == modelId }
    val catalog = service.officialCatalog().providers
        .firstOrNull { it.id == providerId }
        ?.models
        ?.firstOrNull { it.id == modelId }
    // 三处都没有时，models.dev 的缓存（有就用，不联网）是最后一份数据。价格用精确匹配：
    // 裸 id 可能是别家的同名模型，借它的价就是写错账单。
    val devIndex = service.modelsDevIndex()
    val devExact = devIndex?.first?.get("$providerId/$modelId")
    val dev = devExact ?: devIndex?.second?.get(modelId)
    val fields = when {
        declared != null -> ModelFields.of(declared)
        engine != null -> ModelFields(
            name = engine.name,
            contextWindow = engine.contextWindow,
            maxTokens = engine.maxTokens,
            images = engine.acceptsImages,
            costInput = engine.inputCost,
            costOutput = engine.outputCost,
            costCacheRead = engine.cacheReadCost,
            costCacheWrite = engine.cacheWriteCost,
            reasoning = engine.reasoning,
        )
        catalog != null -> ModelFields(
            name = catalog.name,
            contextWindow = catalog.contextWindow,
            maxTokens = catalog.maxTokens,
            images = catalog.acceptsImages,
            costInput = catalog.costInputPerMillion,
            costOutput = catalog.costOutputPerMillion,
            costCacheRead = catalog.costCacheReadPerMillion,
            costCacheWrite = catalog.costCacheWritePerMillion,
            reasoning = catalog.reasoning,
            samplingParams = catalog.samplingParams,
            thinkingLevelMap = catalog.thinkingLevelMap,
            costTiers = catalog.costTiers,
        )
        dev != null -> ModelFields(
            name = null,
            contextWindow = dev.contextWindow,
            maxTokens = dev.maxTokens,
            images = dev.acceptsImages,
            costInput = devExact?.costInput,
            costOutput = devExact?.costOutput,
            costCacheRead = devExact?.costCacheRead,
            costCacheWrite = devExact?.costCacheWrite,
            reasoning = dev.reasoning,
            thinkingLevelMap = dev.thinkingLevelMap,
            costTiers = devExact?.costTiers,
        )
        else -> ModelFields()
    }
    return ModelEditTarget(
        providerId = providerId,
        modelId = modelId,
        subtitle = "$providerId / $modelId",
        fields = fields,
        canReset = declared != null && catalog != null,
        canDelete = declared != null && catalog == null,
    )
}

/**
 * 自建厂商的默认显示名：端点的主机名（`api.foo.com`），拿不到就退回预设名。
 * 以前这里固定写「自定义（OpenAI 兼容）」，于是所有自建厂商在卡片上长得一模一样。
 */
private fun suggestedProviderName(baseUrl: String, preset: PiProviderPresets.Preset): String {
    val host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
    return host ?: preset.displayName
}

private fun fieldsFromKnown(known: KnownModel?): ModelFields = ModelFields(
    name = known?.name,
    contextWindow = known?.contextWindow,
    maxTokens = known?.maxTokens,
    images = known?.acceptsImages,
    costInput = known?.costInput,
    costOutput = known?.costOutput,
    costCacheRead = known?.costCacheRead,
    costCacheWrite = known?.costCacheWrite,
    reasoning = known?.reasoning,
    samplingParams = known?.samplingParams,
    thinkingLevelMap = known?.thinkingLevelMap,
    costTiers = known?.costTiers,
)

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
        if (known.reasoning == true) append(" · 会思考")
        // 价格：官方目录/引擎/厂商扫描任意一处给了就显示（用户报「连价格都不显示」——
        // 扫描器以前只读 id，自建厂商的价格因此永远是空的）。
        known.costInput?.let { append(" · $").append(trimCostText(it)).append("/M 入") }
        known.costOutput?.let { append(" · $").append(trimCostText(it)).append("/M 出") }
        known.costCacheRead?.let { append(" · $").append(trimCostText(it)).append("/M 缓存读") }
        known.costCacheWrite?.let { append(" · $").append(trimCostText(it)).append("/M 缓存写") }
    } else if (builtInPi) {
        append(" · pi 不认识它，勾选后会写进模型配置")
    } else {
        append(" · pi 不认识它，下面勾选的项会写进模型配置")
    }
    // 官方目录与厂商 API 都没给元数据的模型：说清 pi 会补什么默认值，别让人以为那些数字
    // 是厂商数据（`provider-composer.ts` 的 `modelFromJson`）。
    if (known == null || (known.contextWindow == null && known.costInput == null)) {
        append(" · 没有元数据：pi 用默认值（上下文 128k、最大输出 16k、仅文本、价格 0）")
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
