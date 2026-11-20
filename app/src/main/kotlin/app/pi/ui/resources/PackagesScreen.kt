package app.pi.ui.resources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.ExtensionsAccess
import app.pi.host.HostException
import app.pi.host.HostExtensions
import app.pi.ui.PiTopBar
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.PrimaryAction
import app.pi.ui.models.TagRow
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsDialog
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 扩展包（pi package）：装、查更新、更新、移除。官方 `DefaultPackageManager` 在宿主里，这里不碰 npm / git。
 *
 * 对照 SDK 做的几件事：
 *  - **装之前先问 pi 这串来源是什么**（`packages.inspect` = `parseSource`）：没写 `npm:` 前缀的名字 pi 当**本地路径**，
 *    这是最常见的写错，所以边输边说清楚，而不是装失败了才告诉你；
 *  - **固定版本**（npm 精确版本 / git 带 ref）`update` 会跳过，标在行上，「全部更新」也不会动它们；
 *  - **项目层的包**要项目被信任才能装 / 卸 / 更新（SDK `assertProjectTrustedForScope`），没信任就不给选，并说原因；
 *  - 装 / 卸 / 更新是同一时刻一个（宿主串行），进度原样显示 pi 的 `ProgressEvent`；
 *  - 做完自动 `session.reload`（热重载）——新装的扩展、技能当场就进当前会话，不用重启。
 */
