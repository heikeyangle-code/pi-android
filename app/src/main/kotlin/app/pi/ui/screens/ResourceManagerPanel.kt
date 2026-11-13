package app.pi.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import app.pi.packages.AgentLayout
import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.packages.PiResourceCatalog.Override
import app.pi.packages.PiResourceCatalog.Parsed
import app.pi.packages.PiResourceCatalog.SourceKind
import app.pi.packages.PiResourceCatalog.Type
import app.pi.ui.PiSeg
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSwitch
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 工作区「资源」一段的数据与动作。
 *
 * 资源清单**不是扫目录得来的**，是 pi 自己的包管理器给出的结论（宿主 `resources.list`，见 `HostResources`）：所以 git 包、
 * 清单里声明的资源、内置扩展都在，来源标题与 `pi config` 里一字不差。开关写的也是 pi 的
 * `settings.json`（全局 `~/.pi/agent/`，项目 `<工作区>/.pi/`），写法就是 pi 的。
 */
@Stable
internal class ResourceManager(
    private val service: app.pi.host.HostResources,
    private val layout: AgentLayout,
    private val scope: CoroutineScope,
) {
    var snapshot by mutableStateOf<PiResourceCatalog.Snapshot?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var layer by mutableStateOf(Layer.Global)
    var type by mutableStateOf<Type?>(null)
    var busyKey by mutableStateOf<String?>(null)
        private set

    /** 开关写进了 settings.json，但已在跑的引擎要重启才读到。 */
    var changed by mutableStateOf(false)
        private set

    private var trusted = true

    /** pi 的 `checkForAvailableUpdates()` 结果；null = 还没查过。要联网，所以只在点了才查。 */
    var updates by mutableStateOf<List<PiResourceCatalog.Update>?>(null)
        private set
    var checkingUpdates by mutableStateOf(false)
        private set
    var updateError by mutableStateOf<String?>(null)
        private set

    fun checkUpdates() {
        if (checkingUpdates) return
        checkingUpdates = true
        updateError = null
        scope.launch {
            when (val result = service.updates()) {
                is Parsed.Ok -> updates = result.value
                is Parsed.Error -> {
                    updates = null
                    updateError = result.message
                }
            }
            checkingUpdates = false
        }
    }

    private fun cacheKey(trusted: Boolean) = layout.hostWorkspace.absolutePath + if (trusted) "#t" else "#u"

    private companion object {
        /** 进程内、按工作区+信任态记住上次的清单；不落盘，进程没了就没了。 */
        val cache = HashMap<String, PiResourceCatalog.Snapshot>()
    }

    fun refresh() {
        loading = true
        // 先拿上次读到的清单顶上：每次读都要冷启动一遍引擎（几秒），没必要让人对着空白等。
        // 读完再换成新的；换的只是内容，开关位置不变。
        if (snapshot == null) cache[cacheKey(trusted)]?.let { snapshot = it }
        scope.launch {
            val result = service.list()
            when (result) {
                is Parsed.Ok -> {
                    snapshot = result.value
                    // 信任与否是宿主（SDK 的信任库）说了算，不再在 App 里复刻一遍判据。
                    trusted = result.value.trusted
                    cache[cacheKey(trusted)] = result.value
                    error = null
                }
                is Parsed.Error -> error = result.message
            }
            loading = false
        }
    }

    fun toggle(item: PiResourceCatalog.Item) {
        if (busyKey != null) return
        val key = item.type.key + ":" + item.path
        busyKey = key
        val target = layer
        scope.launch {
            val result = service.toggle(target, item)
            when (result) {
                is Parsed.Ok -> {
                    changed = true
                    error = null
                    // 同一个进程写完盘后已经重读了一遍；老脚本没带才再列一次。
                    val fresh = result.value.snapshot?.let { Parsed.Ok(it) }
                        ?: service.list()
                    if (fresh is Parsed.Ok) {
                        snapshot = fresh.value
                        cache[cacheKey(trusted)] = fresh.value
                    }
                }
                is Parsed.Error -> error = result.message
            }
            busyKey = null
        }
    }

    /** pi 报的是 guest 里的路径；宿主上对应哪个文件。 */
    fun hostFile(guestPath: String): File = when {
        guestPath == layout.guestWorkspace || guestPath.startsWith(layout.guestWorkspace + "/") ->
            File(layout.hostWorkspace, guestPath.removePrefix(layout.guestWorkspace).removePrefix("/"))
        guestPath == layout.guestAgentDir || guestPath.startsWith(layout.guestAgentDir + "/") ->
            File(layout.paths.agentDir, guestPath.removePrefix(layout.guestAgentDir).removePrefix("/"))
        else -> File(layout.paths.rootfs, guestPath.removePrefix("/"))
    }
}

