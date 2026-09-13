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
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel

/**
 * Settings level 0 (spec §6.1), 按 v2 重排：搜索入口 → 当前模型卡 → 设备 / 扩展 /
 * 关于 → 全部设置（12 个分组）→ 页脚说明。
 *
 * 层级与取值来自 `06-v2-construction-reference.md` §2：屏水平 14、分组容器圆角 10
 * 且无描边无阴影、行 `padding:10px 12px`、标题 15/500、副行 12 灰、chevron 14、
 * 搜索框 40 高圆角 9。当前模型卡是 v2 里 accent 的合法用法之一：卡内左缘一条
 * 2px accent 条 + 模型 id 用 accent。
 *
 * The screen is stateless apart from the store it reads; navigation is the
 * caller's job, which is why it takes three callbacks rather than owning routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome(
    store: PiSettingsStore,
    contentPadding: PaddingValues,
    onOpenGroup: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSetting: (String) -> Unit,
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
     * 设置 → 模型（[PiModelsScreen]）。v2 的首页把它和终端并排放在「其他」一节
     * （`direction-b-v2.html:2153-2156`，副行「这台设备上配好的厂商与模型」）。
     *
     * 它不是 pi 的一个设置项：这一页列的是这台设备上已经配好的厂商与模型，读的是
     * `models.json` 与引擎，所以它既不属于 12 个分组，也不在「设备 / 扩展」两节里。
     * `null` 隐藏这一行（预览与测试用）。
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
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
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
            item {
                CurrentModelCard(store, onOpenSetting)
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
            // neither is a pi setting. `pi install` is CLI-only in v0.85.1, so
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
            // 其他：终端 + 模型。v2 的首页把这两行放在同一节（`phone4` / `phone33`），
            // 因为它们都不是 pi 的设置项 —— 一个是 TUI 回退口，一个是本应用自己扫
            // 出来的模型清单 —— 也都不属于 12 个分组里的任何一个。
            if (onOpenTerminal != null || onOpenModels != null) {
                item {
                    PiSettingsSectionHeader("其他")
                }
                item {
                    PiSettingsCard {
                        if (onOpenTerminal != null) {
                            PiTerminalEntryRow(onClick = onOpenTerminal)
                        }
                        if (onOpenTerminal != null && onOpenModels != null) {
                            PiSettingsHairline()
                        }
                        if (onOpenModels != null) {
                            PiEntryRow(
                                icon = Icons.Filled.Timeline,
                                title = "模型",
                                supporting = "这台设备上配好的厂商与模型",
                                onClick = onOpenModels,
                            )
                        }
                    }
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
            // v2 把 12 个分组放进**一张**卡片，行间是 1px inset hairline；12 行不
            // 多，所以整块是一个 LazyColumn item，不拆成 12 个 item。
            item {
                PiSettingsSectionHeader(
                    label = "全部设置",
                    count = "${PiSettingsCatalog.settings.size} 项",
                )
                PiSettingsCard {
                    PiSettingsCatalog.groups.forEachIndexed { index, group ->
                        if (index > 0) PiSettingsHairline()
                        GroupEntry(
                            group = group,
                            store = store,
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
 * 当前模型快捷卡（v2：卡内左缘 2px accent 条、副行 `改这里 →`、模型 id 等宽
 * accent、思考等级 `◐` + 中文标签）。
 *
 * 卡整体可点，落到「默认模型」那一行的编辑器；这是这个屏上最常改的两个值，
 * 所以它值一张卡。
 */
@Composable
private fun CurrentModelCard(store: PiSettingsStore, onOpenSetting: (String) -> Unit) {
    val model = PiSettingsCatalog.summaryText(store, "defaultModel")
    val levelText = PiSettingsCatalog.summaryText(store, "defaultThinkingLevel")
    val levelWire = PiSettingsCatalog.byKey["defaultThinkingLevel"]
        ?.current(store)
        ?.primitiveText()
    val levelColor = PiTheme.palette.thinking(PiThinkingLevel.fromWire(levelWire).wire)
    PiSettingsCard(
        modifier = Modifier.padding(top = PiSettingsMetrics.cardPaddingLoose),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSetting("defaultModel") }
                .padding(
                    start = PiSettingsMetrics.rowPaddingHorizontal,
                    end = PiSettingsMetrics.rowPaddingHorizontal,
                    top = PiSettingsMetrics.cardPadding,
                    bottom = PiSettingsMetrics.cardPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Box(
                Modifier
                    .padding(
                        top = PiSettingsMetrics.currentBarInset,
                        bottom = PiSettingsMetrics.currentBarInset,
                    )
                    .fillMaxHeight()
                    .width(PiSettingsMetrics.currentBarWidth)
                    .background(PiTheme.palette.accent),
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
                ) {
                    Text(
                        "当前模型",
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                    Text(
                        "改这里 →",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = PiSettingsMetrics.badgeGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
                ) {
                    Text(
                        model,
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.mono,
                        color = PiTheme.palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "◐",
                        style = PiTheme.text.meta,
                        color = levelColor,
                    )
                    Text(
                        levelText,
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
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
        Column(Modifier.weight(1f)) {
            Text(
                group.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                group.summary(store),
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
