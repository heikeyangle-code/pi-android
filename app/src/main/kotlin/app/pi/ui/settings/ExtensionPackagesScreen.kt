package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import app.pi.host.ExtensionsAccess
import app.pi.host.HostExtensions
import app.pi.ui.PiSeg
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「扩展包」页 —— 全部是宿主命令，背后是 SDK 的 `DefaultPackageManager`。
 *
 * 以前这一页自己解析 `pi list` 的文字、自己扫资源目录、自己判项目信任、装完还要整机重启引擎。
 * 现在：
 *  - 列表 = `packages.list`（`listConfiguredPackages()`）；装 / 卸 / 更新是进程内调用，进度来自
 *    `packages.progress` 事件，原样显示；
 *  - 「有新版」= `packages.updates`（官方 `checkForAvailableUpdates()`），只查不装；
 *  - 项目信任 = 资源清单带回的 `trusted`，表态走 `project.trust`（官方的信任库）；
 *  - 改完点「重新加载」= `session.reload`，扩展 / 技能 / 提示词 / 主题热重载，不重启进程。
 * 包里哪些资源开着，是「资源」页（工作区 → 资源）的事，和 `pi config` 一样按条目三态切换。
 *
 * 引擎没起来时没有宿主可问：整页只说一句话，不画一张假列表。
 */