private val GROUP_ORDER = mapOf(
    SourceKind.Package to 0,
    SourceKind.Settings to 1,
    SourceKind.Directory to 2,
    SourceKind.Builtin to 3,
)

/**
 * 「资源」段的全部内容：层级（全局 / 本项目）→ 类型筛选 → 按来源分组的逐条开关。
 *
 * 全局层只能改用户级资源；项目层每条是三态（继承 / 加载 / 屏蔽），与 `pi config` 的 灰 / [+] / [-]
 * 对应：点一下按 pi 的循环顺序走一格。
 */
internal fun LazyListScope.resourceManagerItems(
    manager: ResourceManager,
    projectUntrusted: Boolean,
    onManagePackages: () -> Unit,
    onOpen: (PiResourceCatalog.Item, File) -> Unit,
) {
    val snapshot = manager.snapshot
    val layer = if (projectUntrusted) Layer.Global else manager.layer

    item(key = "rm:manage") {
        WsCardSlice(index = 0, lastIndex = 0) {
            WsRow(
                title = "扩展包",
                meta = "安装、更新、移除，以及项目信任",
                trail = { Text("管理", style = PiTheme.text.meta, color = PiTheme.palette.accent) },
                onClick = onManagePackages,
            )
        }
    }
    item(key = "rm:updates") {
        WsCardSlice(index = 0, lastIndex = 0) {
            WsRow(
                title = if (manager.checkingUpdates) "检查中…" else "检查扩展包更新",
                meta = manager.updates?.let { found ->
                    if (found.isEmpty()) "都是最新" else "有新版本：" + found.joinToString("、") { it.name }
                } ?: manager.updateError ?: "问 pi 哪些包有新版本（要联网，只查不装）",
                trail = {
                    val found = manager.updates
                    if (found != null && found.isNotEmpty()) {
                        Text("去更新", style = PiTheme.text.meta, color = PiTheme.palette.accent)
                    } else if (!manager.checkingUpdates) {
                        Text("检查", style = PiTheme.text.meta, color = PiTheme.palette.accent)
                    }
                },
                onClick = {
                    if (manager.updates?.isNotEmpty() == true) onManagePackages() else manager.checkUpdates()
                },
            )
        }
    }
    item(key = "rm:layer") {
        PiSeg(
            options = listOf("全局", "本项目"),
            selectedIndex = if (layer == Layer.Global) 0 else 1,
            onSelect = { if (!projectUntrusted) manager.layer = if (it == 0) Layer.Global else Layer.Project },
            modifier = Modifier.padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.groupGap,
            ),
        )
    }
    if (projectUntrusted) {
        item(key = "rm:untrusted") {
            WsNotice(
                text = "这个项目还没被信任：pi 不会读项目里的 .pi 资源，这里只能改全局。去「扩展包」里信任之后才能改项目层。",
                tone = StateTone.Warning,
                glyph = "!",
            )
        }
    }
    manager.error?.let { message ->
        item(key = "rm:error") { WsNotice(text = message, tone = StateTone.Warning, glyph = "!") }
    }
    snapshot?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
        item(key = "rm:failures") {
            Column {
                WsNotice(
                    text = "有包没装上：" + failures.joinToString("；") +
                        "。常见原因：安装时没网、重装过运行时清掉了安装目录、或配置是从别处拷来的。pi 自己开会话时会补装；这里只读，不会偷偷联网装。",
                    tone = StateTone.Warning,
                    glyph = "!",
                )
                WsCardSlice(index = 0, lastIndex = 0) {
                    WsRow(
                        title = "去扩展包页重装或移除",
                        trail = { Text("打开", style = PiTheme.text.meta, color = PiTheme.palette.accent) },
                        onClick = onManagePackages,
                    )
                }
            }
        }
    }
    if (manager.changed) {
        item(key = "rm:changed") {
            WsNotice(text = "开关已写入 settings.json；正在运行的对话要重启引擎后才会读到。", glyph = "·")
        }
    }
    if (snapshot == null) {
        item(key = "rm:loading") {
            WsCardSlice(index = 0, lastIndex = 0) {
                WsRow(title = if (manager.loading) "正在向 pi 读取资源…" else "还没读到资源", meta = "点右上角刷新可重试")
            }
        }
        return
    }

    val groups = snapshot.layer(layer)
        .sortedWith(
            compareBy<PiResourceCatalog.Group>(
                { GROUP_ORDER[it.kind] ?: 9 },
                { if (it.projectScoped) 1 else 0 },
                { it.label },
            ),
        )
    val counts = Type.entries.associateWith { type -> groups.sumOf { it.items(type).size } }
    item(key = "rm:types") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSpacing.small,
                )
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
        ) {
            PiSettingsChip(
                text = "全部 ${counts.values.sum()}",
                active = manager.type == null,
                onClick = { manager.type = null },
            )
            Type.entries.forEach { type ->
                PiSettingsChip(
                    text = "${type.label} ${counts[type] ?: 0}",
                    active = manager.type == type,
                    onClick = { manager.type = type },
                )
            }
        }
    }

    val shown = groups.filter { group -> group.items.any { manager.type == null || it.type == manager.type } }
    if (shown.isEmpty()) {
        item(key = "rm:empty") {
            WsCardSlice(index = 0, lastIndex = 0) {
                WsRow(title = "这一类还没有资源", meta = "装一个扩展包，或把文件放进 .pi / .agents 目录。")
            }
        }
    }
    shown.forEach { group ->
        val rows = group.items
            .filter { manager.type == null || it.type == manager.type }
            .sortedWith(compareBy({ it.type.ordinal }, { it.name }))
        val inheritedGroup = layer == Layer.Project && !group.projectScoped
        item(key = "rm:h:${group.key}") {
            WsSectionHeader(
                label = group.label + (if (inheritedGroup) " · 继承自全局" else "") +
                    (if (group.kind == SourceKind.Package && manager.updates.orEmpty().any { it.source == group.source }) " · 有新版本" else ""),
                count = "${rows.size}",
            )
        }
        item(key = "rm:c:${group.key}") {
            WsCard {
                rows.forEachIndexed { index, item ->
                    if (index > 0) WsHairline()
                    ResourceItemRow(manager, item, layer, inheritedGroup, group.kind, onOpen)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ResourceItemRow(
    manager: ResourceManager,
    item: PiResourceCatalog.Item,
    layer: Layer,
    inheritedGroup: Boolean,
    kind: SourceKind,
    onOpen: (PiResourceCatalog.Item, File) -> Unit,
) {
    val busy = manager.busyKey == item.type.key + ":" + item.path
    val effective = PiResourceCatalog.effectiveEnabled(item, layer)
    val file = if (kind == SourceKind.Builtin) null else manager.hostFile(item.path)
    // 全局层里只有用户级的能改；包/目录里项目级的条目在这一层根本不会出现。
    val editable = !busy
    WsRow(
        modifier = Modifier.alpha(if (inheritedGroup && item.override == Override.Inherit) 0.6f else 1f),
        title = (if (manager.type == null) item.type.label + " · " else "") + item.name,
        meta = when {
            layer == Layer.Project && item.override == Override.Load -> "本项目强制加载"
            layer == Layer.Project && item.override == Override.Unload -> "本项目已屏蔽"
            !effective -> "已关闭"
            else -> null
        },
        onClick = if (file != null && file.isFile) ({ onOpen(item, file) }) else null,
        trail = {
            if (layer == Layer.Project) {
                OverrideChip(item, effective, editable) { manager.toggle(item) }
            } else {
                PiSettingsSwitch(checked = effective, onCheckedChange = { manager.toggle(item) }, enabled = editable)
            }
        },
    )
}

/** 项目层的三态，用 pi 的符号：继承（跟随全局）/ + 加载 / − 屏蔽。 */
@androidx.compose.runtime.Composable
private fun OverrideChip(
    item: PiResourceCatalog.Item,
    effective: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val (glyph, word, color) = when (item.override) {
        Override.Load -> Triple("+", "加载", PiTheme.palette.accent)
        Override.Unload -> Triple("−", "屏蔽", PiTheme.palette.warning)
        Override.Inherit -> Triple(if (effective) "●" else "○", if (effective) "继承·开" else "继承·关", PiTheme.palette.muted)
    }
    Text(
        "$glyph $word",
        modifier = Modifier
            .clip(RoundedCornerShape(WsShape.small))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = PiSpacing.small, vertical = PiSpacing.tiny),
        style = PiTheme.text.meta,
        color = color,
        maxLines = 1,
    )
}
