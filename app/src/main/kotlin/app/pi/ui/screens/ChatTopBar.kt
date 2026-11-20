package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.chat.ChatAction
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.engineStateTone
import app.pi.ui.theme.stateToneColor

/**
 * 聊天页顶栏：状态点 + 会话标题（点开会话列表）、搜索、工作区、⋮ 菜单。
 *
 * 从 `ChatBody` 拆出来：顶栏只关心「显示什么、点了通知谁」，不碰列表/输入框的任何局部状态。
 * 搜索开关、跳转提问、展开工具输出都是回调，真正的状态仍在 `ChatBody`；菜单里每一项
 * 「做什么」只有 `ChatBody.perform` 里那一份（[onAction]），命令面板的同名行走同一个函数。
 */
@Composable
internal fun ChatTopBar(
    engineLabel: String,
    sessionTitle: String?,
    searchOpen: Boolean,
    scrolled: Boolean,
    toolsExpanded: Boolean,
    onOpenSessions: () -> Unit,
    onOpenWorkspace: () -> Unit,
    /** [clearOnClose]：菜单里的「关闭查找」同时清空关键词；顶栏的放大镜只收起。 */
    onToggleSearch: (clearOnClose: Boolean) -> Unit,
    /** 跳到上一条（false）/ 下一条（true）用户提问。 */
    onJumpQuestion: (forward: Boolean) -> Unit,
    onToggleTools: () -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            // **不透明先于 inset**：背景画在状态栏后面，顶栏和页面同色，内容不会在
            // 状态栏/标题下面透出半截。edge-to-edge 把状态栏报成 inset，所以顺序是
            // background → statusBarsPadding。
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 56dp：标准顶栏高度。单行标题 + 7dp 状态点 + 28dp 模型 chip 都在里面。
                .height(PiSpacing.chatTopBarHeight)
                .padding(horizontal = PiSpacing.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            // v2's `TopBar` `gap:10` between the title block and the right cluster.
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 左侧：状态点 + 会话标题 + ▾。整块可点，行为仍是打开会话列表；
            // 引擎状态从一整行收成一个 7dp 的点，颜色沿用原来对勾的 tone。
            val engineDotColor = stateToneColor(engineStateTone(engineLabel), PiTheme.palette)
            val showSearchAction = LocalConfiguration.current.screenWidthDp >= 360
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(PiSpacing.chatTopBarAction)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "打开会话列表", onClick = onOpenSessions),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(PiSpacing.chatTopBarStatusDot)
                        .clip(CircleShape)
                        .background(engineDotColor)
                        .semantics { contentDescription = "引擎：$engineLabel" },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    // pi 自己的会话名（`set_session_name` / `session_info_changed`）。
                    // 拿不到就只写「会话」；`setTitle` 是终端窗口标题，不再冒充 App 标题。
                    text = sessionTitle ?: "会话",
                    modifier = Modifier.weight(1f, fill = false),
                    style = if (sessionTitle != null) PiType.topBarTitle else PiType.topBarPlaceholder,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 模型 chip 已经挪到输入框工具行（2026-10-26）：顶栏把这段宽度
                // 还给会话标题，模型名和环 / 发送放在同一条能看见输入状态的行里。
                // 读模型名的两个来源（`meta.model` 与 transcript 最后一条
                // `model_change`）原样搬到 `ChatBody`，喂给下面那颗 chip。
                if (showSearchAction) {
                    ChatTopBarAction(
                        onClick = { onToggleSearch(false) },
                        contentDescription = if (searchOpen) "关闭对话内查找" else "在对话里查找",
                        icon = Icons.Filled.Search,
                    )
                }
                // 工作区：和会话页顶栏同一个 `FolderOpen` 图标，不再只藏在 ⋮ 里。
                ChatTopBarAction(
                    onClick = onOpenWorkspace,
                    contentDescription = "工作区",
                    icon = Icons.Filled.FolderOpen,
                )
                // 顶栏右侧保留高频入口；「重新加载扩展与资源」（SDK `session.reload`）收在 ⋮ 菜单中。
                // ⋮ 菜单统一使用 `PiMenu` 外壳，保持与输入框菜单一致。
                Box {
                    ChatTopBarAction(
                        onClick = { overflow = true },
                        contentDescription = "更多",
                        icon = Icons.Filled.MoreVert,
                    )
                    // Composed only while it is open. `PiMenu` returns immediately
                    // when `!expanded`, but `items = buildList { … }` is evaluated at
                    // *this* call site, so the ~20 rows, their lambdas and their
                    // captured state were rebuilt on every recomposition of this
                    // screen — which is once per streamed token — for a menu nobody
                    // had opened. The popup still anchors to the same `Box`, so
                    // nothing about its position changes.
                    if (overflow) PiMenu(
                        expanded = true,
                        onDismiss = { overflow = false },
                        // This anchor is at the top of the screen, so the menu
                        // opens **downwards**; the composer's ⋮ is the mirror
                        // image and opens upwards. Neither flips itself.
                        placement = PiMenuPlacement.Below,
                        items = buildList {
                            // 一个入口、三组、两道分隔线（此前是 19 项平铺的抽屉）。每项的「做什么」
                            // 只有 `perform` 里那一份，命令面板的同名行走的是同一个函数；
                            // 顺序是：先「对这段对话做什么」，再「对会话做什么」，再「模型」，最后「全局」。
                            //
                            // 去掉的两项：「命令面板」（输入框工具行就有 `/`）和「循环切换思考等级」
                            // （输入框的思考等级文字就是它；菜单里只留选择器）。
                            //
                            // ---- 对话（只改你看到的，不动会话）
                            if (!showSearchAction) {
                                add(
                                    PiMenuItem(
                                        if (searchOpen) "关闭对话内查找" else "在对话里查找",
                                    ) {
                                        onToggleSearch(true)
                                    },
                                )
                            }
                            add(
                                PiMenuItem("跳到上一条提问") {
                                    onJumpQuestion(false)
                                },
                            )
                            add(
                                PiMenuItem("跳到下一条提问") {
                                    onJumpQuestion(true)
                                },
                            )
                            add(PiMenuItem("复制最后一条回复") { onAction(ChatAction.CopyLastAssistant) })
                            add(
                                PiMenuItem(
                                    if (toolsExpanded) "收起全部工具输出" else "展开全部工具输出",
                                ) { onToggleTools() },
                            )
                            // ---- 会话
                            add(PiMenuItem("新建会话", dividerBefore = true) { onAction(ChatAction.NewSession) })
                            add(PiMenuItem("会话树") { onAction(ChatAction.OpenTree) })
                            add(PiMenuItem("从历史消息分叉") { onAction(ChatAction.PickFork) })
                            add(PiMenuItem("复制当前会话") { onAction(ChatAction.CloneSession) })
                            add(PiMenuItem("重命名") { onAction(ChatAction.RenameSession) })
                            add(PiMenuItem("导出会话（HTML + 可导入文件）") { onAction(ChatAction.ExportSession) })
                            add(PiMenuItem("会话信息") { onAction(ChatAction.SessionStats) })
                            add(PiMenuItem("上下文与用量") { onAction(ChatAction.ContextUsage) })
                            add(PiMenuItem("会话与队列") { onAction(ChatAction.ToolsSheet) })
                            // ---- 模型
                            add(PiMenuItem("选择模型…", dividerBefore = true) { onAction(ChatAction.PickModel) })
                            add(PiMenuItem("下一个模型") { onAction(ChatAction.CycleModel) })
                            add(PiMenuItem("思考等级…") { onAction(ChatAction.PickThinking) })
                            // ---- 全局
                            add(PiMenuItem("重新加载扩展与资源", dividerBefore = true) { onAction(ChatAction.ReloadResources) })
                            // 「设置」是离开这一屏，所以单独排在最后；终端入口已经被用户裁掉，
                            // 设置首页那一行是唯一的门。
                            add(PiMenuItem("设置") { onAction(ChatAction.OpenSettings) })
                        },
                    )
                }
            }
        }
        // 0.5dp 发丝线只在滚动/惯性期间出现；一停就收回。
        if (scrolled) {
            HorizontalDivider(
                thickness = PiSpacing.hairlineThin,
                color = PiTheme.palette.borderMuted.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * 对话页顶栏的一枚图标动作：48dp 热区、24dp 字形、统一 `onSurface`。
 *
 * 原来的 `PiTopBarIcon` 是 30dp 框 + 17dp 字形；对话页顶栏现在只放三枚 24dp 字形，
 * 统一到 48dp 热区后比别处更松快，所以单独留这一套，别的屏仍用 `PiTopBarIcon`。
 */
@Composable
private fun ChatTopBarAction(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
) {
    Box(
        modifier = Modifier
            .size(PiSpacing.chatTopBarAction)
            .clip(CircleShape)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(PiSpacing.chatTopBarGlyph),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
