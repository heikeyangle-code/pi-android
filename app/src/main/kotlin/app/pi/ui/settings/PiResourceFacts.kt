package app.pi.ui.settings

/**
 * 「扩展与资源」这一屏**实际发现了什么**的纯逻辑：计数、来源归类、以及三种诚实读数。
 *
 * ## 它解决什么
 *
 * 用户点进「扩展与资源」看到的是空的：那时这一屏只列**手填的额外搜索路径**（`extensions`/
 * `skills`/`prompts`/`themes`，默认都没有值），而 pi 真正加载的资源是它**自动发现**的 ——
 * 工作区 `.pi/<kind>`、`~/.pi/agent/<kind>`、`.agents/<kind>`、已装资源包自带的，以及扩展
 * 自己写出去的。一屏只有空值、还不说"实际发现了什么"，用户只会以为它没用。
 *
 * 现在这一屏放**只读事实行**，数字来自真实扫描（复用 `app.pi.ui.screens.WorkspaceResourceScan`，
 * 那是项目页资源段用的同一个扫描器，不新写一份）；这个文件只做**纯计算**：把扫描结果按
 * 每一类去重、按来源归组、格式化成行文本。它不 import Compose、不碰磁盘，所以 bare-JVM 的
 * `settings-resources` harness 能逐条覆盖它。
 *
 * ## 三种读数，一条都不许装成真值
 *
 * | 情况 | 行文本 | 为什么不这样写会撒谎 |
 * |---|---|---|
 * | 扫到 N 个 | `3 个 · 项目 .pi 1 · ~/.pi/agent 2` | 只有数字不给来源，用户不知道"在哪、要不要动" |
 * | 扫到 0 个 | `还没有发现任何资源` | 写 `0 个` 会被读成"这个功能是坏的/没装好"；真相是"还没有" |
 * | 读不到 | `读不到：<原因>` | 写 `0 个` 就是**拿假值冒充读数** —— 这个仓库反复在修的形状 |
 *
 * ## 去重与来源归属
 *
 * 扫描器会为同名资源返回**每一份拷贝**（它要把冲突画出来）。"我有几个技能"问的是**名字**，
 * 所以这里按 `(kind, name)` 去重，保留**优先级最高**的来源（项目 `.pi` > `.agents` >
 * `~/.pi/agent` > 已装包 > 扩展贡献），与 pi 的解析顺序一致 —— 也就是说这一行报的是"实际
 * 会生效的那一份来自哪"。同名冲突本身由项目页资源段展示，不在这里重复。
 */
internal enum class DiscoverySource(val label: String, val rank: Int) {
    /** 工作区 `<workspace>/.pi/<kind>`。 */
    ProjectPi("项目 .pi", 0),

    /** 工作区 `<workspace>/.agents/<kind>`。 */
    Agents(".agents", 1),

    /** agent dir（`~/.pi/agent/<kind>`，engine bind 成 guest 的那份）。 */
    Global("~/.pi/agent", 2),

    /** 已装资源包自带的（`packages` 里那些）。 */
    Package("已装包", 3),

    /** 由扩展自己写出去的（目前只有设备桥那个技能）。 */
    Extension("扩展贡献", 4),
}

/**
 * 四类资源，每类一个注册表键。
 *
 * [extraDir] 是 pi 自动发现它的目录名，写进行的说明里（"要加新的放哪"）—— 这个文件名来自
 * pi 自己的 `FILE_PATTERNS`（`core/package-manager.ts:206-211`），由 `PiResourceDiscovery.Kind`
 * 转录，这里只引用同一个拼写，不再抄一遍语义。
 */
internal enum class DiscoveredKind(val key: String, val label: String, val extraDir: String) {
    Skills("app.resources.discovered.skills", "技能", "skills"),
    Themes("app.resources.discovered.themes", "主题", "themes"),
    Prompts("app.resources.discovered.prompts", "提示模板", "prompts"),
    Extensions("app.resources.discovered.extensions", "扩展", "extensions"),
}

/** 扫描给出的一条资源：[name] 是去重的依据，[source] 是它落在哪个根下。 */
internal data class DiscoveredResource(
    val kind: DiscoveredKind,
    val name: String,
    val source: DiscoverySource,
)

/**
 * 一次扫描的结果。
 *
 * `Unreadable` 与"扫到 0 个"是**两件事**，这正是这个类型存在的理由：前者是"读不到"（引擎目录
 * 还没解包、权限、IO 失败），后者是"读了，确实没有"。合并成一个 Int 就再也分不开了。
 */
internal sealed interface ResourceScan {
    data class Found(val resources: List<DiscoveredResource>) : ResourceScan

    data class Unreadable(val reason: String) : ResourceScan
}

