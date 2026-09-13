package app.pi.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import app.pi.packages.PiModelInventory
import app.pi.packages.PiProviderPresets
import app.pi.rpc.PiResponses
import app.pi.runtime.PtyLauncher
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 模型：**这台设备上配了哪些厂商、每个厂商下有哪些模型、它们从哪来、现在能不能用**。
 *
 * ## 为什么这个页面存在
 *
 * 用户的原话是「导入过的模型在里面看不到，没有『已导入的模型』列表」。缺的正是这个页面：在
 * 它之前，`models.json` 的内容只在凭证表单里按厂商显示一次，而对话页的模型选择器只读引擎
 * 的答案——于是「我导入过什么」没有一处能看，「它为什么不在列表里」也没有一处能解释。
 *
 * ## 三件事，都从文件与引擎读（`docs/pi-sourced-lists.md`）
 *
 *  1. **列表**：[PiModelInventory]，输入是四份文件原文 + 引擎的 `get_available_models`。
 *     App 不维护第二份表。
 *  2. **「文件里有、引擎没加载」**：`Status.PENDING_RESTART`。pi 只在 `ModelRuntime.create`
 *     （`core/model-runtime.ts:176`）与 `refresh`（`:699`）读 `models.json`，而 `--mode rpc`
 *     下没有任何命令会 refresh（唯一一次是启动期 `main.ts:925-936`），所以「引擎的列表里
 *     没有 + 有凭证」只有一个解释。
 *  3. **外部改动**：所有内容**直读文件**，不经 `PiSettingsFileStore` 的缓存；再用
 *     [PiDirectoryWatch]（inotify，加回前台比一次 mtime/大小兜底）在文件真的被外部改动时
 *     重读。没有定时器；页面不在组合里时不装监视。
 *
 * **一致性边界**在页面上直说，而不是让用户自己猜：文件写好 ≠ 引擎已经加载。凡是「引擎还没
 * 加载」的，页面给一句后果 + 一个下一步，重启走全 App 同一套 [ExtensionLifecycle] /
 * [EngineRestartCoordinator]，不是这里发明的机制。
 *
 * 另一条 pi 的边界也要说清：`enabledModels` 决定 Ctrl+P **循环**时算哪些模型，而它在引擎
 * **进程启动**时解析一次（`main.ts:788-791` 的 `resolveModelScope`，每个进程只调用一次，
 * `buildSessionOptions` 只在 `main.ts:797` 被调用）——所以改完这个列表要**重启引擎**才生效，
 * 不是「新开一个会话」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiModelsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    /** 全 App 同一套「需要重启」状态机，由设置栈持有（见 `PiSettingsStack`）。 */
    lifecycle: ExtensionLifecycle,
    /** 重启的执行者。null 表示没有引擎入口：界面说清不能重启，而不是给一个假的按钮。 */
    coordinator: EngineRestartCoordinator?,
    /** `get_available_models`：引擎现在真正列出的模型。 */
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
    /** 打开设置里已有的那一行（默认模型 / 循环模型），而不是在这里重做编辑器。 */
    onOpenSetting: (String) -> Unit = {},
    /** 打开凭证表单，带上要编辑的厂商 id（null = 新增）。 */
    onOpenCredentials: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val layout = remember(context) {
        AgentLayout(
            context = context.applicationContext,
            hostWorkspace = PtyLauncher.workspaceHost(context),
        )
    }
    val service = remember(layout) { PiCredentialService(layout, layout.hostWorkspace) }
    val scope = rememberCoroutineScope()

    // 直读的原文 → 组装。整段都在 IO 线程上（pi 的模型目录可能有几百 KB，解析不能放在主
    // 线程），所以这里存的是组装结果本身；旧值一直留到新值算好，避免闪一下空白。
    var inventory by remember { mutableStateOf<PiModelInventory.Inventory?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    // 引擎的答案是异步过来的（`onLoadAvailableModels` 走 RPC），所以把它的快照也作为 key。
    val engineModels = remember(availableModels) {
        availableModels.map {
            PiModelInventory.EngineModel(
                providerId = it.provider.orEmpty(),
                id = it.id,
                name = it.name,
            )
        }
    }

    LaunchedEffect(reloadTick, engineModels) {
        // 一次写入会产生好几个事件（临时文件、改名、属性），200ms 合并成一次重读。
        if (reloadTick > 0) delay(200)
        inventory = withContext(Dispatchers.IO) { service.inventory(engineModels) }
    }
    LaunchedEffect(Unit) { onLoadAvailableModels() }

    // 外部改动：页面在组合里才装监视，`onDispose` 就停。
    PiDirectoryWatch(
        directories = remember(service) { service.watchedDirectories() },
        names = remember { PiCredentialService.MODEL_FILES },
        onChanged = { reloadTick++ },
    )

    var question by remember { mutableStateOf<String?>(null) }
    var restartNote by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateMapOf<String, Boolean>() }

    val data = inventory
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("模型") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
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
                if (restartNote != null) item { Note(restartNote.orEmpty()) }

                item { PiSectionHeader("默认与循环") }
                item {
                    LinkRow(
                        title = "默认模型",
                        value = data.selection.defaultModel ?: "未设置（启动时再选）",
                        onClick = { onOpenSetting("defaultModel") },
                    )
                }
                item {
                    LinkRow(
                        title = "循环模型（Ctrl+P）",
                        value = if (data.selection.enabledPatterns.isEmpty()) {
                            "全部模型"
                        } else {
                            data.selection.enabledPatterns.joinToString("、")
                        },
                        onClick = { onOpenSetting("enabledModels") },
                    )
                }
                item { Note("循环模型在引擎启动时确定，改完要重启引擎才会生效。") }

                item { PiSectionHeader("这台设备上配好的模型") }
                if (data.providers.isEmpty()) {
                    item { Note("还没有配置任何厂商。点下面的「导入模型」选一个厂商、粘上 Key。") }
                }

                items(data.providers.size, key = { data.providers[it].id }) { index ->
                    val provider = data.providers[index]
                    val isOpen = expanded[provider.id] ?: provider.defaultExpanded
                    ProviderCard(
                        provider = provider,
                        expanded = isOpen,
                        onToggle = { expanded[provider.id] = !isOpen },
                        onOpenCredentials = { onOpenCredentials(provider.id) },
                    )
                }

                item {
                    OutlinedButton(
                        onClick = { onOpenCredentials(null) },
                        modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = PiSpacing.unit),
                    ) { Text("导入模型") }
                }
                item { PiSectionHeader("说明") }
                item { Note("「pi 目录」是 pi 自带的模型定义；「手写申报」写在你的模型配置里；「覆盖」只改 pi 目录里的某几个字段。") }
                item { Note("一个厂商的模型一旦手写申报，就会替换这个厂商在 pi 目录里的同名条目；没动的厂商不受影响。") }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    val asked = question
    if (asked != null) {
        AlertDialog(
            onDismissRequest = { question = null },
            title = { Text("重启引擎") },
            text = { Text(asked) },
            confirmButton = {
                TextButton(
                    onClick = {
                        question = null
                        val engine = coordinator
                        if (engine == null) {
                            restartNote = "重启没有接上：配置已经保存好，重启 App 或引擎之后生效。"
                        } else {
                            scope.launch {
                                restartNote = when (val outcome = engine.confirm("用户确认重启引擎以加载新模型")) {
                                    is EngineRestartCoordinator.Outcome.Ok -> {
                                        // 引擎换了，模型列表要重新问一次；这里不是猜它已经好了。
                                        onLoadAvailableModels()
                                        "引擎已重启。新配置的模型现在应该出现在模型列表里了。"
                                    }

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
                        question = null
                        lifecycle.cancelRestart()
                    },
                ) { Text("取消") }
            },
        )
    }
}

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
        modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = PiSpacing.unit),
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.screen, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
            .padding(PiSpacing.screen),
    ) {
        Text(
            "有 ${providers.size} 个厂商已经配好了，但引擎还没有加载它们。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            providers.joinToString("、") { it.id } +
                " 下的模型要重启引擎之后才会出现在模型选择器里。已保存的配置不会丢失。",
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
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

@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PiSpacing.screen, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
) {
    val shown = if (expanded) provider.models else provider.models.filter { it.noteworthy }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.screen, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(PiSpacing.screen),
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
        Spacer(Modifier.height(4.dp))
        if (provider.models.isEmpty()) {
            Text(
                "pi 的目录里还没有这个厂商的模型；导入时会读到模型清单。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        shown.forEach { model -> ModelLine(model) }
        if (provider.models.size > shown.size) {
            Text(
                "还有 ${provider.models.size - shown.size} 个 pi 目录里的模型",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 6.dp),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (provider.models.size > COLLAPSE_ABOVE) {
            Text(
                "收起",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 6.dp),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            if (provider.hasCredential) "换 Key 或改模型" else "填写凭证并导入模型",
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCredentials)
                .padding(vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ModelLine(model: PiModelInventory.Model) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
        // 申报盖在目录上：说清是哪几种叠起来的，而不是只报一个。
        parts.size == 1 -> parts.first()
        else -> parts.joinToString(" + ")
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = 4.dp),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 超过这个数量就默认收起，只留「有事要做」的那几行。 */
private const val COLLAPSE_ABOVE = 8

/** 收起时也值得占一行：已启用、默认、等待重启、缺凭证。 */
private val PiModelInventory.Model.noteworthy: Boolean
    get() = enabled || isDefault || status != PiModelInventory.Status.READY

private val PiModelInventory.Provider.defaultExpanded: Boolean
    get() = models.size <= COLLAPSE_ABOVE
