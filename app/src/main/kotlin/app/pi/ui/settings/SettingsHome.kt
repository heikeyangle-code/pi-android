package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceCapabilityStore
import app.pi.packages.PiPackagesEntryRow
import app.pi.ui.PiTopBar
import app.pi.ui.device.DeviceCapabilityEntryRow
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
        PiTopBar(title = "设置")
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
            if (onOpenModels != null) {
                item {
                    PiSettingsCard {
                        PiSettingsEntryRow(
                            title = "模型与供应商",
                            supporting = "厂商、模型、凭证与导入——官方配置文件的编辑器",
                            onClick = onOpenModels,
                            leadingIcon = Icons.Filled.Timeline,
                        )
                    }
                }
            }
            // 分组一张卡一行（用户裁定：不要全塞进一张大卡，像「模型与供应商」那样一卡一扇门）。
            // 顺序见下面的 `order`：高频在上，扩展与资源垫底。
            //
            // 「模型与推理」的入口仍删掉（组里的行都在「模型与供应商」屏里）。
            val order = listOf(
                "appearance", "sessions", "messages", "prompts", "tools",
                "compaction", "retry", "runtime", "resources",
            )
            val listedGroups = PiSettingsCatalog.groups
                .filterNot { it.id == G_MODEL || it.id == "security" }
                .sortedBy {
                    order.indexOf(it.id).let { index -> if (index < 0) order.size else index }
                }
            listedGroups.forEach { group ->
                item {
                    PiSettingsCard {
                        GroupEntry(
                            group = group,
                            store = store,
                            freshness = freshness,
                            onClick = { onOpenGroup(group.id) },
                        )
                    }
                }
            }
            // 「其他」：一张卡、一行。里面那些与 pi 设置无关的门（终端 / Pi 文件 /
            // 设备能力 / 扩展包与信任 / 安全与隐私 / 开源许可）收进这一屏，点进去再看 ——
            // 用户裁定：首页只留「模型」「分组」「其他」三张卡。
            if (onOpenOther != null) {
                item {
                    PiSettingsCard {
                        PiSettingsEntryRow(
                            title = "其他",
                            supporting = "终端、Pi 文件、设备能力、扩展包与信任、安全与隐私、开源许可",
                            onClick = onOpenOther,
                            leadingIcon = Icons.Filled.MoreVert,
                        )
                    }
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

/**
 * 首页上的一行入口：前置图标 + 标题 + 一行说明 + chevron。
 *
 * 形状就是 [PiSettingsEntryRow]（行内 `14 / 10`、图标 16 灰、标题 15/500、说明 12 灰
 * **一行**、chevron 14），与「设备能力」页的入口行逐像素相同；没有文字尾巴。
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
    PiSettingsEntryRow(
        title = title,
        supporting = supporting,
        onClick = onClick,
        leadingIcon = icon,
    )
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
        supporting = "输入 pi 回车进入原版 TUI：会话导入、以及需要终端的扩展都在那边（订阅登录已内建，在「模型与供应商」里）。",
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
