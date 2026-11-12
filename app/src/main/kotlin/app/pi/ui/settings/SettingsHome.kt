package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.ui.PiTopBar
import app.pi.ui.screens.PiFilesScreen

/**
 * Settings level 0 (spec §6.1), 按 v2 重排：当前模型卡 → 设备 / 扩展 / 关于 →
 * 全部设置（13 个分组：spec §6.4 的 12 组 + 本应用自己的「提示词」）。
 *
 * 层级与取值来自 `06-v2-construction-reference.md` §2：屏水平 14、卡片圆角 10
 * 且无描边无阴影、行 `14 横向 / 10 纵向`、标题 15/500、说明 12 灰**一行**、chevron 14。
 * 首页没有标题、没有搜索框，也没有文字尾巴：每一行就是
 * [PiSettingsEntryRow]（图标 + 标题 + 一行说明 + chevron），与「设备能力」页同一个构件。
 *
 * The screen owns no routes: every destination is the caller's, which is why it takes
 * callbacks rather than owning navigation. The one exception is a fallback — the
 * 「Pi 文件」screen hosts itself here only when the caller passes no `onOpenPiFiles`
 * (the app passes one, from `PiSettingsStack`; see that parameter).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome(
    store: PiSettingsStore,
    contentPadding: PaddingValues,
    /** 顶栏左侧的返回箭头 —— 底栏拆掉之后，设置首页只能回到对话。 */
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    /** `null` hides the row, which is what a preview or a test wants. */
    onOpenDeviceCapabilities: (() -> Unit)? = null,
    /**
     * The package/extension manager ([app.pi.packages.PiPackagesHost]). `null`
     * hides the row for the same reason as the capability one: pi has no package
     * screen to mirror — `pi install` is CLI-only in this version — so the entry
     * exists only where the host can mount it.
     */
    onOpenPackages: (() -> Unit)? = null,
    /**
     * The open-source licence notices ([LicensesScreen]). `null` hides the row.
     * This one is neither a pi setting nor a pi feature: publishing the licences of
     * what we redistribute is *this* app's obligation as the distributor, which is
     * why the row is a screen of its own rather than a row in pi's catalog
     * (`docs/known-gaps.md` §L).
     */
    onOpenLicenses: (() -> Unit)? = null,
    /**
     * The full-screen terminal ([app.pi.ui.screens.TerminalScreen]). `null` hides
     * the row.
     *
     * The terminal used to be a bottom-bar destination and the user retired it
     * ("现在是个废品那个功能"), so it is one ordinary row here rather than a place
     * (`03-navigation-decision.md:24`). It stays reachable because it is the only
     * surface where the extension APIs that draw terminal cells work at all, and
     * the row's supporting line says so rather than leaving the capability
     * invisible.
     */
    onOpenTerminal: (() -> Unit)? = null,
    /**
     * 「模型与供应商」（[ModelProviderScreen]）。**挪到了首页最顶栏**（用户拍板：
     * 「位置在设置的最顶栏就行」）—— 它不再和终端并排放在「其他」一节，那一节现在
     * 只剩终端与 Pi 文件。
     *
     * 它不是 pi 的一个设置项：这一屏是这台设备上几份官方配置文件
     * （models.json / auth.json / settings.json）的编辑器，所以它不属于 13 个分组。
     * `null` 隐藏入口（预览与测试用）。
     */
    onOpenModels: (() -> Unit)? = null,
    /**
     * 设置 → 诊断报告（[app.pi.ui.settings.DiagnosticsScreen]）。v2 的首页把它放在
     * 「关于」一节、开源许可的下面（`direction-b-v2.html:2162-2164`）。
     *
     * 它是运行时与诊断分组里那一行的同一个入口（`app.runtime.diagnostics`），这里再
     * 给一条路径是因为引擎已经退出时用户最先翻的是「关于」。`null` 隐藏这一行。
     */
    onOpenDiagnostics: (() -> Unit)? = null,
    /**
     * 设置 → 「Pi 文件」（[app.pi.ui.screens.PiFilesScreen]）。
     *
     * 本应用传的是 `PiSettingsStack` 的 `{ piFiles = true }`：这一屏是那个栈里的一个层级，
     * 所以它的返回语义、层级与 `rememberSaveable` 状态都在栈里，与搜索 / 分组页同一套。
     * 首页只把点击转上去。
     *
     * `null` 只留作**没有宿主时的退路**（预览、单屏测试）：那一屏由首页自己托管，点开就地
     * 替换首页内容。行在两种情况下都在 —— 一个点了没反应的行比一个自托管的行更坏。
     */
    onOpenPiFiles: (() -> Unit)? = null,
    /**
     * 让首页的读数在「有东西写过设置」之后重读 `store` 的 epoch —— 与
     * [SettingsGroupScreen.freshness] 同一个东西，
     * 由 `PiSettingsStack` 的 `rowsEpoch` 供给（外部改动 + 本进程写入）。
     *
     * 这一屏的每条分组摘要（`GroupEntry` 的 `group.summary`）与「当前模型」卡都是从 store
     * 读出来的，而 store 不是快照状态 ⇒ 写完设置之后这一屏会被 strong skipping 跳过，
     * 摘要停在旧值上（`docs/settings-audit-impl.md:232` 记的正是这条「靠父级重组」的缺口）。
     * 默认 0 让预览/测试维持原样。
     */
    freshness: Int = 0,
    /** 「其他」这一屏的入口；`null` 时整张卡不画（预览与测试用）。 */
    onOpenOther: (() -> Unit)? = null,
    /**
     * 「MCP 服务器」（[McpServersScreen]）：pi 的 `mcp.json` 的点选式编辑器。与「模型与供应商」
     * 同类 —— 都是这台设备上 pi 配置文件的编辑器而不是 pi 的某个设置键，所以紧跟在它下面。
     * `null` 隐藏入口（预览与测试用）。
     */
    onOpenMcp: (() -> Unit)? = null,
    onOpenEngine: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // 退路用的自托管状态。放在早退之前，所以两个分支都走同一份 remember。
    var piFilesOpen by remember { mutableStateOf(false) }
    if (onOpenPiFiles == null && piFilesOpen) {
        PiFilesScreen(
            contentPadding = contentPadding,
            onBack = { piFilesOpen = false },
        )
        return
    }
    val openPiFiles: () -> Unit = onOpenPiFiles ?: { piFilesOpen = true }
    // 手绘 `PiTopBar` 不吃状态栏 inset，而这一屏只消费 `contentPadding` 的底边 ——
    // 顶边在这里补一次（见 `settingsPageTopInset`）。
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val hits = remember(q) {
        if (q.isEmpty()) emptyList() else PiSettingsCatalog.settings.filter {
            it.group != "security" && (
                it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) ||
                    it.key.contains(q, ignoreCase = true)
                )
        }.take(40)
    }
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "设置", onBack = onBack)
        // 卡与卡之间**必须有间距**：`PiSettingsCard` 自己不带上下留白（行自带 10 纵向），
        // 所以首页把四张卡直接堆进 LazyColumn 时，它们会无缝贴在一起，看起来像一整块
        // 没有分节的灰面 —— 这就是「简陋」的来源之一。这里用统一的分组间距
        // (`PiSettingsMetrics.groupGap`) 分开，并给首卡留 8 的顶距。
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 模型与供应商：首页最顶栏的第一扇门（用户拍板的位置）——取代原来那张
            // 「当前模型」卡（卡已按用户裁定删除：同一个东西两处显示，就是"乱"）。
            item(key = "search") {
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    placeholder = "搜索设置：名称、说明或键名",
                    height = PiSettingsMetrics.searchFieldHeight,
                )
            }
            if (q.isNotEmpty()) {
                if (hits.isEmpty()) {
                    item(key = "no-hits") {
                        PiSettingsCard { PiSettingsEntryRow(title = "没有匹配的设置", supporting = "换个词，或清空搜索看分组。") }
                    }
                } else {
                    item(key = "hits") {
                        PiSettingsCard {
                            hits.forEachIndexed { index, hit ->
                                if (index > 0) PiSettingsHairline()
                                PiSettingsEntryRow(
                                    title = hit.title,
                                    supporting = PiSettingsCatalog.groupTitle(hit.group) + " · " + hit.description,
                                    onClick = {
                                        if (hit.group == G_MODEL && onOpenModels != null) onOpenModels() else onOpenGroup(hit.group)
                                    },
                                )
                            }
                        }
                    }
                }
                return@LazyColumn
            }
            // 分区：先「接什么」（模型、工具、资源），再「怎么跑」（对话行为），再外观与系统。
            // 原来是一条 12 项不分段的长列表，找东西只能一屏屏翻。
            fun groupCards(label: String, ids: List<String>, lead: List<HomeEntry> = emptyList()) {
                val groups = ids.mapNotNull { id -> PiSettingsCatalog.groups.firstOrNull { it.id == id } }
                if (groups.isEmpty() && lead.isEmpty()) return
                item(key = "label:$label") { HomeSectionLabel(label) }
                item(key = "card:$label") {
                    PiSettingsCard {
                        var first = true
                        lead.forEach { entry ->
                            if (!first) PiSettingsHairline()
                            first = false
                            PiSettingsEntryRow(
                                title = entry.title,
                                supporting = entry.supporting,
                                onClick = entry.onClick,
                                leadingIcon = entry.icon,
                            )
                        }
                        groups.forEach { group ->
                            if (!first) PiSettingsHairline()
                            first = false
                            GroupEntry(group, store, freshness) { onOpenGroup(group.id) }
                        }
                    }
                }
            }
            groupCards(
                "模型与能力",
                listOf("tools", "resources"),
                lead = listOfNotNull(
                    onOpenModels?.let { HomeEntry("模型与供应商", "厂商、模型、凭证与导入", Icons.Filled.Timeline, it) },
                    onOpenEngine?.let { HomeEntry("引擎", "作用域模型、缓存预热、工具、扩展 flag、系统提示词、导出", Icons.Filled.Build, it) },
                    onOpenMcp?.let { HomeEntry("MCP 服务器", "给 pi 接外部工具：开关、添加、编辑", Icons.Filled.Build, it) },
                ),
            )
            groupCards("对话", listOf("prompts", "messages"))
            groupCards("上下文与网络", listOf("compaction", "retry"))
            groupCards("外观", listOf("appearance"))
            item(key = "label:系统") { HomeSectionLabel("系统") }
            item(key = "card:系统") {
                PiSettingsCard {
                    PiSettingsEntryRow(
                        title = PiSettingsCatalog.groupTitle("runtime"),
                        supporting = PiSettingsCatalog.groups.firstOrNull { it.id == "runtime" }?.summary?.invoke(store),
                        onClick = { onOpenGroup("runtime") },
                        leadingIcon = Icons.Filled.Build,
                    )
                    if (onOpenOther != null) {
                        PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = "其他",
                            supporting = "终端、Pi 文件、设备能力、安全与隐私、开源许可",
                            onClick = onOpenOther,
                            leadingIcon = Icons.Filled.MoreVert,
                        )
                    }
                }
            }
        }
    }
}

private class HomeEntry(
    val title: String,
    val supporting: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun HomeSectionLabel(text: String) {
    androidx.compose.material3.Text(
        text,
        modifier = Modifier.padding(
            start = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
            end = PiSettingsMetrics.pageHorizontal,
        ),
        style = PiTheme.text.meta,
        color = PiTheme.palette.muted,
    )
}

@Composable
private fun GroupEntry(
    group: PiSettingsGroup,
    store: PiSettingsStore,
    freshness: Int,
    onClick: () -> Unit,
) {
    // 摘要从 store 读（`PiSettingsGroup.summary`），所以它是这一行唯一需要重读的东西：
    // 键里带 `freshness` 就是那个「有东西写过设置」的信号，见 [SettingsHome] 的 `freshness`。
    val summary = remember(store, group.id, freshness) { group.summary(store) }
    PiSettingsEntryRow(
        title = group.title,
        supporting = summary,
        onClick = onClick,
        leadingIcon = group.icon,
    )
}

