package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.pi.bridge.DeviceCapabilityStore
import app.pi.packages.PiPackagesEntryRow
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.device.DeviceCapabilityEntryRow
import app.pi.ui.screens.PiFilesScreen
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel

/**
 * Settings level 0 (spec §6.1), 按 v2 重排：搜索入口 → 当前模型卡 → 设备 / 扩展 /
 * 关于 → 全部设置（13 个分组：spec §6.4 的 12 组 + 本应用自己的「提示词」）→ 页脚说明。
 *
 * 层级与取值来自 `06-v2-construction-reference.md` §2：屏水平 14、分组容器圆角 10
 * 且无描边无阴影、行 `padding:10px 12px`、标题 15/500、副行 12 灰、chevron 14、
 * 搜索框 40 高圆角 9。当前模型卡是 v2 里 accent 的合法用法之一：卡内左缘一条
 * 2px accent 条 + 模型 id 用 accent。
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
    onOpenGroup: (String) -> Unit,
    onOpenSearch: () -> Unit,
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
     * [SettingsGroupScreen.freshness]、[SettingsSearchScreen.freshness] 同一个东西，
     * 由 `PiSettingsStack` 的 `rowsEpoch` 供给（外部改动 + 本进程写入）。
     *
     * 这一屏的每条分组摘要（`GroupEntry` 的 `group.summary`）与「当前模型」卡都是从 store
     * 读出来的，而 store 不是快照状态 ⇒ 写完设置之后这一屏会被 strong skipping 跳过，
     * 摘要停在旧值上（`docs/settings-audit-impl.md:232` 记的正是这条「靠父级重组」的缺口）。
     * 默认 0 让预览/测试维持原样。
     */
    freshness: Int = 0,
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
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = "设置",
            actions = {
                PiTopBarIcon(
                    onClick = onOpenSearch,
                    contentDescription = "搜索设置",
                    icon = Icons.Filled.Search,
                )
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item {
                SearchEntry(onOpenSearch)
            }
            // 模型与供应商：首页最顶栏的第一扇门（用户拍板的位置）——取代原来那张
            // 「当前模型」卡（卡已按用户裁定删除：同一个东西两处显示，就是"乱"）。
            if (onOpenModels != null) {
                item {
                    PiSettingsSectionHeader("模型与供应商")
                }
                item {
                    PiSettingsCard {
                        PiEntryRow(
                            icon = Icons.Filled.Timeline,
                            title = "模型与供应商",
                            supporting = "厂商、模型、凭证与导入——官方配置文件的编辑器",
                            onClick = onOpenModels,
                        )
                    }
                }
            }
            // The device capability bridge is the one settings surface that is
            // not a pi setting: pi has no notion of the phone it runs on, so this
            // is the only screen for it. The catalog used to carry a second,
            // disconnected copy of these switches under `app.device.*`; those rows
            // were removed from `PiSettingsRegistry.kt` so there is one authority,
            // `DeviceCapabilityStore`.
            if (onOpenDeviceCapabilities != null) {
                item {
                    PiSettingsSectionHeader("设备")
                }
                item {
                    PiSettingsCard {
                        DeviceCapabilityEntryRow(
                            store = DeviceCapabilityStore.get(context),
                            onClick = onOpenDeviceCapabilities,
                        )
                    }
                }
            }
            // Extension packages, next to the capability row because they answer
            // the same kind of question — "what can the agent use here?" — and
            // neither is a pi setting. `pi install` is CLI-only in v0.86.1 (the RPC
            // command union carries no install/packages command —
            // `modes/rpc/rpc-types.ts:20-74`), so
            // this screen is the app's own, not a transcription of pi's.
            if (onOpenPackages != null) {
                item {
                    PiSettingsSectionHeader("扩展")
                }
                item {
                    PiSettingsCard {
                        PiPackagesEntryRow(onClick = onOpenPackages)
                    }
                }
            }
            // 其他：终端 + Pi 文件。v2 曾把「模型」也放在这一节（`phone4` /
            // `phone33`），但那一行已按用户拍板挪到首页最顶栏（上面的「模型与供应商」）
            // —— 同一件事只有一个门，这正是这次重排要消灭的重复。
            //
            // 「Pi 文件」是第三行，按同一条件成立：它既不是 pi 的设置键，也不是 pi 的功能，
            // 而是 **pi 的文件**（`docs/settings-audit-pi-gap.md` §6.3 说它为什么在这里）。
            // 这一节现在**没有条件**：Pi 文件那一行永远可用，所以整节永远该在。
            item {
                PiSettingsSectionHeader("其他")
            }
            item {
                PiSettingsCard {
                    if (onOpenTerminal != null) {
                        PiTerminalEntryRow(onClick = onOpenTerminal)
                        PiSettingsHairline()
                    }
                    PiFilesEntryRow(onClick = openPiFiles)
                }
            }
            // 关于：开源许可 + 诊断报告。许可不是 pi 的设置也不是 pi 的功能：这个 App
            // 分发了一整套 Linux 用户态、Node、git、proot、一个 pi 引擎和许多库，所以
            // 许可证原文与源码获取方式是**本应用**的义务。诊断报告则是运行时与诊断分组
            // 里那一行的第二个入口（引擎已经退出时，用户先翻的往往是「关于」）。
            if (onOpenLicenses != null || onOpenDiagnostics != null) {
                item {
                    PiSettingsSectionHeader("关于")
                }
                item {
                    PiSettingsCard {
                        if (onOpenLicenses != null) {
                            PiLicensesEntryRow(onClick = onOpenLicenses)
                        }
                        if (onOpenLicenses != null && onOpenDiagnostics != null) {
                            PiSettingsHairline()
                        }
                        if (onOpenDiagnostics != null) {
                            PiEntryRow(
                                icon = Icons.Filled.Timeline,
                                title = "诊断报告",
                                supporting = "引擎最后一次退出、运行时事实与最近的失败",
                                onClick = onOpenDiagnostics,
                            )
                        }
                    }
                }
            }
            // v2 把当时的 12 个分组放进**一张**卡片，行间是 1px inset hairline；分组
            // 行数不多（现在是 13 行，见 `PiSettingsCatalog.groups`），所以整块是一个
            // LazyColumn item，不拆成一个个 item。
            item {
                PiSettingsSectionHeader(
                    label = "全部设置",
                    count = "${PiSettingsCatalog.settings.size} 项",
                )
                PiSettingsCard {
                    // 「模型与推理」这一组的入口**按用户裁定删除**：组里的行（选择与推理）
                    // 全都搬进了顶栏的「模型与供应商」一屏，首页不再有任何一条路进那个分组屏。
                    // 行仍留在注册表里 —— 搜索、`groupTitle` 与编辑器都还要按 key 找到它们。
                    val listedGroups = PiSettingsCatalog.groups.filterNot { it.id == G_MODEL }
                    listedGroups.forEachIndexed { index, group ->
                        if (index > 0) PiSettingsHairline()
                        GroupEntry(
                            group = group,
                            store = store,
                            freshness = freshness,
                            onClick = { onOpenGroup(group.id) },
                        )
                    }
                }
            }
            item {
                Text(
                    "共 ${PiSettingsCatalog.settings.size} 项设置。搜索同时匹配标题、说明与字段名，" +
                        "输入 reserveTokens 或 /compact 都能直达。",
                    modifier = Modifier.padding(
                        start = PiSettingsMetrics.pageHorizontal,
                        end = PiSettingsMetrics.pageHorizontal,
                        top = PiSettingsMetrics.footerTop,
                        bottom = PiSettingsMetrics.groupGap,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 搜索入口（v2：高 40、圆角 9、`surfaceContainerLow` 底、1px `borderMuted` 描边、
 * 内 `padding:0 12px`，图标 16，文字 14 灰，右侧项数 12 灰）。
 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.cardPadding,
            )
            .height(PiSettingsMetrics.searchFieldHeight)
            .clickable(onClick = onClick),
        shape = PiSettingsFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            PiSettingsMetrics.hairline,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                tint = PiTheme.palette.muted,
            )
            Spacer(Modifier.width(PiSettingsMetrics.searchIconGap))
            Text(
                "搜索设置",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = PiTheme.palette.muted,
            )
            Text(
                "${PiSettingsCatalog.settings.size} 项",
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
    }
}

/**
 * 分组入口行：标题 15/500 + 动态摘要 + chevron（v2 的首页分组行没有前置图标，
 * 前置图标留给设备/扩展/关于三行，见 `06 §2` 的 Row `lead` 槽）。
 */
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                group.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.chevronSize),
            tint = PiTheme.palette.muted,
        )
    }
}

