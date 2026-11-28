package app.pi.ui.resources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import app.pi.packages.PiResourceCatalog
import app.pi.ui.PiSeg
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiIconSquareButton
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.TagPill
import app.pi.ui.settings.PiEditorField
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
 * 对照 Pi 1.7.0 SDK 做的几件事：
 *  - **装之前先问 pi 这串来源是什么**（`packages.inspect` = `parseSource`）：没写 `npm:` 前缀的名字 pi 当**本地路径**；
 *  - **固定版本与本地路径**（npm 精确版本 / git 带 ref / local）`checkForAvailableUpdates` 与 `update` 都会跳过，
 *    卡片上直接标明并隐藏无意义的「更新」按钮；
 *  - **项目层的包**需要项目被信任才能装 / 卸 / 更新（SDK `assertProjectTrustedForScope`）；
 *  - 装 / 卸 / 更新串行执行，实时流式显示 pi 的 `ProgressEvent`，完成后自动热重载（`session.reload`）。
 */
@Composable
internal fun PackagesScreen(
    access: ExtensionsAccess,
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette
    var packages by remember { mutableStateOf<List<HostExtensions.Pkg>?>(null) }
    var updates by remember { mutableStateOf<List<HostExtensions.Update>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var installSheetOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<HostExtensions.Pkg?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var currentOp by remember { mutableStateOf("") }

    val projectUsable = model.projectUsable

    suspend fun reloadList() {
        packages = runCatching { access.ext.packages() }.getOrElse { failure = it.message; packages }
    }

    LaunchedEffect(access) { reloadList() }
    LaunchedEffect(access) {
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

    val list = packages.orEmpty()
    val updatableCount = remember(list) {
        list.count { pkg ->
            val info = pkg.info
            pkg.installedPath != null && info?.pinned != true && info?.kind != "local"
        }
    }
    val listState = rememberLazyListState()
    val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }

    Column(Modifier.fillMaxSize()) {
        PiTopBar(
            title = tr("扩展包", "Packages"),
            onBack = onBack,
            scrolled = scrolled,
            actions = {
                PiIconSquareButton(
                    icon = Icons.Filled.Add,
                    contentDescription = tr("安装扩展包", "Install Package"),
                    enabled = busy == null,
                    tint = palette.accent,
                    containerColor = palette.accent.copy(alpha = 0.12f),
                    borderColor = palette.accent.copy(alpha = 0.28f),
                    onClick = { installSheetOpen = true },
                )
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 6.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 1. 顶部概览与更新控制卡（高频操作置顶） ----
            item(key = "overview") {
                val updateCount = updates?.size ?: 0
                ModelsCard(accent = updateCount > 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(ModelsCardPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val iconTone = if (updateCount > 0) palette.accent else MaterialTheme.colorScheme.onSurface
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (updateCount > 0) palette.accent.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    )
                                    .border(
                                        PiSpacing.hairlineThin,
                                        if (updateCount > 0) palette.accent.copy(alpha = 0.32f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                        RoundedCornerShape(10.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Inventory2,
                                    contentDescription = null,
                                    tint = iconTone,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    when {
                                        packages == null -> tr("正在读取扩展包…", "Loading packages…")
                                        list.isEmpty() -> tr("尚未安装扩展包", "No packages installed")
                                        else -> tr("已配置 ${list.size} 个扩展包", "${list.size} package(s) configured")
                                    },
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    when {
                                        checking -> tr("正在向 npm / git 查询最新版本…", "Checking npm / git for updates…")
                                        updates != null && updates!!.isNotEmpty() -> tr(
                                            "发现 ${updates!!.size} 个包有新版本，更新后自动热重载。",
                                            "${updates!!.size} update(s) available; reloads automatically after update.",
                                        )
                                        updates != null -> tr(
                                            "已是最新（固定版本与本地路径不参与更新检查）。",
                                            "Up to date (pinned npm/git versions and local paths are skipped).",
                                        )
                                        else -> tr(
                                            "管理 npm、git 与本地扩展包，安装或更新后立即生效。",
                                            "Manage npm, git, and local pi packages with automatic reload.",
                                        )
                                    },
                                    style = PiTheme.text.meta,
                                    color = if (updateCount > 0) palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PiSecondaryButton(
                                label = if (checking) tr("检查中…", "Checking…") else tr("检查更新", "Check Updates"),
                                leadingIcon = Icons.Filled.Refresh,
                                compact = true,
                                enabled = !checking && busy == null && updatableCount > 0,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    checking = true
                                    failure = null
                                    scope.launch {
                                        updates = runCatching { access.ext.updates() }.getOrElse {
                                            failure = it.message ?: tr("检查更新失败（需要联网）", "Failed to check updates (network required)")
                                            null
                                        }
                                        checking = false
                                    }
                                },
                            )
                            if (updateCount > 0) {
                                PiPrimaryButton(
                                    label = tr("全部更新（$updateCount）", "Update All ($updateCount)"),
                                    leadingIcon = Icons.Filled.Download,
                                    compact = true,
                                    enabled = busy == null,
                                    modifier = Modifier.weight(1f),
                                    onClick = { run("update") { id -> access.ext.update(null, id) } },
                                )
                            } else {
                                PiPrimaryButton(
                                    label = tr("安装扩展包", "Install Package"),
                                    leadingIcon = Icons.Filled.Add,
                                    compact = true,
                                    enabled = busy == null,
                                    modifier = Modifier.weight(1f),
                                    onClick = { installSheetOpen = true },
                                )
                            }
                        }
                    }
                }
            }

            // ---- 2. 进行中 / 完成 / 失败状态卡 ----
            if (busy != null || log.isNotEmpty() || result != null || failure != null) {
                item(key = "activity") {
                    val isErr = failure != null
                    val isOk = busy == null && failure == null && result != null
                    val borderTone = when {
                        isErr -> palette.error.copy(alpha = 0.35f)
                        isOk -> palette.success.copy(alpha = 0.35f)
                        else -> palette.accent.copy(alpha = 0.32f)
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(PiSpacing.hairlineThin, borderTone),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(ModelsCardPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when {
                                    busy != null -> CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.accent,
                                    )
                                    isErr -> Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = palette.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    else -> Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = palette.success,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    when {
                                        busy != null -> {
                                            val opName = actionLabel(busy!!) ?: busy!!
                                            tr("正在$opName…", "$opName in progress…")
                                        }
                                        failure != null -> failure!!
                                        result != null -> result!!
                                        else -> tr("操作日志", "Activity Log")
                                    },
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                    color = when {
                                        isErr -> palette.error
                                        isOk -> palette.success
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (busy == null) {
                                    PiIconSquareButton(
                                        icon = Icons.Filled.Close,
                                        contentDescription = tr("关闭", "Dismiss"),
                                        size = 28.dp,
                                        iconSize = 15.dp,
                                        onClick = {
                                            result = null
                                            failure = null
                                            log.clear()
                                        },
                                    )
                                }
                            }
                            if (log.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(9.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        PiSpacing.hairlineThin,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        log.takeLast(6).forEach { line ->
                                            Text(
                                                line,
                                                style = PiTheme.text.monoSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 3. 失败包警告 ----
            val failures = model.snapshot?.failures.orEmpty()
            if (failures.isNotEmpty()) {
                item(key = "pkg-failures") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = RoundedCornerShape(14.dp),
                        color = palette.error.copy(alpha = 0.08f),
                        border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(ModelsCardPadding),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                tr("以下扩展包未能安装", "Failed to install packages"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.error,
                            )
                            failures.forEach { msg ->
                                Text(msg, style = PiTheme.text.monoSmall, color = palette.error)
                            }
                        }
                    }
                }
            }

            // ---- 4. 空状态 ----
            if (packages != null && list.isEmpty()) {
                item(key = "empty") {
                    ModelsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Inventory2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(30.dp),
                            )
                            Text(
                                tr("还没有安装任何扩展包", "No packages installed yet"),
                                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                tr(
                                    "扩展包可把扩展、技能、提示模板与主题打包安装，支持 npm、git 与本地路径。",
                                    "Packages bundle extensions, skills, prompts, and themes from npm, git, or local paths.",
                                ),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- 5. 已安装包列表 ----
            if (list.isNotEmpty()) {
                item(key = "list-header") {
                    ModelsSectionLabel(tr("已配置扩展包（${list.size}）", "Configured Packages (${list.size})"))
                }
                val snapshotGroups = model.snapshot?.let { it.global + it.project }.orEmpty()
                items(list, key = { it.scope + ":" + it.source }) { p ->
                    val info = p.info
                    val view = if (info != null) {
                        sourceView(p.source, info.kind, info.name, info.version, info.pinned)
                    } else {
                        sourceView(p.source, "unknown", null, null, false)
                    }
                    val update = updates?.firstOrNull { it.source == p.source && it.scope == p.scope }
                    val provided = remember(snapshotGroups, p.source, p.isProject) {
                        val matched = snapshotGroups.filter { it.source == p.source && it.projectScoped == p.isProject }
                        val ext = matched.sumOf { it.items(PiResourceCatalog.Type.Extensions).size }
                        val sk = matched.sumOf { it.items(PiResourceCatalog.Type.Skills).size }
                        val pr = matched.sumOf { it.items(PiResourceCatalog.Type.Prompts).size }
                        val th = matched.sumOf { it.items(PiResourceCatalog.Type.Themes).size }
                        buildList {
                            if (ext > 0) add(tr("$ext 个扩展", "$ext ext"))
                            if (sk > 0) add(tr("$sk 个技能", "$sk skills"))
                            if (pr > 0) add(tr("$pr 个模板", "$pr prompts"))
                            if (th > 0) add(tr("$th 个主题", "$th themes"))
                        }.joinToString(" · ")
                    }
                    val canModify = busy == null && (p.scope != "project" || projectUsable)
                    val isLocal = info?.kind == "local"
                    val canUpdate = p.installedPath != null && !view.pinned && !isLocal
                    val displayName = info?.name?.takeIf { it.isNotBlank() } ?: p.source
                    val metaLine = buildList {
                        when (info?.kind) {
                            "npm" -> add("npm")
                            "git" -> add("git")
                            "local" -> add(tr("本地", "Local"))
                        }
                        add(if (p.isProject) tr("本项目", "Project") else tr("全局", "Global"))
                        if (info?.version != null) {
                            add(if (view.pinned) "@${info.version}" else info.version)
                        }
                        if (p.filtered) add(tr("已筛选", "Filtered"))
                        if (provided.isNotEmpty()) add(provided)
                    }.joinToString(" · ")

                    val iconVec = when (info?.kind) {
                        "git" -> Icons.Filled.Code
                        "local" -> Icons.Filled.Folder
                        else -> Icons.Filled.Inventory2
                    }
                    val badgeSpec: Pair<String, androidx.compose.ui.graphics.Color>? = when {
                        p.installedPath == null -> tr("未安装", "Not installed") to palette.error
                        update != null -> tr("有更新", "Update") to palette.accent
                        view.pinned -> tr("固定版本", "Pinned") to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> null
                    }

                    ModelsCard(accent = update != null) {
                        Column(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ModelsCardPadding),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                when {
                                                    update != null -> palette.accent.copy(alpha = 0.12f)
                                                    p.installedPath == null -> palette.error.copy(alpha = 0.10f)
                                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                                },
                                            )
                                            .border(
                                                PiSpacing.hairlineThin,
                                                when {
                                                    update != null -> palette.accent.copy(alpha = 0.30f)
                                                    p.installedPath == null -> palette.error.copy(alpha = 0.30f)
                                                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                                                },
                                                RoundedCornerShape(10.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            iconVec,
                                            contentDescription = null,
                                            tint = when {
                                                update != null -> palette.accent
                                                p.installedPath == null -> palette.error
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            displayName,
                                            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (metaLine.isNotEmpty()) {
                                            Text(
                                                metaLine,
                                                style = PiTheme.text.meta,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    badgeSpec?.let { (label, tone) ->
                                        TagPill(label = label, tone = tone)
                                    }
                                }

                                val pathSub = p.installedPath?.let { shortPath(it) }
                                    ?: p.source.takeIf { it != displayName }
                                if (pathSub != null) {
                                    Text(
                                        pathSub,
                                        style = PiTheme.text.monoSmall,
                                        color = palette.dim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                view.warning?.let {
                                    Text(it, style = PiTheme.text.meta, color = palette.warning)
                                }
                                if (p.scope == "project" && !projectUsable) {
                                    Text(
                                        tr("项目尚未信任，无法修改项目级扩展包。", "Project is not trusted; project packages cannot be modified."),
                                        style = PiTheme.text.meta,
                                        color = palette.warning,
                                    )
                                }
                            }

                            ModelsDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ModelsCardPadding, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when {
                                    p.installedPath == null -> PiPrimaryButton(
                                        label = tr("重新安装", "Reinstall"),
                                        leadingIcon = Icons.Filled.Download,
                                        compact = true,
                                        enabled = canModify,
                                        onClick = { run("install") { id -> access.ext.install(p.source, p.isProject, id) } },
                                    )
                                    !canUpdate -> Text(
                                        if (view.pinned) tr("固定版本 · 跳过更新", "Pinned · Skipped on update")
                                        else tr("本地路径 · 跳过更新", "Local path · Skipped on update"),
                                        style = PiTheme.text.meta,
                                        color = palette.dim,
                                    )
                                    update != null -> PiPrimaryButton(
                                        label = tr("立即更新", "Update Now"),
                                        leadingIcon = Icons.Filled.Download,
                                        compact = true,
                                        enabled = canModify,
                                        onClick = { run("update") { id -> access.ext.update(p.source, id) } },
                                    )
                                    else -> PiSecondaryButton(
                                        label = tr("更新", "Update"),
                                        leadingIcon = Icons.Filled.Refresh,
                                        compact = true,
                                        enabled = canModify,
                                        onClick = { run("update") { id -> access.ext.update(p.source, id) } },
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                PiSecondaryButton(
                                    label = tr("移除", "Remove"),
                                    leadingIcon = Icons.Filled.Delete,
                                    tone = palette.error,
                                    compact = true,
                                    enabled = canModify,
                                    onClick = { confirmRemove = p },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (installSheetOpen) {
        InstallPackageSheet(
            access = access,
            projectUsable = projectUsable,
            busy = busy != null,
            onDismiss = { installSheetOpen = false },
            onInstall = { src, useProject ->
                installSheetOpen = false
                run("install") { id -> access.ext.install(src, useProject, id) }
            },
        )
    }

    confirmRemove?.let { p ->
        PiSettingsDialog(
            onDismissRequest = { confirmRemove = null },
            title = tr("移除这个扩展包？", "Remove this package?"),
            sub = p.source,
            body = if (p.isProject) {
                tr("会从本项目设置里去掉它，并删掉装下来的文件。它带来的扩展、技能、提示模板、主题随即消失。", "This removes it from this project's settings and deletes its installed files. All extensions, skills, prompt templates, and themes it provides will be removed.")
            } else {
                tr("会从全局设置里去掉它，并删掉装下来的文件。它带来的扩展、技能、提示模板、主题随即消失。", "This removes it from global settings and deletes its installed files. All extensions, skills, prompt templates, and themes it provides will be removed.")
            },
            confirmationLabel = tr("移除", "Remove"),
            onConfirm = { confirmRemove = null; run("remove") { id -> access.ext.remove(p.source, p.isProject, id) } },
            confirmationTone = palette.error,
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmRemove = null },
        )
    }
}

@Composable
private fun InstallPackageSheet(
    access: ExtensionsAccess,
    projectUsable: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onInstall: (source: String, project: Boolean) -> Unit,
) {
    val palette = PiTheme.palette
    var source by remember { mutableStateOf("") }
    var project by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<HostExtensions.SourceInfo?>(null) }
    val useProject = project && projectUsable

    LaunchedEffect(source) {
        inspected = null
        val s = source.trim()
        if (s.isEmpty()) return@LaunchedEffect
        delay(250)
        inspected = runCatching { access.ext.inspect(s) }.getOrNull()
    }

    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    tr("安装扩展包", "Install Package"),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    tr("由官方 Pi PackageManager 解析来源并安装，完成后自动热重载。", "Parsed and installed by official Pi PackageManager with live reload."),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("包来源", "Package Source"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
                )
                PiEditorField(
                    value = source,
                    onValueChange = { source = it },
                    placeholder = tr("npm:@scope/name · git:github.com/user/repo · 本地路径", "npm:@scope/name · git:github.com/user/repo · local path"),
                    enabled = !busy,
                )
                val v = inspected?.let { sourceView(source, it.kind, it.name, it.version, it.pinned) }
                if (v != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (v.warning != null) palette.warning.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            PiSpacing.hairlineThin,
                            if (v.warning != null) palette.warning.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    v.headline,
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (v.pinned) {
                                    TagPill(tr("固定版本", "Pinned"), tone = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            v.warning?.let {
                                Text(it, style = PiTheme.text.meta, color = palette.warning)
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("安装范围", "Install Scope"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
                )
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
                        !projectUsable -> tr("项目尚未信任，请先在「扩展与资源 → 项目信任」中确认后才能安装项目级包。", "Project is not trusted yet; review Project Trust on the hub before installing project packages.")
                        useProject -> tr("写入本项目的 .pi/settings.json，仅对当前项目生效。", "Saved to this project's .pi/settings.json for this project only.")
                        else -> tr("写入全局设置，所有项目均可使用。", "Saved to global settings and available in all projects.")
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PiSecondaryButton(
                    label = tr("取消", "Cancel"),
                    onClick = onDismiss,
                )
                PiPrimaryButton(
                    label = tr("安装", "Install"),
                    leadingIcon = Icons.Filled.Download,
                    enabled = !busy && source.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = { onInstall(source.trim(), useProject) },
                )
            }
        }
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
