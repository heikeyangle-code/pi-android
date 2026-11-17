package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.pi.host.HostAdvanced
import app.pi.rpc.PiResponses
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiSheet
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「引擎」页：SDK 里有、之前没有界面的那一批——作用域模型、缓存预热、中止压缩/分支摘要、
 * 系统提示词、工具详情、扩展 flag、待发队列、JSONL 导出、问题报告。
 *
 * 全部走 [HostAdvanced]（宿主命令，SDK 的直接方法）；这里没有任何 pi 逻辑，只有读、写与展示。
 * 引擎没跑（[advanced] 为 null）时整页只显示一句说明——不拿旧值冒充。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EngineScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    advanced: () -> HostAdvanced?,
    availableModels: List<PiResponses.ModelInfo>,
    onLoadAvailableModels: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<Loaded?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(reload) {
        val api = advanced()
        data = if (api == null) null else Loaded(
            scoped = runCatching { api.scopedModels() }.getOrDefault(emptyList()),
            cache = runCatching { api.cacheStatus() }.getOrNull(),
            tools = runCatching { api.tools() }.getOrDefault(emptyList()),
            flags = runCatching { api.flags() }.getOrDefault(emptyList()),
            queue = runCatching { api.queueMessages() }.getOrNull(),
        )
    }

    /** 跑一个宿主调用：失败原话进 [notice]，成功后刷新。 */
    fun act(label: String, refresh: Boolean = true, block: suspend (HostAdvanced) -> Unit) {
        val api = advanced()
        if (api == null) { notice = "引擎没在运行：$label 没有执行。"; return }
        scope.launch {
            busy = label
            runCatching { block(api) }.onFailure { notice = "$label 失败：${it.message}" }
            busy = null
            if (refresh) reload++
        }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "引擎", onBack = onBack)
        val d = data
        if (d == null) {
            PiInfoNote("引擎没有在运行，这一页读不到 SDK 的状态。启动后再来。")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = PiSpacing.small * 2, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            notice?.let { n ->
                item(key = "notice") {
                    PiSettingsCard { PiSettingsEntryRow(title = n, supporting = "点一下关掉", onClick = { notice = null }) }
                }
            }
            busy?.let { b -> item(key = "busy") { Label("$b…") } }

            // ---- 作用域模型 ----
            item(key = "l:scoped") { Label("作用域模型（循环切换只在这几个里转）") }
            item(key = "c:scoped") {
                PiSettingsCard {
                    if (d.scoped.isEmpty()) {
                        PiSettingsEntryRow(title = "没有设置", supporting = "切换模型时在全部可用模型里转。")
                    }
                    d.scoped.forEachIndexed { i, m ->
                        if (i > 0) PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = m.name ?: m.id,
                            supporting = m.provider + (m.thinkingLevel?.let { " · 思考 $it" } ?: "") + " · 点一下移除",
                            onClick = { act("更新作用域模型") { it.setScopedModels(d.scoped.filterIndexed { j, _ -> j != i }) } },
                        )
                    }
                    PiSettingsHairline()
                    PiSettingsEntryRow(title = "添加模型…", supporting = null, onClick = { onLoadAvailableModels(); picking = true })
                    if (d.scoped.isNotEmpty()) {
                        PiSettingsHairline()
                        PiSettingsEntryRow(title = "清空", supporting = "回到「全部可用模型」", onClick = { act("清空作用域模型") { it.setScopedModels(emptyList()) } })
                    }
                }
            }

            // ---- 缓存预热 ----
            item(key = "l:cache") { Label("缓存预热") }
            item(key = "c:cache") {
                PiSettingsCard {
                    val cache = d.cache
                    Row(
                        Modifier.fillMaxWidth().padding(PiSettingsMetrics.pageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                    ) {
                        for ((mode, label) in listOf("off" to "关", "streaming" to "流式时", "idle" to "空闲时")) {
                            PiSettingsChip(text = label, active = cache?.mode == mode, onClick = { act("设置缓存预热") { it.setCacheMode(mode) } })
                        }
                    }
                    cache?.let {
                        PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = "状态：${it.state ?: "—"}",
                            supporting = listOfNotNull(it.reason, it.nextWarmAt?.let { t -> "下次预热 $t" }).joinToString(" · ").ifEmpty { null },
                        )
                    }
                }
            }

            // ---- 会话里的操作 ----
            item(key = "l:ops") { Label("会话") }
            item(key = "c:ops") {
                PiSettingsCard {
                    PiSettingsEntryRow(title = "中止压缩", supporting = "正在压缩上下文时才有效", onClick = { act("中止压缩") { api -> if (!api.abortCompaction()) notice = "现在没有在压缩。" } })
                    PiSettingsHairline()
                    PiSettingsEntryRow(title = "中止分支摘要", supporting = "跳转时的摘要在跑才有效", onClick = { act("中止分支摘要") { it.abortBranchSummary() } })
                    PiSettingsHairline()
                    PiSettingsEntryRow(title = "查看系统提示词", supporting = "此刻发给模型的完整系统提示", onClick = {
                        act("读取系统提示词", refresh = false) { sheet = "系统提示词" to it.systemPrompt().ifBlank { "（空）" } }
                    })
                    PiSettingsHairline()
                    PiSettingsEntryRow(title = "导出 JSONL", supporting = "把当前会话分支原样导出", onClick = {
                        act("导出 JSONL", refresh = false) { notice = "已导出到：" + it.exportJsonl() }
                    })
                    PiSettingsHairline()
                    PiSettingsEntryRow(title = "生成问题报告", supporting = "只在本地生成文本，由你决定发给谁；会调用一次模型", onClick = {
                        act("生成问题报告", refresh = false) { sheet = "问题报告" to it.bugReport().ifBlank { "（空）" } }
                    })
                    PiSettingsHairline()
                    PiSettingsEntryRow(title = "取消问题报告", supporting = null, onClick = { act("取消问题报告", refresh = false) { it.cancelBugReport() } })
                }
            }

            // ---- 待发队列 ----
            val q = d.queue
            if (q != null && (q.steering.isNotEmpty() || q.followUp.isNotEmpty())) {
                item(key = "l:queue") { Label("待发队列") }
                item(key = "c:queue") {
                    PiSettingsCard {
                        var first = true
                        for (s in q.steering) { if (!first) PiSettingsHairline(); first = false; PiSettingsEntryRow(title = s.take(120), supporting = "插话（当前回合里送达）") }
                        for (s in q.followUp) { if (!first) PiSettingsHairline(); first = false; PiSettingsEntryRow(title = s.take(120), supporting = "跟进（回合结束后送达）") }
                    }
                }
            }

            // ---- 工具 ----
            item(key = "l:tools") { Label("工具（${d.tools.count { it.active }} / ${d.tools.size} 启用）") }
            item(key = "c:tools") {
                PiSettingsCard {
                    d.tools.forEachIndexed { i, t ->
                        if (i > 0) PiSettingsHairline()
                        PiSettingsToggleRow(
                            title = t.name,
                            supporting = listOfNotNull(
                                t.description.lineSequence().firstOrNull()?.take(100),
                                t.source?.let { "来自 $it" },
                                if (t.active && !t.callable) "已启用但当前不可调用" else null,
                            ).joinToString(" · ").ifEmpty { null },
                            checked = t.active,
                            onCheckedChange = { on ->
                                val names = d.tools.filter { it.active && it.name != t.name }.map { it.name } + if (on) listOf(t.name) else emptyList()
                                act("更新工具") { it.setActiveTools(names) }
                            },
                        )
                    }
                }
            }

            // ---- 扩展 flag ----
            if (d.flags.isNotEmpty()) {
                item(key = "l:flags") { Label("扩展 flag") }
                item(key = "c:flags") {
                    PiSettingsCard {
                        d.flags.forEachIndexed { i, f ->
                            if (i > 0) PiSettingsHairline()
                            if (f.isBoolean) {
                                PiSettingsToggleRow(
                                    title = "--" + f.name,
                                    supporting = f.description,
                                    checked = (f.value as? JsonPrimitive)?.content == "true",
                                    onCheckedChange = { on -> act("设置 flag") { it.setFlag(f.name, on) } },
                                )
                            } else {
                                var text by remember(f.name, f.value) { mutableStateOf((f.value as? JsonPrimitive)?.content.orEmpty()) }
                                Column(Modifier.padding(PiSettingsMetrics.pageHorizontal)) {
                                    Text("--" + f.name, style = PiTheme.text.prose, color = PiTheme.palette.text)
                                    f.description?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.muted) }
                                    PiEditorField(value = text, onValueChange = { text = it }, placeholder = "值")
                                    PiSettingsChip(text = "保存", onClick = { act("设置 flag") { it.setFlag(f.name, text) } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sheet?.let { (title, body) ->
        PiSheet(onDismiss = { sheet = null }, fullHeight = true) {
            Column(Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = PiTheme.text.prose, color = PiTheme.palette.text)
                    PiSettingsChip(text = "复制", onClick = { clipboard.setText(AnnotatedString(body)) })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = PiSpacing.small)) {
                    Text(body, style = PiTheme.text.monoSmall, color = PiTheme.palette.text)
                }
            }
        }
    }

    if (picking) {
        val have = data?.scoped.orEmpty().map { it.provider to it.id }.toSet()
        val options = availableModels.filter { (it.provider.orEmpty() to it.id) !in have }
        PiSheet(onDismiss = { picking = false }, fullHeight = true) {
            LazyColumn(Modifier.weight(1f)) {
                if (options.isEmpty()) {
                    item { PiInfoNote("没有可添加的模型（先在「模型与供应商」里配置，或稍等目录加载）。") }
                }
                items(options.size) { i ->
                    val m = options[i]
                    PiSettingsEntryRow(
                        title = m.name.ifBlank { m.id },
                        supporting = m.provider,
                        onClick = {
                            picking = false
                            val next = data?.scoped.orEmpty() + HostAdvanced.ModelRef(m.provider.orEmpty(), m.id, m.name)
                            act("添加作用域模型") { it.setScopedModels(next) }
                        },
                    )
                }
            }
        }
    }
}

private class Loaded(
    val scoped: List<HostAdvanced.ModelRef>,
    val cache: HostAdvanced.CacheStatus?,
    val tools: List<HostAdvanced.ToolDetail>,
    val flags: List<HostAdvanced.ExtensionFlag>,
    val queue: HostAdvanced.QueueMessages?,
)

@Composable
private fun Label(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = PiSettingsMetrics.pageHorizontal + PiSpacing.small, end = PiSettingsMetrics.pageHorizontal),
        style = PiTheme.text.meta,
        color = PiTheme.palette.muted,
    )
}
