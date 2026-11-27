package app.pi.ui.resources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
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
import app.pi.i18n.tr
import app.pi.ui.PiSeg
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsSectionLabel
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
    fun run(opKey: String, op: suspend (opId: String) -> Unit) {
        if (busy != null) return
        val id = "pkg-" + System.nanoTime()
        currentOp = id
        log.clear()
        busy = opKey
        failure = null
        result = null
        scope.launch {
            val opName = actionLabel(opKey) ?: opKey
            try {
                op(id)
                val reloaded = runCatching { access.ext.reload() }
                result = if (reloaded.isSuccess) {
                    tr("$opName 完成，已重新加载。", "$opName complete and reloaded.")
                } else {
                    tr(
                        "$opName 完成，但重新加载失败：${reloaded.exceptionOrNull()?.message}。到枢纽页点「重新加载」重试。",
                        "$opName complete, but reload failed: ${reloaded.exceptionOrNull()?.message}. Tap \"Reload\" on the hub to retry.",
                    )
                }
                updates = null
                reloadList()
                model.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                failure = e.message ?: e.code
            } catch (e: Exception) {
                failure = e.message ?: tr("$opName 失败", "$opName failed")
            }
            busy = null
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    Column(Modifier.fillMaxSize()) {
        PiTopBar(title = tr("扩展包", "Packages"), onBack = onBack, scrolled = scrolled)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.inline + PiSpacing.small),
        ) {
            item {
                PiInfoNote(
                    tr(
                        "pi 包把扩展、技能、提示模板、主题打成一个来源，从 npm 或 git 装。装进来的资源在「扩展 / 技能 / 提示模板 / 主题」里按包分组显示。",
                        "Pi packages bundle extensions, skills, prompt templates, and themes into one source installed from npm or git. Installed resources appear grouped by package under Extensions, Skills, Prompt Templates, and Themes.",
                    ),
                )
            }

            // ---- 安装 ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(tr("安装", "Install"))
                    ModelsCard {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                            PiEditorField(
                                value = source,
                                onValueChange = { source = it },
                                placeholder = tr("npm:@scope/name   git:github.com/user/repo   或本地路径", "npm:@scope/name   git:github.com/user/repo   or local path"),
                                enabled = busy == null,
                            )
                            val v = inspected?.let { sourceView(source, it.kind, it.name, it.version, it.pinned) }
                            if (v != null) {
                                Text(v.headline, style = PiTheme.text.prose, color = PiTheme.palette.text)
                                if (v.tags.isNotEmpty()) TagRow(v.tags)
                                v.warning?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                            }
                            PiSeg(
                                options = listOf(tr("全局", "Global"), tr("本项目", "Project")),
                                selectedIndex = if (useProject) 1 else 0,
                                disabledIndices = if (projectUsable) emptySet() else setOf(1),
                                onSelect = { idx ->
                                    if (idx == 0 || projectUsable) project = idx == 1
                                },
                                fillWidth = true,
                            )
                            Text(
                                when {
                                    !projectUsable -> tr("项目还没被信任，pi 不允许往项目里装包。先到枢纽页的「项目信任」表态。", "Project is not trusted yet; pi does not allow installing project packages. Review Project Trust on the hub first.")
                                    useProject -> tr("写进本项目的 .pi/settings.json，换项目就没有了。", "Saved to this project's .pi/settings.json and applies only to this project.")
                                    else -> tr("写进全局设置，所有项目都能用。", "Saved to global settings and available in all projects.")
                                },
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                            PiPrimaryButton(
                                label = if (busy == "install") tr("安装中…", "Installing…") else tr("安装", "Install"),
                                enabled = busy == null && source.isNotBlank(),
                                compact = true,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val s = source.trim()
                                    run("install") { id -> access.ext.install(s, useProject, id); source = "" }
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
                            busy?.let {
                                val opName = actionLabel(it) ?: it
                                Text(tr("$opName 进行中…（同一时刻只能做一件）", "$opName in progress… (one operation at a time)"), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                            }
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
                    ModelsSectionLabel(if (list == null) tr("读取中…", "Loading…") else tr("已配置 · ${list.size}", "Configured · ${list.size}"))
                    Row(
                        Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                    ) {
                        PiSettingsChip(
                            text = if (checking) tr("检查中…", "Checking…") else tr("检查更新", "Check Updates"),
                            enabled = !checking && busy == null && !list.isNullOrEmpty(),
                            onClick = {
                                checking = true
                                scope.launch {
                                    updates = runCatching { access.ext.updates() }.getOrElse { failure = it.message ?: tr("检查更新失败（要联网）", "Failed to check updates (network required)"); null }
                                    checking = false
                                }
                            },
                        )
                        if (!updates.isNullOrEmpty()) {
                            PiSettingsChip(text = tr("全部更新（${updates!!.size}）", "Update All (${updates!!.size})"), enabled = busy == null, onClick = { run("update") { id -> access.ext.update(null, id) } })
                        }
                    }
                    updates?.let {
                        Text(
                            if (it.isEmpty()) tr("都是最新的（固定 npm 版本不参与更新检查）。", "Everything is up to date (pinned npm versions are skipped by update checks).")
                            else tr("有 ${it.size} 个包有可用更新。", "${it.size} package(s) have available updates."),
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.muted,
                            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        )
                    }
                }
            }
            val failures = model.snapshot?.failures.orEmpty()
            if (failures.isNotEmpty()) {
                item(key = "pkg-failures") {
                    PiInfoNote(
                        tr("以下扩展包未能安装：", "Failed to install packages:") + "\n" + failures.joinToString("\n"),
                        tone = PiTheme.palette.error,
                    )
                }
            }
            val list = packages.orEmpty()
            if (packages != null && list.isEmpty()) {
                item { PiInfoNote(tr("还没装任何包。在上面填一个来源装一个。", "No packages installed yet. Enter a source above to install one.")) }
            }
            val snapshotGroups = model.snapshot?.let { it.global + it.project }.orEmpty()
            items(list, key = { it.scope + ":" + it.source }) { p ->
                val info = p.info
                val view = if (info != null) sourceView(p.source, info.kind, info.name, info.version, info.pinned) else sourceView(p.source, "unknown", null, null, false)
                val update = updates?.firstOrNull { it.source == p.source && it.scope == p.scope }
                val provided = remember(snapshotGroups, p.source, p.isProject) {
                    val matched = snapshotGroups.filter { it.source == p.source && it.projectScoped == p.isProject }
                    val ext = matched.sumOf { it.items(app.pi.packages.PiResourceCatalog.Type.Extensions).size }
                    val sk = matched.sumOf { it.items(app.pi.packages.PiResourceCatalog.Type.Skills).size }
                    val pr = matched.sumOf { it.items(app.pi.packages.PiResourceCatalog.Type.Prompts).size }
                    val th = matched.sumOf { it.items(app.pi.packages.PiResourceCatalog.Type.Themes).size }
                    buildList {
                        if (ext > 0) add(tr("$ext 个扩展", "$ext ext"))
                        if (sk > 0) add(tr("$sk 个技能", "$sk skills"))
                        if (pr > 0) add(tr("$pr 个模板", "$pr prompts"))
                        if (th > 0) add(tr("$th 个主题", "$th themes"))
                    }.joinToString(" · ")
                }
                ModelsCard {
                    Column(Modifier.fillMaxWidth().padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(
                                        PiSpacing.hairlineThin,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                        RoundedCornerShape(10.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Inventory2,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
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
                        TagRow(packageTags(p.scope, p.filtered, p.installedPath != null, view) + listOfNotNull(if (update != null) tr("有更新", "Update available") else null))
                        if (provided.isNotEmpty()) {
                            Text(
                                tr("提供：$provided", "Provides: $provided"),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                        }
                        p.installedPath?.let { Text(shortPath(it), style = PiTheme.text.monoSmall, color = PiTheme.palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        view.warning?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                        Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                            if (p.installedPath == null) {
                                PiSettingsChip(text = tr("重新安装", "Reinstall"), enabled = busy == null && (p.scope != "project" || projectUsable), onClick = { run("install") { id -> access.ext.install(p.source, p.isProject, id) } })
                            } else {
                                PiSettingsChip(
                                    text = tr("更新", "Update"),
                                    active = update != null,
                                    enabled = busy == null && (p.scope != "project" || projectUsable),
                                    onClick = { run("update") { id -> access.ext.update(p.source, id) } },
                                )
                            }
                            PiSettingsChip(text = tr("移除", "Remove"), enabled = busy == null && (p.scope != "project" || projectUsable), onClick = { confirmRemove = p })
                        }
                        if (p.scope == "project" && !projectUsable) {
                            Text(tr("项目没被信任，不能改项目里的包。", "Project is not trusted; project packages cannot be modified."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { p ->
        PiSettingsDialog(
            onDismissRequest = { confirmRemove = null },
            title = tr("移除这个包？", "Remove this package?"),
            sub = p.source,
            body = if (p.isProject) {
                tr("会从本项目设置里去掉它，并删掉装下来的文件。它带来的扩展、技能、提示模板、主题随即消失。", "This removes it from this project's settings and deletes its installed files. All extensions, skills, prompt templates, and themes it provides will be removed.")
            } else {
                tr("会从全局设置里去掉它，并删掉装下来的文件。它带来的扩展、技能、提示模板、主题随即消失。", "This removes it from global settings and deletes its installed files. All extensions, skills, prompt templates, and themes it provides will be removed.")
            },
            confirmationLabel = tr("移除", "Remove"),
            onConfirm = { confirmRemove = null; run("remove") { id -> access.ext.remove(p.source, p.isProject, id) } },
            confirmationTone = PiTheme.palette.error,
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRemove = null },
        )
    }
}

private fun actionLabel(action: String): String? = when (action) {
    "install" -> tr("安装", "Install")
    "remove" -> tr("移除", "Remove")
    "update" -> tr("更新", "Update")
    "clone" -> tr("克隆", "Clone")
    "pull" -> tr("拉取", "Pull")
    else -> action.ifEmpty { null }
}