@Composable
internal fun PackagesScreen(
    access: ExtensionsAccess,
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<HostExtensions.Pkg>?>(null) }
    var updates by remember { mutableStateOf<List<HostExtensions.Update>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var project by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<HostExtensions.SourceInfo?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<HostExtensions.Pkg?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var currentOp by remember { mutableStateOf("") }

    val projectUsable = model.projectUsable
    val useProject = project && projectUsable

    suspend fun reloadList() {
        packages = runCatching { access.ext.packages() }.getOrElse { failure = it.message; packages }
    }
    LaunchedEffect(Unit) { reloadList() }

    // 进度：只收自己这次操作（opId）的事件。
    LaunchedEffect(Unit) {
        access.events.collect { event ->
            val p = HostExtensions.progress(event) ?: return@collect
            if (p.opId != currentOp) return@collect
            if (p.type == "complete" && p.message.isNullOrBlank()) return@collect
            val line = listOfNotNull(
                actionLabel(p.action),
                p.source.takeIf { it.isNotEmpty() },
                p.message?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (line.isNotEmpty() && log.lastOrNull() != line) log.add(line)
        }
    }

    // 边输边问 pi：这串来源会被当成什么。停手 300ms 才问，别每个字符都打一遍宿主。
    LaunchedEffect(source) {
        inspected = null
        val s = source.trim()
        if (s.isEmpty()) return@LaunchedEffect
        delay(300)
        inspected = runCatching { access.ext.inspect(s) }.getOrNull()
    }

    /** 跑一次装 / 卸 / 更新：串行、带进度、做完热重载。 */
    fun run(label: String, op: suspend (opId: String) -> Unit) {
        if (busy != null) return
        val id = "pkg-" + System.nanoTime()
        currentOp = id
        log.clear()
        busy = label
        failure = null
        result = null
        scope.launch {
            try {
                op(id)
                val reloaded = runCatching { access.ext.reload() }
                result = if (reloaded.isSuccess) "$label 完成，已重新加载。" else "$label 完成，但重新加载失败：${reloaded.exceptionOrNull()?.message}。到枢纽页点「重新加载」重试。"
                updates = null
                reloadList()
                model.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                failure = e.message ?: e.code
            } catch (e: Exception) {
                failure = e.message ?: "$label 失败"
            }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        PiTopBar(title = "扩展包", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.inline + PiSpacing.small),
        ) {
            item {
                PiInfoNote("pi 包把扩展、技能、提示模板、主题打成一个来源，从 npm 或 git 装。装进来的资源在「扩展 / 技能 / 提示模板 / 主题」里按包分组显示。")
            }

            // ---- 安装 ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel("安装")
                    ModelsCard {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                            PiEditorField(
                                value = source,
                                onValueChange = { source = it },
                                placeholder = "npm:@scope/name   git:github.com/user/repo   或本地路径",
                                enabled = busy == null,
                            )
                            val v = inspected?.let { sourceView(source, it.kind, it.name, it.version, it.pinned) }
                            if (v != null) {
                                Text(v.headline, style = PiTheme.text.prose, color = PiTheme.palette.text)
                                if (v.tags.isNotEmpty()) TagRow(v.tags)
                                v.warning?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                                PiSettingsChip(text = "全局", active = !useProject, onClick = { project = false })
                                PiSettingsChip(text = "本项目", active = useProject, enabled = projectUsable, onClick = { project = true })
                            }
                            Text(
                                when {
                                    !projectUsable -> "项目还没被信任，pi 不允许往项目里装包。先到枢纽页的「项目信任」表态。"
                                    useProject -> "写进本项目的 .pi/settings.json，换项目就没有了。"
                                    else -> "写进全局设置，所有项目都能用。"
                                },
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                            PrimaryAction(
                                if (busy == "安装") "安装中…" else "安装",
                                enabled = busy == null && source.isNotBlank(),
                                onClick = {
                                    val s = source.trim()
                                    run("安装") { id -> access.ext.install(s, useProject, id); source = "" }
                                },
                            )
                        }
                    }
                }
            }

            if (busy != null || log.isNotEmpty() || result != null || failure != null) {
                item {
                    ModelsCard(accent = failure != null) {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                            busy?.let { Text("$it 进行中…（同一时刻只能做一件）", style = PiTheme.text.meta, color = PiTheme.palette.muted) }
                            log.takeLast(8).forEach { Text(it, style = PiTheme.text.monoSmall, color = PiTheme.palette.dim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            result?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.success) }
                            failure?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
                        }
                    }
                }
            }

            // ---- 已配置的包 ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    val list = packages
                    ModelsSectionLabel(if (list == null) "读取中…" else "已配置 · ${list.size}")
                    Row(
                        Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                    ) {
                        PiSettingsChip(
                            text = if (checking) "检查中…" else "检查更新",
                            enabled = !checking && busy == null && !list.isNullOrEmpty(),
                            onClick = {
                                checking = true
                                scope.launch {
                                    updates = runCatching { access.ext.updates() }.getOrElse { failure = it.message ?: "检查更新失败（要联网）"; null }
                                    checking = false
                                }
                            },
                        )
                        if (!updates.isNullOrEmpty()) {
                            PiSettingsChip(text = "全部更新（${updates!!.size}）", enabled = busy == null, onClick = { run("更新") { id -> access.ext.update(null, id) } })
                        }
                    }
                    updates?.let {
                        Text(
                            if (it.isEmpty()) "都是最新的。固定了版本的包不参与检查。" else "有 ${it.size} 个包可以更新。固定了版本的包不会动。",
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.muted,
                            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        )
                    }
                }
            }
            val list = packages.orEmpty()
            if (packages != null && list.isEmpty()) {
                item { PiInfoNote("还没装任何包。在上面填一个来源装一个。") }
            }
            items(list, key = { it.scope + ":" + it.source }) { p ->
                val info = p.info
                val view = if (info != null) sourceView(p.source, info.kind, info.name, info.version, info.pinned) else sourceView(p.source, "unknown", null, null, false)
                val update = updates?.firstOrNull { it.source == p.source && it.scope == p.scope }
                ModelsCard {
                    Column(Modifier.fillMaxWidth().padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(PiTheme.palette.accent.copy(alpha = 0.13f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Inventory2,
                                    contentDescription = null,
                                    tint = PiTheme.palette.accent,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Text(
                                p.source,
                                style = PiTheme.text.mono.copy(fontWeight = FontWeight.Medium),
                                color = PiTheme.palette.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TagRow(packageTags(p.scope, p.filtered, p.installedPath != null, view) + listOfNotNull(if (update != null) "有更新" else null))
                        p.installedPath?.let { Text(shortPath(it), style = PiTheme.text.monoSmall, color = PiTheme.palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        view.warning?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                        Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                            if (p.installedPath == null) {
                                PiSettingsChip(text = "重新安装", enabled = busy == null && (p.scope != "project" || projectUsable), onClick = { run("安装") { id -> access.ext.install(p.source, p.isProject, id) } })
                            }
                            if (update != null) {
                                PiSettingsChip(text = "更新", enabled = busy == null && (p.scope != "project" || projectUsable), onClick = { run("更新") { id -> access.ext.update(p.source, id) } })
                            }
                            PiSettingsChip(text = "移除", enabled = busy == null && (p.scope != "project" || projectUsable), onClick = { confirmRemove = p })
                        }
                        if (p.scope == "project" && !projectUsable) {
                            Text("项目没被信任，不能改项目里的包。", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { p ->
        PiSettingsDialog(
            onDismissRequest = { confirmRemove = null },
            title = "移除这个包？",
            sub = p.source,
            body = "会从${if (p.isProject) "本项目" else "全局"}设置里去掉它，并删掉装下来的文件。它带来的扩展、技能、提示模板、主题随即消失。",
            confirmationLabel = "移除",
            onConfirm = { confirmRemove = null; run("移除") { id -> access.ext.remove(p.source, p.isProject, id) } },
            confirmationTone = PiTheme.palette.error,
            dismissalLabel = "取消",
            onDismissButton = { confirmRemove = null },
        )
    }
}

private fun actionLabel(action: String): String? = when (action) {
    "install" -> "安装"
    "remove" -> "移除"
    "update" -> "更新"
    "clone" -> "克隆"
    "pull" -> "拉取"
    else -> action.ifEmpty { null }
}
