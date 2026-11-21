package app.pi.ui.resources

import app.pi.i18n.tr
import app.pi.packages.PiLoadedResources
import app.pi.packages.PiLoadedResources.DiagKind
import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.packages.PiResourceCatalog.SourceKind
import app.pi.packages.PiResourceCatalog.Type
import app.pi.ui.models.matches

/*
 * 「扩展与资源」页的**全部判断**，不含 Compose：
 *  - 开关清单（[PiResourceCatalog.Snapshot]，含没开的、按来源分组）与「实际加载」（[PiLoadedResources.Loaded]，
 *    带描述 / 注册了什么 / 冲突与错误）按**路径**对上——两边的路径都是 pi 自己算的，SDK 里同一条资源两处的
 *    路径逐字相同（技能 / 提示 / 主题是文件路径，内置扩展是 `builtin:名字`），所以对上用相等，不用猜；
 *  - 诊断（SDK 的 `ResourceDiagnostic`）说成人话；
 *  - 概览数字、分组与排序、搜索。
 * 每条规则有 `ResourcesPresentationCheck` 钉着。
 */

// ------------------------------------------------------------------ 概览

/** 枢纽页的数字：每类**实际加载**几个、几个被关掉了；有几处问题；有几个包没装上。 */
data class Overview(
    val loaded: Map<Type, Int>,
    val disabled: Map<Type, Int>,
    val contextFiles: Int,
    val problems: Int,
    val installFailures: Int,
)

fun overview(loaded: PiLoadedResources.Loaded, snapshot: PiResourceCatalog.Snapshot?, layer: Layer): Overview {
    val groups = snapshot?.layer(layer).orEmpty()
    return Overview(
        loaded = mapOf(
            Type.Extensions to loaded.visibleExtensions.size,
            Type.Skills to loaded.skills.size,
            Type.Prompts to loaded.prompts.size,
            Type.Themes to loaded.themes.size,
        ),
        disabled = Type.entries.associateWith { type ->
            groups.sumOf { g -> g.items(type).count { !PiResourceCatalog.effectiveEnabled(it, layer) } }
        },
        contextFiles = loaded.contextFiles.size,
        problems = loaded.problemCount,
        installFailures = snapshot?.failures?.size ?: 0,
    )
}

/** 枢纽页每一类那一行的副标题：「3 个已加载 · 1 个已关闭」；都是 0 就说没有。 */
fun typeSummary(type: Type, ov: Overview): String {
    val loaded = ov.loaded[type] ?: 0
    val off = ov.disabled[type] ?: 0
    return when {
        loaded == 0 && off == 0 -> tr("没有", "None")
        off == 0 -> tr("$loaded 个已加载", "$loaded loaded")
        loaded == 0 -> tr("$off 个已关闭", "$off disabled")
        else -> tr("$loaded 个已加载 · $off 个已关闭", "$loaded loaded · $off disabled")
    }
}

internal val TAG_NOT_LOADED: String get() = tr("未加载", "Not loaded")

// ------------------------------------------------------------------ 一类资源的列表

/** 一行的补充信息；[loaded] = pi 此刻真的加载了它。 */
data class RowInfo(val loaded: Boolean, val description: String?, val tags: List<String>)

data class ResourceRow(val item: PiResourceCatalog.Item, val effective: Boolean, val info: RowInfo)

/** 按来源分的一段：[inherited] = 项目视图里这段其实是全局的（灰显）。 */
data class SourceSection(val group: PiResourceCatalog.Group, val inherited: Boolean, val rows: List<ResourceRow>)

private val KIND_ORDER = mapOf(
    SourceKind.Package to 0,
    SourceKind.Settings to 1,
    SourceKind.Directory to 2,
    SourceKind.Builtin to 3,
)