/** 一类的计数与来源分布。 */
internal data class ResourceCounts(
    val total: Int,
    /** 按来源优先级排序，只含数量 > 0 的来源。 */
    val bySource: List<Pair<DiscoverySource, Int>>,
)

/**
 * [resources] 里 [kind] 这一类的数量与来源分布。
 *
 * 同名去重保留优先级最高的来源（见文件头），所以 `total` 是"pi 会加载的名字数"，而不是
 * "磁盘上的文件数"。
 */
internal fun countDiscovered(resources: List<DiscoveredResource>, kind: DiscoveredKind): ResourceCounts {
    val winnerBySource = LinkedHashMap<String, DiscoverySource>()
    resources.forEach { resource ->
        if (resource.kind != kind) return@forEach
        val current = winnerBySource[resource.name]
        if (current == null || resource.source.rank < current.rank) {
            winnerBySource[resource.name] = resource.source
        }
    }
    val bySource = winnerBySource.values
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedBy { it.key.rank }
        .map { it.key to it.value }
    return ResourceCounts(total = winnerBySource.size, bySource = bySource)
}

/** 一个来源分布在行里怎么写：`项目 .pi 1 · ~/.pi/agent 2`。 */
private fun sourceText(bySource: List<Pair<DiscoverySource, Int>>): String =
    bySource.joinToString(" · ") { (source, count) -> "${source.label} $count" }

/**
 * 四个只读事实行的文本，键就是 [DiscoveredKind.key]。
 *
 * - `Found`：`"3 个 · 项目 .pi 1 · ~/.pi/agent 2"`；
 * - `Found` 但一个都没有：`"还没有发现任何资源"`（**不是** `0 个`）；
 * - `Unreadable`：`"读不到：<原因>"`（原因来自调用方，原样透出，不翻译、不吞）。
 */
internal fun resourceFactOverrides(scan: ResourceScan): Map<String, String> =
    DiscoveredKind.entries.associate { kind ->
        kind.key to when (scan) {
            is ResourceScan.Unreadable -> "读不到：${scan.reason}"
            is ResourceScan.Found -> {
                val counts = countDiscovered(scan.resources, kind)
                if (counts.total == 0) {
                    "还没有发现任何资源"
                } else {
                    "${counts.total} 个 · ${sourceText(counts.bySource)}"
                }
            }
        }
    }

/**
 * 扫描还没落地时，四个只读行的占位读数：四个键都是「未读取」。
 *
 * 与 [resourceFactOverrides] 分开是有意的：**"还没读"不等于"读不到"**，也不等于"没有"。
 * 占位里不许出现任何数字 —— 第一帧显示 `0 个` 会被读成一个读数。
 */
internal fun resourceFactPlaceholders(): Map<String, String> =
    DiscoveredKind.entries.associate { it.key to "未读取" }

/**
 * 分组摘要里那半句"实际发现了多少"，**没有扫描结果时返回 null**。
 *
 * 返回 null 而不是 `"0 个"`：摘要是在设置首页画的，扫描可能还没跑（用户没进过这一组），
 * 此时唯一的诚实做法是**不提**。调用方把 null 拼掉即可。
 */
internal fun discoveredSummaryLine(scan: ResourceScan?): String? {
    val found = scan as? ResourceScan.Found ?: return null
    val parts = DiscoveredKind.entries.mapNotNull { kind ->
        val counts = countDiscovered(found.resources, kind)
        if (counts.total == 0) null else "${counts.total} 个${kind.label}"
    }
    return if (parts.isEmpty()) "还没有发现任何资源" else "已发现 " + parts.joinToString(" · ")
}

/**
 * 设置栈写完一次扫描后放在这里，供**分组摘要**读（摘要的签名只有 `PiSettingsStore`，拿不到
 * 扫描结果）。
 *
 * 只保存最近一次：键是工作区路径，`SettingsHome` 画摘要时用的是最近一次扫描。设置栈在进入
 * 设置目的地时按 `workspacePath` 重新扫描（`LaunchedEffect` 的键包含它），所以看完首页再切
 * 工作区时，重新进入设置就会刷新 —— 这份缓存的过期窗口是"切换工作区且停留在设置首页"这段。
 * 不保存它就只能在摘要里写死一个恒为 0 的数字，那正是这一批要删掉的那种谎话。
 */
internal object PiResourceFactsCache {
    @Volatile
    private var last: Pair<String, ResourceScan>? = null

    fun put(workspacePath: String, scan: ResourceScan) {
        last = workspacePath to scan
    }

    /** 最近一次扫描结果（不区分工作区）；没有扫描过就是 null。 */
    fun lastScan(): ResourceScan? = last?.second
}