/**
 * 首页上的一行入口：前置图标 + 标题 + 副行 + 「打开」 + chevron
 * （v2 `SettingsHome` 的 `Row`：`padding:10px 12px`、图标 16 灰、标题 15/500、
 * 副行 12 灰、尾部值 13、chevron 14）。
 *
 * 「其他」与「关于」两节里的四行形状完全一样，所以这里只写一次。
 */
@Composable
private fun PiEntryRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = PiTheme.palette.muted,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                supporting,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "打开",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.chevronSize),
            tint = PiTheme.palette.muted,
        )
    }
}

/**
 * 终端入口行 —— 终端从底部目的地降下来的那一行（`03-navigation-decision.md:24`）。
 *
 * 副行的话是从原来的工作区页搬过来的**原话**（旧 `WorkbenchScreen.kt:72`），比 v2 首页
 * 上那句「需要终端的扩展在那边」长：它是应用里唯一说明「哪些能力只有原版 TUI 有」的地方
 * （订阅登录、会话导入、需要终端的扩展），而 v2 的 `06 §5` 只给了那半句。这处文案差异**故意
 * 保留**并已写进本批的偏差清单等裁决 —— 删掉它就等于把这些能力从界面上抹掉。
 * `docs/settings-review.md` §9 的教训是这句话不该在每个 TUI 相关的设置行上重复，而应该只
 * 说一次——这里就是那一次。
 */
@Composable
private fun PiTerminalEntryRow(onClick: () -> Unit) {
    PiEntryRow(
        icon = Icons.Filled.Terminal,
        title = "终端",
        supporting = "输入 pi 回车进入原版 TUI：订阅登录、会话导入、以及需要终端的扩展都在那边。",
        onClick = onClick,
    )
}

/**
 * 「Pi 文件」入口行 —— `PiFilesScreen` 的门。
 *
 * 副行只讲**这一屏能做什么**，不讲它在哪：pi 的文件分两处（agent 目录与项目 `.pi`），
 * 把它们写成路径只会多一行用户不需要记的字，进去以后第一件看到的就是那两个根的名字。
 *
 * 与终端那行一样，这一段是应用里**唯一**说这句话的地方，不在别处重复。
 */
@Composable
private fun PiFilesEntryRow(onClick: () -> Unit) {
    PiEntryRow(
        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
        title = "Pi 文件",
        supporting = "看 pi 读的那些文件：设置、模型、提示词、主题、技能。只给看的不给改。",
        onClick = onClick,
    )
}