fun infoFor(item: PiResourceCatalog.Item, effective: Boolean, loaded: PiLoadedResources.Loaded): RowInfo = when (item.type) {
    Type.Extensions -> {
        val e = loaded.extensions.firstOrNull { it.path == item.path || it.resolvedPath == item.path }
        if (e == null) {
            RowInfo(false, null, if (effective) listOf(TAG_NOT_LOADED) else emptyList())
        } else {
            RowInfo(
                loaded = true,
                description = null,
                tags = listOfNotNull(
                    e.commands.size.takeIf { it > 0 }?.let { tr("$it 命令", "$it cmds") },
                    e.tools.size.takeIf { it > 0 }?.let { tr("$it 工具", "$it tools") },
                    e.flags.size.takeIf { it > 0 }?.let { tr("$it 参数", "$it flags") },
                    e.shortcuts.size.takeIf { it > 0 }?.let { tr("$it 快捷键", "$it keys") },
                ).ifEmpty { if (e.events.isNotEmpty()) listOf(tr("监听事件", "Event hooks")) else emptyList() },
            )
        }
    }
    Type.Skills -> {
        val k = loaded.skills.firstOrNull { it.filePath == item.path }
        if (k == null) {
            RowInfo(false, null, if (effective) listOf(TAG_NOT_LOADED) else emptyList())
        } else {
            RowInfo(true, k.description.ifBlank { null }, listOfNotNull("/skill:${k.name}", if (k.disableModelInvocation) tr("仅手动调用", "Manual only") else null))
        }
    }
    Type.Prompts -> {
        val p = loaded.prompts.firstOrNull { it.filePath == item.path }
        if (p == null) {
            RowInfo(false, null, if (effective) listOf(TAG_NOT_LOADED) else emptyList())
        } else {
            RowInfo(true, p.description.ifBlank { null }, listOfNotNull("/${p.name}", p.argumentHint?.takeIf { it.isNotBlank() }))
        }
    }
    Type.Themes -> {
        val t = loaded.themes.firstOrNull { it.path == item.path }
        if (t == null) RowInfo(false, null, if (effective) listOf(TAG_NOT_LOADED) else emptyList()) else RowInfo(true, null, emptyList())
    }
}

/**
 * 一类资源在某一层的列表：只留这一类、按来源分段（包 → 设置里列的 → 目录 → 内置；项目级的排后面），
 * 段内按名字；搜索对名字、描述、路径各查一遍。没有命中的段不出现。
 */
fun sections(
    type: Type,
    snapshot: PiResourceCatalog.Snapshot,
    layer: Layer,
    loaded: PiLoadedResources.Loaded,
    query: String,
): List<SourceSection> =
    snapshot.layer(layer)
        .sortedWith(compareBy({ KIND_ORDER[it.kind] ?: 9 }, { if (it.projectScoped) 1 else 0 }, { it.label }))
        .mapNotNull { group ->
            val rows = group.items(type)
                .map { item ->
                    val effective = PiResourceCatalog.effectiveEnabled(item, layer)
                    ResourceRow(item, effective, infoFor(item, effective, loaded))
                }
                .filter { matches(query, it.item.name, it.info.description.orEmpty(), it.item.path) }
                .sortedBy { it.item.name.lowercase() }
            if (rows.isEmpty()) {
                null
            } else {
                SourceSection(group, inherited = layer == Layer.Project && !group.projectScoped, rows = rows)
            }
        }

// ------------------------------------------------------------------ 诊断

/** 路径只留最后三段，前面用 `…/`：手机屏幕放不下整条，而区分两个文件只要末尾几段。 */
fun shortPath(path: String, keep: Int = 3): String {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= keep) path else "…/" + parts.takeLast(keep).joinToString("/")
}

/** SDK 一条诊断说成一句人话。冲突要说清谁赢了、谁被忽略了。 */
fun describeDiagnostic(d: PiLoadedResources.Diagnostic): String {
    val c = d.collision
    if (d.kind == DiagKind.Collision && c != null) {
        val kind = Type.of(c.resourceType + "s")?.label ?: c.resourceType
        val winner = shortPath(c.winnerPath)
        val loser = shortPath(c.loserPath)
        return tr(
            "$kind「${c.name}」重名：用了 $winner，忽略了 $loser",
            "$kind \"${c.name}\" collision: kept $winner, ignored $loser",
        )
    }
    val where = d.path?.takeIf { it.isNotBlank() }?.let { tr("（${shortPath(it)}）", " (${shortPath(it)})") }.orEmpty()
    return d.message.trim() + where
}

fun diagnosticLabel(kind: DiagKind): String = when (kind) {
    DiagKind.Error -> tr("错误", "Error")
    DiagKind.Warning -> tr("警告", "Warning")
    DiagKind.Collision -> tr("重名", "Collision")
}

/** 某一类资源相关的诊断。扩展没有 `ResourceDiagnostic`，它的问题是加载错误 / 警告，单独给。 */
fun diagnosticsFor(type: Type, loaded: PiLoadedResources.Loaded): List<PiLoadedResources.Diagnostic> = when (type) {
    Type.Skills -> loaded.skillDiagnostics
    Type.Prompts -> loaded.promptDiagnostics
    Type.Themes -> loaded.themeDiagnostics
    Type.Extensions ->
        loaded.extensionErrors.map { PiLoadedResources.Diagnostic(DiagKind.Error, it.message, it.path, null) } +
            loaded.extensionWarnings.map { PiLoadedResources.Diagnostic(DiagKind.Warning, it.message, it.path, null) }
}