@Composable
internal fun ExtensionPackagesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    access: () -> ExtensionsAccess?,
) {
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<HostExtensions.Pkg>?>(null) }
    var updates by remember { mutableStateOf<Map<String, HostExtensions.Update>>(emptyMap()) }
    var trusted by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var projectScope by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HostExtensions.Pkg?>(null) }
    val log = remember { mutableStateListOf<String>() }
    val host = access()

    suspend fun load(h: ExtensionsAccess) {
        try {
            packages = h.ext.packages()
            (h.resources.list() as? app.pi.packages.PiResourceCatalog.Parsed.Ok)?.let { trusted = it.value.trusted }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }

    LaunchedEffect(host != null) { host?.let { load(it) } }

    // 进度事件：只在有操作时订阅；每条原样进日志（官方的 message，不改写）。
    LaunchedEffect(host) {
        host?.events?.collect { event ->
            HostExtensions.progress(event)?.let { p ->
                val line = listOfNotNull(p.action, p.source, p.message).joinToString(" · ")
                if (line.isNotBlank()) {
                    log.add(line)
                    while (log.size > 40) log.removeAt(0)
                }
            }
        }
    }

    /** 跑一次装 / 卸 / 更新：串行（宿主一次只允许一个），成功后标记「待重新加载」。 */
    fun run(label: String, action: suspend (HostExtensions, String) -> List<HostExtensions.Pkg>) {
        val h = host ?: return
        if (busy != null) return
        busy = label
        error = null
        log.clear()
        scope.launch {
            try {
                packages = action(h.ext, "o${System.nanoTime().toString(36)}")
                dirty = true
                updates = emptyMap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "$label 失败"
            }
            busy = null
        }
    }

    fun reload() {
        val h = host ?: return
        if (busy != null) return
        busy = "重新加载"
        scope.launch {
            try {
                h.ext.reload()
                dirty = false
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "重新加载失败"
            }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "扩展包", onBack = onBack)
        if (host == null) {
            PiInfoNote("引擎还没起来。扩展包由引擎里的官方包管理器管，起来之后这里才有内容。")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            error?.let { message -> item { PiInfoNote(message, tone = MaterialTheme.colorScheme.error) } }

            if (dirty) {
                item {
                    PiSettingsCard {
                        PiSettingsEntryRow(
                            title = if (busy == "重新加载") "正在重新加载…" else "让改动生效",
                            supporting = "热重载扩展、技能、提示词和主题，不重启引擎",
                            onClick = { reload() },
                        )
                    }
                }
            }

            // 项目信任：官方的信任库说了算；这里只显示结论与表态。
            item {
                PiSettingsCard {
                    PiSettingsEntryRow(
                        title = when (trusted) {
                            true -> "本项目：受信任"
                            false -> "本项目：未信任"
                            null -> "本项目：读取中…"
                        },
                        supporting = if (trusted == false) {
                            "项目里的扩展、技能和 MCP 配置不会被加载。点这里信任它。"
                        } else {
                            "项目自带的扩展、技能和 MCP 配置会被加载。点这里取消信任。"
                        },
                        onClick = trusted?.let { now ->
                            {
                                scope.launch {
                                    when (val r = host.resources.setTrust(!now)) {
                                        is app.pi.packages.PiResourceCatalog.Parsed.Ok -> {
                                            trusted = !now
                                            dirty = true
                                        }
                                        is app.pi.packages.PiResourceCatalog.Parsed.Error -> error = r.message
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item {
                PiSettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(PiSettingsMetrics.pageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
                    ) {
                        Text("安装", style = PiType.rowTitle, color = MaterialTheme.colorScheme.onSurface)
                        PiEditorField(
                            value = source,
                            onValueChange = { source = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "npm:包名、git:仓库、或本地路径",
                        )
                        PiSeg(
                            options = listOf("全局", "本项目"),
                            selectedIndex = if (projectScope) 1 else 0,
                            onSelect = { projectScope = it == 1 },
                        )
                        Button(
                            onClick = {
                                val s = source.trim()
                                run("安装") { ext, id -> ext.install(s, projectScope, id).also { source = "" } }
                            },
                            enabled = source.isNotBlank() && busy == null,
                        ) { Text(if (busy == "安装") "正在安装…" else "安装") }
                    }
                }
            }

            item {
                PiSettingsCard {
                    PiSettingsEntryRow(
                        title = if (busy == "检查更新") "正在检查更新…" else "检查更新",
                        supporting = if (updates.isEmpty()) "只查不装，要联网" else "${updates.size} 个有新版",
                        onClick = {
                            if (busy == null) {
                                busy = "检查更新"
                                scope.launch {
                                    try {
                                        updates = host.ext.updates().associateBy { it.source }
                                        error = null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.message ?: "检查失败"
                                    }
                                    busy = null
                                }
                            }
                        },
                    )
                    if (updates.isNotEmpty()) {
                        PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = "全部更新",
                            supporting = updates.values.joinToString("、") { it.name },
                            onClick = { run("更新") { ext, id -> ext.update(null, id) } },
                        )
                    }
                }
            }

            val list = packages
            item {
                if (list == null) {
                    PiInfoNote("读取中…")
                } else if (list.isEmpty()) {
                    PiInfoNote("还没有安装任何扩展包。")
                }
            }
            if (list != null && list.isNotEmpty()) {
                item {
                    PiSettingsCard {
                        list.forEachIndexed { index, pkg ->
                            if (index > 0) PiSettingsHairline()
                            ExtPackageRow(
                                pkg = pkg,
                                update = updates[pkg.source],
                                enabled = busy == null,
                                onUpdate = { run("更新") { ext, id -> ext.update(pkg.source, id) } },
                                onRemove = { removing = pkg },
                            )
                        }
                    }
                }
            }

            if (log.isNotEmpty()) {
                items(log.toList()) { line ->
                    Text(
                        line,
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        style = PiTheme.text.mono,
                        color = PiTheme.palette.muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item {
                PiInfoNote("包里哪些扩展、技能、提示词、主题开着，在「工作区 → 资源」里按条目切换。")
            }
        }
    }

    removing?.let { pkg ->
        PiSettingsDialog(
            onDismissRequest = { removing = null },
            title = "移除这个扩展包？",
            body = "${pkg.source}（${if (pkg.isProject) "本项目" else "全局"}）会从设置里删掉。",
            confirmationLabel = "移除",
            onConfirm = {
                removing = null
                run("移除") { ext, id -> ext.remove(pkg.source, pkg.isProject, id) }
            },
            dismissalLabel = "取消",
            onDismissButton = { removing = null },
        )
    }
}

@Composable
private fun ExtPackageRow(
    pkg: HostExtensions.Pkg,
    update: HostExtensions.Update?,
    enabled: Boolean,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal, vertical = PiSettingsMetrics.rowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(pkg.source, style = PiTheme.text.mono, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val tags = buildList {
                add(if (pkg.isProject) "本项目" else "全局")
                if (pkg.filtered) add("已筛选")
                if (pkg.installedPath == null) add("未安装")
                if (update != null) add("有新版")
            }
            Text(
                tags.joinToString(" · "),
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = if (update != null) PiTheme.palette.accent else PiTheme.palette.muted,
            )
        }
        if (update != null) TextButton(onClick = onUpdate, enabled = enabled) { Text("更新") }
        TextButton(onClick = onRemove, enabled = enabled) { Text("移除", color = MaterialTheme.colorScheme.error) }
    }
}