// ------------------------------------------------------------------ 上下文文件与系统提示

/** 「上下文」页的一行：什么文件、起什么作用。 */
data class ContextRow(val path: String, val role: String, val size: Long, val truncated: Boolean)

/**
 * pi 实际读到的上下文：`AGENTS.md` / `CLAUDE.md`（沿目录往上找，全部拼进系统提示）、`SYSTEM.md`（替换系统提示）、
 * `APPEND_SYSTEM.md`（追加）。顺序与 SDK `buildSystemPromptSections` 一致：前言（SYSTEM.md 替换默认前言）→
 * `addendum`（追加）→ `project_context`（上下文文件）→ 技能清单 → cwd。
 */
fun contextRows(loaded: PiLoadedResources.Loaded): List<ContextRow> = buildList {
    loaded.systemPrompt?.let { add(ContextRow(it, tr("替换系统提示", "Replaces System Prompt"), 0, false)) }
    loaded.appendSystemPrompt.forEach { add(ContextRow(it, tr("追加到系统提示", "Appends to System Prompt"), 0, false)) }
    loaded.contextFiles.forEach { add(ContextRow(it.path, tr("上下文文件", "Context File"), it.body.size, it.body.truncated)) }
}

// ------------------------------------------------------------------ 扩展包的来源

/**
 * 一个包来源 pi 会怎么理解它。解析是 SDK 的 `parseSource`（宿主 `describeSource`），这里只说成人话。
 * [warning] 非空 = 用户八成想错了（最常见：写了 `foo` 没写 `npm:foo`——pi 把它当**本地路径**）。
 */
data class SourceView(val headline: String, val tags: List<String>, val warning: String?, val pinned: Boolean)

fun looksLikePath(source: String): Boolean {
    val s = source.trim()
    return s.startsWith("/") || s.startsWith("./") || s.startsWith("../") || s.startsWith("~") || s.startsWith("file:") ||
        Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(s)
}

fun sourceView(source: String, kind: String, name: String?, version: String?, pinned: Boolean): SourceView {
    val s = source.trim()
    return when (kind) {
        "npm" -> SourceView(
            headline = tr("npm 包 ${name ?: s}", "npm package ${name ?: s}"),
            tags = listOfNotNull(
                version?.let { if (pinned) tr("固定 @$it", "Pinned @$it") else tr("版本范围 $it", "Range $it") },
                if (pinned) tr("更新会跳过", "Skipped on update") else null,
            ),
            warning = null,
            pinned = pinned,
        )
        "git" -> SourceView(
            headline = tr("git 仓库 ${name ?: s}", "git repo ${name ?: s}"),
            tags = listOfNotNull(
                if (pinned) tr("固定到 ${version ?: "某个 ref"}", "Pinned to ${version ?: "ref"}") else tr("跟随默认分支", "Tracks default branch"),
                if (pinned) tr("更新会跳过", "Skipped on update") else null,
            ),
            warning = null,
            pinned = pinned,
        )
        "local" -> SourceView(
            headline = tr("本地路径 ${name ?: s}", "Local path ${name ?: s}"),
            tags = listOf(tr("本地", "Local")),
            warning = if (looksLikePath(s)) null else tr("没有 npm: 或 git: 前缀，pi 会把「$s」当作本地路径。要装 npm 包请写 npm:$s。", "Without an npm: or git: prefix, pi treats \"$s\" as a local path. Use npm:$s to install an npm package."),
            pinned = false,
        )
        else -> SourceView(
            tr("无法识别的来源", "Unrecognized source"),
            emptyList(),
            tr("pi 解析不了这个来源。npm 包写 npm:名字，git 写 git:github.com/用户/仓库 或完整的 https:// 地址。", "Pi cannot parse this source. Use npm:<name> for npm packages, or git:github.com/user/repo or a full https:// URL for git."),
            false,
        )
    }
}

/** 一个已配置包那一行的标签：层级 + 来源种类 + 固定 + 过滤。 */
fun packageTags(scope: String, filtered: Boolean, installed: Boolean, view: SourceView): List<String> = buildList {
    add(if (scope == "project") tr("本项目", "Project") else tr("全局", "Global"))
    addAll(view.tags)
    if (filtered) add(tr("只加载其中一部分", "Filtered"))
    if (!installed) add(tr("没装好", "Not installed"))
}
